package natsuri.manifest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 清单与索引共用的 JSON 配置。 */
val ManifestJson: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    // 允许清单里出现本版本不认识的字段: 以后加新字段时旧 natsuri 不会因此拒绝整个清单。
    ignoreUnknownKeys = true
}

const val MANIFEST_SCHEMA_VERSION = 1

/**
 * 发行清单 —— natsuri 的安装单位。
 *
 * ## 为什么是"整包"而不是"每个模块各自 latest"
 *
 * natsu 的运行时是一组必须同版本的模块: `yutori-core-api` 定义 mirai 兼容 API,
 * `natsu-console` 按那份 API 编译并生成防夹带表, `yutori-core` 实现 `IMirai` 服务,
 * 前端把它们接起来。这几个之间既有二进制兼容约束, 也有"同名类只能有一份"的约束
 * (见 natsu 的 `verifyDistribution`)。把它们当成可以各自取 latest 的包来拼, 得到的组合
 * 没有人验证过 —— 出问题的形态还往往是"不报错, 但行为不对"。
 *
 * 所以 natsuri 管理的是**一个经过验证的 zip**, 清单只描述它, 不描述怎么拼出它。
 * 客户端也因此**不需要**再解析一遍 Maven 传递依赖。
 *
 * ## 版本相同不等于内容相同
 *
 * `0.1.0-SNAPSHOT` 会被重发很多次。所以判断"装的是不是这一份"用的是 [installId] ——
 * 版本 + 归档摘要前缀, 而不是版本字符串。[buildId] 是发布方给的构建标识, 用于展示与排序,
 * 但**信任边界在摘要**: 两个清单只要 `archive.sha256` 不同就是两份不同的东西。
 */
