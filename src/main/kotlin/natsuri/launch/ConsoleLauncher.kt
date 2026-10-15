package natsuri.launch

import natsuri.state.InstalledRelease
import natsuri.util.Log
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 起 Console。
 *
 * ## 为什么是独立子进程, 而不是把 classpath 装进本进程的类加载器
 *
 * 1. **classpath 干净**。natsuri 自己也用 kotlin-stdlib 和 kotlinx-serialization; 同进程加载意味着
 *    这两份要和发行包里的版本共存, 而 natsu 恰恰是一个对"同名类有几份"极度敏感的项目
 *    (见 [natsuri.verify.DistributionVerifier])。分进程之后, Console 的 classpath 就是清单说的那些, 一个不多。
 * 2. **退出码是真的**。`System.exit` 在同进程里会把启动器一起带走, 也就没法区分"Console 正常退出"
 *    和"启动器自己崩了"。
 * 3. **Windows 上能更新**。同进程持有 jar 文件句柄, 更新时删不掉也换不掉。
 *
 * 代价是要自己处理 IO 继承、信号与退出码 —— 下面每一处都为此写了说明。
 */
object ConsoleLauncher {

    data class Outcome(
        val exitCode: Int,
        /** 这次启动是否是"Console 生成了 natsu.yml 模板然后正常退出"。 */
        val generatedConfigTemplate: Boolean,
    )

    /**
     * 命令行超过这个长度就改用 `@argfile`。
     *
     * Windows 的 `CreateProcess` 上限是 32767 个字符, 而 classpath 有 53 个 jar、每个还带一段
     * 可能很长的目录前缀 —— 中文路径 + 深层目录很容易把它顶上去。
     */
    private const val COMMAND_LINE_THRESHOLD = 30_000

