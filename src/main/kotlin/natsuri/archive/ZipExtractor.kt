package natsuri.archive

import natsuri.util.Fs
import natsuri.util.Log
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * 解压。归档一律当成**不可信输入**处理 —— 哪怕它刚刚通过了 SHA-256 校验:
 * 摘要证明的是"和清单说的一致", 不是"内容无害"。
 */
object ZipExtractor {

    /**
     * 解压限额。挡的是"解压炸弹": 一个几百 KB 的包能解出几十 GB 把磁盘填满。
     *
     * 默认值按 natsu 现在的发行包留了充裕余量 (实测 53 个 jar / 约 21MB 压缩 / 约 35MB 解压),
     * 但仍然是**有限**的 —— 无上限的解压等于把磁盘交给发布方。
     */
    data class Limits(
        val maxEntries: Int = 20_000,
        val maxTotalBytes: Long = 4L * 1024 * 1024 * 1024,
        val maxEntryBytes: Long = 1L * 1024 * 1024 * 1024,
        /** 单个条目允许的最大压缩比。文本/类文件到 20 倍很常见, 200 倍已经不像正常内容。 */
        val maxCompressionRatio: Long = 200,
    )

    data class Report(val entries: Int, val bytes: Long, val symlinkCheck: String)

    /**
     * 把 [zip] 解到 [targetDir]。
     *
     * @param stripRoot 归档里的应用根目录 (例如 `natsu-console-terminal-0.1.0-SNAPSHOT`)。
     *   非空时**所有**条目都必须在它之下, 解压结果里这一层被去掉 ——
     *   于是 `.natsuri/releases/<installId>/lib/...` 直接就是 classpath 的相对基准, 少一层猜。
     */
    fun extract(zip: Path, targetDir: Path, stripRoot: String = "", limits: Limits = Limits()): Report {
        val base = targetDir.toAbsolutePath().normalize()
        Files.createDirectories(base)

        val symlinkCheck = precheckSymlinks(zip)

        val prefix = stripRoot.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        var entryCount = 0
        var totalBytes = 0L
        val seen = HashSet<String>()

        ZipFile(zip.toFile(), java.nio.charset.StandardCharsets.UTF_8).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount++
                if (entryCount > limits.maxEntries) {
                    throw UnsafeArchiveException("归档条目数超过上限 ${limits.maxEntries}")
                }

                val safeName = ZipSafety.sanitize(entry.name)
                val relative = if (prefix.isEmpty()) {
                    safeName
                } else {
                    if (safeName == prefix.trimEnd('/')) continue // 根目录条目自身
                    if (!safeName.startsWith(prefix)) {
                        throw UnsafeArchiveException(
                            "归档里有不在应用根目录 '$stripRoot' 之下的条目: ${entry.name}",
                        )
                    }
                    safeName.removePrefix(prefix)
                }
                if (relative.isEmpty()) continue

                val target = ZipSafety.resolveInside(base, relative)

                if (entry.isDirectory) {
                    Files.createDirectories(target)
                    continue
                }
                if (!seen.add(relative)) {
                    throw UnsafeArchiveException("归档里有重名条目: $relative")
                }
                Files.createDirectories(target.parent)

                val written = archive.getInputStream(entry).use { input ->
                    Files.newOutputStream(target).use { output ->
                        copyCapped(input, output, limits.maxEntryBytes, limits.maxTotalBytes - totalBytes, relative)
                    }
                }
                totalBytes += written

                // 压缩比只在压缩后的大小已知且不算太小的时候有意义 —— 几百字节的条目比值天然会很大。
                val compressed = entry.compressedSize
                if (compressed in 1..Long.MAX_VALUE && written > 64 * 1024 &&
                    written / compressed > limits.maxCompressionRatio
                ) {
                    throw UnsafeArchiveException(
                        "条目 $relative 的压缩比 ${written / compressed}:1 超过上限 ${limits.maxCompressionRatio}:1",
                    )
                }

                restoreExecutableBit(relative, target)
            }
        }

        if (seen.isEmpty()) throw UnsafeArchiveException("归档里没有任何文件条目")
        return Report(entryCount, totalBytes, symlinkCheck)
    }

    /**
     * 解压前扫一遍中央目录。
     *
     * 三件 `java.util.zip.ZipFile` 的公开 API 做不到、或做得太晚的事:
     * 1. **符号链接条目** —— `ZipEntry` 不暴露 external attributes, 而链接标志就在那 4 个字节里;
     * 2. **加密条目** —— 通用位标志 bit 0 / bit 6; 让它走到解压再失败, 错误信息会指向别处;
     * 3. **条目名的另一种解读** —— 未置 UTF-8 标志的条目名按 CP437 解, 与 `ZipFile` 的解读可能不同,
     *    所以两种解读都要过一遍 [ZipSafety]。
     */
    private fun precheckSymlinks(zip: Path): String = try {
        requireZipMagic(zip)
        val entries = ZipCentralDirectory.read(zip)
        val links = entries.filter { it.isSymlink }
        if (links.isNotEmpty()) {
            throw UnsafeArchiveException(
                "归档里有符号链接条目 (前 5 条): ${links.take(5).joinToString { it.name }}\n" +
                    "natsu 的发行包不含符号链接, 出现即说明这不是预期的内容。",
            )
        }
        val encrypted = entries.filter { it.isEncrypted }
        if (encrypted.isNotEmpty()) {
            throw UnsafeArchiveException(
                "归档里有加密条目 (前 5 条): ${encrypted.take(5).joinToString { it.name }} —— 发行包不应加密",
            )
        }
        // 中央目录里读出来的名字先过一遍安全检查: ZipFile 那边可能按另一种编码解读同一串字节。
        entries.forEach { ZipSafety.sanitize(it.name) }
        "已检查 ${entries.size} 个条目, 无符号链接与加密条目"
    } catch (e: ZipCentralDirectory.ParseFailure) {
        // 读不动中央目录不等于包有问题 (Zip64 边角、非常规写法都可能), 但要如实说没检查成。
        // 逃逸风险本身由 ZipSafety 与"绝不创建链接"挡住。
        Log.warn("无法解析归档的中央目录 (${e.message}), 跳过符号链接检查")
        "未完成 (${e.message})"
    }

    /**
     * 文件头必须是 `PK\x03\x04` (本地文件头) 或 `PK\x05\x06` (空归档)。
     *
     * 便宜, 而且能把"下到一半的 HTML 错误页"这种最常见的情况在解压前就说清楚 ——
     * 不然报出来的是一句 `error in opening zip file`, 指向不了原因。
     */
    private fun requireZipMagic(zip: Path) {
        val head = ByteArray(4)
        java.io.RandomAccessFile(zip.toFile(), "r").use { file ->
            if (file.length() < 4) throw UnsafeArchiveException("文件只有 ${file.length()} 字节, 不是 ZIP")
            file.readFully(head)
        }
        val ok = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            ((head[2].toInt() == 3 && head[3].toInt() == 4) || (head[2].toInt() == 5 && head[3].toInt() == 6))
        if (!ok) {
            throw UnsafeArchiveException(
                "文件开头不是 ZIP 魔数 (拿到 ${head.joinToString(" ") { "%02x".format(it) }}) —— 下到的可能是一个错误页面",
            )
        }
    }

    private fun copyCapped(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        maxEntryBytes: Long,
        remainingTotal: Long,
        name: String,
    ): Long {
        val buffer = ByteArray(1 shl 16)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            written += read
            // getSize() 是归档自己声明的, 不可信 —— 上限要在**实际写出的字节**上执行。
            if (written > maxEntryBytes) {
                throw UnsafeArchiveException("条目 $name 解压超过单条上限 ${Fs.humanSize(maxEntryBytes)}")
            }
            if (written > remainingTotal) {
                throw UnsafeArchiveException("解压总量超过上限 (在 $name 处中止)")
            }
            output.write(buffer, 0, read)
        }
        return written
    }

    /** `bin/` 下的启动脚本在 POSIX 上给回执行位。natsuri 不用它们启动, 但用户可能想直接跑。 */
    private fun restoreExecutableBit(relative: String, target: Path) {
        if (!relative.startsWith("bin/") || relative.endsWith(".bat")) return
        runCatching {
            val view = Files.getFileAttributeView(target, java.nio.file.attribute.PosixFileAttributeView::class.java)
                ?: return
            val perms = HashSet(view.readAttributes().permissions())
            perms += java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
            perms += java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
            perms += java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
            view.setPermissions(perms)
        }
    }
}
