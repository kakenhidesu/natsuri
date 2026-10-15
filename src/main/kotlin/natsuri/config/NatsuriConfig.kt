package natsuri.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import natsuri.manifest.Channels
import natsuri.util.Fs
import java.nio.file.Files
import java.nio.file.Path

/**
 * `natsuri.json` —— 启动器自己的配置。
 *
 * **边界**: 这里只有"从哪下载、装在哪、用哪个 Java、加什么 JVM 参数"。
 * 账号、签名服务地址、后端选择全都在 `natsu.yml` 里, 由 Console 管理, natsuri 不碰也不复制一份 ——
 * 两份配置各说一套的系统, 用户永远不知道哪一份在生效。
 *
 * 文件不存在时 natsuri 用默认值跑, 并在需要写入时 (`pin` / `config set` 等) 才落盘 ——
 * 一个只想 `natsuri doctor` 看看情况的人不该被塞一个配置文件。
 */
@Serializable
data class NatsuriConfig(
    val schemaVersion: Int = 1,
    val source: Source = Source(),
    /** 默认跟哪个频道。`update` 与"未安装时自动安装"都看它。 */
    val channel: String = Channels.STABLE,
    /**
     * Console 的运行目录, 相对 natsuri 根目录。`natsu.yml` / `plugins/` / `bots/` 都在这里面。
     * 默认 `.` —— 也就是和 natsuri 自己同一个目录, 与 README 里画的布局一致。
     */
    val workingDir: String = ".",
    val java: JavaConfig = JavaConfig(),
    /** 追加给 Console 子进程的 JVM 参数, 排在发行包自带参数之后 (所以同名 -D 由这里覆盖)。 */
    val jvmArgs: List<String> = emptyList(),
    /** 追加给 Console 的参数, 排在命令行 `--` 之后的参数之前。 */
    val consoleArgs: List<String> = emptyList(),
    val download: Download = Download(),
    /** 保留多少个旧版本目录供回滚。至少 1 —— 0 等于没有回滚。 */
    val keepPreviousReleases: Int = 1,
    /**
     * 启动命令行超过这个长度就改用 `@argfile`。
     *
     * Windows 的 `CreateProcess` 上限是整条命令行 32767 个字符 (实测在 32766 处开始失败,
     * 报 `CreateProcess error=206`); Linux 的 `execve` 是单个参数 128 KiB。
     * 默认 30000 是给前者留的余量。natsu 的 classpath 有五十多个 jar,
     * 装在很深或很长的路径下时确实会顶上去 —— 那时把这个值调小即可。
     */
    val commandLineThreshold: Int = 30_000,
) {

    @Serializable
    data class Source(
        /** 版本索引地址 (`index.json`)。`https://` 或本地路径。留空表示没有配置远程源。 */
        val index: String? = null,
        /**
         * 直接指定一份发行清单。配了 [index] 时它只是"上次 install --manifest 用的地址"的记录,
         * 版本查询与更新走索引。
         */
        val manifest: String? = null,
        /**
         * 允许 `http://` 源。
         *
         * 默认 false, 并且**没有任何隐式降级**: HTTPS 地址不会因为跳转就变成 HTTP
         * (JDK 的重定向策略本身就拒绝 HTTPS→HTTP), 明文源必须在这里显式打开。
         * 本地文件路径不受这个开关限制 —— 它根本没有传输层。
         */
        val allowInsecure: Boolean = false,
    )

    @Serializable
    data class JavaConfig(
        /** 显式指定的 java 可执行文件。留空则按 `JAVA_HOME` -> `PATH` 找。 */
        val path: String? = null,
        /**
         * 额外的最低版本要求。实际生效的是它与发行清单 `javaMin` 里更高的那个 ——
         * 清单说要 21 就是要 21, 这里只能提高门槛, 不能降低。
         */
        val minVersion: Int? = null,
    )

    @Serializable
    data class Download(
        val connectTimeoutSeconds: Long = 20,
        /** 单次请求的整体上限。大包走慢网时要留够, 但不能没有上限。 */
        val requestTimeoutSeconds: Long = 1800,
        /** 网络错误的重试次数 (每次都会尝试断点续传)。 */
        val maxRetries: Int = 3,
        /** 允许下载的最大字节数, 挡住"清单说 20MB 实际推 20GB"。 */
        val maxArchiveBytes: Long = 2L * 1024 * 1024 * 1024,
    )

    fun validate(): List<String> {
        val problems = ArrayList<String>()
        if (schemaVersion > 1) problems += "natsuri.json 的 schemaVersion=$schemaVersion 超出本版本 (1), 请升级 natsuri"
        if (channel !in Channels.ALL) problems += "natsuri.json 的 channel 是 '$channel', 只支持 ${Channels.ALL.joinToString(" / ")}"
        if (keepPreviousReleases < 1) problems += "keepPreviousReleases 至少为 1, 否则无法回滚"
        java.minVersion?.let { if (it < 8) problems += "java.minVersion 非法: $it" }
        if (download.maxRetries < 0) problems += "download.maxRetries 不能为负"
        if (download.maxArchiveBytes <= 0) problems += "download.maxArchiveBytes 必须为正"
        if (commandLineThreshold < 1000) problems += "commandLineThreshold 太小了 ($commandLineThreshold), 至少 1000"
        return problems
    }

    companion object {
        const val FILE_NAME = "natsuri.json"

        private val json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun load(root: Path): NatsuriConfig {
            val file = root.resolve(FILE_NAME)
            if (!Files.exists(file)) return NatsuriConfig()
            val text = Files.readString(file)
            return try {
                json.decodeFromString(serializer(), text)
            } catch (e: Exception) {
                throw IllegalStateException("${file} 解析失败: ${e.message}", e)
            }
        }

        fun save(root: Path, config: NatsuriConfig) {
            Fs.writeAtomically(root.resolve(FILE_NAME), json.encodeToString(serializer(), config) + "\n")
        }

        fun exists(root: Path): Boolean = Files.exists(root.resolve(FILE_NAME))

        fun render(config: NatsuriConfig): String = json.encodeToString(serializer(), config)
    }
}
