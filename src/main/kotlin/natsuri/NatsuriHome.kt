package natsuri

import natsuri.config.NatsuriConfig
import java.nio.file.Files
import java.nio.file.Path

/**
 * natsuri 的运行目录布局。
 *
 * ```
 * <root>/
 * ├── natsuri / natsuri.cmd / natsuri.jar   启动器自己
 * ├── natsuri.json                          启动器配置 (下载源 / Java / JVM 参数)
 * ├── .natsuri/                             启动器的私有区域, 用户不用管
 * │   ├── releases/<installId>/             已安装的 natsu 发行版本 (直接就是应用根目录)
 * │   ├── downloads/<sha256>.zip            校验过的归档缓存 (含 .part 续传片段)
 * │   ├── staging/                          解压中转; 只有校验通过才会被搬进 releases/
 * │   ├── state.json                        装了什么、当前用哪个、锁在哪个
 * │   ├── natsuri.log                       启动器自己的诊断日志
 * │   └── lock                              同目录互斥锁
 * ├── natsu.yml                             **Console 的配置, natsuri 不碰**
 * ├── plugins/ config/ data/ bots/ logs/    **Console 的业务目录, natsuri 不碰**
 * └── plugin-libraries/ plugin-shared-libraries/
 * ```
 *
 * 两条边界写在这里, 后面每个命令都照它执行:
 *
 * - **`.natsuri/` 以外的东西 natsuri 只读不写**, 唯一的例外是 `natsuri.json` 与
 *   `plugins/` 下由 `natsuri plugins` 显式管理的文件。
 * - **业务目录由 Console 按需创建**。natsuri 不预先造 `config/` `data/` `bots/` ——
 *   造出来一堆空目录, 只会让人以为已经初始化过了。
 */
class NatsuriHome(val root: Path) {

    val natsuriDir: Path get() = root.resolve(".natsuri")
    val releasesDir: Path get() = natsuriDir.resolve("releases")
    val downloadsDir: Path get() = natsuriDir.resolve("downloads")
    val stagingDir: Path get() = natsuriDir.resolve("staging")
    val logFile: Path get() = natsuriDir.resolve("natsuri.log")

    fun config(): NatsuriConfig = NatsuriConfig.load(root)

    /** Console 的工作目录。默认就是 [root]。 */
    fun workingDir(config: NatsuriConfig): Path =
        root.resolve(config.workingDir).toAbsolutePath().normalize()

    fun pluginsDir(config: NatsuriConfig): Path = workingDir(config).resolve("plugins")

    fun natsuConfigFile(config: NatsuriConfig): Path = workingDir(config).resolve("natsu.yml")

    companion object {

        const val ENV_HOME = "NATSURI_HOME"

        /**
         * 定位运行目录。
         *
         * 顺序: `--dir` > `NATSURI_HOME` > **natsuri.jar 所在目录** > 当前目录。
         *
         * 把 jar 所在目录排在当前目录之前, 是因为发行包的布局就是"启动器和运行数据在同一个文件夹",
         * 用户从别处敲 `C:\tools\natsuri\natsuri.cmd` 时, 他要的显然是那个文件夹里的安装,
         * 而不是在当前目录里另起一份。
         */
        fun locate(explicitDir: String?): NatsuriHome {
            explicitDir?.takeIf { it.isNotBlank() }?.let {
                return NatsuriHome(Path.of(it).toAbsolutePath().normalize())
            }
            System.getenv(ENV_HOME)?.takeIf { it.isNotBlank() }?.let {
                return NatsuriHome(Path.of(it).toAbsolutePath().normalize())
            }
            jarDirectory()?.let { return NatsuriHome(it) }
            return NatsuriHome(Path.of("").toAbsolutePath().normalize())
        }

        private fun jarDirectory(): Path? = runCatching {
            val source = NatsuriHome::class.java.protectionDomain?.codeSource?.location ?: return null
            val path = Path.of(source.toURI())
            // 从 jar 里跑的时候是 .../natsuri.jar; 从 IDE/测试跑的时候是 .../classes/kotlin/main,
            // 那种情况没有意义, 交给下一档 (当前目录)。
            if (Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar")) {
                path.parent?.toAbsolutePath()?.normalize()
            } else {
                null
            }
        }.getOrNull()
    }
}
