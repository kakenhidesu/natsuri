package natsuri.launch

import natsuri.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 找一个能用的 Java, 并**核实它的版本**。
 *
 * 为什么不能只找到就算: natsu 用 Java 21 的工具链编译, class 文件版本是 65。拿 Java 17 去跑,
 * 报出来的是 `UnsupportedClassVersionError` —— 对没踩过的人来说这条信息几乎不指向"你的 Java 太旧"。
 * 与其让它在子进程里以那种形式失败, 不如启动前一句话说清楚。
 *
 * 查找顺序 (前面的优先):
 * 1. `natsuri.json` 里的 `java.path` —— 显式配置永远第一;
 * 2. `JAVA_HOME` —— 用户/系统的显式选择;
 * 3. **natsuri 自己所在的运行时** (`java.home`);
 * 4. `PATH` 上的 `java`。
 *
 * ## 为什么 `java.home` 排在 `PATH` 前面
 *
 * 因为 `PATH` 上的那个 `java` 常常**不是真正的 JVM, 而是一个中转进程**。
 * Windows 上 Oracle 的安装器会往 `C:\Program Files\Common Files\Oracle\Java\javapath\` 放一个
 * `java.exe` 垫片, 它自己再去 `fork` 真正的 `jdk-xx\bin\java.exe`。实测的进程树:
 *
 * ```
 * natsuri  ->  javapath\java.exe (垫片)  ->  jdk-21\bin\java.exe (真正的 natsu)
 * ```
 *
 * 这会带来两个真实后果: `Process.destroyForcibly()` 杀掉的是垫片, 真正的 natsu 会活下来变成孤儿;
 * 进程树多一层也让"到底谁在跑"变得难查。
 *
 * 而 `System.getProperty("java.home")` **总是指向真正的 JDK 目录** —— 即使 natsuri 自己是被垫片启动的。
 * 同时它并不违背用户的选择: natsuri 的启动脚本本来就是按 `NATSURI_JAVA` / `JAVA_HOME` / `PATH`
 * 找到这个运行时的, 用它等于沿用同一个选择, 只是解析到了真身。
 *
 * natsuri **不下载 Java**。首版明确不做这件事: 装运行时是系统级决定 (装哪个发行版、装在哪、要不要进 PATH),
 * 启动器替用户做这个决定, 出问题时既难查也难卸。
 */
object JavaLocator {

    data class Candidate(val path: Path, val origin: String, val version: JavaVersion?)

    data class JavaVersion(val major: Int, val raw: String) {
        override fun toString(): String = "$raw (主版本 $major)"
    }

    class NotFound(message: String) : Exception(message)

    /** 按顺序列出所有候选 (含探测到的版本), 供 `doctor` 展示。 */
    fun candidates(explicit: String?): List<Candidate> {
        val out = LinkedHashMap<Path, Candidate>()

        fun add(path: Path?, origin: String) {
            if (path == null) return
            val normalized = runCatching { path.toAbsolutePath().normalize() }.getOrNull() ?: return
            if (out.containsKey(normalized)) return
            if (!Files.isRegularFile(normalized)) return
            out[normalized] = Candidate(normalized, origin, probeVersion(normalized))
        }

        explicit?.takeIf { it.isNotBlank() }
            ?.let { add(resolveJavaBinary(it), CONFIG_ORIGIN) }
        System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }
            ?.let { add(resolveJavaBinary(it), "JAVA_HOME") }
        // java.home 先于 PATH —— 见类文档里关于 Windows javapath 垫片的说明。
        add(resolveJavaBinary(System.getProperty("java.home")), "natsuri 自己的运行时")
        findOnPath()?.let { add(it, "PATH") }

        return out.values.toList()
    }

    /**
     * 选一个满足 [minMajor] 的 java。
     *
     * 显式配置的那个若版本不够, **直接失败**而不是悄悄换一个 —— 用户指定了它就是要用它,
     * 换掉之后跑出来的东西和他以为的不是一回事。
     */
    fun locate(explicit: String?, minMajor: Int): Path {
        val list = candidates(explicit)
        if (list.isEmpty()) {
            throw NotFound(
                "找不到 java 可执行文件。\n" +
                    "请安装 Java $minMajor 或更高版本, 并把它放进 PATH, 或者在 natsuri.json 里指定:\n" +
                    "  { \"java\": { \"path\": \"C:\\\\Program Files\\\\Java\\\\jdk-21\\\\bin\\\\java.exe\" } }\n" +
                    "natsuri 不会自动下载 Java。",
            )
        }

        /*
         * 显式配置了 java.path 却没能解析成一个可执行文件时**必须失败**, 不能悄悄换一个。
         *
         * 以前这里只是"没进候选列表", 于是 natsuri 会安静地退回 JAVA_HOME / PATH ——
         * 用户明明指定了一个 JVM, 跑起来的却是另一个, 而且 doctor 还会报告一切正常。
         * 路径写错、JDK 被卸载、盘符换了, 都是这条路。
         */
        if (!explicit.isNullOrBlank() && list.none { it.origin == CONFIG_ORIGIN }) {
            throw NotFound(
                "natsuri.json 里的 java.path 指向 '$explicit', 但那里没有可执行的 java。\n" +
                    "它可以是 java 可执行文件本身、bin 目录, 或 JDK 根目录。\n" +
                    "改对它, 或者删掉这一项让 natsuri 自己找 —— natsuri 不会在你指定了之后偷偷换一个。",
            )
        }

        val explicitCandidate = list.firstOrNull { it.origin == CONFIG_ORIGIN }
        if (explicitCandidate != null) {
            val v = explicitCandidate.version
                ?: throw NotFound("无法确定 ${explicitCandidate.path} 的版本 (它能执行吗?)")
            if (v.major < minMajor) {
                throw NotFound(
                    "natsuri.json 指定的 java 是 $v, 但这份 natsu 发行包需要 Java $minMajor 或更高。\n" +
                        "改掉 java.path, 或删掉它让 natsuri 自己找。",
                )
            }
            return explicitCandidate.path
        }

        val usable = list.firstOrNull { (it.version?.major ?: -1) >= minMajor }
        if (usable != null) {
            Log.debug("使用 ${usable.path} (${usable.origin}, ${usable.version})")
            return usable.path
        }

        val detail = list.joinToString("\n") { "  ${it.path}  <- ${it.origin}, ${it.version ?: "版本未知"}" }
        throw NotFound(
            "找到的 java 都低于所需的 Java $minMajor:\n$detail\n" +
                "natsu 用 Java 21 编译, 版本不够时子进程会以 UnsupportedClassVersionError 结束。\n" +
                "装一个够新的 JDK, 或在 natsuri.json 里用 java.path 指向它。",
        )
    }

    /**
     * 传进来的可能是 JDK 目录、bin 目录或 java 本身, 三种都认。
     *
     * 收的是 [String] 而不是 [Path]: `Path.of` 对含非法字符的串抛 [java.nio.file.InvalidPathException],
     * 而**带引号的 `JAVA_HOME` 是 Windows 上最常见的配置错误之一** (`gradlew.bat` 专门为它写了一行
     * 去引号)。让那个未检异常一路冒到顶, 会把 `run` 和 `doctor` 一起打死 ——
     * 偏偏 `doctor` 正是用来诊断这种环境问题的那条命令。
     *
     * 所以这里先去掉包裹的引号 (那几乎总是用户的本意), 再把解析失败当成"这个候选不可用"。
     */
    private fun resolveJavaBinary(raw: String): Path? {
        val cleaned = raw.trim().removeSurrounding("\"").trim()
        if (cleaned.isEmpty()) return null
        val base = runCatching { Path.of(cleaned) }.getOrElse {
            Log.debug("'$raw' 不是一个合法路径 (${it.message}), 跳过")
            return null
        }
        val exe = if (isWindows) "java.exe" else "java"
        val candidates = listOf(base, base.resolve(exe), base.resolve("bin").resolve(exe))
        return candidates.firstOrNull { runCatching { Files.isRegularFile(it) }.getOrDefault(false) }
    }

    private fun findOnPath(): Path? {
        val exe = if (isWindows) "java.exe" else "java"
        val path = System.getenv("PATH") ?: return null
        for (dir in path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            val candidate = runCatching { Path.of(dir).resolve(exe) }.getOrNull() ?: continue
            if (Files.isRegularFile(candidate)) return candidate
        }
        return null
    }

    /**
     * 跑 `java -version` 读版本。
     *
     * 不去解析 `release` 文件: JRE 目录被人整理过、或者 java 是个包装脚本时那份文件可能不在或不准。
     * 真正要跑的那个可执行文件自己说的话才算数。
     */
    fun probeVersion(java: Path): JavaVersion? = runCatching {
        val process = ProcessBuilder(java.toString(), "-version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        parseVersion(output)
    }.getOrNull()

    private val VERSION_LINE = Regex(""""([0-9][0-9._\-a-zA-Z+]*)"""")

    fun parseVersion(output: String): JavaVersion? {
        val raw = VERSION_LINE.find(output)?.groupValues?.get(1) ?: return null
        // `1.8.0_202` -> 8; `21.0.10` -> 21; `24-ea` -> 24
        val major = if (raw.startsWith("1.")) {
            raw.split('.').getOrNull(1)?.toIntOrNull()
        } else {
            raw.takeWhile { it.isDigit() }.toIntOrNull()
        } ?: return null
        return JavaVersion(major, raw)
    }

    private val isWindows: Boolean
        get() = System.getProperty("os.name").lowercase().contains("windows")

    /** 候选的来源标签。显式配置那一条要能被精确认出来, 所以做成常量而不是前缀匹配。 */
    const val CONFIG_ORIGIN = "natsuri.json 的 java.path"
}

