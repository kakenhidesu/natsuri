package natsuri.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import natsuri.manifest.ReleaseManifest
import natsuri.util.Fs
import java.nio.file.Files
import java.nio.file.Path

/**
 * `.natsuri/state.json` —— natsuri 对"本机装了什么"的全部记忆。
 *
 * 写入永远是**整份原子替换** ([Fs.writeAtomically]): 更新流程里真正不可逆的一步就是改这个文件,
 * 它之前的所有失败 (下载断、校验错、解压中止) 都只影响 `downloads/` 与 `staging/`,
 * 原来那个能跑的版本一个字节都没动。
 *
 * 状态里存的是**相对路径**, 所以整个 natsuri 目录可以整体搬走。
 */
@Serializable
data class InstallState(
    val schemaVersion: Int = 1,
    /** 当前选定版本的 installId; 没装东西时为 null。 */
    val current: String? = null,
    /** 上一个 current, 供 `rollback` 用。 */
    val previous: String? = null,
    /**
     * 版本锁定。存的是**版本字符串**而不是 installId ——
     * 用户说的"锁在 0.1.0"是"别给我换版本", 不是"别给我换这一份构建";
     * 同版本的重新构建 (SNAPSHOT) 仍然允许通过 `install`/`update` 显式换上。
     */
    val pinned: String? = null,
    val installs: List<InstalledRelease> = emptyList(),
    val plugins: List<PluginRecord> = emptyList(),
) {

    fun find(installId: String): InstalledRelease? = installs.firstOrNull { it.installId == installId }

    /**
     * 按版本字符串找; 同版本有多份构建时返回**最近安装**的那个。
     *
     * 排序以 `installedAt` 为主、列表下标为次。时间戳精确到秒, 同一秒里装两份同名重发时
     * 只看时间戳会退化成"取列表里第一个", 也就是最旧的那个 —— 与"最近安装"正好相反。
     * `installs` 按安装顺序追加, 下标补上了这一位。
     */
    fun findByVersion(version: String): InstalledRelease? =
        installs.withIndex()
            .filter { it.value.version == version }
            .maxWithOrNull(compareBy({ it.value.installedAt }, { it.index }))
            ?.value

    /** `use` / `install` 接受的标识: 完整 installId, 或版本字符串。 */
    fun resolve(selector: String): InstalledRelease? = find(selector) ?: findByVersion(selector)

    val currentRelease: InstalledRelease? get() = current?.let { find(it) }
    val previousRelease: InstalledRelease? get() = previous?.let { find(it) }

    companion object {
        const val FILE_NAME = "state.json"

        private val json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun load(natsuriDir: Path): InstallState {
            val file = natsuriDir.resolve(FILE_NAME)
            if (!Files.exists(file)) return InstallState()
            return try {
                json.decodeFromString(serializer(), Files.readString(file))
            } catch (e: Exception) {
                throw IllegalStateException(
                    "$file 解析失败: ${e.message}\n" +
                        "这个文件是 natsuri 对已安装版本的记录。如果它损坏了, 删掉它会让 natsuri 忘记所有已安装版本 " +
                        "(.natsuri/releases/ 下的目录还在, 可以用 natsuri install --manifest 重新登记)。",
                    e,
                )
            }
        }

        fun save(natsuriDir: Path, state: InstallState) {
            Files.createDirectories(natsuriDir)
            Fs.writeAtomically(natsuriDir.resolve(FILE_NAME), json.encodeToString(serializer(), state) + "\n")
        }
    }
}

/**
 * 一个已安装的 natsu 发行版本。
 *
 * [classpath] 是从清单里抄下来的**有序**列表, 启动时原样使用 —— 不在客户端重新解析依赖,
 * 也不重排顺序 (顺序决定同名类谁先加载, 而 natsu 恰好非常在意这件事)。
 */
