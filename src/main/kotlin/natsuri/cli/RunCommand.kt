package natsuri.cli

import natsuri.ExitCodes
import natsuri.NatsuriHome
import natsuri.config.NatsuriConfig
import natsuri.install.Installer
import natsuri.launch.ConsoleLauncher
import natsuri.launch.JavaLocator
import natsuri.state.InstallState
import natsuri.state.InstalledRelease
import natsuri.util.Log
import java.nio.file.Files

/**
 * `natsuri run` (也是不带命令时的默认行为)。
 *
 * ## 默认行为的几条硬规定
 *
 * - **已安装时, 裸命令只启动, 不检查更新、更不自动升级。** 一个人敲 `natsuri` 是想让机器人跑起来,
 *   不是想在这一刻换一个版本。更新是 `natsuri update`, 那是一个需要人来决定的动作。
 * - **未安装但配了源时**, 才自动装频道对应的版本再启动 —— 那时没有"原有版本"可言, 装了才有东西跑。
 * - **未安装且没配源时**, 给出配置来源或本地安装的具体命令, 不去猜任何地址。
 * - **离线时允许启动**已经装好并且校验过的本地版本: 这条路上一次网络请求都没有。
 * - **绝不自动重启。** 尤其是首次生成 `natsu.yml` 之后 —— 那种情况下重启只会无限循环地
 *   生成、退出、再生成。
 */
class RunCommand(
    private val home: NatsuriHome,
    private val config: NatsuriConfig,
    private val args: Args,
) {

    fun execute(): Int {
        args.rejectUnknown("dir", "verbose", "quiet", "help")

        val state = InstallState.load(home.natsuriDir)
        val release = state.currentRelease ?: autoInstall() ?: return ExitCodes.NOT_INSTALLED

        val dir = release.resolveDir(home.natsuriDir)
        if (!Files.isDirectory(dir)) {
            Log.error(
                "当前版本 ${release.installId} 的目录不见了: $dir\n" +
                    "用 natsuri doctor 看看情况, 或重新安装。",
            )
            return ExitCodes.FAILED
        }

        /*
         * 启动前只做**便宜的**完整性检查: classpath 上每个文件是否还在。
         * 完整的发行校验 (逐类归属、ServiceLoader 冲突) 在安装时做过一次, 每次启动都重跑要扫五十多个
         * jar、三万多个类 —— 那点开销对"我就想把机器人拉起来"是纯粹的税。
         * 想随时复核的话: natsuri doctor --deep。
         */
        val missing = release.classpath.filterNot { Files.isRegularFile(dir.resolve(it)) }
        if (missing.isNotEmpty()) {
            Log.error(
                "当前版本的 classpath 有 ${missing.size} 个文件不在了 (前 5 个): ${missing.take(5)}\n" +
                    "重新安装这个版本, 或 natsuri use <别的版本>。",
            )
            return ExitCodes.FAILED
        }

        val minJava = maxOf(release.javaMin, config.java.minVersion ?: 0)
        val java = JavaLocator.locate(config.java.path, minJava)

        val workingDir = home.workingDir(config)
        val configFile = home.natsuConfigFile(config)
        val firstRun = !Files.exists(configFile)
        if (firstRun) {
            Log.info("工作目录里还没有 natsu.yml —— Console 会生成一份模板然后退出, 那是正常的。")
        }

        val outcome = ConsoleLauncher.launch(
            java = java,
            natsuriDir = home.natsuriDir,
            release = release,
            workingDir = workingDir,
            extraJvmArgs = config.jvmArgs,
            consoleArgs = config.consoleArgs + args.passthrough,
            commandLineThreshold = config.commandLineThreshold,
        )

        if (outcome.generatedConfigTemplate) {
            println()
            println("natsu 首次启动已生成配置模板: $configFile")
            println("请按里面的注释填好 (至少 sign.url 与 accounts, 或把 backend.type 改成 onebot11),")
            println("把插件放进 ${home.pluginsDir(config)}, 然后再次运行 natsuri。")
            println()
            println("这不是崩溃 —— natsuri 到此正常结束。")
            return ExitCodes.OK
        }

        if (outcome.exitCode != 0) {
            Log.warn("natsu 以退出码 ${outcome.exitCode} 结束。natsuri 不会自动重启。")
        }
        // 退出码原样传出去, 让上层的 systemd / 计划任务 / 脚本能按它判断。
        return outcome.exitCode
    }

    /** 没装东西时的处置。装成功返回新版本, 否则打印指引并返回 null。 */
    private fun autoInstall(): InstalledRelease? {
        val sourcing = Sourcing(config)
        if (!sourcing.hasSource) {
            Log.error("还没有安装任何 natsu 版本。")
            println()
            println(Sourcing.NO_SOURCE_HELP)
            return null
        }
        Log.info("还没有安装任何版本, 按频道 '${config.channel}' 自动安装一份。")
        return try {
            val resolved = sourcing.resolve(null, null, config.channel)
            val installer = Installer(home, config)
            val manifest = installer.readManifest(resolved.locator)
            installer.install(resolved.locator, manifest, makeCurrent = true).release
        } catch (e: Exception) {
            Log.error("自动安装失败: ${e.message}", e)
            println()
            println("也可以先离线装一份: natsuri install --manifest <本地清单路径>")
            null
        }
    }
}
