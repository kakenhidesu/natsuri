package natsuri.source

import natsuri.util.Fs
import natsuri.util.Hashes
import natsuri.util.Hashes.toHex
import natsuri.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 下载。三件事必须做对: **断点续传**、**内容校验**、**失败时不留下半个文件冒充成品**。
 *
 * 落盘路径是 `downloads/<sha256>.part` -> 校验通过后改名成 `downloads/<sha256>.zip`。
 * 用摘要做文件名有两个好处: 不同发行的续传片段永远不会互相污染 (即使版本号一样),
 * 已经下好的包再次安装时直接命中缓存。
 */
object Downloader {

    /** 没有新字节超过这么久就判定链路失活。HttpRequest.timeout 覆盖不到"连着但不发数据"。 */
    private const val STALL_SECONDS = 120L

    @Volatile
    private var clientCache: Pair<Long, HttpClient>? = null

    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "natsuri-download-watchdog").apply { isDaemon = true }
    }

    private fun client(connectTimeoutSeconds: Long): HttpClient {
        clientCache?.let { (t, c) -> if (t == connectTimeoutSeconds) return c }
        val c = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
            /*
             * NORMAL 而不是 ALWAYS: NORMAL 会跟随重定向, 但**拒绝 HTTPS -> HTTP 的降级跳转**。
             * 这正是"HTTPS 源不得被静默降级"要靠的机制 —— 源地址是 https 就一路是 https,
             * 一个能改跳转的中间人也没法把它拽到明文上。
             */
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        clientCache = connectTimeoutSeconds to c
        return c
    }

    data class Options(
        val connectTimeoutSeconds: Long = 20,
        val requestTimeoutSeconds: Long = 1800,
        val maxRetries: Int = 3,
        val maxBytes: Long = 2L * 1024 * 1024 * 1024,
    )

    /**
     * 小文件的整体读取 (清单 / 索引 / 插件 jar)。
     *
     * **边读边计上限**, 而不是收完再判。`BodyHandlers.ofByteArray()` 会先把整个响应装进内存,
     * 那时再说"超过上限"已经晚了 —— 一个恶意或出错的源推来几个 GB, natsuri 先 OOM,
     * 上限那行代码根本没机会执行。
     */
    fun fetchBytes(uri: URI, maxBytes: Long, options: Options = Options()): ByteArray {
        val response = fetch(uri, maxBytes, options = options)
        // 重定向次数用尽时 JDK 把那个 3xx **原样交回来**, 不抛异常 —— 必须显式判。
        if (response.status != 200) {
            throw IOException("HTTP ${response.status} <- ${Log.redact(uri.toString())}")
        }
        return response.body
    }

    /** 一次小请求的完整结果。给需要看状态码与响应头的调用方用 (例如 GitHub API 的限流判断)。 */
    class Fetched(
        val status: Int,
        val headers: java.net.http.HttpHeaders,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers.firstValue(name).orElse(null)
    }

    /**
     * 与 [fetchBytes] 相同的读取纪律 (HTTPS 落点检查、边读边计上限), 但**不因非 200 抛异常**,
     * 把状态码与响应头交还给调用方自己判断。
     *
     * @param extraHeaders 附加请求头。**只会随这一个请求发出** —— JDK 在跨源重定向时会剥掉
     *   `Authorization`, 所以凭据不会被带到别的主机上。
     */
    fun fetch(
        uri: URI,
        maxBytes: Long,
        extraHeaders: Map<String, String> = emptyMap(),
        options: Options = Options(),
    ): Fetched {
        val builder = HttpRequest.newBuilder(uri)
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(options.requestTimeoutSeconds.coerceAtMost(120)))
            .GET()
        if (extraHeaders.keys.none { it.equals("Accept", ignoreCase = true) }) {
            builder.header("Accept", "application/json, */*")
        }
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        val response = client(options.connectTimeoutSeconds)
            .send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())

        response.body().use { stream ->
            if (uri.scheme.equals("https", ignoreCase = true) &&
                !response.uri().scheme.equals("https", ignoreCase = true)
            ) {
                throw IOException("HTTPS 源被重定向到了非 HTTPS 地址 (${Log.redact(response.uri().toString())}), 已中止")
            }
            // Content-Length 只是声明, 不可信 (HTTP/2 下 JDK 也不校验它); 用它做一次便宜的早退,
            // 真正的上限仍然在下面按**实际读到的字节**执行。
            response.headers().firstValueAsLong("content-length").ifPresent { declared ->
                if (declared > maxBytes) throw IOException("响应声明有 $declared 字节, 超过上限 $maxBytes")
            }

            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1 shl 16)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("响应超过上限 $maxBytes 字节, 已中止")
                out.write(buffer, 0, read)
            }
            return Fetched(response.statusCode(), response.headers(), out.toByteArray())
        }
    }

    /**
     * 把一个归档取到 [downloadsDir], 返回校验通过的文件路径。
     *
     * 本地来源直接校验原文件, 不做拷贝 —— 离线安装时没必要把 20MB 抄一遍。
     * 远程来源走续传下载。无论哪条路, **返回之前一定完成了大小与 SHA-256 校验**;
     * 校验不过就抛异常, 已有的安装完全不受影响。
     */
    fun obtain(
        locator: Locator,
        downloadsDir: Path,
        expectedSize: Long,
        expectedSha256: String,
        options: Options,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Path {
        require(Hashes.isSha256Hex(expectedSha256)) { "期望的 SHA-256 不是 64 位十六进制: $expectedSha256" }
        if (expectedSize > options.maxBytes) {
            throw IOException("清单声明的归档有 $expectedSize 字节, 超过 download.maxArchiveBytes=${options.maxBytes}")
        }
        Files.createDirectories(downloadsDir)
        val sha = expectedSha256.lowercase()
        val cached = downloadsDir.resolve("$sha.zip")

        if (Files.exists(cached)) {
            if (Files.size(cached) == expectedSize && Hashes.matches(sha, Hashes.sha256(cached))) {
                Log.info("已有缓存的归档, 跳过下载: ${cached.fileName}")
                onProgress(expectedSize, expectedSize)
                return cached
            }
            Log.warn("缓存的 ${cached.fileName} 校验不过, 删除后重取")
            Files.deleteIfExists(cached)
        }

        when (locator) {
            is Locator.LocalFile -> {
                val src = locator.path
                if (!Files.exists(src)) throw IOException("找不到归档: $src")
                val size = Files.size(src)
                if (size != expectedSize) {
                    throw IOException("归档大小不符: $src 有 $size 字节, 清单说 $expectedSize")
                }
                val actual = Hashes.sha256(src)
                if (!Hashes.matches(sha, actual)) {
                    throw IOException("归档 SHA-256 不符: $src\n  期望 $sha\n  实际 $actual")
                }
                onProgress(size, size)
                return src
            }

            is Locator.Remote -> {
                val part = downloadsDir.resolve("$sha.part")
                var attempt = 0
                while (true) {
                    attempt++
                    try {
                        downloadWithResume(locator.uri, part, expectedSize, options, onProgress)
                        break
                    } catch (e: IOException) {
                        if (attempt > options.maxRetries) {
                            throw IOException(
                                "下载失败 (试了 $attempt 次): ${e.message}\n" +
                                    "已下载的部分留在 ${part.fileName}, 下次会从断点继续。原有安装未受影响。",
                                e,
                            )
                        }
                        val waitMs = (1000L shl minOf(attempt - 1, 4))
                        Log.warn("下载出错 (第 $attempt 次): ${e.message} —— ${waitMs}ms 后从断点重试")
                        Thread.sleep(waitMs)
                    }
                }
                val size = Files.size(part)
                if (size != expectedSize) {
                    throw IOException("下载完成但大小不符: 得到 $size 字节, 清单说 $expectedSize")
                }
                val actual = Hashes.sha256(part)
                if (!Hashes.matches(sha, actual)) {
                    // 内容不对的片段不能留着续传 —— 下次接着下只会把错误延续下去。
                    Files.deleteIfExists(part)
                    throw IOException(
                        "下载完成但 SHA-256 不符 (已丢弃):\n  期望 $sha\n  实际 $actual\n" +
                            "可能是源上的文件变了, 也可能是传输被改过。原有安装未受影响。",
                    )
                }
                Fs.moveReplacing(part, cached)
                return cached
            }
        }
    }

    /**
     * 一次续传下载尝试。
     *
     * 续传的正确性有两个坑, 这里都堵上了:
     * 1. **服务端可能忽略 Range**。那时它回 200 + 完整 body, 如果照旧 append 就会得到一个
     *    "前面一段重复"的文件 —— 大小对不上还好, 万一恰好对上就是无声的损坏。所以只有 206 才 append,
     *    200 一律从零开始 (先把 part 截断)。
     * 2. **源上的文件可能已经换了**。带上 `If-Range` 之后, 服务端发现资源变了就会回 200 而不是 206,
     *    于是自动落到"从零开始"那条路上; 没有 ETag/Last-Modified 时最后的 SHA-256 校验兜底。
     */
    private fun downloadWithResume(
        uri: URI,
        part: Path,
        expectedSize: Long,
        options: Options,
        onProgress: (Long, Long) -> Unit,
    ) {
        var existing = if (Files.exists(part)) Files.size(part) else 0L
        if (existing > expectedSize) {
            Log.warn("已有的下载片段比清单声明的还大 ($existing > $expectedSize), 丢弃重下")
            Files.deleteIfExists(part)
            existing = 0
        }
        if (existing == expectedSize) {
            onProgress(existing, expectedSize)
            return
        }

        val validator = readValidator(part)
        val builder = HttpRequest.newBuilder(uri)
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(options.requestTimeoutSeconds))
            .GET()
        if (existing > 0) {
            builder.header("Range", "bytes=$existing-")
            validator?.let { builder.header("If-Range", it) }
            Log.info("从 ${Fs.humanSize(existing)} 处续传")
        }

        val response = client(options.connectTimeoutSeconds)
            .send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())

        /*
         * 重定向策略 NORMAL 已经挡住了 HTTPS -> HTTP, 但它仍然允许 https 跳到**任意别的 https 主机**,
         * 也允许 http 跳到 https。所以在写任何字节之前再确认一次: 出发时是 https 的, 落点也必须是 https。
         * 这一条是"不得被静默降级"的最后一道, 放在这里是因为只有此刻才知道最终落在哪。
         */
        if (uri.scheme.equals("https", ignoreCase = true) &&
            !response.uri().scheme.equals("https", ignoreCase = true)
        ) {
            response.body().close()
            throw IOException("HTTPS 源被重定向到了非 HTTPS 地址 (${Log.redact(response.uri().toString())}), 已中止")
        }

        val status = response.statusCode()
        val append = when (status) {
            206 -> true
            200 -> {
                if (existing > 0) Log.info("服务端未接受续传请求 (HTTP 200), 从头下载")
                false
            }
            416 -> {
                // Range 越界: 本地片段比服务端的资源还长, 说明源换了。丢掉重来。
                Files.deleteIfExists(part)
                throw IOException("服务端拒绝了续传范围 (HTTP 416), 已丢弃本地片段, 请重试")
            }
            else -> throw IOException("HTTP $status <- ${Log.redact(uri.toString())}")
        }

        // 服务端声明的总长度: 206 看 Content-Range, 200 看 Content-Length。
        val declaredTotal = if (append) {
            response.headers().firstValue("content-range").orElse("").substringAfter('/', "").toLongOrNull()
        } else {
            response.headers().firstValueAsLong("content-length").orElse(-1L).takeIf { it >= 0 }
        }
        if (declaredTotal != null && declaredTotal != expectedSize) {
            throw IOException("服务端声明的长度 $declaredTotal 与清单的 $expectedSize 不符 —— 源上的文件可能换了")
        }

        val startOffset = if (append) existing else 0L
        response.body().use { stream ->
            RandomAccessFile(part.toFile(), "rw").use { file ->
                file.setLength(startOffset)
                file.seek(startOffset)
                copyWithWatchdog(stream, file, startOffset, expectedSize, options.maxBytes, onProgress)
            }
        }
        writeValidator(part, response)
    }

    private fun copyWithWatchdog(
        stream: InputStream,
        file: RandomAccessFile,
        startOffset: Long,
        expectedSize: Long,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        var written = startOffset
        // 看门狗线程和读循环各在一个线程上, 所以进度时间戳必须是可见的原子量。
        val lastProgress = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
        // HttpRequest.timeout 管的是"多久拿到响应", 管不住"响应来了但之后一个字节都不来"。
        // 拔网线的典型表现正是后者, 所以另开一个看门狗, 停滞超时就把流关掉, 让读循环抛 IOException。
        val task = watchdog.scheduleWithFixedDelay({
            if (System.nanoTime() - lastProgress.get() > TimeUnit.SECONDS.toNanos(STALL_SECONDS)) {
                runCatching { stream.close() }
            }
        }, STALL_SECONDS, 5, TimeUnit.SECONDS)

        try {
            val buffer = ByteArray(1 shl 16)
            var lastReport = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                written += read
                if (written > maxBytes) throw IOException("下载超过上限 $maxBytes 字节, 已中止")
                if (written > expectedSize) {
                    throw IOException("服务端发来的数据比清单声明的多 ($written > $expectedSize), 已中止")
                }
                file.write(buffer, 0, read)
                lastProgress.set(System.nanoTime())
                if (written - lastReport > 1 shl 20) {
                    lastReport = written
                    onProgress(written, expectedSize)
                }
            }
            onProgress(written, expectedSize)
        } finally {
            task.cancel(false)
        }
    }

    /**
     * 把校验器存在 `<part>.meta` 里, 供下次 `If-Range` 用。
     *
     * **弱 ETag (`W/"…"`) 不能用**: RFC 9110 §13.1.5 明确规定 `If-Range` 只接受强校验器 ——
     * 弱 ETag 表示"语义等价"而不是"字节相同", 拿它续传有可能把两份内容不同的字节拼在一起。
     * 没有强 ETag 时退回 `Last-Modified`; 两者都没有就不带 `If-Range`,
     * 由最后的整体 SHA-256 兜底 (拼错了就校验不过, 片段被丢弃重下)。
     */
    private fun writeValidator(part: Path, response: HttpResponse<*>) {
        val etag = response.headers().firstValue("etag").orElse(null)
        val validator = etag?.takeIf { !it.trimStart().startsWith("W/") }
            ?: response.headers().firstValue("last-modified").orElse(null)
            ?: return
        runCatching { Files.writeString(metaFile(part), validator, StandardCharsets.UTF_8) }
    }

    private fun readValidator(part: Path): String? = runCatching {
        val meta = metaFile(part)
        if (Files.exists(meta)) Files.readString(meta).trim().ifBlank { null } else null
    }.getOrNull()

    private fun metaFile(part: Path): Path = part.resolveSibling("${part.fileName}.meta")

    /** 流式算摘要, 给"边下边算"的场景留的; 目前下载完再算, 20MB 的包不值得为省一次读复杂化。 */
    fun digestOf(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private const val USER_AGENT = "natsuri"
}