    /**
     * @param workingDir Console 的工作目录 —— `natsu.yml` / `plugins/` / `bots/` 都相对它
     * @param extraJvmArgs 用户在 natsuri.json 里加的 JVM 参数, 排在发行包自带参数之后
     * @param consoleArgs 传给 Console 的参数 (命令行 `--` 之后的内容原样在这里)
     */
    fun launch(
        java: Path,
        natsuriDir: Path,
        release: InstalledRelease,
        workingDir: Path,
        extraJvmArgs: List<String>,
        consoleArgs: List<String>,
        commandLineThreshold: Int = COMMAND_LINE_THRESHOLD,
    ): Outcome {
        val appRoot = release.resolveDir(natsuriDir)
        val classpath = release.classpath.joinToString(File.pathSeparator) {
            appRoot.resolve(it).toAbsolutePath().normalize().toString()
        }

        /*
         * JVM 参数与 Console 参数分开:
         *   - 发行包自带的 JVM 参数在前 (natsu 的三个 UTF-8 开关就在这里),
         *   - 用户的在后 —— 同名 `-D` 后面的赢, 于是"想改就能改"。
         * Console 参数同理: 清单的默认值、natsuri.json 的 consoleArgs、命令行 `--` 之后的内容, 依次拼接。
         */
        val jvmArgs = release.defaultJvmArgs + extraJvmArgs
        val programArgs = release.defaultArgs + consoleArgs

        val direct = buildList {
            add(java.toString())
            addAll(jvmArgs)
            add("-classpath")
            add(classpath)
            add(release.mainClass)
            addAll(programArgs)
        }

        // Windows 上算的是**整条命令行**: 可执行文件路径 (带空格时 JDK 会加引号) + 每个参数 +
        // 分隔空格 + JDK 为含空格参数补的引号。这里按每项 +3 估算那些引号与空格, 留足余量。
        val estimatedCommandLine = direct.sumOf { it.length + 3 }
        val command = if (estimatedCommandLine > commandLineThreshold) {
            val argFile = writeArgFile(natsuriDir, jvmArgs, classpath)
            Log.info("命令行约 $estimatedCommandLine 字符, 超过阈值 $commandLineThreshold, 改用参数文件: ${argFile.fileName}")
            buildList {
                add(java.toString())
                add("@$argFile")
                add(release.mainClass)
                addAll(programArgs)
            }
        } else {
            direct
        }

        Files.createDirectories(workingDir)
        val configFile = workingDir.resolve("natsu.yml")
        val configExistedBefore = Files.exists(configFile)

        Log.debug("启动命令: ${command.joinToString(" ")}")
        Log.info("启动 natsu ${release.describe()}  (工作目录 $workingDir)")

        val process = try {
            ProcessBuilder(command)
                .directory(workingDir.toFile())
                /*
                 * 必须继承标准流, 不能自己转发:
                 *   - JLine 要一个真正的终端才有行编辑、历史与补全;
                 *   - 二维码是一整块 ANSI 图形, 中间插任何一行都会把它切断;
                 *   - stdin 是 Console 的命令输入;
                 *   - Windows 上只有继承了控制台句柄, 子进程才和 natsuri 处在同一个控制台上,
                 *     Ctrl+C 才会同时发给它 (JDK 正是据此不给子进程加 CREATE_NO_WINDOW)。
                 * 转发会让这几样全部变形, 而且没有任何补救办法。
                 */
                .inheritIO()
                .start()
        } catch (e: IOException) {
            // Windows 的 CreateProcess 上限是 32767 个字符 (整条命令行, 含引号与分隔空格)。
            // 错误文本是本地化的, 只有这个数字是稳定的, 所以按它匹配。
            if (e.message?.contains("error=206") == true || e.message?.contains("error=7") == true) {
                throw IOException(
                    "命令行超过了操作系统的长度上限 (Windows 是整条命令行 32767 字符, Linux 是单个参数 128 KiB)。\n" +
                        "natsuri 当前的参数文件阈值是 $commandLineThreshold 字符 —— " +
                        "把 natsuri.json 的 commandLineThreshold 调小, 或把 natsuri 装到更短的路径下。\n" +
                        "原始错误: ${e.message}",
                    e,
                )
            }
            throw e
        }

        // Ctrl+C 的处理见 installShutdownHook 与 SignalShield 的注释。
        val shielded = SignalShield.install { forceKillTree(process) }
        val hook = installShutdownHook(process)
        val exit = try {
            process.waitFor()
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        }
        if (!shielded && exit != 0) {
            Log.debug("未能接管中断信号; 若这次是 Ctrl+C 结束的, natsuri 自己的退出码可能是 130 而非 $exit")
        }

        val generated = !configExistedBefore && Files.exists(configFile) && exit == 0
        return Outcome(exit, generated)
    }

    /**
     * `@argfile`: 一行一个 JVM 参数, 最后两行是 `-classpath` 与路径列表。
     *
     * 三个坑, 每一个都会安静地把事情弄坏:
     *
     * 1. **反斜杠**。java 启动器 (`libjli/args.c`) 只在**引号内**把 `\` 当转义符:
     *    `"D:\temp\new\foo"` 会被解成 `D:<TAB>emp<LF>ew\foo`。所以这里把 classpath 里的 `\`
     *    全换成 `/` —— Windows 的 JVM 接受正斜杠的 classpath, 而正斜杠在参数文件里毫无特殊含义。
     * 2. **编码**。参数文件是**按字节读**的 (`fopen` + `fread`, 中间没有任何解码),
     *    那些字节最后按 `sun.jnu.encoding` 解释。所以它**不是 UTF-8 文件** ——
     *    在 `sun.jnu.encoding=GBK` 的中文 Windows 上写 UTF-8 会得到乱码路径。
     *    这里按 `sun.jnu.encoding` 写, 与 java 启动器解读它的方式一致。
     * 3. **`0x1A`**。Windows 上文件以文本模式打开, `0x1A` 会被当成 EOF **静默截断**参数文件。
     *    路径里不会有它, 但既然代价只有一行检查, 就检查。
     */
    private fun writeArgFile(natsuriDir: Path, jvmArgs: List<String>, classpath: String): Path {
        val file = natsuriDir.resolve("launch-args.txt")
        val text = buildString {
            for (arg in jvmArgs) {
                appendLine(quoteForArgFile(arg))
            }
            appendLine("-classpath")
            appendLine(quoteForArgFile(classpath.replace('\\', '/')))
        }
        if (text.any { it.code == 0x1A }) {
            throw IllegalStateException("参数文件里出现了 0x1A, Windows 会据此截断它 —— 路径里有控制字符?")
        }

        val charset = argFileCharset()
        if (text.any { it.code > 127 } && charset != StandardCharsets.UTF_8) {
            Log.debug("参数文件含非 ASCII 字符, 按 $charset 写出 (与 java 启动器解读它的编码一致)")
        }
        Files.write(file, text.toByteArray(charset))
        return file.toAbsolutePath()
    }

