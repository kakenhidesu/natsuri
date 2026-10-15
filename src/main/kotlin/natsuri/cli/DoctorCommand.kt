package natsuri.cli

import natsuri.ExitCodes
import natsuri.NatsuriHome
import natsuri.config.NatsuriConfig
import natsuri.launch.JavaLocator
import natsuri.plugins.PluginManager
import natsuri.state.InstallState
import natsuri.util.Fs
import natsuri.util.Hashes
import natsuri.util.Log
import natsuri.util.MiniYaml
import natsuri.util.RunLock
import natsuri.verify.DistributionVerifier
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration

/**
 * `natsuri doctor` —— 体检。
 *
 * 两条纪律:
 *
 * 1. **只诊断, 不修**。不改配置、不重新下载、不切版本、不创建业务目录。发现问题就说是什么、
 *    并给出该敲哪条命令 —— 让"看一眼情况"这个动作永远是安全的。
 * 2. **不发起 QQ 登录**, 一次都不。签名服务那一项只做可达性探测。
 *
 * ## 签名服务这一项为什么只说一半
 *
 * natsu 的签名请求是 `POST <sign.url>/<appClientVersion>`, 带上命令名与报文体。
 * 但当前的签名服务**可能忽略路径里的那个版本号**, 于是"返回 200"只证明有个服务在那儿,
 * **不证明它支持 51102**。在没有一个可核查的能力接口之前, doctor 只能报"可达 / 不可达",
 * 并明说版本未验证。伪造一个"匹配"的结论, 会让人在真正登录失败时朝完全错误的方向排查。
 */
