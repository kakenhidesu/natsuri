package natsuri.cli

import natsuri.ExitCodes
import natsuri.NatsuriHome
import natsuri.config.NatsuriConfig
import natsuri.plugins.PluginManager
import natsuri.util.Fs
import natsuri.util.RunLock

/** `natsuri plugins list|install|remove`。 */
class PluginCommands(
    private val home: NatsuriHome,
    private val config: NatsuriConfig,
    private val args: Args,
) {

    private val manager = PluginManager(home, config)

    fun execute(): Int = when (val sub = args.positional(1)) {
        null, "list" -> list()
        // 装/删要动 plugins/ 目录, 与正在跑的 Console 冲突 —— 和版本操作用同一把锁。
        "install", "add" -> RunLock.acquire(home.natsuriDir, "plugins install").use { install() }
        "remove", "rm", "uninstall" -> RunLock.acquire(home.natsuriDir, "plugins remove").use { remove() }
        else -> throw CliError("不认识的子命令 'plugins $sub' —— 支持 list / install / remove")
    }

    private fun list(): Int {
        args.rejectUnknown("dir", "verbose", "quiet", "help")
        val entries = manager.list()
        if (entries.isEmpty()) {
            println("plugins/ 里没有插件 (${home.pluginsDir(config)})")
            return ExitCodes.OK
        }
        println("plugins/ (${home.pluginsDir(config)}):")
        for (entry in entries) {
            val identity = entry.identity
            println()
            println("  ${entry.fileName}")
            if (identity.declaredId != null) {
                val version = identity.declaredVersion?.let { " $it" } ?: ""
                val name = identity.declaredName?.takeIf { it != identity.declaredId }?.let { " ($it)" } ?: ""
                println("    插件 id : ${identity.declaredId}$version$name   <- plugin.yml")
            } else {
                println("    插件 id : 未知 —— 这个 jar 没有 plugin.yml, 它的 id 是在代码里构造的, 只有 Console 加载后才知道")
                println("    natsuri 用文件名 '${identity.id}' 作为标识")
            }
            if (identity.mainClasses.isNotEmpty()) {
                println("    主类     : ${identity.mainClasses.joinToString()}")
            }
            println("    大小     : ${Fs.humanSize(entry.size)}")
            println("    来源     : " + (entry.record?.let { "${it.source.ifBlank { "(未记录)" }}, 装于 ${it.installedAt}" } ?: "手动放入 (natsuri 没有安装记录)"))
            entry.shadowedBy?.let {
                println("    注意     : 被 $it 遮蔽 —— Console 的 .mirai2.jar 去重规则会跳过这个文件")
            }
        }
        println()
        println("natsuri 只管插件文件本身: 放进去、记摘要、按标识删掉。")
        println("加载、依赖解析与兼容性由 Console 决定。")
        return ExitCodes.OK
    }

    private fun install(): Int {
        args.rejectUnknown("dir", "verbose", "quiet", "help", "sha256", "force")
        val reference = args.positional(2)
            ?: throw CliError("用法: natsuri plugins install <本地 jar 路径或 https 地址> [--sha256 <摘要>] [--force]")
        val record = manager.install(reference, args.value("sha256"), args.flag("force"))
        println("已安装插件 ${record.file}")
        println("  标识   : ${record.id}")
        println("  sha256 : ${record.sha256}")
        if (record.providers.isNotEmpty()) println("  主类   : ${record.providers.joinToString()}")
        println("  下次启动 natsuri 时由 Console 加载它。")
        return ExitCodes.OK
    }

    private fun remove(): Int {
        args.rejectUnknown("dir", "verbose", "quiet", "help")
        val selector = args.positional(2)
            ?: throw CliError("用法: natsuri plugins remove <标识或文件名>")
        val dataDirs = manager.list()
            .firstOrNull { it.identity.id == selector || it.fileName == selector || it.identity.declaredId == selector }
            ?.let { manager.dataDirectories(it) }
            ?: emptyList()

        val entry = manager.remove(selector)
        println("已删除 ${entry.fileName}")
        if (dataDirs.isEmpty()) {
            /*
             * "没有发现数据目录"只在**知道插件 id** 的前提下才是一句有信息量的话。
             * 没有 plugin.yml 时 natsuri 根本不知道该去 data/ 下找哪个目录 ——
             * 那时说"没发现"是在暗示"没有", 而实际上是"没查"。
             */
            if (entry.identity.declaredId == null) {
                println("这个 jar 没有 plugin.yml, natsuri 不知道它的插件 id, 因此**没有查**它的数据目录。")
                println("它的数据在 ${home.workingDir(config).resolve("data")} 下以插件自报的 id 命名, 需要的话自行确认。")
            } else {
                println("没有发现它的数据目录 (${entry.identity.declaredId})。")
            }
        } else {
            println()
            println("**保留**了它的数据与配置 (natsuri 不会删这些):")
            dataDirs.forEach { println("  $it") }
            println("要不要清理由你决定。")
        }
        return ExitCodes.OK
    }
}
