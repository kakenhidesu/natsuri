package natsuri.plugins

import natsuri.NatsuriHome
import natsuri.config.NatsuriConfig
import natsuri.install.Installer
import natsuri.source.Downloader
import natsuri.source.Locator
import natsuri.source.SourcePolicy
import natsuri.source.Sources
import natsuri.state.InstallState
import natsuri.state.PluginRecord
import natsuri.util.Fs
import natsuri.util.Hashes
import natsuri.util.Log
import natsuri.util.MiniYaml
import natsuri.util.PathNames
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * 插件文件管理。
 *
 * ## 首版做什么、不做什么
 *
 * **做**: 把插件 jar 放进 `plugins/`、记下摘要与来源、按标识删掉它、列出目录里有什么
 * (包括用户自己拷进去的)、按 Console 的真实规则识别同一个插件。
 *
 * **不做**: 依赖解析、兼容性判断、批量更新用户手放的插件。
 *
 * ## 标识是怎么来的 (按可靠性排序)
 *
 * 全部对照 mirai-console 2.16.0 的加载器源码 (natsu 的 `natsu-console` 后端就是它的分支):
 *
 * 1. **`plugin.yml`** (归档根目录)。`BuiltInJvmPluginLoaderImpl.extractPlugins()` 里
 *    `getResourceAsStream("plugin.yml")` 读的就是它, 结构是 `SimpleJvmPluginDescription.SerialData`
 *    (`id` 必填、`version` 必填、`name` 缺省等于 `id`)。**这是唯一不执行代码就能拿到插件 id 的地方**,
 *    也正是 `data/<id>/` 与 `config/<id>/` 的那个 id。
 * 2. **服务声明**。Console 用 `PluginServiceHelper.findServices` 读三个文件:
 *    `META-INF/services/net.mamoe.mirai.console.plugin.jvm.{JvmPlugin,KotlinPlugin,JavaPlugin}`,
 *    内容是主类全名 (跳过空行与 `#` 开头的行)。改了文件名它也不变。
 * 3. **文件名**去掉扩展名。前两样都没有时的兜底。
 *
 * `plugin.yml` 是**可选**的 —— 没有它的插件在代码里构造描述, 静态读不到 id。那种情况 natsuri
 * 如实显示"(未声明 plugin.yml)"并退回主类/文件名, 不假装知道。
 *
 * ## 数据绝不动
 *
 * `remove` 只删 jar。`data/<id>/` 与 `config/<id>/` 一概保留 —— 那是用户的数据,
 * 不是安装副产品。删除时会把这两个目录的位置打出来, 要不要清由人决定。
 */