@Serializable
data class ReleaseManifest(
    val schemaVersion: Int = MANIFEST_SCHEMA_VERSION,
    /** 固定为 `natsu`。留字段是为了让"这份清单不是给 natsuri 的"能被识破而不是被误装。 */
    val product: String = "natsu",
    /** natsu 自己的发行版本, 例如 `0.1.0-SNAPSHOT`。**不是** Console 为兼容插件报告的 `2.16.0`。 */
    val version: String,
    /** 发布方的构建标识; 同名重发时用它区分。可以为空, 那时只靠归档摘要区分。 */
    val buildId: String = "",
    /** `stable` 或 `preview`。 */
    val channel: String = Channels.STABLE,
    /** ISO-8601 时间戳, 只用于展示。 */
    val publishedAt: String = "",
    /** 运行这份发行包所需的最低 Java 主版本。natsu 当前要 21。 */
    val javaMin: Int,
    val archive: Archive,
    val launch: Launch,
    /** 归档内文件的逐个摘要。可选; 给 `doctor --deep` 做完整性复核用。 */
    val components: List<Component> = emptyList(),
    /** 原生后端的协议 / 签名配套约束。OneBot 模式用不到, 但清单里始终记录。 */
    val native: NativeCompat? = null,
    /** 给用户看的发行说明 (一行)。 */
    val notes: String = "",
) {

    @Serializable
    data class Archive(
        /**
         * 归档地址。可以是:
         * - 相对路径 —— 相对**清单自身所在位置**解析 (本地目录或 HTTPS 目录都适用);
         * - 绝对本地路径;
         * - `https://` 绝对地址。
         *
         * 相对路径是首选: 同一份 releases 目录不管挂在本地还是挂在静态站点上都不用改清单。
         */
        val url: String,
        /** 字节长度。下载前用它判断磁盘与续传, 下载后先校验它再校验摘要 (便宜的先做)。 */
        val size: Long,
        /** 归档内容的 SHA-256。**内容校验, 不是发布者签名。** */
        val sha256: String,
        /**
         * 归档里的应用根目录。natsu 的 `distZip` 产出 `natsu-console-terminal-<version>/`。
         * 空串表示归档根目录就是应用根目录。
         */
        val rootDir: String = "",
        val format: String = "zip",
    )

    @Serializable
    data class Launch(
        /**
         * 启动类。natsuri 固定校验它必须是 `natsu.console.MainKt` ——
         * 那个入口负责在 Console 起来之前装配 `Natsu.backendFactory`, 绕过它会漏掉后端装配,
         * 表现为插件一调登录就抛"没有可用的 BackendFactory"。
         */
        val mainClass: String,
        /** 有序的运行时 classpath, 每项相对应用根目录。顺序即加载顺序, 不能重排。 */
        val classpath: List<String>,
        /** 发行包自带的 JVM 参数 (natsu 的启动脚本里那三个 UTF-8 开关就在这里)。 */
        val defaultJvmArgs: List<String> = emptyList(),
        /** 发行包自带的 Console 参数; 通常为空。 */
        val defaultArgs: List<String> = emptyList(),
    )

    @Serializable
    data class Component(
        /** 相对应用根目录的路径。 */
        val path: String,
        val size: Long,
        val sha256: String,
    )

    /**
     * 原生后端的协议配套信息。
     *
     * natsu 当前把 `Protocols.LINUX` 写死在 `NativeBackendFactory` 里, 指纹在
     * `BotAppInfo.LINUX`: QQNT `3.2.31-51102`, `appClientVersion = 51102`。
     * 没有用户可配置的 protocol / appinfo, natsuri 也**不要**恢复这类配置 ——
     * 清单只是把这个约束记录下来, 好让跨协议版本的更新能提示"签名端要一起换"。
     */
    @Serializable
    data class NativeCompat(
        /** `linux` —— 描述的是 QQ 协议身份, 与 natsuri 跑在哪个操作系统上无关。 */
        val protocol: String,
        /** 客户端版本, 例如 `3.2.31-51102`。 */
        val clientVersion: String,
        /** 签名服务必须支持的版本号, 例如 `51102` (= `appClientVersion`)。 */
        val signVersion: Int,
        val qua: String = "",
    )

    /** 安装标识: 版本 + 归档摘要前缀。同名重发因摘要不同而互不覆盖。 */
    val installId: String
        get() = "$version+${archive.sha256.take(12).lowercase()}"

    fun validate(): List<String> {
        val problems = ArrayList<String>()
        if (schemaVersion > MANIFEST_SCHEMA_VERSION) {
            problems += "清单 schemaVersion=$schemaVersion, 这个 natsuri 只认到 $MANIFEST_SCHEMA_VERSION —— 请升级 natsuri"
        }
        if (schemaVersion < 1) problems += "清单 schemaVersion 非法: $schemaVersion"
        if (product != "natsu") problems += "清单的 product 是 '$product', natsuri 只管理 natsu 的发行版本"
        if (version.isBlank()) {
            problems += "清单缺少 version"
        } else {
            /*
             * version 会变成 installId, 而 installId 就是 `.natsuri/releases/` 与 `staging/` 下的目录名。
             * 不检查的话, `"version": "../../../../Documents"` 会让安装流程在解压之前先把那个目录
             * 递归删掉 —— 下面 launch.classpath 的越界检查早就在做同一件事, 这里当初漏了。
             */
            natsuri.util.PathNames.problemWith(version, "清单的 version")?.let { problems += it }
        }
        if (buildId.isNotBlank()) {
            natsuri.util.PathNames.problemWith(buildId, "清单的 buildId")?.let { problems += it }
        }
        if (channel !in Channels.ALL) problems += "清单的 channel 是 '$channel', 只支持 ${Channels.ALL.joinToString(" / ")}"
        if (javaMin < 8) problems += "清单的 javaMin 非法: $javaMin"
        if (archive.size <= 0) problems += "清单的 archive.size 非法: ${archive.size}"
        if (!natsuri.util.Hashes.isSha256Hex(archive.sha256)) {
            problems += "清单的 archive.sha256 不是 64 位十六进制: '${archive.sha256}'"
        }
        if (archive.format != "zip") problems += "首版只支持 zip 归档, 清单写的是 '${archive.format}'"
        if (archive.url.isBlank()) problems += "清单缺少 archive.url"
        if (launch.mainClass != EXPECTED_MAIN_CLASS) {
            problems += "清单的启动类是 '${launch.mainClass}', natsu 的正式入口是 $EXPECTED_MAIN_CLASS —— " +
                "绕过它会漏掉 natsu 后端装配 (Natsu.backendFactory 必须在 Console 启动前设好)"
        }
        if (launch.classpath.isEmpty()) problems += "清单的 launch.classpath 是空的"
        launch.classpath.forEachIndexed { i, entry ->
            if (entry.isBlank()) problems += "launch.classpath[$i] 是空串"
            if (entry.startsWith("/") || entry.startsWith("\\") || entry.contains(":\\") || entry.contains(":/")) {
                problems += "launch.classpath[$i] 是绝对路径: '$entry' —— 必须相对应用根目录"
            }
            if (entry.split('/', '\\').any { it == ".." }) {
                problems += "launch.classpath[$i] 越出应用根目录: '$entry'"
            }
        }
        if (launch.classpath.size != launch.classpath.distinct().size) {
            val dup = launch.classpath.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            problems += "launch.classpath 有重复项: $dup"
        }
        components.forEachIndexed { i, c ->
            if (!natsuri.util.Hashes.isSha256Hex(c.sha256)) problems += "components[$i].sha256 不是 64 位十六进制"
        }
        return problems
    }

    companion object {
        /** natsu 的正式启动入口。见 `natsu-console-terminal` 的 `Main.kt`。 */
        const val EXPECTED_MAIN_CLASS = "natsu.console.MainKt"

        fun parse(text: String): ReleaseManifest = ManifestJson.decodeFromString(serializer(), text)
    }
}

