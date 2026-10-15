package natsuri.util

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 同一个运行目录的互斥锁。
 *
 * 为什么是**一把**排他锁而不是"运行锁 + 更新锁"两把:
 *
 * - 更新与运行必须互斥。Windows 上正在被 JVM 打开的 jar 删不掉也改不了, 更新会以一个莫名其妙的
 *   `AccessDeniedException` 收场, 而且是发生在 staging 已经就绪、正要切换的时刻 —— 最糟的位置。
 * - 两个 Console 同时跑在同一个目录本来就不能允许: `bots/` 里的会话文件、`data/` 里的
 *   PluginData 都会被两边交替覆写。这不是启动器的洁癖, 是数据安全。
 *
 * 所以首版就是: 谁拿到锁谁干活, 拿不到的**立刻失败并说清楚是谁占着**, 不排队、不超时重试 ——
 * 让人等三十秒再告诉他"锁被占了", 不如立刻说。
 *
 * 锁用 [FileChannel.tryLock]。进程崩溃时操作系统会释放它, 所以不存在"残留锁文件卡死"的问题;
 * 旁边的 `lock.info` 只是给人看的线索 (pid + 命令 + 时间), 它可能是陈旧的, 判定权在锁本身。
 */
class RunLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val infoFile: Path,
) : AutoCloseable {

    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
        runCatching { Files.deleteIfExists(infoFile) }
    }

    class Busy(val holder: String?) : Exception(
        buildString {
            append("这个运行目录已经被另一个 natsuri 占用")
            if (holder != null) append(" (", holder, ")")
            append("。")
        },
    )

    companion object {

        /**
         * 拿锁。拿不到抛 [Busy]。
         *
         * @param purpose 写进 lock.info 的用途 (`run` / `update` …), 只为让被拒的那一方看到原因
         */
        fun acquire(natsuriDir: Path, purpose: String): RunLock {
            Files.createDirectories(natsuriDir)
            val lockFile = natsuriDir.resolve("lock")
            val infoFile = natsuriDir.resolve("lock.info")
            val channel = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
            val fileLock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // 同一个 JVM 里重复加锁。natsuri 只在入口加一次, 走到这里说明是编码错误, 但对用户
                // 而言表现和"被别人占着"一样, 就按同一条路处理。
                runCatching { channel.close() }
                throw Busy(readHolder(infoFile))
            }
            if (fileLock == null) {
                runCatching { channel.close() }
                throw Busy(readHolder(infoFile))
            }
            runCatching {
                Files.writeString(
                    infoFile,
                    "pid=${ProcessHandle.current().pid()}\npurpose=$purpose\nsince=${java.time.ZonedDateTime.now()}\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            }
            return RunLock(channel, fileLock, infoFile)
        }

        /** 只看锁是否被占, 不占用它。给 `doctor` 用。 */
        fun probe(natsuriDir: Path): String? {
            val lockFile = natsuriDir.resolve("lock")
            if (!Files.exists(lockFile)) return null
            return try {
                FileChannel.open(lockFile, StandardOpenOption.READ, StandardOpenOption.WRITE).use { ch ->
                    val l = ch.tryLock()
                    if (l == null) readHolder(natsuriDir.resolve("lock.info")) ?: "未知进程"
                    else {
                        l.release()
                        null
                    }
                }
            } catch (_: Exception) {
                readHolder(natsuriDir.resolve("lock.info")) ?: "未知进程"
            }
        }

        private fun readHolder(infoFile: Path): String? = runCatching {
            if (!Files.exists(infoFile)) return null
            Files.readAllLines(infoFile, StandardCharsets.UTF_8)
                .filter { it.isNotBlank() }
                .joinToString(", ")
                .ifBlank { null }
        }.getOrNull()
    }
}