class PluginManager(
    private val home: NatsuriHome,
    private val config: NatsuriConfig,
) {

    data class Identity(
        /** natsuri 与用户交互用的标识: 优先 plugin.yml 的 id, 否则文件名。 */
        val id: String,
        /** plugin.yml 声明的 id; 没有 plugin.yml 时为 null。 */
        val declaredId: String?,
        val declaredName: String?,
        val declaredVersion: String?,
        /** 三个服务文件里声明的主类。 */
        val mainClasses: List<String>,
    )

    data class Entry(
        val file: Path,
        val identity: Identity,
        val size: Long,
        val record: PluginRecord?,
        /** Console 会不会真的加载它 (`.mirai2.jar` 去重规则的结果)。 */
        val shadowedBy: String?,
    ) {
        /** 没有安装记录 = 用户自己拷进来的。列出来, 但不擅自处置。 */
        val managed: Boolean get() = record != null
        val fileName: String get() = file.fileName.toString()
    }

    private fun pluginsDir(): Path = home.pluginsDir(config)

    /**
     * 列出 `plugins/` 里的插件, 并标出哪些会被 Console 忽略。
     *
     * 去重规则照抄 `BuiltInJvmPluginLoaderImpl.pluginsFilesSequence()`:
     * 对每个 `X.mirai2.jar`, 把**去掉两级扩展名后同名**的其它候选删掉。
     * 那个双重 `substringBeforeLast('.')` 有个真实存在的怪癖: `Foo.mirai2.jar` -> `Foo`,
     * 会连普通的 `Foo.jar` 一起盖掉; 但 `Foo-1.0.mirai2.jar` -> `Foo-1.0` 只盖得住
     * `Foo-1.0.mirai.jar` (-> `Foo-1.0`), 盖不住 `Foo-1.0.jar` (-> `Foo-1`)。
     * natsuri 照它复述, 而不是照"应该怎样"复述 —— 用户要看到的是 Console 真正会加载的那一份。
     */
    fun list(): List<Entry> {
        val dir = pluginsDir()
        if (!Files.isDirectory(dir)) return emptyList()
        val state = InstallState.load(home.natsuriDir)
        val records = state.plugins.associateBy { it.file }

        val files = Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar", ignoreCase = true) }
                .sorted()
                .toList()
        }

        val shadow = HashMap<String, String>()
        for (mirai2 in files.filter { it.fileName.toString().endsWith(".mirai2.jar", ignoreCase = true) }) {
            val stem = stripTwoExtensions(mirai2.fileName.toString())
            for (other in files) {
                if (other == mirai2) continue
                if (stripTwoExtensions(other.fileName.toString()) == stem) {
                    shadow[other.fileName.toString()] = mirai2.fileName.toString()
                }
            }
        }

        return files.map { path ->
            val name = path.fileName.toString()
            Entry(
                file = path,
                identity = identify(path),
                size = Files.size(path),
                record = records[name],
                shadowedBy = shadow[name],
            )
        }
    }

    /**
     * 装一个插件。
     *
     * @param reference 本地 jar 路径或 https 地址
     * @param expectedSha256 期望的摘要; 远程安装**强烈建议**给出
     * @param force 允许覆盖同标识的已有插件
     */
    fun install(reference: String, expectedSha256: String?, force: Boolean): PluginRecord {
        val policy = SourcePolicy(config.source.allowInsecure)
        val locator = Sources.locate(reference, policy)
        val fileName = fileNameOf(locator, reference)
        if (!fileName.endsWith(".jar", ignoreCase = true)) {
            throw IllegalArgumentException(
                "插件文件必须以 .jar 结尾 (含 .mirai2.jar / .mirai.jar), 给的是 '$fileName'",
            )
        }

        val dir = pluginsDir()
        Files.createDirectories(dir)
        // 名字检查过了, 落点再确认一次 —— 两道各挡各的 (与解压那边同一套做法)。
        val target = PathNames.resolveInside(dir, fileName, "插件文件名")

        // 先落到临时文件再进 plugins/ —— 半个 jar 摆在那里, Console 下次启动就会去加载它。
        val temp = Files.createTempFile(dir, ".natsuri-plugin-", ".part")
        val record: PluginRecord
        try {
            when (locator) {
                is Locator.LocalFile -> {
                    if (!Files.isRegularFile(locator.path)) throw IllegalArgumentException("找不到文件: ${locator.path}")
                    Files.copy(locator.path, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }

                is Locator.Remote -> {
                    if (expectedSha256 == null) {
                        Log.warn(
                            "从网络安装插件但没给 --sha256。natsuri 只能确认传输没出错, " +
                                "不能确认拿到的是不是你要的那一份。",
                        )
                    }
                    Files.write(temp, Downloader.fetchBytes(locator.uri, MAX_PLUGIN_BYTES))
                }
            }

            val actual = Hashes.sha256(temp)
            if (expectedSha256 != null) {
                if (!Hashes.isSha256Hex(expectedSha256)) {
                    throw IllegalArgumentException("--sha256 不是 64 位十六进制: $expectedSha256")
                }
                if (!Hashes.matches(expectedSha256, actual)) {
                    throw IllegalStateException("插件 SHA-256 不符:\n  期望 ${expectedSha256.lowercase()}\n  实际 $actual")
                }
            }

            // 注意从**目标文件名**取兜底标识: 这时内容还在临时文件里, 用它的名字会得到 `.part` 那一串。
            val identity = identify(temp).let { it.copy(id = it.declaredId ?: stripJarExtension(fileName)) }
            if (identity.mainClasses.isEmpty() && identity.declaredId == null) {
                Log.warn(
                    "$fileName 里既没有 plugin.yml, 也没有 mirai-console 的插件服务声明 —— " +
                        "它可能不是 mirai-console 插件。natsuri 照装, 能不能用由 Console 说了算。",
                )
            }
            checkConflicts(identity, fileName, force)

            Fs.moveReplacing(temp, target)
            record = PluginRecord(
                id = identity.id,
                file = fileName,
                size = Files.size(target),
                sha256 = actual,
                installedAt = Installer.timestamp(),
                source = Log.redact(reference),
                providers = identity.mainClasses,
            )
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(temp) }
            throw e
        }

        val state = InstallState.load(home.natsuriDir)
        InstallState.save(
            home.natsuriDir,
            state.copy(plugins = state.plugins.filterNot { it.file == fileName } + record),
        )
        return record
    }

    /** 按标识或文件名删掉一个插件 jar。**只删 jar。** */
    fun remove(selector: String): Entry {
        val entries = list()
        val match = entries.filter {
            it.identity.id == selector || it.fileName == selector || it.identity.declaredId == selector
        }
        if (match.isEmpty()) {
            throw IllegalArgumentException(
                "plugins/ 里没有 '$selector'。已有: " +
                    entries.joinToString { it.identity.id }.ifEmpty { "(空)" },
            )
        }
        if (match.size > 1) {
            throw IllegalArgumentException("'$selector' 对应多个文件: ${match.joinToString { it.fileName }}")
        }
        val entry = match.single()
        Files.deleteIfExists(entry.file)
        val state = InstallState.load(home.natsuriDir)
        InstallState.save(home.natsuriDir, state.copy(plugins = state.plugins.filterNot { it.file == entry.fileName }))
        return entry
    }

    /** 这个插件的数据与配置目录 (存在才返回)。删除时打给用户看, natsuri 自己不碰。 */
    fun dataDirectories(entry: Entry): List<Path> {
        val working = home.workingDir(config)
        val ids = listOfNotNull(entry.identity.declaredId, entry.identity.declaredName).distinct()
        return ids.flatMap { listOf(working.resolve("data").resolve(it), working.resolve("config").resolve(it)) }
            .filter { Files.isDirectory(it) }
    }

    private fun checkConflicts(identity: Identity, fileName: String, force: Boolean) {
        /*
         * 会被新文件"遮蔽"的旧文件不算冲突, 那正是升级路径本身。
         *
         * `Foo.jar` / `Foo.mirai.jar` 旁边放上 `Foo.mirai2.jar` 时, Console 的去重规则
         * (见 list() 的说明) 只会加载 `.mirai2.jar` 那一份 —— 两份同 id 的插件并不会同时加载,
         * 也就不会报 id 冲突。把这种情况拦下来并告诉用户"Console 会因此拒绝启动"是错的:
         * 既挡住了正常的升级, 说的还不是事实。
         */
        val superseded = supersededBy(fileName)
        val existing = list().filter { it.fileName != fileName && it.fileName !in superseded }
        superseded.forEach {
            Log.info("$fileName 会遮蔽同目录下的 $it —— 按 Console 的 .mirai2.jar 去重规则, 只有前者会被加载")
        }
        val problems = ArrayList<String>()

        if (identity.declaredId != null) {
            val sameId = existing.filter { it.identity.declaredId == identity.declaredId }
            if (sameId.isNotEmpty()) {
                problems += "已有声明了同一个插件 id '${identity.declaredId}' 的文件: " +
                    sameId.joinToString { it.fileName }
            }
        }
        val sameMain = existing.filter { e ->
            identity.mainClasses.isNotEmpty() && e.identity.mainClasses.any { it in identity.mainClasses }
        }
        if (sameMain.isNotEmpty()) {
            problems += "已有声明了相同插件主类的文件: " +
                sameMain.joinToString { "${it.fileName} (${it.identity.mainClasses.joinToString()})" }
        }
        if (identity.declaredId == null && identity.mainClasses.isEmpty()) {
            val sameStem = existing.filter { it.identity.id == identity.id }
            if (sameStem.isNotEmpty()) problems += "已有同名的文件: ${sameStem.joinToString { it.fileName }}"
        }

        if (problems.isEmpty()) return
        if (!force) {
            throw IllegalStateException(
                problems.joinToString("\n") { "  - $it" } + "\n" +
                    "同一个插件装两份, Console 会按插件 id 冲突报错并拒绝启动。\n" +
                    "先 natsuri plugins remove 掉旧的, 或者加 --force 覆盖。",
            )
        }
        problems.forEach { Log.warn("--force: ${it.trim()}") }
    }

    /** 静态读 jar: `plugin.yml` + 三个服务文件。读不动就退回文件名, 不因此拒绝。 */
    fun identify(jar: Path): Identity {
        var declaredId: String? = null
        var declaredName: String? = null
        var declaredVersion: String? = null
        val mainClasses = LinkedHashSet<String>()

        runCatching {
            ZipFile(jar.toFile(), StandardCharsets.UTF_8).use { zip ->
                zip.getEntry("plugin.yml")?.let { entry ->
                    val text = zip.getInputStream(entry).use { it.readBytes().toString(StandardCharsets.UTF_8) }
                    val doc = MiniYaml.parse(text)
                    declaredId = doc.scalar("id")?.takeIf { it.isNotBlank() }
                    declaredName = doc.scalar("name")?.takeIf { it.isNotBlank() }
                    declaredVersion = doc.scalar("version")?.takeIf { it.isNotBlank() }
                }
                for (service in PLUGIN_SERVICES) {
                    val entry = zip.getEntry(service) ?: continue
                    zip.getInputStream(entry).use { input ->
                        input.readBytes().toString(StandardCharsets.UTF_8)
                            .lineSequence()
                            .map { it.trim() }
                            // Console 的过滤就是这两条: 非空 + 首字符不是 '#'。
                            .filter { it.isNotBlank() && it[0] != '#' }
                            .forEach { mainClasses += it }
                    }
                }
            }
        }.onFailure { Log.debug("读取 $jar 失败: ${it.message}") }

        val fallback = stripJarExtension(jar.fileName.toString())
        return Identity(
            id = declaredId ?: fallback,
            declaredId = declaredId,
            declaredName = declaredName ?: declaredId,
            declaredVersion = declaredVersion,
            mainClasses = mainClasses.toList(),
        )
    }

    private fun stripJarExtension(fileName: String): String {
        for (ext in listOf(".mirai2.jar", ".mirai.jar", ".jar")) {
            if (fileName.endsWith(ext, ignoreCase = true)) return fileName.dropLast(ext.length)
        }
        return fileName
    }

    /** 复刻 Console 的 `substringBeforeLast('.').substringBeforeLast('.')`, 怪癖照旧。 */
    private fun stripTwoExtensions(fileName: String): String =
        fileName.substringBeforeLast('.').substringBeforeLast('.')

    /** 装上 [fileName] 之后, `plugins/` 里哪些现有文件会被 Console 的去重规则跳过。 */
    private fun supersededBy(fileName: String): Set<String> {
        if (!fileName.endsWith(".mirai2.jar", ignoreCase = true)) return emptySet()
        val stem = stripTwoExtensions(fileName)
        val dir = pluginsDir()
        if (!Files.isDirectory(dir)) return emptySet()
        return Files.list(dir).use { stream ->
            stream.map { it.fileName.toString() }
                .filter { it != fileName && it.endsWith(".jar", ignoreCase = true) && stripTwoExtensions(it) == stem }
                .toList()
                .toSet()
        }
    }

    /**
     * 从来源推出要写进 `plugins/` 的文件名。
     *
     * **远程地址的最后一段是发布方给的字符串, 不是本机的东西。** `URI.getPath()` 已经做过
     * 百分号解码, 于是 `https://host/%2e%2e%2f%2e%2e%2fnatsuri.json` 解出来就是
     * `../../natsuri.json` —— 直接拿去 `pluginsDir.resolve(...)` 就写到 `plugins/` 之外了。
     * 所以这里按"一段就是一段"校验 ([PathNames.requireSingleSegment]),
     * 调用方还会再做一次落点包含检查。
     */
    private fun fileNameOf(locator: Locator, reference: String): String = when (locator) {
        is Locator.LocalFile -> locator.path.fileName?.toString()
            ?: throw IllegalArgumentException("从 $reference 看不出文件名")

        is Locator.Remote -> {
            val last = (locator.uri.path ?: "").substringAfterLast('/')
            if (last.isBlank()) throw IllegalArgumentException("从 ${Log.redact(reference)} 看不出文件名")
            PathNames.requireSingleSegment(last, "插件文件名")
        }
    }

    private companion object {
        const val MAX_PLUGIN_BYTES = 256L * 1024 * 1024

        /** Console 的 `findServices(JvmPlugin::class, KotlinPlugin::class, JavaPlugin::class)` 对应的三个资源。 */
        val PLUGIN_SERVICES = listOf(
            "META-INF/services/net.mamoe.mirai.console.plugin.jvm.JvmPlugin",
            "META-INF/services/net.mamoe.mirai.console.plugin.jvm.KotlinPlugin",
            "META-INF/services/net.mamoe.mirai.console.plugin.jvm.JavaPlugin",
        )
    }
}
