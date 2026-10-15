package natsuri.util

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * natsuri 自己的日志。
 *
 * 三条规矩:
 *
 * 1. **面向用户的正文走 stdout, 诊断走 stderr。** 这样 `natsuri versions > list.txt` 拿到的是干净的列表,
 *    而进度、警告不会污染它。
 * 2. **子进程的输出不经过这里。** Console 的 stdout/stderr 是直接继承的 (见 ConsoleLauncher),
 *    加时间戳前缀会把二维码和 JLine 的行编辑弄坏。
 * 3. **落盘的诊断日志要脱敏。** natsuri 自己不碰 QQ 票据, 但它会打印源地址与配置, 里面可能带
 *    `?token=` / `Authorization` 这类东西 —— [redact] 负责把它们抹掉再写。
 */
object Log {

    enum class Level(val order: Int, val label: String) {
        DEBUG(0, "DEBUG"), INFO(1, "INFO"), WARN(2, "WARN"), ERROR(3, "ERROR")
    }

    @Volatile
    var level: Level = Level.INFO

    /** 落盘目标; 未设置时只往终端写 (例如还没定位到运行目录的早期阶段)。 */
    @Volatile
    private var file: Path? = null

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun attachFile(path: Path) {
        runCatching {
            Files.createDirectories(path.parent)
            file = path
        }
    }

    /** 用户正文: 命令的实际输出, 不带级别前缀, 走 stdout。 */
    fun out(message: String) {
        println(message)
        write("OUT", message)
    }

    fun debug(message: String) = log(Level.DEBUG, message, System.err)
    fun info(message: String) = log(Level.INFO, message, System.err)
    fun warn(message: String) = log(Level.WARN, message, System.err)
    fun error(message: String) = log(Level.ERROR, message, System.err)

    fun error(message: String, cause: Throwable) {
        log(Level.ERROR, message, System.err)
        if (level <= Level.DEBUG) {
            cause.printStackTrace(System.err)
        } else {
            // 默认只给一行成因链 —— 完整堆栈对用户没用, 但"到底是哪一层失败"有用。
            val chain = buildString {
                val seen = HashSet<Throwable>()
                var c: Throwable? = cause
                // 成因链可能自成环 (有的库会把外层异常当成因塞回去), seen 保证这里不会转不出来。
                while (c != null && seen.add(c)) {
                    if (isNotEmpty()) append(" <- ")
                    append(c.javaClass.simpleName).append(": ").append(c.message ?: "(no message)")
                    c = c.cause
                }
            }
            log(Level.ERROR, "  成因: ${redact(chain)}", System.err)
        }
        writeStackTrace(cause)
    }

    private fun log(l: Level, message: String, stream: PrintStream) {
        if (l.order >= level.order) stream.println("[${l.label}] $message")
        write(l.label, message)
    }

    private fun write(label: String, message: String) {
        val f = file ?: return
        runCatching {
            Files.writeString(
                f,
                "${ZonedDateTime.now().format(stamp)} [$label] ${redact(message)}\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    private fun writeStackTrace(cause: Throwable) {
        val f = file ?: return
        runCatching {
            val text = java.io.StringWriter().also { cause.printStackTrace(java.io.PrintWriter(it)) }.toString()
            Files.writeString(f, redact(text) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    private val SECRETS = listOf(
        Regex("""((?:access_?token|token|key|secret|password|passwd|apikey|api_key)["']?\s*[=:]\s*["']?)([^\s"'&,}]+)""", RegexOption.IGNORE_CASE),
        Regex("""((?:Authorization|X-Api-Key)\s*:\s*(?:Bearer\s+)?)(\S+)""", RegexOption.IGNORE_CASE),
        // URL 里的 user:pass@host
        Regex("""(://[^/\s:@]+:)([^/\s@]+)(@)"""),
    )

    /** 抹掉像凭据的片段。宁可多抹一点 —— 诊断日志里少一个 token 的价值高于多一行细节。 */
    fun redact(text: String): String {
        var out = text
        for (r in SECRETS) {
            out = r.replace(out) { m ->
                if (m.groupValues.size > 3 && m.groupValues[3].isNotEmpty()) {
                    m.groupValues[1] + "***" + m.groupValues[3]
                } else {
                    m.groupValues[1] + "***"
                }
            }
        }
        return out
    }
}