object Channels {
    const val STABLE = "stable"
    const val PREVIEW = "preview"
    val ALL = listOf(STABLE, PREVIEW)
}

/**
 * 版本索引 —— 一个地址就能查到"有哪些版本、各频道当前是哪个"。
 *
 * 放静态站点或对象存储上即可, 不需要服务端逻辑。清单地址同样支持相对路径 (相对索引自身)。
 */
@Serializable
data class ReleaseIndex(
    val schemaVersion: Int = MANIFEST_SCHEMA_VERSION,
    val product: String = "natsu",
    /** 频道 -> 该频道当前推荐的发行。 */
    val channels: Map<String, ChannelPointer> = emptyMap(),
    /** 全部已发布的发行 (含历史版本), 新的在前。 */
    val releases: List<Entry> = emptyList(),
) {

    @Serializable
    data class ChannelPointer(
        val version: String,
        val buildId: String = "",
        /** 清单地址; 相对路径相对索引自身解析。 */
        val manifest: String,
    )

    @Serializable
    data class Entry(
        val version: String,
        val buildId: String = "",
        val channel: String = Channels.STABLE,
        val manifest: String,
        val publishedAt: String = "",
        @SerialName("sha256")
        /** 归档摘要; 只为让 `versions` 能在不抓清单的情况下判断"这个我已经装了"。 */
        val archiveSha256: String = "",
    )

    fun validate(): List<String> {
        val problems = ArrayList<String>()
        if (schemaVersion > MANIFEST_SCHEMA_VERSION) {
            problems += "索引 schemaVersion=$schemaVersion, 这个 natsuri 只认到 $MANIFEST_SCHEMA_VERSION —— 请升级 natsuri"
        }
        if (product != "natsu") problems += "索引的 product 是 '$product', natsuri 只管理 natsu 的发行版本"
        if (channels.isEmpty() && releases.isEmpty()) problems += "索引里既没有 channels 也没有 releases"
        for ((name, pointer) in channels) {
            if (name !in Channels.ALL) problems += "索引里有未知频道 '$name'"
            if (pointer.manifest.isBlank()) problems += "频道 '$name' 没有 manifest 地址"
        }
        return problems
    }

    companion object {
        fun parse(text: String): ReleaseIndex = ManifestJson.decodeFromString(serializer(), text)
    }
}
