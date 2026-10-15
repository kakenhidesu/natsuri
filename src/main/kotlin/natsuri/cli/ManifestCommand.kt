package natsuri.cli

import natsuri.ExitCodes
import natsuri.manifest.Channels
import natsuri.manifest.ManifestJson
import natsuri.manifest.ReleaseIndex
import natsuri.manifest.ReleaseManifest
import natsuri.util.Fs
import natsuri.util.Hashes
import natsuri.util.Log
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * `natsuri manifest` —— **维护者工具**。给 natsu 的发行产物生成清单与索引。
 *
 * ## 一条硬规矩: 只读真实产物
 *
 * classpath、启动类、JVM 参数、每个文件的摘要, 全部从 `distZip` / `installDist` 的**实际内容**里读出来。
 * 具体地说, 有序 classpath 与启动类来自 Gradle `application` 插件生成的**启动脚本本身**
 * (`bin/<app>.bat` 的 `set CLASSPATH=…` 与那行 `-classpath "%CLASSPATH%" <主类>`) ——
 * 那是构建对"运行时该按什么顺序加载什么"的最终结论。
 *
 * 手抄一份依赖清单是行不通的: natsu 的运行时里有 53 个 jar, 顺序还有意义
 * (同名类谁先加载由它决定)。抄错一个字, 出来的症状是"能启动但行为不对"。
 *
 * 原生后端的协议信息也不是常量, 而是从 `natsu-core` 的 class 常量池里把 QUA 串
 * (`V1_LNX_NQ_3.2.31_51102_GW_B`) 认出来 —— 那个串同时编码了协议、客户端版本与签名版本。
 */
class ManifestCommand(private val args: Args) {

    fun execute(): Int = when (val sub = args.positional(1)) {
        "generate" -> generate()
        "index" -> index()
        null -> throw CliError(USAGE)
        else -> throw CliError("不认识的子命令 'manifest $sub'\n\n$USAGE")
    }

    // -----------------------------------------------------------------------------------------------------
    // generate
    // -----------------------------------------------------------------------------------------------------

    private fun generate(): Int {
        args.rejectUnknown(
            "dir", "verbose", "quiet", "help",
            "dist", "out", "version", "channel", "build-id", "url", "notes", "java-min", "archive-out", "no-components",
        )
        val distRef = args.value("dist") ?: throw CliError("需要 --dist <natsu 的 distZip 产物或 installDist 目录>")
        val dist = Path.of(distRef).toAbsolutePath().normalize()
        if (!Files.exists(dist)) throw CliError("找不到 $dist")

        val outRef = args.value("out") ?: "natsu-release.json"
        val out = Path.of(outRef).toAbsolutePath().normalize()

        // installDist 目录先打成 zip —— 清单描述的必须是一个可下载、可校验的归档。
        val archive: Path = if (Files.isDirectory(dist)) {
            val target = args.value("archive-out")?.let { Path.of(it).toAbsolutePath().normalize() }
                ?: out.resolveSibling("${dist.fileName}.zip")
            Log.info("--dist 是目录, 打包成 $target")
            packDirectory(dist, target)
            target
        } else {
            dist
        }

        val inspection = inspect(archive)
        val version = args.value("version") ?: inspection.inferredVersion
            ?: throw CliError("从归档里推不出版本号, 请用 --version 指定")
        val channel = args.value("channel") ?: Channels.STABLE
        if (channel !in Channels.ALL) throw CliError("--channel 只支持 ${Channels.ALL.joinToString(" / ")}")

        val size = Files.size(archive)
        Log.info("计算 SHA-256 (${Fs.humanSize(size)})…")
        val sha = Hashes.sha256(archive)

        /*
         * buildId 默认取"内容摘要 + 打包时间"。
         * 为什么不能只用版本串: `0.1.0-SNAPSHOT` 会被重发无数次, 版本相同不代表内容相同。
         * buildId 让人一眼看出是不是同一次构建, 而真正的判定依据始终是 archive.sha256。
         */
        val buildId = args.value("build-id") ?: buildId(sha, archive)

        val manifest = ReleaseManifest(
            product = "natsu",
            version = version,
            buildId = buildId,
            channel = channel,
            publishedAt = timestamp(),
            javaMin = args.value("java-min")?.toIntOrNull() ?: inspection.javaMin,
            archive = ReleaseManifest.Archive(
                // 默认写成相对路径: 清单和 zip 放同一个目录, 本地与静态托管都不用改。
                url = args.value("url") ?: relativeOrName(out, archive),
                size = size,
                sha256 = sha,
                rootDir = inspection.rootDir,
            ),
            launch = ReleaseManifest.Launch(
                mainClass = inspection.mainClass,
                classpath = inspection.classpath,
                defaultJvmArgs = inspection.defaultJvmArgs,
            ),
            components = if (args.flag("no-components")) emptyList() else inspection.components,
            native = inspection.native,
            notes = args.value("notes") ?: "",
        )

        val problems = manifest.validate()
        if (problems.isNotEmpty()) {
            throw IllegalStateException(
                "生成的清单不合法 —— 这通常说明产物本身不对劲:\n" + problems.joinToString("\n") { "  - $it" },
            )
        }

        Fs.writeAtomically(out, ManifestJson.encodeToString(ReleaseManifest.serializer(), manifest) + "\n")

        println("已生成清单: $out")
        println("  产品/版本 : ${manifest.product} ${manifest.version}  [${manifest.channel}]")
        println("  buildId  : ${manifest.buildId}")
        println("  归档     : ${manifest.archive.url}  ${Fs.humanSize(size)}")
        println("  sha256   : $sha")
        println("  根目录   : ${manifest.archive.rootDir}")
        println("  启动类   : ${manifest.launch.mainClass}")
        println("  classpath: ${manifest.launch.classpath.size} 项 (顺序取自启动脚本, 不要重排)")
        println("  JVM 参数 : ${manifest.launch.defaultJvmArgs.joinToString(" ").ifEmpty { "(无)" }}")
        println("  Java 要求 : ${manifest.javaMin}+")
        manifest.native?.let {
            println("  原生后端  : ${it.protocol} QQNT ${it.clientVersion}, 签名版本 ${it.signVersion} (从 ${it.qua} 读出)")
        } ?: println("  原生后端  : 未能从产物里读出协议信息 (natsu-core 的 QUA 常量没找到)")
        println("  逐文件摘要: ${manifest.components.size} 项")
        return ExitCodes.OK
    }