    /**
     * java 启动器解读参数文件用的编码。
     *
     * `sun.jnu.encoding` 是 JVM 用来在"平台原生字节"与"Java 字符串"之间转换的那一个
     * (Windows 上通常是 ANSI 代码页, 中文系统是 GBK), 正是参数文件那些字节最终被解释的方式。
     * 取不到时退回 `native.encoding` (JDK 18+), 再退回默认字符集。
     */
    private fun argFileCharset(): java.nio.charset.Charset {
        for (property in listOf("sun.jnu.encoding", "native.encoding")) {
            val name = System.getProperty(property) ?: continue
            runCatching { return java.nio.charset.Charset.forName(name) }
        }
        return java.nio.charset.Charset.defaultCharset()
    }

    private fun quoteForArgFile(value: String): String =
        if (value.any { it.isWhitespace() || it == '"' || it == '\'' || it == '#' }) {
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            value
        }

    /**
     * 关机钩子: **等**子进程, 不主动杀它。
     *
     * 终端里按 Ctrl+C 时, Windows 会把 `CTRL_C_EVENT` 发给整个控制台进程组, Linux 会把 `SIGINT`
     * 发给整个前台进程组 —— 两边**子进程都会自己收到那一下**, 不需要启动器转发。这时候
     * `Process.destroy()` 反而有害: Windows 上它是 `TerminateProcess`, 会在 Console 正保存
     * PluginData / 关连接的时候把它硬砍掉。
     *
     * 所以这里只等, 给足时间; 只有等超时 (子进程真的卡住了) 才依次升级到 destroy / destroyForcibly。
     */
    /**
     * 强制结束时连**子孙进程**一起结束。
     *
     * 直接杀 `process` 是不够的: 我们启动的那个 `java` 有可能只是个中转
     * (Windows 上 Oracle 的 `javapath\java.exe` 垫片就是, 它再去 fork 真正的 JVM)。
     * 只杀垫片的话, 真正的 natsu 会活下来变成孤儿, 还继续占着 `bots/` 与端口。
     *
     * 先杀子孙再杀自己 —— 反过来的话父进程一没, 子孙的父子关系就断了, `descendants()` 也就找不到它们。
     */
    private fun forceKillTree(process: Process) {
        runCatching {
            process.descendants().forEach { runCatching { it.destroyForcibly() } }
        }
        runCatching { process.destroyForcibly() }
    }

    private fun installShutdownHook(process: Process): Thread {
        val hook = Thread({
            if (!process.isAlive) return@Thread
            System.err.println("[INFO] 正在等待 natsu 退出…")
            // JVM 会等所有关闭钩子跑完才真的退出, 所以这个时限同时也是"用户要多久才能回到 shell"。
            if (process.waitFor(10, TimeUnit.SECONDS)) return@Thread
            System.err.println("[WARN] natsu 10 秒内没有退出, 强制结束 (Windows 上没有温和终止这一档)")
            forceKillTree(process)
        }, "natsuri-shutdown")
        Runtime.getRuntime().addShutdownHook(hook)
        return hook
    }
}

