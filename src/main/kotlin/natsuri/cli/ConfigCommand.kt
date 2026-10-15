package natsuri.cli

import natsuri.ExitCodes
import natsuri.NatsuriHome
import natsuri.config.NatsuriConfig
import natsuri.manifest.Channels
import natsuri.util.Log

/**
 * `natsuri config show|set` —— 改 natsuri 自己的那几项配置。
 *
 * 只覆盖**启动器**的配置。`natsu.yml` 一个字都不碰: 那是 Console 的地盘,
 * 两套工具改同一份配置, 用户永远不知道哪一份在生效。
 */
class ConfigCommand(
    private val home: NatsuriHome,
    private val config: NatsuriConfig,
    private val args: Args,
) {

    fun execute(): Int = when (val sub = args.positional(1)) {
        null, "show", "get" -> show()
        "set" -> set()
        else -> throw CliError("不认识的子命令 'config $sub' —— 支持 show / set")
    }

    private fun show(): Int {
        args.rejectUnknown("dir", "verbose", "quiet", "help")
        println("# 当前生效的配置" + if (NatsuriConfig.exists(home.root)) " (${home.root.resolve(NatsuriConfig.FILE_NAME)})" else " (文件不存在, 这些是默认值)")
        println(Log.redact(NatsuriConfig.render(config)))
        println()
        println("可以 natsuri config set 的键:")
        KEYS.forEach { (key, help) -> println("  ${key.padEnd(26)} $help") }
        return ExitCodes.OK
    }

    private fun set(): Int {
        args.rejectUnknown("dir", "verbose", "quiet", "help")
        val key = args.positional(2) ?: throw CliError("用法: natsuri config set <键> <值>")
        /*
         * 值可以来自两个地方:
         *   natsuri config set jvmArgs -Xmx2g            <- 位置参数
         *   natsuri config set jvmArgs -- --add-opens=x  <- `--` 之后
         *
         * 第二种是必需的: JVM 参数里 `--add-opens` / `--enable-preview` 这类以 `--` 开头的写法很常见,
         * 而命令行解析必须把它们当选项 —— 否则 `--verbose` 之类就永远传不进 natsuri 自己。
         * `--` 之后一律不解析, 正好是这里要的语义。
         *
         * 值也可能是空串 (用来清空一项), 所以不能用 `?:` 兜底成 null。
         */
        val raw = args.positionals.getOrNull(3)
            ?: args.passthrough.takeIf { it.isNotEmpty() }?.joinToString(" ")
            ?: throw CliError(
                "用法: natsuri config set <键> <值>\n" +
                    "  值以 -- 开头时放在 `--` 之后: natsuri config set $key -- --add-opens=java.base/java.lang=ALL-UNNAMED\n" +
                    "  清空一项:                    natsuri config set $key \"\"",
            )
        val value = raw.takeIf { it.isNotEmpty() }

        val next = when (key) {
            "source.index" -> config.copy(source = config.source.copy(index = value))
            "source.manifest" -> config.copy(source = config.source.copy(manifest = value))
            "source.allowInsecure" -> config.copy(source = config.source.copy(allowInsecure = parseBool(key, raw)))
            "channel" -> {
                if (raw !in Channels.ALL) throw CliError("channel 只支持 ${Channels.ALL.joinToString(" / ")}")
                config.copy(channel = raw)
            }
            "workingDir" -> config.copy(workingDir = value ?: ".")
            "java.path" -> config.copy(java = config.java.copy(path = value))
            "java.minVersion" -> config.copy(java = config.java.copy(minVersion = value?.let { parseInt(key, it) }))
            "jvmArgs" -> config.copy(jvmArgs = splitArgs(raw))
            "consoleArgs" -> config.copy(consoleArgs = splitArgs(raw))
            "keepPreviousReleases" -> config.copy(keepPreviousReleases = parseInt(key, raw))
            "commandLineThreshold" -> config.copy(commandLineThreshold = parseInt(key, raw))
            "download.maxRetries" -> config.copy(download = config.download.copy(maxRetries = parseInt(key, raw)))
            "download.connectTimeoutSeconds" ->
                config.copy(download = config.download.copy(connectTimeoutSeconds = parseInt(key, raw).toLong()))
            "download.requestTimeoutSeconds" ->
                config.copy(download = config.download.copy(requestTimeoutSeconds = parseInt(key, raw).toLong()))
            else -> throw CliError(
                "不认识的配置键 '$key'。可设置的键:\n" + KEYS.joinToString("\n") { "  ${it.first.padEnd(26)} ${it.second}" },
            )
        }

        val problems = next.validate()
        if (problems.isNotEmpty()) {
            throw CliError("这样设置之后配置就不合法了:\n" + problems.joinToString("\n") { "  - $it" })
        }
        NatsuriConfig.save(home.root, next)
        println("已设置 $key = ${Log.redact(raw)}")
        return ExitCodes.OK
    }

    /** `jvmArgs` / `consoleArgs` 是列表。按空格切, 想带空格就用引号 —— shell 已经帮忙处理过一层了。 */
    private fun splitArgs(raw: String): List<String> =
        raw.split(' ').map { it.trim() }.filter { it.isNotEmpty() }

    private fun parseBool(key: String, raw: String): Boolean = when (raw.lowercase()) {
        "true", "yes", "1", "on" -> true
        "false", "no", "0", "off" -> false
        else -> throw CliError("$key 只接受 true / false, 给的是 '$raw'")
    }

    private fun parseInt(key: String, raw: String): Int =
        raw.toIntOrNull() ?: throw CliError("$key 需要一个整数, 给的是 '$raw'")

    private companion object {
        val KEYS = listOf(
            "source.index" to "版本索引地址 (index.json)",
            "source.manifest" to "单份发行清单地址",
            "source.allowInsecure" to "允许明文 HTTP 源 (默认 false)",
            "channel" to "跟哪个频道: stable / preview",
            "workingDir" to "Console 的工作目录 (相对 natsuri 根目录, 默认 .)",
            "java.path" to "指定 java 可执行文件",
            "java.minVersion" to "额外的最低 Java 版本要求 (只能提高门槛)",
            "jvmArgs" to "追加给 Console 的 JVM 参数, 空格分隔",
            "consoleArgs" to "追加给 Console 的参数, 空格分隔",
            "keepPreviousReleases" to "保留几个旧版本供回滚 (至少 1)",
            "commandLineThreshold" to "命令行超过多少字符改用 @argfile (默认 30000)",
            "download.maxRetries" to "下载失败重试次数",
            "download.connectTimeoutSeconds" to "连接超时 (秒)",
            "download.requestTimeoutSeconds" to "单次请求上限 (秒)",
        )
    }
}