class DoctorCommand(
    private val home: NatsuriHome,
    private val config: NatsuriConfig,
    private val args: Args,
) {

    private val problems = ArrayList<String>()
    private val notes = ArrayList<String>()

    fun execute(): Int {
        args.rejectUnknown("dir", "verbose", "quiet", "help", "deep")
        val deep = args.flag("deep")

        /*
         * 每一节都各自兜住异常。doctor 是用来诊断坏掉的环境的 —— 一个损坏的 state.json、
         * 一个读不动的 jar、一个非法的 JAVA_HOME 都不该让报告在中途断掉:
         * 半份报告比没有报告更容易把人引偏。
         */
        section("natsuri")
        guard("natsuri") { checkHome() }

        section("配置")
        guard("配置") { checkConfig() }

        section("已安装的 natsu 版本")
        val state = try {
            checkInstalls(deep)
        } catch (e: Exception) {
            bad("检查已安装版本时出错: ${e.message}")
            InstallState()
        }

        section("Java")
        guard("Java") { checkJava(state) }

        section("natsu 的配置与运行目录")
        guard("natsu 配置") { checkNatsuConfig() }

        section("插件")
        guard("插件") { checkPlugins() }

        println()
        if (problems.isEmpty()) {
            println("体检结果: 没有发现问题。" + if (notes.isEmpty()) "" else " (有 ${notes.size} 条提示, 见上文)")
            return ExitCodes.OK
        }
        println("体检结果: 发现 ${problems.size} 个问题:")
        problems.forEach { println("  - $it") }
        return ExitCodes.FAILED
    }

    private fun section(title: String) {
        println()
        println("== $title ==")
    }

    /** 跑一节检查, 出错就记成一个问题并继续下一节。 */
    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            bad("检查「$what」时出错: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun ok(text: String) = println("  [ok]   $text")
    private fun info(text: String) = println("  [--]   $text")

    private fun warn(text: String) {
        println("  [warn] $text")
        notes += text
    }

    private fun bad(text: String) {
        println("  [BAD]  $text")
        problems += text
    }

    // -----------------------------------------------------------------------------------------------------

    private fun checkHome() {
        info("版本: ${javaClass.`package`?.implementationVersion ?: "(开发版本)"}")
        info("运行目录: ${home.root}")
        if (!Files.isDirectory(home.root)) {
            bad("运行目录不存在: ${home.root}")
            return
        }
        // 能不能写 .natsuri 决定了几乎所有命令能不能干活, 所以真的试一下, 而不是看权限位。
        try {
            Files.createDirectories(home.natsuriDir)
            val probe = home.natsuriDir.resolve(".write-probe")
            Files.writeString(probe, "ok")
            Files.deleteIfExists(probe)
            ok("可以写 .natsuri/")
        } catch (e: Exception) {
            bad("写不了 ${home.natsuriDir}: ${e.message}")
        }

        val holder = RunLock.probe(home.natsuriDir)
        if (holder != null) {
            warn("这个运行目录正被占用 ($holder) —— 更新 / 切换 / 回滚会被拒绝")
        } else {
            ok("没有别的 natsuri 占着这个目录")
        }

        runCatching {
            val store = Files.getFileStore(home.natsuriDir)
            val free = store.usableSpace
            info("可用磁盘: ${Fs.humanSize(free)}")
            if (free < 512L * 1024 * 1024) warn("可用磁盘不足 512 MiB, 更新时可能放不下下载与解压的中间文件")
        }
    }

    private fun checkConfig() {
        if (NatsuriConfig.exists(home.root)) {
            ok("${NatsuriConfig.FILE_NAME} 存在")
        } else {
            info("${NatsuriConfig.FILE_NAME} 不存在, 当前使用默认配置 (这不是问题)")
        }
        config.validate().forEach { bad("natsuri.json: $it") }

        val index = config.source.index
        val manifest = config.source.manifest
        when {
            !index.isNullOrBlank() -> ok("更新源 (索引): ${Log.redact(index)}   频道 ${config.channel}")
            !manifest.isNullOrBlank() -> {
                ok("安装来源 (单份清单): ${Log.redact(manifest)}")
                info("没有配置版本索引, 所以 natsuri update 只能重新读这一份清单")
            }
            else -> {
                warn("没有配置任何来源 —— 可以离线运行已装好的版本, 但没法检查更新")
                info("配置办法见 natsuri --help 的 install 一节")
            }
        }
        if (config.source.allowInsecure) {
            warn("source.allowInsecure = true: 允许明文 HTTP 源。发行包的来源可信度依赖 HTTPS, 只在内网测试源上开它")
        }
        if (config.jvmArgs.isNotEmpty()) info("附加 JVM 参数: ${config.jvmArgs.joinToString(" ")}")
        if (config.consoleArgs.isNotEmpty()) info("附加 Console 参数: ${config.consoleArgs.joinToString(" ")}")
    }

    private fun checkInstalls(deep: Boolean): InstallState {
        val state = try {
            InstallState.load(home.natsuriDir)
        } catch (e: Exception) {
            bad(e.message ?: "state.json 读取失败")
            return InstallState()
        }

        if (state.installs.isEmpty()) {
            warn("一个 natsu 版本都没装 —— natsuri install --manifest <清单> 装一份")
            return state
        }
        ok("已安装 ${state.installs.size} 个版本")
        state.pinned?.let { info("版本锁定在 $it") }

        val current = state.currentRelease
        if (current == null) {
            bad("state.json 里没有当前版本 (current) —— natsuri use <版本> 选一个")
            return state
        }
        info("当前版本: ${current.describe()}   installId ${current.installId}")
        info("  归档摘要: ${current.archiveSha256}")
        current.nativeClientVersion.takeIf { it.isNotBlank() }?.let {
            info("  原生后端: ${current.nativeProtocol} QQNT $it, 签名版本 ${current.nativeSignVersion}")
            info("  (Linux 指的是 QQ 协议身份, 与 natsuri 跑在哪个操作系统上无关)")
        }

        val dir = current.resolveDir(home.natsuriDir)
        if (!Files.isDirectory(dir)) {
            bad("当前版本的目录不在了: $dir")
            return state
        }

        val missing = current.classpath.filterNot { Files.isRegularFile(dir.resolve(it)) }
        if (missing.isNotEmpty()) {
            bad("classpath 有 ${missing.size} 个文件缺失, 例如 ${missing.take(3)}")
        } else {
            ok("classpath 上的 ${current.classpath.size} 个文件都在")
        }

        if (deep) {
            val result = DistributionVerifier.verify(dir, current.classpath, current.mainClass)
            result.warnings.forEach { warn(it) }
            if (result.ok) {
                ok(result.summary)
            } else {
                result.errors.forEach { bad("发行校验: $it") }
            }
            checkComponentDigests(current, dir)
            info("Java 要求: ${current.javaMin}+")
        } else {
            info("(加 --deep 会重新做一遍完整的发行校验: 逐类归属、ServiceLoader 冲突、防夹带表、逐文件摘要)")
        }

        state.previousRelease?.let {
            val exists = Files.isDirectory(it.resolveDir(home.natsuriDir))
            if (exists) ok("可回滚到 ${it.describe()}")
            else warn("state.json 里的上一个版本 ${it.installId} 目录已不在, rollback 会失败")
        } ?: info("没有可回滚的上一个版本 (第一次安装之后本来就没有)")

        return state
    }

    /**
     * 逐文件复核: 盘上的每个 jar 还是当初装的那一份吗。
     *
     * `DistributionVerifier` 看的是"类由谁提供"这类结构性问题, 它**看不出**一个 jar 被人整个换掉 ——
     * 换进去的那份只要类名一样就照样通过。真正能发现这件事的只有摘要, 所以 `--deep` 必须做这一项;
     * 文档里也是这么写的。
     */
    private fun checkComponentDigests(release: natsuri.state.InstalledRelease, dir: java.nio.file.Path) {
        if (release.components.isEmpty()) {
            warn("这个版本的记录里没有逐文件摘要 (清单没提供 components), 无法核对单个 jar 是否被替换过")
            return
        }
        val mismatches = ArrayList<String>()
        for (component in release.components) {
            val file = dir.resolve(component.path)
            when {
                !Files.isRegularFile(file) -> mismatches += "${component.path} 不在了"
                Files.size(file) != component.size ->
                    mismatches += "${component.path} 大小 ${Files.size(file)}, 安装时是 ${component.size}"
                !Hashes.matches(component.sha256, Hashes.sha256(file)) ->
                    mismatches += "${component.path} 的内容与安装时不同"
            }
        }
        if (mismatches.isEmpty()) {
            ok("逐文件摘要核对通过 (${release.components.size} 个文件与安装时一致)")
        } else {
            mismatches.take(10).forEach { bad(it) }
            if (mismatches.size > 10) bad("…另有 ${mismatches.size - 10} 个文件不一致")
        }
    }

    private fun checkJava(state: InstallState) {
        val required = maxOf(state.currentRelease?.javaMin ?: 21, config.java.minVersion ?: 0)
        val candidates = try {
            JavaLocator.candidates(config.java.path)
        } catch (e: Exception) {
            // doctor 是用来诊断坏掉的环境的, 它自己不能被环境弄死。
            bad("查找 java 时出错: ${e.message}")
            return
        }
        // 显式配置了却没解析出来 —— 那是配置错误, 不是"用别的凑合一下"。
        if (!config.java.path.isNullOrBlank() && candidates.none { it.origin == JavaLocator.CONFIG_ORIGIN }) {
            bad(
                "natsuri.json 的 java.path 指向 '${config.java.path}', 但那里没有可执行的 java —— " +
                    "natsuri 不会因此换用别的 JVM, 启动会直接失败",
            )
        }
        if (candidates.isEmpty()) {
            bad("找不到任何 java 可执行文件。装一个 Java $required+ 并放进 PATH, 或在 natsuri.json 里配 java.path")
            return
        }
        for (c in candidates) {
            val v = c.version
            val mark = when {
                v == null -> "版本未知"
                v.major >= required -> "${v.raw} ✓"
                else -> "${v.raw} —— 低于所需的 $required"
            }
            info("${c.path}  <- ${c.origin}, $mark")
        }
        val usable = candidates.firstOrNull { (it.version?.major ?: -1) >= required }
        if (usable == null) {
            bad("没有满足 Java $required+ 的运行时。natsu 用 Java 21 编译, 版本不够会以 UnsupportedClassVersionError 结束")
        } else {
            ok("将使用 ${usable.path} (${usable.version})")
        }
        info("natsuri 不会自动下载 Java —— 装运行时是系统级决定, 交给你自己做")
    }

    private fun checkNatsuConfig() {
        val workingDir = home.workingDir(config)
        info("Console 工作目录: $workingDir")

        val configFile = home.natsuConfigFile(config)
        if (!Files.exists(configFile)) {
            info("还没有 natsu.yml —— 第一次运行 natsuri 时由 Console 生成模板, 那不是崩溃")
            info("natsuri 不生成、也不修改 natsu.yml")
            return
        }
        ok("natsu.yml 存在: $configFile")

        val doc = runCatching { MiniYaml.parse(Files.readString(configFile)) }.getOrNull()
        if (doc == null) {
            warn("natsu.yml 读不出来 (编码?), 跳过后端与签名服务的检查")
            return
        }
        val backend = doc.scalar("backend.type")?.lowercase()
        when (backend) {
            null -> {
                // MiniYaml 只认简单结构; 读不到不等于没写。绝不因此假设它是 native。
                warn("从 natsu.yml 里读不出 backend.type (natsuri 的 YAML 读取器只认简单结构), 跳过签名服务检查")
            }

            "onebot11" -> {
                ok("backend.type = onebot11 —— 用外部 OneBot 实现, **不需要**签名服务")
                doc.scalar("backend.onebot11.mode")?.let { info("  模式: $it") }
                doc.scalar("backend.onebot11.url")?.let { info("  地址: ${Log.redact(it)}") }
            }

            "native" -> {
                ok("backend.type = native —— natsu 自己的 QQNT 协议栈")
                checkSignService(doc.scalar("sign.url"))
            }

            else -> bad("natsu.yml 的 backend.type 是 '$backend', natsu 只支持 native 或 onebot11")
        }

        val accounts = doc.sequence("accounts")
        if (accounts.isNullOrEmpty()) info("accounts 是空的 —— 启动后不会自动登录任何账号")
        else info("配置了 ${accounts.size} 个自动登录账号")

        // 业务目录: 只报告, 不创建。它们由 Console 按需生成。
        val businessDirs = listOf("plugins", "config", "data", "bots", "logs", "plugin-libraries", "plugin-shared-libraries")
        val present = businessDirs.filter { Files.isDirectory(workingDir.resolve(it)) }
        info("已有的业务目录: ${present.joinToString().ifEmpty { "(还没有, Console 会按需创建)" }}")
    }

    /**
     * 签名服务探测。
     *
     * 只做一次无鉴权的 GET 探可达性 —— **不**发真正的签名请求 (那会把一段 wtlogin 报文送出去),
     * 更不会走任何登录流程。
     */
    private fun checkSignService(signUrl: String?) {
        if (signUrl.isNullOrBlank()) {
            bad("natsu.yml 的 sign.url 是空的 —— native 后端没有签名服务无法登录")
            return
        }
        info("签名服务: ${Log.redact(signUrl)}")
        val uri = runCatching { URI(signUrl) }.getOrNull()
        if (uri == null || uri.host == null) {
            bad("sign.url 不是一个合法地址: ${Log.redact(signUrl)}")
            return
        }
        val reachable = runCatching {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            val request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "natsuri-doctor")
                .GET()
                .build()
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        }
        reachable.onSuccess { status ->
            ok("签名服务可达 (HTTP $status)")
        }.onFailure { e ->
            bad("签名服务不可达: ${e.message} —— native 后端登录会在预检阶段就失败")
        }
        println("  [--]   尚未验证签名端版本。")
        println("         natsu 的请求是 POST <sign.url>/51102, 但签名服务可能忽略路径里的版本号,")
        println("         所以\"可达\"或\"返回 200\"都不能证明它支持 QQNT 3.2.31-51102。")
        println("         在签名服务提供可核查的能力接口之前, natsuri 不会给出\"版本匹配\"的结论。")
    }

    private fun checkPlugins() {
        val dir = home.pluginsDir(config)
        if (!Files.isDirectory(dir)) {
            info("还没有 plugins/ 目录 (Console 首次启动时会建)")
            return
        }
        val entries = PluginManager(home, config).list()
        if (entries.isEmpty()) {
            info("plugins/ 是空的")
            return
        }
        ok("plugins/ 里有 ${entries.size} 个 jar")
        for (entry in entries) {
            val id = entry.identity.declaredId?.let { "$it" } ?: "${entry.identity.id} (未声明 plugin.yml)"
            val tags = buildList {
                if (!entry.managed) add("手动放入")
                entry.shadowedBy?.let { add("被 $it 遮蔽, Console 不会加载") }
            }
            info("  $id  <- ${entry.fileName}  ${Fs.humanSize(entry.size)}${if (tags.isEmpty()) "" else "  [" + tags.joinToString(", ") + "]"}")
        }
        // 记录里有、盘上没有的插件
        val state = InstallState.load(home.natsuriDir)
        val names = entries.map { it.fileName }.toSet()
        state.plugins.filterNot { it.file in names }.forEach {
            warn("state.json 里记着插件 ${it.file}, 但 plugins/ 里没有它 (被手动删了?)")
        }
        // 摘要漂移: 装完之后被人改过
        for (entry in entries) {
            val record = entry.record ?: continue
            val actual = runCatching { Hashes.sha256(entry.file) }.getOrNull() ?: continue
            if (!Hashes.matches(record.sha256, actual)) {
                warn("${entry.fileName} 的内容与安装时不同 (被替换过?)")
            }
        }
    }
}