@Serializable
data class InstalledRelease(
    val installId: String,
    val version: String,
    val buildId: String = "",
    val channel: String,
    val javaMin: Int,
    /** 相对 `.natsuri/` 的目录, 例如 `releases/0.1.0-SNAPSHOT+3f2a1b0c9d8e`。 */
    val dir: String,
    val mainClass: String,
    /** 相对 [dir] 的有序 classpath。 */
    val classpath: List<String>,
    val defaultJvmArgs: List<String> = emptyList(),
    val defaultArgs: List<String> = emptyList(),
    /** 归档摘要 —— 同名重发的唯一可靠区分依据。 */
    val archiveSha256: String,
    /** 装它的时候用的清单地址 (脱敏后)。`update --check` 会从这里回溯源。 */
    val sourceManifest: String = "",
    /** ISO-8601。 */
    val installedAt: String,
    val nativeProtocol: String = "",
    val nativeClientVersion: String = "",
    val nativeSignVersion: Int = 0,
    /** 安装时发行校验通过的时间; 空表示没有记录 (例如是更早版本的 natsuri 装的)。 */
    val verifiedAt: String = "",
    /**
     * 清单里的逐文件摘要, 安装时抄下来。
     *
     * 留着它是为了让 `doctor --deep` 能真的复核"盘上的 jar 还是当初装的那一份" ——
     * 不留的话就得重新抓一次清单, 而 `doctor` 必须能离线用。
     * 清单没给 `components` 时这里是空的, `doctor --deep` 会如实说没得核对。
     */
    val components: List<ComponentDigest> = emptyList(),
) {
    fun resolveDir(natsuriDir: Path): Path = natsuriDir.resolve(dir)

    fun describe(): String = buildString {
        append(version)
        if (buildId.isNotBlank()) append(" (build ").append(buildId).append(')')
        append("  [").append(channel).append(']')
    }

    companion object {
        fun from(manifest: ReleaseManifest, dir: String, sourceManifest: String, installedAt: String, verifiedAt: String) =
            InstalledRelease(
                installId = manifest.installId,
                version = manifest.version,
                buildId = manifest.buildId,
                channel = manifest.channel,
                javaMin = manifest.javaMin,
                dir = dir,
                mainClass = manifest.launch.mainClass,
                classpath = manifest.launch.classpath,
                defaultJvmArgs = manifest.launch.defaultJvmArgs,
                defaultArgs = manifest.launch.defaultArgs,
                archiveSha256 = manifest.archive.sha256,
                sourceManifest = sourceManifest,
                installedAt = installedAt,
                nativeProtocol = manifest.native?.protocol ?: "",
                nativeClientVersion = manifest.native?.clientVersion ?: "",
                nativeSignVersion = manifest.native?.signVersion ?: 0,
                verifiedAt = verifiedAt,
                components = manifest.components.map { ComponentDigest(it.path, it.size, it.sha256) },
            )
    }
}

/** 一个发行包内文件的大小与摘要。与清单里的 `components` 一一对应。 */
@Serializable
data class ComponentDigest(val path: String, val size: Long, val sha256: String)

/**
 * natsuri 装过的一个插件文件。
 *
 * 首版只管**文件与安装记录**: 放进 `plugins/`、记下摘要、能按标识删掉。
 * 不解析插件描述、不做依赖解析、不判断兼容性 —— 那些要真正加载插件才知道,
 * 而加载插件是 Console 的事。
 */
@Serializable
data class PluginRecord(
    /** natsuri 用的标识: 文件名去掉 `.jar` / `.mirai2.jar` 后缀。 */
    val id: String,
    /** `plugins/` 下的文件名。 */
    val file: String,
    val size: Long,
    val sha256: String,
    val installedAt: String,
    /** 装它时用的地址 (脱敏后); 本地文件则是原路径。 */
    val source: String = "",
    /**
     * jar 里 `META-INF/services/net.mamoe.mirai.console.plugin.jvm.JvmPlugin` 声明的实现类。
     * 这是**静态可读**的插件身份线索, 比文件名可靠: 同一个插件改了文件名, 这一项还是一样的。
     */
    val providers: List<String> = emptyList(),
)
