package natsuri.util

/**
 * 一个**只读、只认标量与简单序列**的极小 YAML 读取器。
 *
 * natsuri 需要读两个 YAML, 但都只是为了"看一眼", 不是为了理解它们:
 *
 * - `natsu.yml` 的 `backend.type` 与 `sign.url` —— `doctor` 要据此决定"该不该检查签名服务";
 * - 插件 jar 里的 `plugin.yml` 的 `id` / `version` / `name` —— 这是 mirai-console 唯一
 *   **静态可读**的插件身份 (Console 用 yamlkt 解 `SimpleJvmPluginDescription.SerialData`)。
 *
 * 为此引一个 YAML 库不划算 —— natsuri 的卖点之一就是"一个 jar, 两个脚本"。
 *
 * **它的能力边界必须说清楚, 因为调用方要按边界写代码**:
 * - 支持: 按缩进嵌套的 `key: value` 标量、行内 `#` 注释、单双引号、`key:` 下的 `- item` 标量序列;
 * - 不支持: 锚点/别名、多行标量 (`|` `>`)、流式集合 (`{}` `[]`)、复杂键、多文档。
 *
 * 读不出来时一律返回 null 让调用方降级, **绝不猜**。`doctor` 对此的处理是明说"读不出 natsu.yml 的
 * backend.type", 而不是假设它是 native。
 */
object MiniYaml {

    class Document(private val scalars: Map<String, String>, private val sequences: Map<String, List<String>>) {
        /** 按 `a.b.c` 取标量; 不存在或不是标量时返回 null。 */
        fun scalar(path: String): String? = scalars[path]

        fun sequence(path: String): List<String>? = sequences[path]

        val keys: Set<String> get() = scalars.keys + sequences.keys
    }

    fun parse(text: String): Document {
        val scalars = LinkedHashMap<String, String>()
        val sequences = LinkedHashMap<String, MutableList<String>>()
        // 缩进栈: (缩进列数, 该层的键前缀)
        val stack = ArrayList<Pair<Int, String>>()
        var lastKeyPath: String? = null
        var lastKeyIndent = -1

        for (rawLine in text.lineSequence()) {
            if (rawLine.isBlank()) continue
            val indent = rawLine.indexOfFirst { !it.isWhitespace() }
            if (indent < 0) continue
            val line = stripComment(rawLine.substring(indent)).trimEnd()
            if (line.isEmpty()) continue
            if (line.startsWith("---") || line.startsWith("...")) continue

            if (line.startsWith("- ") || line == "-") {
                // 序列项归属最近的那个"空值键"。
                val owner = lastKeyPath ?: continue
                if (indent < lastKeyIndent) continue
                val item = unquote(line.removePrefix("-").trim())
                if (item.isNotEmpty()) sequences.getOrPut(owner) { ArrayList() }.add(item)
                continue
            }

            val colon = findKeySeparator(line) ?: continue
            val key = unquote(line.substring(0, colon).trim())
            if (key.isEmpty()) continue
            val value = line.substring(colon + 1).trim()

            while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeAt(stack.lastIndex)
            val prefix = stack.lastOrNull()?.second
            val path = if (prefix.isNullOrEmpty()) key else "$prefix.$key"

            if (value.isEmpty()) {
                // 可能是嵌套映射的父键, 也可能是一个空值键 (下面接序列, 或者真的是空)。
                stack.add(indent to path)
                lastKeyPath = path
                lastKeyIndent = indent
                // `accounts: []` 之外的空值也登记一个空标量, 好让"这个键存在但没填"能被看出来。
                scalars[path] = ""
            } else {
                scalars[path] = unquote(value)
                lastKeyPath = path
                lastKeyIndent = indent
            }
        }
        return Document(scalars, sequences)
    }

    /** `key: value` 里第一个不在引号内的冒号。`url: "http://x"` 不能被里面那个冒号切开。 */
    private fun findKeySeparator(line: String): Int? {
        var quote: Char? = null
        for (i in line.indices) {
            val c = line[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == ':' -> {
                    // `a:b` 在 YAML 里不是键值对; 冒号后必须是空白或行尾。
                    if (i + 1 >= line.length || line[i + 1].isWhitespace()) return i
                }
            }
        }
        return null
    }

    /** 去掉行内注释: `#` 前必须是空白或行首, 且不在引号内。 */
    private fun stripComment(line: String): String {
        var quote: Char? = null
        for (i in line.indices) {
            val c = line[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '#' && (i == 0 || line[i - 1].isWhitespace()) -> return line.substring(0, i)
            }
        }
        return line
    }

    private fun unquote(value: String): String {
        if (value.length >= 2) {
            val first = value.first()
            if ((first == '"' || first == '\'') && value.last() == first) {
                return value.substring(1, value.length - 1)
            }
        }
        return value
    }
}
