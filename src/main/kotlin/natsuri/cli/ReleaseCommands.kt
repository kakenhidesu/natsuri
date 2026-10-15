package natsuri.cli

import natsuri.ExitCodes
import natsuri.NatsuriHome
import natsuri.config.NatsuriConfig
import natsuri.install.Installer
import natsuri.manifest.ReleaseManifest
import natsuri.state.InstallState
import natsuri.util.Fs
import natsuri.util.Log
import natsuri.util.Versions
import java.nio.file.Files

/**
 * 版本相关的命令: `install` / `versions` / `update` / `use` / `pin` / `unpin` / `rollback`。
 *
 * 这几个命令共享一条原则: **改变"当前用哪个版本"永远是一次显式操作**。
 * 没有任何一条路径会在用户没要求的时候把版本换掉 —— 唯一的例外是"一个版本都没装"时的自动安装,
 * 那时没有可以被换掉的东西。
 */
class ReleaseCommands(
    private val home: NatsuriHome,
    private val config: NatsuriConfig,
    private val args: Args,
) {

    private val sourcing = Sourcing(config)
    private val installer = Installer(home, config)

    // -----------------------------------------------------------------------------------------------------
    // install
    // -----------------------------------------------------------------------------------------------------

    fun install(): Int {
        args.rejectUnknown("dir", "verbose", "quiet", "help", "manifest", "index", "channel", "no-use", "force")

        val selector = args.positional(1)
        val manifestRef = args.value("manifest")
        val indexRef = args.value("index")

        // `natsuri install <已安装版本>` = 切过去。等价于 use, 但很多人会这么敲。
        if (selector != null && manifestRef == null && indexRef == null) {
            return switchTo(selector)
        }
        if (selector != null) {
            // 两种意思同时给出来了, 而它们要装的可能是不同的东西 —— 不猜, 也不静默丢掉一个。
            throw CliError(
                "不能同时给出版本 '$selector' 和 --manifest/--index: 前者的意思是切到已装的版本, " +
                    "后者的意思是按清单装一份新的。\n" +
                    "  切版本   : natsuri use $selector\n" +
                    "  按清单装 : natsuri install --manifest <地址>",
            )
        }

        val channel = args.value("channel") ?: config.channel
        val resolved = try {
            sourcing.resolve(manifestRef, indexRef, channel)
        } catch (e: Sourcing.NoSource) {
            Log.error("无法确定安装来源。")
            println()
            println(e.message)
            return ExitCodes.NOT_INSTALLED
        }

        val manifest = installer.readManifest(resolved.locator)
        val state = InstallState.load(home.natsuriDir)
        checkPin(state, manifest.version)

        val outcome = installer.install(resolved.locator, manifest, makeCurrent = !args.flag("no-use"))
        rememberSource(resolved, manifestRef, indexRef)

        println()
        println("已安装 ${outcome.release.describe()}")
        println("  installId : ${outcome.release.installId}")
        println("  目录       : ${outcome.release.resolveDir(home.natsuriDir)}")
        println("  Java 要求  : ${outcome.release.javaMin}+")
        outcome.manifest.native?.let {
            println("  原生后端   : ${it.protocol} QQNT ${it.clientVersion} (签名版本 ${it.signVersion})")
        }
        if (args.flag("no-use")) {
            println("  (--no-use: 当前版本没有改变)")
        } else {
            println("  已设为当前版本。运行 natsuri 启动它。")
        }
        return ExitCodes.OK
    }

    /** 把这次用的源记进 natsuri.json, 下次 `update` 就不用再敲一遍。 */
    private fun rememberSource(resolved: Sourcing.Resolved, manifestRef: String?, indexRef: String?) {
        var next = config
        if (indexRef != null && indexRef != config.source.index) {
            next = next.copy(source = next.source.copy(index = indexRef))
        }
        if (manifestRef != null && resolved.viaIndex == null && manifestRef != config.source.manifest) {
            next = next.copy(source = next.source.copy(manifest = manifestRef))
        }
        if (next != config) {
            NatsuriConfig.save(home.root, next)
            Log.info("已把这次用的来源记进 ${NatsuriConfig.FILE_NAME}")
        }
    }

    // -----------------------------------------------------------------------------------------------------
    // versions
    // -----------------------------------------------------------------------------------------------------

    fun versions(): Int {
        args.rejectUnknown("dir", "verbose", "quiet", "help", "remote", "index", "channel")
        val state = InstallState.load(home.natsuriDir)

        if (state.installs.isEmpty()) {
            println("还没有安装任何 natsu 版本。")
        } else {
            println("已安装的版本 (* = 当前, ~ = 上一个):")
            for (release in state.installs.sortedByDescending { it.installedAt }) {
                val marker = when (release.installId) {
                    state.current -> "*"
                    state.previous -> "~"
                    else -> " "
                }
                val dirExists = Files.isDirectory(release.resolveDir(home.natsuriDir))
                println(
                    " $marker ${release.installId.padEnd(34)} ${release.channel.padEnd(8)} " +
                        "Java ${release.javaMin}+  装于 ${release.installedAt}" +
                        (if (dirExists) "" else "   [目录已丢失]"),
                )
            }
        }
        state.pinned?.let { println("\n版本已锁定在: $it  (natsuri unpin 解除)") }

        if (args.flag("remote")) {
            // 读远程失败要以非零退出 —— 打一行 [ERROR] 然后返回 0, 脚本会以为一切正常。
            return printRemote(state)
        }
        if (sourcing.hasSource) {
            println("\n(加 --remote 可以列出源上的版本)")
        }
        return ExitCodes.OK
    }

    private fun printRemote(state: InstallState): Int {
        val locator = try {
            sourcing.indexLocator(args.value("index"))
        } catch (e: Exception) {
            Log.error("解析索引地址失败: ${e.message}")
            return ExitCodes.FAILED
        }
        if (locator == null) {
            println()
            println("没有配置版本索引, 无法列出远程版本。")
            println("natsuri config set source.index <索引地址>")
            return ExitCodes.NOT_INSTALLED
        }
        val index = try {
            sourcing.readIndex(locator)
        } catch (e: Exception) {
            Log.error("读取索引失败: ${e.message}", e)
            return ExitCodes.FAILED
        }
        println("\n源 ${locator.display} 上的版本:")
        for ((channel, pointer) in index.channels) {
            println("  [$channel] ${pointer.version}${if (pointer.buildId.isNotBlank()) " (build ${pointer.buildId})" else ""}")
        }
        if (index.releases.isNotEmpty()) {
            /*
             * 索引里的 `sha256` 是归档摘要, 而本地 installId 里带的正是它的前 12 位 ——
             * 于是不抓每一份清单就能说出"这个我已经装了"。索引没写 sha256 时如实留空,
             * 不拿版本号去猜 (同名重发的两份版本号是一样的)。
             */
            val installedDigests = state.installs.map { it.archiveSha256.lowercase() }.toSet()
            println("  全部发行:")
            for (entry in index.releases) {
                val mark = when {
                    entry.archiveSha256.isBlank() -> ""
                    entry.archiveSha256.lowercase() in installedDigests -> "  [已安装]"
                    else -> ""
                }
                println("    ${entry.version.padEnd(24)} ${entry.channel.padEnd(8)} ${entry.publishedAt}$mark")
            }
            if (index.releases.all { it.archiveSha256.isBlank() }) {
                println("  (索引里没有 sha256, 无法标出哪些已经装过)")
            }
        }
        return ExitCodes.OK
    }

    // -----------------------------------------------------------------------------------------------------
    // update
    // -----------------------------------------------------------------------------------------------------

    /** `update --check`: **只查不改**。任何情况下都不写状态、不下载归档。 */
    fun updateCheck(): Int {
        args.rejectUnknown("dir", "verbose", "quiet", "help", "check", "channel", "index")
        val state = InstallState.load(home.natsuriDir)
        val channel = args.value("channel") ?: config.channel

        val resolved = try {
            sourcing.resolve(null, args.value("index"), channel)
        } catch (e: Sourcing.NoSource) {
            println("没有配置更新源, 无法检查更新。")
            println()
            println(e.message)
            return ExitCodes.NOT_INSTALLED
        }
        val manifest = sourcing.readManifest(resolved.locator)
        val current = state.currentRelease

        println("频道 $channel 上的最新发行: ${manifest.version}" + buildIdSuffix(manifest))
        println("  清单     : ${resolved.locator.display}")
        println("  归档     : ${Fs.humanSize(manifest.archive.size)}, sha256 ${manifest.archive.sha256.take(16)}…")
        println("  Java 要求 : ${manifest.javaMin}+")
        manifest.native?.let { println("  原生后端  : ${it.protocol} QQNT ${it.clientVersion} (签名版本 ${it.signVersion})") }

        if (current == null) {
            println("\n本地还没有安装任何版本 —— natsuri install 会装上它。")
            return ExitCodes.OK
        }
        println("\n当前安装: ${current.version}${if (current.buildId.isNotBlank()) " (build ${current.buildId})" else ""}")

        when {
            current.installId == manifest.installId ->
                println("已经是这一份了 (installId 相同), 无需更新。")

            current.version == manifest.version -> {
                // 同版本不同内容 —— SNAPSHOT 的常态。这里必须说清楚是靠摘要判断的。
                println("版本号相同但内容不同 (归档摘要不一样): 本地 ${current.archiveSha256.take(12)}… -> 源上 ${manifest.archive.sha256.take(12)}…")
                println("这是同名重新发布。natsuri update 会换成源上那一份。")
            }

            !Versions.isComparable(current.version, manifest.version) ->
                println("两个版本号无法按 SemVer 比较, natsuri 不猜哪个新。要换就显式 natsuri install / use。")

            Versions.isNewer(manifest.version, current.version) -> println("有更新可用。")

            else -> println("源上的版本比本地的旧, natsuri update 不会自动降级 —— 需要降级请显式 natsuri install --manifest。")
        }

        state.pinned?.let {
            println("\n注意: 版本被锁定在 $it, natsuri update 不会越过它 (natsuri unpin 解除)。")
        }
        // --check 到此为止: 没有下载, 没有写任何文件。
        return ExitCodes.OK
    }

    fun update(): Int {
        args.rejectUnknown("dir", "verbose", "quiet", "help", "check", "channel", "index", "force")
        val state = InstallState.load(home.natsuriDir)
        val channel = args.value("channel") ?: config.channel

        val resolved = try {
            sourcing.resolve(null, args.value("index"), channel)
        } catch (e: Sourcing.NoSource) {
            Log.error("没有配置更新源。")
            println()
            println(e.message)
            return ExitCodes.NOT_INSTALLED
        }
        val manifest = installer.readManifest(resolved.locator)
        val current = state.currentRelease

        checkPin(state, manifest.version)

        if (current != null && current.installId == manifest.installId) {
            println("已经是最新的了: ${current.describe()}")
            return ExitCodes.OK
        }
        if (current != null && current.version != manifest.version &&
            Versions.isComparable(current.version, manifest.version) &&
            !Versions.isNewer(manifest.version, current.version) && !args.flag("force")
        ) {
            Log.error(
                "源上的 ${manifest.version} 比当前的 ${current.version} 旧, update 不做自动降级。\n" +
                    "确实要降级: natsuri install --manifest ${resolved.locator.display}",
            )
            return ExitCodes.FAILED
        }

        val outcome = installer.install(resolved.locator, manifest, makeCurrent = true)
        println()
        if (outcome.alreadyInstalled) {
            println("这一份本来就装着, 已切换过去: ${outcome.release.describe()}")
        } else {
            println("已更新到 ${outcome.release.describe()}")
            current?.let { println("上一个版本 ${it.describe()} 仍保留着, natsuri rollback 可以回去。") }
        }
        println("下次运行 natsuri 就会用新版本。")
        return ExitCodes.OK
    }

    // -----------------------------------------------------------------------------------------------------
    // use / pin / unpin / rollback
    // -----------------------------------------------------------------------------------------------------

    fun use(): Int {
        args.rejectUnknown("dir", "verbose", "quiet", "help")
        val selector = args.positional(1)
            ?: throw CliError("用法: natsuri use <版本或 installId>。可用的版本用 natsuri versions 看。")
        return switchTo(selector)
    }

    private fun switchTo(selector: String): Int {
        val state = InstallState.load(home.natsuriDir)
        val target = state.resolve(selector)
            ?: throw CliError(
                "没有安装过 '$selector'。已安装: " +
                    state.installs.joinToString { it.version }.ifEmpty { "(无)" },
            )
        if (state.current == target.installId) {
            println("当前已经是 ${target.describe()} 了。")
            return ExitCodes.OK
        }
        val release = installer.select(target.installId)
        println("已切换到 ${release.describe()}  (${release.installId})")
        println("上一个版本 ${state.currentRelease?.describe() ?: "(无)"} 保留着, natsuri rollback 可以回去。")
        return ExitCodes.OK
    }

    fun pin(): Int {
        args.rejectUnknown("dir", "verbose", "quiet", "help")
        val version = args.positional(1)
            ?: throw CliError("用法: natsuri pin <版本>")
        val state = InstallState.load(home.natsuriDir)
        val known = state.installs.map { it.version }.distinct()
        if (version !in known) {
            Log.warn("本地没有装过 $version (已装: ${known.joinToString().ifEmpty { "无" }}) —— 仍然锁定, 但要先装上才能用。")
        }
        InstallState.save(home.natsuriDir, state.copy(pinned = version))
        println("已锁定版本: $version")
        println("natsuri update 从现在起不会越过它; 要换版本请先 natsuri unpin, 或用 --force 显式越过。")
        return ExitCodes.OK
    }

    fun unpin(): Int {
        args.rejectUnknown("dir", "verbose", "quiet", "help")
        val state = InstallState.load(home.natsuriDir)
        if (state.pinned == null) {
            println("本来就没有锁定。")
            return ExitCodes.OK
        }
        InstallState.save(home.natsuriDir, state.copy(pinned = null))
        println("已解除对 ${state.pinned} 的锁定。")
        return ExitCodes.OK
    }

    /**
     * 回滚。
     *
     * 边界说在前面, 因为这是最容易被误解的一个命令: 它把**程序与 classpath** 换回上一个版本。
     * 插件写进 `data/` 的数据、Console 或插件做过的配置迁移, natsuri 既没备份也没有语义去理解,
     * **回不去**。所以这里明确打印出来, 而不是让人以为 rollback 是个时光机。
     */
    fun rollback(): Int {
        args.rejectUnknown("dir", "verbose", "quiet", "help")
        val state = InstallState.load(home.natsuriDir)
        val previous = state.previousRelease
            ?: throw CliError(
                "没有可回滚的上一个版本。\n" +
                    "natsuri 只在切换/更新时记住前一个版本; 第一次安装之后是没有的。",
            )
        val current = state.currentRelease

        val dir = previous.resolveDir(home.natsuriDir)
        if (!Files.isDirectory(dir)) {
            throw CliError(
                "上一个版本 ${previous.installId} 的目录不在了: $dir\n" +
                    "它可能被 keepPreviousReleases 清理掉了。用 natsuri install 重新装一份。",
            )
        }

        val release = installer.select(previous.installId)
        println("已回滚到 ${release.describe()}  (${release.installId})")
        current?.let { println("刚才的 ${it.describe()} 现在成了\"上一个\", 再执行一次 rollback 就回去了。") }
        println()
        println("回滚的范围: 程序文件与 classpath。")
        println("**不包括**: natsu.yml、bots/、config/、data/、plugins/ —— 那些一个字节都没动,")
        println("           也包括插件已经做过的数据迁移, 那些 natsuri 无法回退。")
        return ExitCodes.OK
    }

    // -----------------------------------------------------------------------------------------------------

    private fun checkPin(state: InstallState, targetVersion: String) {
        val pinned = state.pinned ?: return
        if (targetVersion == pinned) return
        if (args.flag("force")) {
            Log.warn("--force: 越过版本锁定 $pinned, 装的是 $targetVersion (锁定本身不变)")
            return
        }
        throw CliError(
            "版本被锁定在 $pinned, 而这次要装的是 $targetVersion。\n" +
                "  解除锁定 : natsuri unpin\n" +
                "  仅此一次 : 在命令后加 --force",
        )
    }

    private fun buildIdSuffix(manifest: ReleaseManifest): String =
        if (manifest.buildId.isNotBlank()) " (build ${manifest.buildId})" else ""
}
