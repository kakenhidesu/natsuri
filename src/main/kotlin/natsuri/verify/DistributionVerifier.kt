package natsuri.verify

import natsuri.manifest.ReleaseManifest
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * 发行校验 —— natsuri 这一侧的 `verifyDistribution`。
 *
 * ## 为什么启动器也要做这件事
 *
 * natsu 的构建里已经有 `checkNoOfficialMirai` 与 `verifyDistribution` 两个任务。但那是**构建期**的检查,
 * 检查的是构建产物; 用户手里的 zip 从下载、解压到被启动, 中间还有一段构建管不到的路。而这条路上出错的
 * 形态恰恰是最难查的那种:
 *
 * 官方 `mirai-core` 与 natsu 的 `yutori-core` 都用 `META-INF/services/net.mamoe.mirai.IMirai` 注册实现。
 * 两份同时在 classpath 上时, `ServiceLoader` 取哪个**取决于顺序**, 而且**不会报错** ——
 * 插件调用会静静地走进另一套协议栈, 表现只是"登录不上、事件收不到"。同理, 维护版
 * `mirai-core-api` / `mirai-console` 与官方版提供同名类, 混在一起时插件调到哪一份也是顺序决定的。
 *
 * 所以这几条规则必须在**每次安装时**、对**真实解压出来的文件**再跑一遍。
 *
 * ## 不能简化成"看到 net.mamoe 就拒绝"
 *
 * `mirai-core-utils` 是 natsu 前端与后端都在用的合法依赖, `mirai-console-compiler-annotations`
 * 在官方 console 2.16.0 的 POM 里就有。按包名一刀切会把正常的发行包判死。
 * 判据是**归属**: 每个受管命名空间下的类只能由指定的那一个 jar 提供。
 */
object DistributionVerifier {

    data class Result(
        val errors: List<String>,
        val warnings: List<String>,
        val jarCount: Int,
        val classCount: Int,
        val providers: Map<String, List<String>>,
        val summary: String,
    ) {
        val ok: Boolean get() = errors.isEmpty()
    }

    /** 维护版模块的识别。用 `\d` 收尾很关键 —— `natsu-console-` 否则会连 `natsu-console-terminal-` 一起匹配上。 */
    private val API_JAR = Regex("""^yutori-core-api-\d""")
    private val CONSOLE_JAR = Regex("""^natsu-console-\d""")
    private val TERMINAL_JAR = Regex("""^natsu-console-terminal-\d""")

    /**
     * 官方实现的文件名特征。
     *
     * 这是**便宜的第一道**, 用于给出好读的错误信息; 真正的判据是下面按类归属做的检查 ——
     * 改个文件名就能绕过的规则不能当成安全边界。
     */
    private val OFFICIAL_JAR_PREFIXES = listOf(
        "mirai-core-api-", "mirai-core-jvm-", "mirai-core-all-", "mirai-core-2",
        "mirai-console-2", "mirai-console-terminal-", "mirai-console-frontend-base-",
    )

    /** 官方 mirai-core 的实现类; 出现即说明官方协议栈被夹带进来了 (哪怕 jar 改了名)。 */
    private val OFFICIAL_IMPL_CLASSES = listOf(
        "net/mamoe/mirai/internal/MiraiImpl.class",
        "net/mamoe/mirai/internal/QQAndroidBot.class",
    )

    /** mirai 通过 ServiceLoader 找实现的那几个接口。每一个都只能有一个提供者。 */
    private val MIRAI_SERVICES = listOf(
        "META-INF/services/net.mamoe.mirai.IMirai",
        "META-INF/services/net.mamoe.mirai.auth.DefaultBotAuthorizationFactory",
        "META-INF/services/net.mamoe.mirai.event.InternalGlobalEventChannelProvider",
        "META-INF/services/net.mamoe.mirai.message.data.InternalImageProtocol",
        "META-INF/services/net.mamoe.mirai.message.data.OfflineAudio\$Factory",
    )

    private const val ALLCLASSES = "META-INF/mirai-console/allclasses.txt"

