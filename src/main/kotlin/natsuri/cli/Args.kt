package natsuri.cli

/**
 * 极简命令行解析。
 *
 * 不引第三方库有一个具体的理由: `--` 的语义必须**完全**由 natsuri 说了算。
 * `natsuri run -- --no-console` 里, `--` 之后的东西要原样交给 Console, 一个字符都不能被解释 ——
 * 而通用解析库对未知选项、缩写合并、`--flag=value` 各有各的想法, 把 `--no-console` 当成
 * "natsuri 的未知选项"报错是最常见的那种。自己写这一小段, 语义就是确定的。
 *
 * 规则:
 * - `--name value` 与 `--name=value` 等价;
 * - `--flag` 后面若接的是另一个 `--…` 或没有东西, 它就是布尔开关;
 * - 第一个**单独的** `--` 之后的所有内容进 [passthrough], 不再解析;
 * - 其余非选项参数按出现顺序进 [positionals]。
 */
class Args private constructor(
    private val options: Map<String, String?>,
    val positionals: List<String>,
    val passthrough: List<String>,
) {

    fun has(vararg names: String): Boolean = names.any { it in options }

    /**
     * 选项出现了**而且没有带值**。
     *
     * 用来区分全局的 `natsuri --version` (打印版本) 与
     * `natsuri manifest generate --version 0.2.0` (给清单指定版本) —— 同一个选项名, 两种意思。
     */
    fun bare(name: String): Boolean = name in options && options[name] == null

    fun value(vararg names: String): String? {
        for (name in names) {
            if (name in options) return options[name] ?: throw CliError("选项 --$name 需要一个值")
        }
        return null
    }

    fun flag(vararg names: String): Boolean {
        for (name in names) {
            if (name in options) {
                val v = options[name] ?: return true
                return when (v.lowercase()) {
                    "true", "yes", "1", "on" -> true
                    "false", "no", "0", "off" -> false
                    else -> throw CliError("选项 --$name 只接受 true/false, 给的是 '$v'")
                }
            }
        }
        return false
    }

    /** 未知选项要报错而不是忽略 —— 打错一个选项名却"看起来跑成功了"是最糟的。 */
    fun rejectUnknown(vararg allowed: String) {
        val known = allowed.toSet()
        val unknown = options.keys.filter { it !in known }
        if (unknown.isNotEmpty()) {
            throw CliError(
                "不认识的选项: ${unknown.joinToString { "--$it" }}\n" +
                    (if (known.isEmpty()) "这个命令不接受选项。" else "这个命令支持: ${known.joinToString { "--$it" }}"),
            )
        }
    }

    fun positional(index: Int): String? = positionals.getOrNull(index)

    companion object {

        /**
         * 这些选项即使后面跟着一个非 `--` 开头的词, 也仍然是布尔开关。
         *
         * `version` **不在**这张表里: `natsuri manifest generate --version 0.2.0` 里它要带值。
         * 全局的 `natsuri --version` 仍然成立 —— 后面没有东西时它自然被解析成布尔开关。
         */
        private val BOOLEAN_ONLY = setOf(
            "help", "verbose", "quiet", "check", "deep", "force", "remote", "no-use", "yes",
        )

        /** 纯字母的单横线写法 —— 那就是想写选项。带数字/点/等号的 (`-Xmx2g`、`-Dk=v`) 是值。 */
        private val SHORT_OPTION_SHAPE = Regex("""^-[A-Za-z]+$""")

        fun parse(tokens: List<String>): Args {
            val options = LinkedHashMap<String, String?>()
            val positionals = ArrayList<String>()
            val passthrough = ArrayList<String>()

            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]
                if (token == "--") {
                    passthrough += tokens.subList(i + 1, tokens.size)
                    break
                }
                if (token.startsWith("--") && token.length > 2) {
                    val body = token.substring(2)
                    val eq = body.indexOf('=')
                    if (eq >= 0) {
                        options[body.substring(0, eq)] = body.substring(eq + 1)
                    } else {
                        val next = tokens.getOrNull(i + 1)
                        if (body !in BOOLEAN_ONLY && next != null && next != "--" && !next.startsWith("--")) {
                            options[body] = next
                            i++
                        } else {
                            options[body] = null
                        }
                    }
                } else if (token == "-h") {
                    options["help"] = null
                } else if (token == "-v") {
                    options["verbose"] = null
                } else if (SHORT_OPTION_SHAPE.matches(token)) {
                    // 只挡"看起来就是短选项"的写法 (`-x`, `-remote`)。
                    // **不能**一律拒绝以 `-` 开头的词: `natsuri config set jvmArgs -Xmx2g` 里
                    // `-Xmx2g` 是个值, 不是选项。
                    throw CliError("不认识的选项 '$token' —— natsuri 只用长选项 (--name)")
                } else {
                    positionals += token
                }
                i++
            }
            return Args(options, positionals, passthrough)
        }
    }
}

/** 用户用法错误。和内部异常分开: 这一类不该打堆栈, 该打用法。 */
class CliError(message: String) : Exception(message)
