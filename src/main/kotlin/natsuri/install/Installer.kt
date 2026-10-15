package natsuri.install

import natsuri.NatsuriHome
import natsuri.archive.ZipExtractor
import natsuri.config.NatsuriConfig
import natsuri.manifest.ReleaseManifest
import natsuri.source.Downloader
import natsuri.source.Locator
import natsuri.source.SourcePolicy
import natsuri.state.InstallState
import natsuri.state.InstalledRelease
import natsuri.util.Fs
import natsuri.util.Hashes
import natsuri.util.Log
import natsuri.util.PathNames
import natsuri.verify.DistributionVerifier
import java.nio.file.Files
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 安装 / 更新事务。
 *
 * ## 顺序不能变
 *
 * ```
 * 下载到临时路径  ->  校验大小与 SHA-256  ->  解到 staging/  ->  校验入口、classpath 与冲突
 *                ->  搬进 releases/<installId>/  ->  原子改写 state.json  ->  清理旧版本
 * ```
 *
 * 前四步全都发生在 `downloads/` 与 `staging/` 里 —— 那两个目录是 natsuri 的草稿纸,
 * 随便失败。**唯一改变"当前用哪个版本"的动作是最后那次 `state.json` 原子替换**;
 * 在它之前中断多少次, 原来那个能跑的版本都一个字节没动。
 *
 * ## 为什么保留上一版
 *
 * 回滚要的是"上一份**能跑的东西还在盘上**", 不是"再下一次"。断网时能回滚才有意义。
 * 但要说清楚回滚的边界: 它只回滚**程序与 classpath**, 不回滚插件写进 `data/` 的数据,
 * 也不回滚 Console 或插件做过的配置迁移 —— 那些 natsuri 既没有备份也没有语义去理解。
 */