    // -----------------------------------------------------------------------------------------------------
    // index
    // -----------------------------------------------------------------------------------------------------

    private fun index(): Int {
        args.rejectUnknown("dir", "verbose", "quiet", "help", "out", "add", "channel")
        val outRef = args.value("out") ?: "index.json"
        val out = Path.of(outRef).toAbsolutePath().normalize()
        val addRef = args.value("add") ?: throw CliError("需要 --add <清单文件> (可多次执行以累加)")
        val manifestPath = Path.of(addRef).toAbsolutePath().normalize()

        val manifest = ReleaseManifest.parse(Files.readString(manifestPath))
        manifest.validate().takeIf { it.isNotEmpty() }?.let {
            throw IllegalStateException("要加入索引的清单不合法:\n" + it.joinToString("\n") { p -> "  - $p" })
        }
        val channel = args.value("channel") ?: manifest.channel

        val existing = if (Files.exists(out)) ReleaseIndex.parse(Files.readString(out)) else ReleaseIndex()
        val manifestRef = relativeOrName(out, manifestPath)

        val entry = ReleaseIndex.Entry(
            version = manifest.version,
            buildId = manifest.buildId,
            channel = channel,
            manifest = manifestRef,
            publishedAt = manifest.publishedAt.ifBlank { timestamp() },
            archiveSha256 = manifest.archive.sha256,
        )
        val next = existing.copy(
            channels = existing.channels + (
                channel to ReleaseIndex.ChannelPointer(
                    version = manifest.version,
                    buildId = manifest.buildId,
                    manifest = manifestRef,
                )
                ),
            releases = listOf(entry) + existing.releases.filterNot {
                it.version == entry.version && it.buildId == entry.buildId && it.channel == entry.channel
            },
        )
        Fs.writeAtomically(out, ManifestJson.encodeToString(ReleaseIndex.serializer(), next) + "\n")

        println("已更新索引: $out")
        println("  [$channel] -> ${manifest.version} ($manifestRef)")
        println("  共 ${next.releases.size} 条发行记录")
        return ExitCodes.OK
    }

    // -----------------------------------------------------------------------------------------------------
    // 读产物
    // -----------------------------------------------------------------------------------------------------

    private class Inspection(
        val rootDir: String,
        val classpath: List<String>,
        val mainClass: String,
        val defaultJvmArgs: List<String>,
        val components: List<ReleaseManifest.Component>,
        val native: ReleaseManifest.NativeCompat?,
        val inferredVersion: String?,
        val javaMin: Int,
    )

