package natsuri.source

import natsuri.util.Log
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * 一个"能取到字节的地方" —— 本地文件或 HTTPS 地址。
 *
 * 两种来源都必须真正能用:
 * - **本地**给开发、离线安装与验收用: 一份 `distZip` 加一份清单放在同一个目录里就能装;
 * - **HTTPS** 给正式发布用, 指向静态托管的索引与清单即可, 不需要服务端逻辑。
 *
 * 关键在 [resolve]: 清单里的 `archive.url` 通常是相对路径, 相对**清单自身所在位置**解析。
 * 于是同一份 `releases/` 目录, 本地也好、挂到静态站点也好, 清单一个字都不用改。
 */
sealed interface Locator {

    /** 给人看的地址 (已脱敏)。 */
    val display: String

    /** 相对当前位置解析一个子地址; 传进来的若是绝对地址则直接采用。 */
    fun resolve(reference: String, policy: SourcePolicy): Locator

    /** 小文件 (清单/索引) 的整体读取, 带大小上限。 */
    fun readAll(maxBytes: Long = 8L * 1024 * 1024): ByteArray

    data class LocalFile(val path: Path) : Locator {
        override val display: String get() = path.toString()

        override fun resolve(reference: String, policy: SourcePolicy): Locator =
            Sources.locate(reference, policy, base = this)

        override fun readAll(maxBytes: Long): ByteArray {
            if (!Files.exists(path)) throw NoSuchFileException(path.toFile(), null, "找不到文件")
            val size = Files.size(path)
            if (size > maxBytes) throw IllegalStateException("$path 有 $size 字节, 超过上限 $maxBytes")
            return Files.readAllBytes(path)
        }
    }

    data class Remote(val uri: URI) : Locator {
        override val display: String get() = Log.redact(uri.toString())

        override fun resolve(reference: String, policy: SourcePolicy): Locator {
            val trimmed = reference.trim()
            if (Sources.hasScheme(trimmed) || trimmed.startsWith("/")) {
                // 绝对地址或站点绝对路径 —— 交给统一入口, 好让协议策略也生效。
                val absolute = if (Sources.hasScheme(trimmed)) trimmed else uri.resolve(trimmed).toString()
                return Sources.locate(absolute, policy, base = null)
            }
            return Sources.locate(uri.resolve(trimmed).toString(), policy, base = null)
        }

        override fun readAll(maxBytes: Long): ByteArray = Downloader.fetchBytes(uri, maxBytes)
    }
}

/** 协议策略。明文源必须显式打开, 而且这个决定只在配置里, 不会被任何跳转悄悄改掉。 */
data class SourcePolicy(val allowInsecure: Boolean = false) {
    companion object {
        val SECURE = SourcePolicy(false)
    }
}

object Sources {

    private val SCHEME = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*:""")

    fun hasScheme(reference: String): Boolean {
        val m = SCHEME.find(reference) ?: return false
        // Windows 盘符 `C:\...` 会匹配 `^[a-zA-Z]:`, 那不是 scheme。
        return m.value.length > 2
    }

    /**
     * 把一个字符串解析成 [Locator]。
     *
     * 规则:
     * - 没有 scheme -> 本地路径 (相对 [base] 或当前目录);
     * - `file:` -> 本地路径;
     * - `https:` -> 远程, 始终允许;
     * - `http:` -> 远程, **需要 [SourcePolicy.allowInsecure]**;
     * - 其余 scheme -> 拒绝。
     */
    fun locate(reference: String, policy: SourcePolicy, base: Locator? = null): Locator {
        val ref = reference.trim()
        require(ref.isNotEmpty()) { "地址是空的" }

        if (!hasScheme(ref)) {
            val path = Path.of(ref)
            val resolved = when {
                path.isAbsolute -> path
                base is Locator.LocalFile -> (base.path.parent ?: Path.of(".")).resolve(path)
                base is Locator.Remote -> return base.resolve(ref, policy)
                else -> Path.of("").toAbsolutePath().resolve(path)
            }
            return Locator.LocalFile(resolved.normalize())
        }

        val uri = try {
            URI(ref)
        } catch (e: Exception) {
            throw IllegalArgumentException("地址无法解析: $ref (${e.message})", e)
        }
        return when (uri.scheme.lowercase()) {
            "file" -> Locator.LocalFile(Path.of(uri).normalize())
            "https" -> Locator.Remote(uri)
            "http" -> {
                if (!policy.allowInsecure) {
                    throw IllegalArgumentException(
                        "拒绝使用明文 HTTP 源: ${Log.redact(ref)}\n" +
                            "发行包的信任来自\"源地址可信 + 传输没被改过\", HTTP 两条都不成立 —— " +
                            "SHA-256 只能证明内容和清单一致, 而清单本身也是从同一条明文链路取来的。\n" +
                            "确实要用 (例如内网测试源), 在 natsuri.json 里把 source.allowInsecure 设成 true。",
                    )
                }
                Log.warn("正在使用明文 HTTP 源 (source.allowInsecure=true): ${Log.redact(ref)}")
                Locator.Remote(uri)
            }
            else -> throw IllegalArgumentException("不支持的地址协议 '${uri.scheme}': ${Log.redact(ref)} —— 只支持本地路径与 https://")
        }
    }
}