class Installer(
    private val home: NatsuriHome,
    private val config: NatsuriConfig,
) {

    private val policy = SourcePolicy(config.source.allowInsecure)

    data class Outcome(
        val release: InstalledRelease,
        val manifest: ReleaseManifest,
        val alreadyInstalled: Boolean,
        val verification: DistributionVerifier.Result?,
    )

    /** 读一份清单并校验它自身的合法性 (还没碰归档)。 */
    fun readManifest(locator: Locator): ReleaseManifest {
        Log.info("读取发行清单: ${locator.display}")
        // 取不到与解不动分开报 —— 断网被说成"清单格式有问题"会把人引去改一个没错的文件。
        val text = try {
            String(locator.readAll(), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException("取不到发行清单 (${locator.display}): ${e.message}", e)
        }
        val manifest = try {
            ReleaseManifest.parse(text)
        } catch (e: Exception) {
            throw IllegalStateException("清单解析失败 (${locator.display}): ${e.message}", e)
        }
        val problems = manifest.validate()
        if (problems.isNotEmpty()) {
            throw IllegalStateException("清单不合法 (${locator.display}):\n" + problems.joinToString("\n") { "  - $it" })
        }
        return manifest
    }

    /**
     * 安装一份发行。已经装过 (installId 相同且目录完好) 就直接返回, 不重复下载。
     *
     * @param manifestLocator 清单位置; 归档地址相对它解析
     * @param makeCurrent 装完是否把它设为当前版本
     */
    fun install(manifestLocator: Locator, manifest: ReleaseManifest, makeCurrent: Boolean): Outcome {
        val state = InstallState.load(home.natsuriDir)
        val installId = manifest.installId
        /*
         * installId 直接当目录名用, 所以在**任何**删除或解压之前先确认它解析后仍在 releases/ 与
         * staging/ 之内。`readManifest` 里的 version 检查已经挡过一道, 这里是第二道 ——
         * 这两个动作不可逆, 值得各挡一次。
         */
        val targetDir = PathNames.resolveInside(home.releasesDir, installId, "installId")

        val existing = state.find(installId)
        if (existing != null && Files.isDirectory(targetDir)) {
            val check = DistributionVerifier.verify(targetDir, existing.classpath, existing.mainClass)
            if (check.ok) {
                Log.info("$installId 已经装好了, 跳过下载")
                if (makeCurrent && state.current != installId) select(installId)
                return Outcome(existing, manifest, alreadyInstalled = true, verification = check)
            }
            Log.warn("已安装的 $installId 校验不过 (${check.errors.size} 项), 重新安装")
        }

        // ---- 1. 取归档 (本地直接校验, 远程续传下载); 出来的一定是校验过的文件 ----
        val archiveLocator = manifestLocator.resolve(manifest.archive.url, policy)
        Log.info(
            "获取归档: ${archiveLocator.display} (${Fs.humanSize(manifest.archive.size)}, " +
                "sha256 ${manifest.archive.sha256.take(12)}…)",
        )
        val archive = Downloader.obtain(
            locator = archiveLocator,
            downloadsDir = home.downloadsDir,
            expectedSize = manifest.archive.size,
            expectedSha256 = manifest.archive.sha256,
            options = Downloader.Options(
                connectTimeoutSeconds = config.download.connectTimeoutSeconds,
                requestTimeoutSeconds = config.download.requestTimeoutSeconds,
                maxRetries = config.download.maxRetries,
                maxBytes = config.download.maxArchiveBytes,
            ),
            onProgress = ProgressPrinter(manifest.archive.size)::report,
        )
        Log.info("归档校验通过")

        // ---- 2. 解到 staging/ ----
        val staging = PathNames.resolveInside(home.stagingDir, installId, "installId")
        Fs.deleteRecursively(staging)
        val report = try {
            ZipExtractor.extract(archive, staging, manifest.archive.rootDir)
        } catch (e: Throwable) {
            Fs.deleteRecursively(staging)
            throw e
        }
        Log.info("解压完成: ${report.entries} 个条目 / ${Fs.humanSize(report.bytes)} (${report.symlinkCheck})")

        // ---- 3. 校验解出来的东西: 入口、classpath、mirai 冲突 ----
        // 从这里到搬运完成之前, 任何失败都必须把 staging 清干净 —— 留着的话下次安装会先看到一棵
        // 半成品的树, 而它长得和装好的一模一样。
        val verification = try {
            val result = DistributionVerifier.verify(staging, manifest.launch.classpath, manifest.launch.mainClass)
            result.warnings.forEach { Log.warn(it) }
            if (!result.ok) {
                throw IllegalStateException(
                    "发行校验未通过, 已丢弃这次安装 (原有版本未受影响):\n" +
                        result.errors.joinToString("\n") { "  - $it" },
                )
            }
            verifyComponents(manifest, staging)
            result
        } catch (e: Throwable) {
            Fs.deleteRecursively(staging)
            throw e
        }
        Log.info(verification.summary)

        // ---- 4. 搬进 releases/ ----
        /*
         * 目标目录已经存在时**先挪开、搬完再删**, 而不是先删后搬。
         *
         * 走到这一步的"已存在"只有一种来路: 同一个 installId 装过但校验不过 (上面第一段的
         * `Log.warn("...重新安装")`)。即便如此也不该先把它删掉 —— 万一移动失败, 用户手里就从
         * "一个坏掉但还在的版本"变成"什么都没有"。
         */
        var displaced: java.nio.file.Path? = null
        if (Files.exists(targetDir)) {
            displaced = targetDir.resolveSibling("${targetDir.fileName}.replacing-${ProcessHandle.current().pid()}")
            Fs.deleteRecursively(displaced)
            Log.warn("同名版本已存在, 先挪开再替换: ${targetDir.fileName}")
            Fs.moveDirectory(targetDir, displaced)
        }
        try {
            Fs.moveDirectory(staging, targetDir)
        } catch (e: Throwable) {
            // 搬不过去就把旧的放回原位, 至少不比开始时更糟。
            displaced?.let { runCatching { Fs.moveDirectory(it, targetDir) } }
            throw e
        }
        displaced?.let { runCatching { Fs.deleteRecursively(it) } }

        // ---- 5. 原子改写状态 ----
        val now = timestamp()
        val record = InstalledRelease.from(
            manifest = manifest,
            dir = "releases/$installId",
            sourceManifest = manifestLocator.display,
            installedAt = now,
            verifiedAt = now,
        )
        val without = state.installs.filterNot { it.installId == installId }
        var next = state.copy(installs = without + record)
        if (makeCurrent && state.current != installId) {
            next = next.copy(current = installId, previous = state.current)
        }
        InstallState.save(home.natsuriDir, next)
        Log.info("已安装 ${record.describe()}  ->  ${targetDir.fileName}")

        prune()
        return Outcome(record, manifest, alreadyInstalled = false, verification = verification)
    }

    /** 切换当前版本。目录必须在且能通过校验 —— 切到一个坏掉的版本比不切更糟。 */
    fun select(installId: String): InstalledRelease {
        val state = InstallState.load(home.natsuriDir)
        val release = state.find(installId)
            ?: throw IllegalStateException("没有安装过 $installId")
        val dir = release.resolveDir(home.natsuriDir)
        if (!Files.isDirectory(dir)) {
            throw IllegalStateException("$installId 的目录不在了: $dir —— 用 natsuri install 重新安装")
        }
        val check = DistributionVerifier.verify(dir, release.classpath, release.mainClass)
        if (!check.ok) {
            throw IllegalStateException(
                "$installId 的发行校验未通过, 拒绝切换:\n" + check.errors.joinToString("\n") { "  - $it" },
            )
        }
        if (state.current == installId) return release
        InstallState.save(home.natsuriDir, state.copy(current = installId, previous = state.current))
        return release
    }

    /** 逐文件复核 (清单带了 components 时)。安装时做一次, 之后 `doctor --deep` 可以再做。 */
    private fun verifyComponents(manifest: ReleaseManifest, appRoot: java.nio.file.Path) {
        if (manifest.components.isEmpty()) return
        val bad = ArrayList<String>()
        for (component in manifest.components) {
            val file = appRoot.resolve(component.path)
            if (!Files.isRegularFile(file)) {
                bad += "缺少 ${component.path}"
                continue
            }
            val size = Files.size(file)
            if (size != component.size) {
                bad += "${component.path} 大小 $size, 清单说 ${component.size}"
                continue
            }
            val sha = Hashes.sha256(file)
            if (!Hashes.matches(component.sha256, sha)) bad += "${component.path} 的 SHA-256 不符"
        }
        if (bad.isNotEmpty()) {
            throw IllegalStateException("归档内文件与清单不符:\n" + bad.take(10).joinToString("\n") { "  - $it" })
        }
        Log.info("逐文件校验通过 (${manifest.components.size} 个)")
    }

    /**
     * 清理旧版本目录。
     *
     * 保护名单: 当前版本、上一版本 (回滚要用)、被 pin 住的版本。
     * 其余按安装时间从新到旧保留 `keepPreviousReleases` 个。
     */
    fun prune() {
        val state = InstallState.load(home.natsuriDir)
        val protectedIds = HashSet<String>()
        state.current?.let { protectedIds += it }
        state.previous?.let { protectedIds += it }
        state.pinned?.let { pin -> state.installs.filter { it.version == pin }.forEach { protectedIds += it.installId } }

        /*
         * 新旧排序以 installedAt 为主, **列表下标为次**。
         * 只按时间戳排是不够的: 时间戳精确到秒, 同一秒里装两个版本时排序就退化成"原样",
         * 于是被清掉的可能恰好是较新的那个。installs 是按安装顺序追加的, 下标正好补上这一位。
         */
        val removable = state.installs.withIndex()
            .filter { it.value.installId !in protectedIds }
            .sortedWith(
                compareByDescending<IndexedValue<InstalledRelease>> { it.value.installedAt }
                    .thenByDescending { it.index },
            )
            .map { it.value }
            .drop(config.keepPreviousReleases)

        if (removable.isEmpty()) return
        var next = state
        for (release in removable) {
            val dir = release.resolveDir(home.natsuriDir)
            Log.info("清理旧版本 ${release.installId}")
            val deleted = runCatching { Fs.deleteRecursively(dir) }
                .onFailure {
                    // Windows 上文件可能还被谁占着。删不掉就**不要**从状态里划掉它 ——
                    // 记录与磁盘保持一致, 下次再清。
                    Log.warn("删除 $dir 失败: ${it.message} (记录保留, 下次再清)")
                }
                .isSuccess
            if (deleted) {
                next = next.copy(installs = next.installs.filterNot { it.installId == release.installId })
            }
        }
        if (next != state) InstallState.save(home.natsuriDir, next)
    }

    private class ProgressPrinter(private val total: Long) {
        private var lastPercent = -1

        fun report(done: Long, @Suppress("UNUSED_PARAMETER") ignored: Long) {
            if (total <= 0) return
            val percent = ((done * 100) / total).toInt()
            if (percent / 5 == lastPercent / 5 && percent != 100) return
            lastPercent = percent
            System.err.print("\r  下载中 $percent%  (${Fs.humanSize(done)} / ${Fs.humanSize(total)})   ")
            if (percent >= 100) System.err.println()
        }
    }

    companion object {
        fun timestamp(): String =
            ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))
    }
}
