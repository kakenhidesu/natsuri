package natsuri

import natsuri.cli.Args
import natsuri.cli.CliError
import natsuri.cli.ConfigCommand
import natsuri.cli.DoctorCommand
import natsuri.cli.Help
import natsuri.cli.ManifestCommand
import natsuri.cli.PluginCommands
import natsuri.cli.ReleaseCommands
import natsuri.cli.RunCommand
import natsuri.config.NatsuriConfig
import natsuri.util.Log
import natsuri.util.RunLock
import kotlin.system.exitProcess

/**
 * natsuri 的入口。
 *
 * 阶段划分借 MCL 的 prepare / cli / load / boot 一条线, 但只留 natsuri 真正需要的四段:
 *
 * ```
 * prepare  定位运行目录、开日志、读 natsuri.json
 * cli      解析命令与参数 (`--` 之后的一律不解析)
 * load     拿锁、读状态、按命令解析出要操作的版本
 * boot     执行 —— 只有 run 会真的拉起 Console
 * ```
 *
 * 首版**不实现**可扩展的模块系统: MCL 的 module 机制要能执行第三方脚本, 那等于把
 * "启动前执行任意代码"作为默认能力开出去。natsuri 的四个阶段都是内置的。
 */
object ExitCodes {
    const val OK = 0
    const val FAILED = 1
    const val USAGE = 2
    const val LOCKED = 3
    const val NOT_INSTALLED = 4
}

fun main(argv: Array<String>) {
    val exit = try {
        run(argv)
    } catch (e: CliError) {
        Log.error(e.message ?: "用法错误")
        System.err.println("用 natsuri --help 看用法。")
        ExitCodes.USAGE
    } catch (e: RunLock.Busy) {
        Log.error(
            (e.message ?: "运行目录被占用") + "\n" +
                "同一个目录同时跑两个 natsu 会互相覆写 bots/ 与 data/ 里的文件, 所以这里直接拒绝。\n" +
                "如果确定那个进程已经不在了, 它持有的锁会被操作系统自动释放, 重试即可。",
        )
        ExitCodes.LOCKED
    } catch (e: Throwable) {
        Log.error(e.message ?: e.javaClass.name, e)
        ExitCodes.FAILED
    }
    exitProcess(exit)
}

private fun run(argv: Array<String>): Int {
    val args = Args.parse(argv.toList())

    // 只有裸的 `natsuri --version` 才是"打印版本"; `manifest generate --version 0.2.0` 里它是个带值的选项。
    if (args.bare("version") && args.positionals.isEmpty()) {
        println(Help.version())
        return ExitCodes.OK
    }

    // ---- prepare ----
    when {
        args.flag("verbose") -> Log.level = Log.Level.DEBUG
        args.flag("quiet") -> Log.level = Log.Level.WARN
    }
    val command = args.positional(0) ?: "run"
    // 帮助在定位运行目录之前处理: `natsuri --help` 不该在任何地方留下 .natsuri/ 目录。
    if (args.flag("help") || command == "help") {
        println(Help.usage())
        return ExitCodes.OK
    }

    val home = NatsuriHome.locate(args.value("dir"))
    Log.attachFile(home.logFile)
    Log.debug("运行目录: ${home.root}")

    val config = try {
        home.config()
    } catch (e: Exception) {
        Log.error(e.message ?: "natsuri.json 读取失败")
        return ExitCodes.FAILED
    }
    config.validate().let { problems ->
        if (problems.isNotEmpty()) {
            Log.error("natsuri.json 有问题:\n" + problems.joinToString("\n") { "  - $it" })
            return ExitCodes.FAILED
        }
    }

    // ---- cli / load / boot ----
    return when (command) {
        "run" -> withLock(home, "run") { RunCommand(home, config, args).execute() }
        "install" -> withLock(home, "install") { ReleaseCommands(home, config, args).install() }
        "update" -> {
            // --check 不改任何状态, 所以不需要占锁 —— 正在跑 Console 的时候也该能查。
            if (args.flag("check")) ReleaseCommands(home, config, args).updateCheck()
            else withLock(home, "update") { ReleaseCommands(home, config, args).update() }
        }
        "versions" -> ReleaseCommands(home, config, args).versions()
        "use" -> withLock(home, "use") { ReleaseCommands(home, config, args).use() }
        "pin" -> withLock(home, "pin") { ReleaseCommands(home, config, args).pin() }
        "unpin" -> withLock(home, "unpin") { ReleaseCommands(home, config, args).unpin() }
        "rollback" -> withLock(home, "rollback") { ReleaseCommands(home, config, args).rollback() }
        "doctor" -> DoctorCommand(home, config, args).execute()
        "plugins" -> PluginCommands(home, config, args).execute()
        // config show 只读; config set 要写 natsuri.json, 与安装流程抢同一个文件, 所以在锁里做。
        "config" -> if (args.positional(1) == "set") {
            withLock(home, "config set") { ConfigCommand(home, config, args).execute() }
        } else {
            ConfigCommand(home, config, args).execute()
        }
        "manifest" -> ManifestCommand(args).execute()
        else -> throw CliError("不认识的命令 '$command'")
    }
}

/**
 * 需要改状态或要跑 Console 的命令统一在锁里做。
 *
 * 拿不到锁就立刻失败 —— 见 [RunLock] 的说明。首版不排队。
 */
private inline fun withLock(home: NatsuriHome, purpose: String, block: () -> Int): Int =
    RunLock.acquire(home.natsuriDir, purpose).use { block() }

/** 让命令实现共用的配置读取, 避免每个命令各写一遍。 */
internal fun NatsuriHome.configOrDefault(): NatsuriConfig = runCatching { config() }.getOrElse { NatsuriConfig() }