    private fun inspect(archive: Path): Inspection {
        ZipFile(archive.toFile(), StandardCharsets.UTF_8).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            if (names.isEmpty()) throw IllegalStateException("$archive 是空归档")

            val roots = names.map { it.substringBefore('/') }.distinct()
            val rootDir = if (roots.size == 1 && names.any { it.contains('/') }) roots.single() else ""
            val prefix = if (rootDir.isEmpty()) "" else "$rootDir/"

            // 启动脚本是构建对"运行时 classpath"的最终结论 —— 从它读, 不另起一套解析。
            val batName = names.firstOrNull { it.startsWith("${prefix}bin/") && it.endsWith(".bat") }
            val shName = names.firstOrNull {
                it.startsWith("${prefix}bin/") && !it.endsWith(".bat") && it.count { c -> c == '/' } == prefix.count { c -> c == '/' } + 1
            }
            val script = batName ?: shName
                ?: throw IllegalStateException("归档里找不到 bin/ 下的启动脚本 —— 这看起来不是 Gradle application 插件的产物")
            val text = zip.getInputStream(zip.getEntry(script)).use {
                it.readBytes().toString(StandardCharsets.UTF_8)
            }
            val isBat = script.endsWith(".bat")

            val classpath = parseClasspath(text, isBat)
            if (classpath.isEmpty()) throw IllegalStateException("从 $script 里解析不出 classpath")
            val mainClass = parseMainClass(text, isBat)
                ?: throw IllegalStateException("从 $script 里解析不出启动类")
            val jvmArgs = parseDefaultJvmArgs(text, isBat)

            // 每一项 classpath 都必须真的在归档里 —— 脚本和内容对不上就是产物有问题。
            val missing = classpath.filterNot { names.contains(prefix + it) }
            if (missing.isNotEmpty()) {
                throw IllegalStateException("启动脚本引用的这些文件不在归档里: ${missing.take(5)}")
            }

            val components = classpath.map { relative ->
                val entry = zip.getEntry(prefix + relative)!!
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                ReleaseManifest.Component(relative, bytes.size.toLong(), Hashes.sha256(bytes))
            }

            val native = readNativeCompat(zip, prefix, classpath)
            val inferredVersion = rootDir.takeIf { it.isNotEmpty() }
                ?.let { Regex("""-(\d[\w.\-+]*)$""").find(it)?.groupValues?.get(1) }

            return Inspection(
                rootDir = rootDir,
                classpath = classpath,
                mainClass = mainClass,
                defaultJvmArgs = jvmArgs,
                components = components,
                native = native,
                inferredVersion = inferredVersion,
                javaMin = 21,
            )
        }
    }

    /**
     * 从 `natsu-core` 的 class 常量池里认出 QUA 串。
     *
     * `BotAppInfo.LINUX` 的 `qua = "V1_LNX_NQ_3.2.31_51102_GW_B"` —— 这一个串同时说明了
     * 协议 (LNX = Linux)、客户端版本 (3.2.31-51102) 与签名服务必须支持的版本 (51102)。
     * 它是编译进 class 文件的 UTF-8 常量, 所以在 jar 的字节流里原样存在, 认它比抄一份常量可靠:
     * natsu 换了指纹, 这里读出来的就跟着变。
     */
    private fun readNativeCompat(zip: ZipFile, prefix: String, classpath: List<String>): ReleaseManifest.NativeCompat? {
        val coreJar = classpath.firstOrNull { it.substringAfterLast('/').startsWith("natsu-core-") } ?: return null
        val pattern = Regex("""V1_LNX_NQ_(\d+\.\d+\.\d+)_(\d+)_GW_B""")
        val entry = zip.getEntry(prefix + coreJar) ?: return null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { inner ->
            while (true) {
                val e = inner.nextEntry ?: break
                if (!e.name.endsWith(".class")) continue
                // ISO-8859-1 逐字节解码: 只是为了在字节流里找一个纯 ASCII 的串, 不涉及真正的解码语义。
                val text = inner.readBytes().toString(StandardCharsets.ISO_8859_1)
                val match = pattern.find(text) ?: continue
                return ReleaseManifest.NativeCompat(
                    protocol = "linux",
                    clientVersion = "${match.groupValues[1]}-${match.groupValues[2]}",
                    signVersion = match.groupValues[2].toInt(),
                    qua = match.value,
                )
            }
        }
        return null
    }

    private fun parseClasspath(text: String, isBat: Boolean): List<String> {
        val line = text.lineSequence().firstOrNull {
            if (isBat) it.trimStart().startsWith("set CLASSPATH=") else it.startsWith("CLASSPATH=")
        } ?: return emptyList()
        val body = line.substringAfter("CLASSPATH=").trim()
        val separator = if (isBat) ';' else ':'
        return body.split(separator)
            .map { it.trim().removePrefix("%APP_HOME%").removePrefix("\$APP_HOME").trimStart('\\', '/') }
            .filter { it.isNotEmpty() }
            .map { it.replace('\\', '/') }
    }

    private fun parseMainClass(text: String, isBat: Boolean): String? {
        if (isBat) {
            // `... -classpath "%CLASSPATH%" natsu.console.MainKt %* & call :exitWithErrorLevel`
            val line = text.lineSequence().firstOrNull { it.contains("-classpath \"%CLASSPATH%\"") } ?: return null
            return line.substringAfter("-classpath \"%CLASSPATH%\"").trim()
                .split(Regex("\\s+")).firstOrNull { it.isNotEmpty() && it != "%*" }
        }
        // sh: `-classpath "$CLASSPATH" \` 的下一行就是主类
        val lines = text.lines()
        val index = lines.indexOfFirst { it.contains("-classpath \"\$CLASSPATH\"") }
        if (index < 0 || index + 1 >= lines.size) return null
        return lines[index + 1].trim().removeSuffix("\\").trim().takeIf { it.isNotEmpty() }
    }

    private fun parseDefaultJvmArgs(text: String, isBat: Boolean): List<String> {
        val line = text.lineSequence().firstOrNull {
            if (isBat) it.trimStart().startsWith("set DEFAULT_JVM_OPTS=") else it.startsWith("DEFAULT_JVM_OPTS=")
        } ?: return emptyList()
        val body = line.substringAfter("DEFAULT_JVM_OPTS=").trim().let {
            // sh 版整体外面还有一层单引号
            if (!isBat && it.length >= 2 && it.first() == '\'' && it.last() == '\'') it.substring(1, it.length - 1) else it
        }
        return Regex("\"([^\"]*)\"|(\\S+)").findAll(body)
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            .filter { it.isNotBlank() }
            .toList()
    }

    // -----------------------------------------------------------------------------------------------------

    /** 把 installDist 目录打成 zip, 保持目录名作为归档根目录 (与 distZip 的形状一致)。 */
    private fun packDirectory(dir: Path, target: Path) {
        Files.createDirectories(target.parent)
        val root = dir.fileName.toString()
        val buffer = ByteArrayOutputStream()
        Files.newOutputStream(target).use { fileOut ->
            ZipOutputStream(fileOut, StandardCharsets.UTF_8).use { zip ->
                Files.walk(dir).use { stream ->
                    stream.sorted().forEach { path ->
                        val relative = dir.relativize(path).toString().replace('\\', '/')
                        val name = if (relative.isEmpty()) "$root/" else "$root/$relative"
                        if (Files.isDirectory(path)) {
                            zip.putNextEntry(ZipEntry(if (name.endsWith("/")) name else "$name/"))
                            zip.closeEntry()
                        } else {
                            zip.putNextEntry(ZipEntry(name))
                            buffer.reset()
                            Files.copy(path, zip)
                            zip.closeEntry()
                        }
                    }
                }
            }
        }
    }

    /** 优先写成相对清单/索引自身的路径; 不在同一棵树上时退回文件名。 */
    private fun relativeOrName(from: Path, target: Path): String {
        val base = from.parent ?: return target.fileName.toString()
        return runCatching {
            val relative = base.relativize(target).toString().replace('\\', '/')
            if (relative.startsWith("..")) target.fileName.toString() else relative
        }.getOrElse { target.fileName.toString() }
    }

    private fun buildId(sha: String, archive: Path): String {
        val time = runCatching {
            ZonedDateTime.ofInstant(Files.getLastModifiedTime(archive).toInstant(), ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        }.getOrElse { timestamp() }
        return "$time-${sha.take(8)}"
    }

    private fun timestamp(): String =
        ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))

    private companion object {
        val USAGE = """
            维护者工具 —— 从真实的 natsu 发行产物生成清单与索引。

              natsuri manifest generate --dist <distZip 产物或 installDist 目录> [选项]
                --out <文件>          清单输出路径 (默认 natsu-release.json)
                --version <版本>      发行版本; 不给则从归档根目录名推断
                --channel <频道>      stable (默认) / preview
                --build-id <标识>     构建标识; 不给则用 打包时间-摘要前缀
                --url <地址>          写进清单的归档地址; 默认写成相对清单自身的路径
                --java-min <n>        最低 Java 版本 (默认 21)
                --notes <一句话>       发行说明
                --archive-out <文件>  --dist 是目录时, 打包出来的 zip 放哪
                --no-components       不写逐文件摘要 (清单会小很多, 但 doctor --deep 就没得核对)

              natsuri manifest index --add <清单文件> [--out index.json] [--channel <频道>]

            classpath 顺序、启动类与 JVM 参数都从产物里的启动脚本读取, 不接受手工传入 ——
            那份顺序决定同名类谁先加载, 抄错的症状是"能启动但行为不对"。
        """.trimIndent()
    }
}
