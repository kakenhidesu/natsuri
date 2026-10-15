package natsuri.cli

import natsuri.config.NatsuriConfig
import natsuri.manifest.Channels
import natsuri.manifest.ReleaseIndex
import natsuri.manifest.ReleaseManifest
import natsuri.source.Locator
import natsuri.source.SourcePolicy
import natsuri.source.Sources
import natsuri.util.Log

/**
 * "从哪里拿清单" 的统一入口。
 *
 * 三条路, 优先级从高到低:
 * 1. 命令行 `--manifest` —— 一次性的显式指定;
 * 2. 索引 (`--index` 或 `natsuri.json` 的 `source.index`) + 频道;
 * 3. `natsuri.json` 里记着的 `source.manifest`。
 *
 * 发行包通过随包附带的 natsuri.json 预置真实发行清单。
 * 用户清空所有来源时不猜地址, 而是给出配置来源的指引。
 */
class Sourcing(private val config: NatsuriConfig) {

    private val policy = SourcePolicy(config.source.allowInsecure)

    class NoSource(message: String) : Exception(message)

    data class Resolved(
        val locator: Locator,
        /** 清单是从索引里选出来的时候, 这里是索引地址; 否则为 null。 */
        val viaIndex: String?,
    )

    /**
     * 解析出一份清单的位置。
     *
     * @param explicitManifest 命令行 `--manifest`
     * @param explicitIndex 命令行 `--index`
     * @param channel 频道 (`--channel` 或配置里的)
     */
    fun resolve(explicitManifest: String?, explicitIndex: String?, channel: String): Resolved {
        explicitManifest?.takeIf { it.isNotBlank() }?.let {
            return Resolved(Sources.locate(it, policy), null)
        }

        val indexRef = explicitIndex?.takeIf { it.isNotBlank() } ?: config.source.index?.takeIf { it.isNotBlank() }
        if (indexRef != null) {
            val indexLocator = Sources.locate(indexRef, policy)
            val index = readIndex(indexLocator)
            val pointer = index.channels[channel]
                ?: throw NoSource(
                    "索引 ${indexLocator.display} 里没有 '$channel' 频道。" +
                        "它有的频道: ${index.channels.keys.joinToString().ifEmpty { "(一个都没有)" }}",
                )
            return Resolved(indexLocator.resolve(pointer.manifest, policy), indexLocator.display)
        }

        config.source.manifest?.takeIf { it.isNotBlank() }?.let {
            return Resolved(Sources.locate(it, policy), null)
        }

        throw NoSource(NO_SOURCE_HELP)
    }

    fun readIndex(locator: Locator): ReleaseIndex {
        Log.info("读取版本索引: ${locator.display}")
        /*
         * 取不到和解不动要分开说。以前两种都报"解析失败", 于是断网、404、证书错全都被说成
         * "索引格式有问题" —— 用户会去改一个根本没错的文件。
         */
        val text = try {
            String(locator.readAll(), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException(
                "取不到版本索引 (${locator.display}): ${e.message}\n" +
                    "地址对吗? 网络通吗? 离线时可以直接跑已装好的版本, 或用 natsuri install --manifest <本地清单>。",
                e,
            )
        }
        val index = try {
            ReleaseIndex.parse(text)
        } catch (e: Exception) {
            throw IllegalStateException("版本索引解析失败 (${locator.display}): ${e.message}", e)
        }
        val problems = index.validate()
        if (problems.isNotEmpty()) {
            throw IllegalStateException("版本索引不合法 (${locator.display}):\n" + problems.joinToString("\n") { "  - $it" })
        }
        return index
    }

    fun indexLocator(explicitIndex: String?): Locator? {
        val ref = explicitIndex?.takeIf { it.isNotBlank() } ?: config.source.index?.takeIf { it.isNotBlank() }
        return ref?.let { Sources.locate(it, policy) }
    }

    fun readManifest(locator: Locator): ReleaseManifest {
        val text = String(locator.readAll(), Charsets.UTF_8)
        val manifest = ReleaseManifest.parse(text)
        val problems = manifest.validate()
        if (problems.isNotEmpty()) {
            throw IllegalStateException("清单不合法 (${locator.display}):\n" + problems.joinToString("\n") { "  - $it" })
        }
        return manifest
    }

    val hasSource: Boolean
        get() = !config.source.index.isNullOrBlank() || !config.source.manifest.isNullOrBlank()

    companion object {
        val NO_SOURCE_HELP: String = """
            还没有配置安装来源。两种办法:

            1) 本地安装 (开发、离线、验收都用这条):
                 natsuri install --manifest ./natsu-release.json
               清单与发行 zip 放在同一个目录即可 —— 清单里的 archive.url 是相对它自身解析的。
               清单可以用维护者工具从真实产物生成:
                 natsuri manifest generate --dist <natsu 的 distZip 产物或 installDist 目录> --out ./natsu-release.json

            2) 远程源 (放静态托管即可, 不需要服务端逻辑):
                 natsuri config set source.index https://<你的发布地址>/index.json
                 natsuri install

            发行包的默认来源在随包附带的 natsuri.json 里; 清空来源后需重新指定清单或索引。
            频道: ${Channels.ALL.joinToString(" / ")}
        """.trimIndent()
    }
}