    fun verify(appRoot: Path, classpath: List<String>, mainClass: String): Result {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()

        // ---- 1. classpath 上的每一项都必须真的在, 且在应用根目录之内 ----
        val base = appRoot.toAbsolutePath().normalize()
        val jars = ArrayList<Path>()
        for (entry in classpath) {
            val resolved = base.resolve(entry).normalize()
            if (!resolved.startsWith(base)) {
                errors += "classpath 项越出应用根目录: $entry"
                continue
            }
            if (!Files.isRegularFile(resolved)) {
                errors += "classpath 项不存在: $entry"
                continue
            }
            jars.add(resolved)
        }
        if (errors.isNotEmpty()) {
            return Result(errors, warnings, jars.size, 0, emptyMap(), "发行校验未通过 (classpath 不完整)")
        }

        // ---- 2. 文件名层面的官方 jar ----
        for (jar in jars) {
            val name = jar.fileName.toString()
            if (OFFICIAL_JAR_PREFIXES.any { name.startsWith(it) }) {
                errors += "classpath 上有官方 mirai 实现: $name —— 它会和 natsu 的实现争同一批类与 ServiceLoader 注册"
            }
        }

        // ---- 3. 逐 jar 建立"类 -> 提供者"索引, 同时收集服务注册 ----
        val owners = HashMap<String, MutableList<String>>()
        val serviceProviders = HashMap<String, MutableList<Pair<String, List<String>>>>()
        val allclassesJars = ArrayList<String>()
        val mainClassJars = ArrayList<String>()
        val mainClassEntry = mainClass.replace('.', '/') + ".class"

        for (jar in jars) {
            val jarName = jar.fileName.toString()
            try {
                ZipFile(jar.toFile(), StandardCharsets.UTF_8).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        val n = e.name
                        if (n.endsWith(".class") && !n.startsWith("META-INF/") && n != "module-info.class") {
                            owners.getOrPut(n) { ArrayList() }.add(jarName)
                            if (n == mainClassEntry) mainClassJars.add(jarName)
                        } else if (n == ALLCLASSES) {
                            allclassesJars += jarName
                        } else if (n in MIRAI_SERVICES) {
                            val declared = zip.getInputStream(e).use { input ->
                                input.readBytes().toString(StandardCharsets.UTF_8)
                                    .lineSequence()
                                    .map { it.substringBefore('#').trim() }
                                    .filter { it.isNotEmpty() }
                                    .toList()
                            }
                            serviceProviders.getOrPut(n) { ArrayList() }.add(jarName to declared)
                        }
                    }
                }
            } catch (e: Exception) {
                errors += "无法读取 $jarName: ${e.message}"
            }
        }

        // ---- 4. 启动类必须真的在, 而且只有一份 ----
        when (mainClassJars.size) {
            0 -> errors += "classpath 上找不到启动类 $mainClass ($mainClassEntry)"
            1 -> Unit
            else -> errors += "启动类 $mainClass 由多个 jar 提供: $mainClassJars"
        }
        if (mainClass != ReleaseManifest.EXPECTED_MAIN_CLASS) {
            errors += "启动类是 $mainClass, natsu 的正式入口是 ${ReleaseManifest.EXPECTED_MAIN_CLASS}"
        }

        // ---- 5. 官方实现类被夹带 (jar 改名也躲不掉) ----
        for (klass in OFFICIAL_IMPL_CLASSES) {
            owners[klass]?.let { errors += "classpath 上出现官方 mirai-core 的实现类 $klass (来自 $it)" }
        }

        // ---- 6. 维护版模块各只能有一份 ----
        fun uniqueJar(what: String, pattern: Regex): String? {
            val found = jars.map { it.fileName.toString() }.filter { pattern.containsMatchIn(it) }
            return when (found.size) {
                1 -> found[0]
                0 -> { errors += "classpath 上没有 $what"; null }
                else -> { errors += "classpath 上有多份 $what: $found"; null }
            }
        }
        uniqueJar("维护版 mirai API (yutori-core-api)", API_JAR)
        uniqueJar("维护版 mirai-console 后端 (natsu-console)", CONSOLE_JAR)
        uniqueJar("Console 终端前端 (natsu-console-terminal)", TERMINAL_JAR)

        // ---- 7. 受管命名空间的归属 ----
        fun outsiders(provider: Regex, belongs: (String) -> Boolean): Map<String, List<String>> =
            owners.filterKeys(belongs).filterValues { list -> list.none { provider.containsMatchIn(it) } }

        val apiOutsiders = outsiders(API_JAR) { c ->
            c.startsWith("natsu/api/") ||
                (
                    c.startsWith("net/mamoe/mirai/") &&
                        !c.startsWith("net/mamoe/mirai/console/") &&
                        // mirai-core-utils 与 API 分摊 utils 包, 与官方一致
                        !c.startsWith("net/mamoe/mirai/utils/") &&
                        // mirai-core-utils 里重定位的 ktor
                        !c.startsWith("net/mamoe/mirai/internal/deps/")
                    )
        }
        val frontendPackages = listOf("net/mamoe/mirai/console/terminal/", "net/mamoe/mirai/console/frontendbase/")
        val consoleOutsiders = outsiders(CONSOLE_JAR) { c ->
            c.startsWith("net/mamoe/mirai/console/") &&
                // 官方单独发布的注解库, natsu 不维护它
                !c.startsWith("net/mamoe/mirai/console/compiler/") &&
                frontendPackages.none { c.startsWith(it) }
        }
        val frontendOutsiders = outsiders(TERMINAL_JAR) { c -> frontendPackages.any { c.startsWith(it) } }

        fun reportOutsiders(what: String, map: Map<String, List<String>>) {
            if (map.isEmpty()) return
            errors += "有 $what 不是由预期的 jar 提供 (共 ${map.size} 个, 前 5 个):\n" +
                map.entries.take(5).joinToString("\n") { "    ${it.key} <- ${it.value}" }
        }
        reportOutsiders("net.mamoe.mirai.* / natsu.api.* 类", apiOutsiders)
        reportOutsiders("net.mamoe.mirai.console.* 类", consoleOutsiders)
        reportOutsiders("Console 前端类", frontendOutsiders)

        // ---- 8. 同名类重复 ----
        val duplicates = owners.filterValues { it.size > 1 }
        val managedDuplicates = duplicates.filterKeys { it.startsWith("net/mamoe/") || it.startsWith("natsu/") }
        if (managedDuplicates.isNotEmpty()) {
            errors += "有 mirai / natsu 的类由多个 jar 提供 (共 ${managedDuplicates.size} 个, 前 5 个):\n" +
                managedDuplicates.entries.take(5).joinToString("\n") { "    ${it.key} <- ${it.value}" }
        }
        val otherDuplicates = duplicates.size - managedDuplicates.size
        if (otherDuplicates > 0) {
            // 第三方库自己的重复 (例如两个库都打包了同一个 annotations 类) 与 natsu 无关, 只提一句。
            warnings += "有 $otherDuplicates 个非 mirai/natsu 类由多个 jar 提供 —— 第三方依赖自身的重复, 不影响 natsu"
        }

        // ---- 9. ServiceLoader 注册: 每个接口只能有一个提供者 ----
        for (service in MIRAI_SERVICES) {
            val found = serviceProviders[service] ?: continue
            if (found.size > 1) {
                errors += "$service 由多个 jar 注册: ${found.map { it.first }} —— " +
                    "ServiceLoader 取哪个由 classpath 顺序决定, 而且不会报错"
            }
            val declaredClasses = found.flatMap { it.second }
            for (impl in declaredClasses) {
                if (impl.startsWith("net.mamoe.mirai.internal.")) {
                    errors += "$service 注册的实现是官方的 $impl"
                }
            }
        }
        val imirai = serviceProviders["META-INF/services/net.mamoe.mirai.IMirai"]
        if (imirai.isNullOrEmpty()) {
            errors += "classpath 上没有 net.mamoe.mirai.IMirai 的服务注册 —— natsu 的核心实现没进来"
        }

        // ---- 10. 插件类加载器的防夹带表 ----
        when (allclassesJars.size) {
            0 -> errors += "没有任何 jar 带 $ALLCLASSES —— 少了它, 插件夹带的 mirai / natsu 类会生效"
            1 -> if (!CONSOLE_JAR.containsMatchIn(allclassesJars[0])) {
                errors += "$ALLCLASSES 来自 ${allclassesJars[0]}, 应当由 natsu-console 提供"
            }
            else -> errors += "$ALLCLASSES 由多个 jar 提供: $allclassesJars"
        }

        // ---- 11. lib/ 里有但不在 classpath 上的 jar ----
        val libDir = base.resolve("lib")
        if (Files.isDirectory(libDir)) {
            val onClasspath = jars.map { it.fileName.toString() }.toSet()
            val stray = Files.list(libDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".jar") }
                    .map { it.fileName.toString() }
                    .filter { it !in onClasspath }
                    .toList()
            }
            if (stray.isNotEmpty()) {
                warnings += "lib/ 里有 ${stray.size} 个 jar 不在清单的 classpath 上: ${stray.take(5)} —— 清单可能过期"
            }
        }

        val summary = if (errors.isEmpty()) {
            "发行校验通过: ${jars.size} 个 jar / ${owners.size} 个类; " +
                "mirai API <- ${jars.map { it.fileName.toString() }.firstOrNull { API_JAR.containsMatchIn(it) }}, " +
                "console 后端 <- ${allclassesJars.firstOrNull()} (allclasses.txt ✓), " +
                "IMirai <- ${imirai?.firstOrNull()?.second?.firstOrNull() ?: "?"}"
        } else {
            "发行校验未通过 (${errors.size} 项)"
        }

        return Result(
            errors = errors,
            warnings = warnings,
            jarCount = jars.size,
            classCount = owners.size,
            providers = serviceProviders.mapValues { (_, v) -> v.map { it.first } },
            summary = summary,
        )
    }
}
