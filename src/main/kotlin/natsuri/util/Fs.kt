package natsuri.util

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/** 文件系统操作。要么完整成功, 要么原地不动 —— 更新流程的可靠性最后都落在这几个函数上。 */
object Fs {

    /**
     * 原子替换一个文件: 先写同目录下的临时文件, 再 move 过去。
     *
     * 同目录很关键 —— 跨盘/跨挂载点没有原子 move。Windows 上 [StandardCopyOption.ATOMIC_MOVE]
     * 对已存在的目标是支持的 (MoveFileEx + MOVEFILE_REPLACE_EXISTING), Linux 上是 rename(2);
     * 万一底层拒绝 (少见, 例如某些网络盘), 退回非原子的 REPLACE_EXISTING 并把这件事记下来 ——
     * 悄悄降级会让"状态文件半写"这种最难查的故障无迹可寻。
     */
    fun writeAtomically(target: Path, content: String) = writeAtomically(target, content.toByteArray(StandardCharsets.UTF_8))

    fun writeAtomically(target: Path, content: ByteArray) {
        val dir = target.parent ?: throw IOException("没有父目录: $target")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".${target.fileName}.", ".tmp")
        try {
            Files.write(tmp, content)
            // 先把数据刷到盘上再改名, 否则断电后可能得到一个"改好名的空文件"。
            runCatching {
                java.io.RandomAccessFile(tmp.toFile(), "rw").use { it.fd.sync() }
            }
            moveReplacing(tmp, target)
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    fun moveReplacing(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Log.debug("目标文件系统不支持原子 move, 退回 REPLACE_EXISTING: $target")
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * 把一个目录移到另一个位置。
     *
     * 先试整目录 move (同一文件系统下就是一次 rename, 真原子); 不行再递归拷贝 + 删源。
     * 拷贝路径上**先拷到 target 的兄弟临时目录再改名**, 免得"拷了一半的目录"被当成安装好的版本。
     */
    fun moveDirectory(source: Path, target: Path) {
        Files.createDirectories(target.parent)
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            return
        } catch (_: AtomicMoveNotSupportedException) {
            // 落到下面的拷贝路径
        } catch (_: java.nio.file.FileSystemException) {
            // Windows 上跨卷 move 会抛 FileSystemException 而不是 AtomicMoveNotSupported
        }
        val staging = target.resolveSibling("${target.fileName}.incoming-${ProcessHandle.current().pid()}")
        deleteRecursively(staging)
        copyRecursively(source, staging)
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        deleteRecursively(source)
    }

    fun copyRecursively(source: Path, target: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.copy(f, target.resolve(source.relativize(f).toString()), StandardCopyOption.COPY_ATTRIBUTES)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /**
     * 递归删除。
     *
     * 只允许删 natsuri 自己管的目录 —— 调用点必须自己保证这一点。这里额外挡一层:
     * 拒绝删根目录和用户主目录, 免得一个算错的相对路径把人家的家目录清了。
     */
    fun deleteRecursively(path: Path) {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.nameCount > 0) { "拒绝删除根目录: $normalized" }
        require(normalized != Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()) {
            "拒绝删除用户主目录: $normalized"
        }
        Files.walkFileTree(normalized, object : SimpleFileVisitor<Path>() {
            override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(f)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    fun sizeOf(path: Path): Long = if (Files.exists(path)) Files.size(path) else -1L

    /** 人眼可读的字节数。日志里 `21694737` 不如 `20.7 MiB` 有用。 */
    fun humanSize(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble() / 1024
        var i = 0
        while (value >= 1024 && i < units.lastIndex) {
            value /= 1024
            i++
        }
        return String.format("%.1f %s", value, units[i])
    }
}
