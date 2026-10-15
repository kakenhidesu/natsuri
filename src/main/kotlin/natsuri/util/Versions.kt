package natsuri.util

/**
 * 版本号比较。
 *
 * natsu 现在的版本是 `0.1.0-SNAPSHOT`, 以后大概率是 `0.2.0` / `0.2.0-beta1` 这一类。按 SemVer 的规则:
 * 数字段逐段数值比较, 带预发布后缀的排在同号正式版**之前** (`0.1.0-SNAPSHOT` < `0.1.0`),
 * 预发布段之间按点分段比 (数字段数值比, 非数字段字典序, 数字段小于非数字段)。
 *
 * 认不出来的版本串不装懂: [compare] 退回大小写无关的字典序, 并且 [isComparable] 会说"没把握",
 * 让 `update --check` 能如实报告"两边版本无法比较, 需要显式 install/use"而不是瞎猜哪个新。
 *
 * **不参与比较的东西**: 发行的 buildId。同名重发 (SNAPSHOT) 的新旧只能靠 buildId/摘要区分,
 * 那是 [natsuri.state.InstallState] 的事, 不是版本号的事。
 */
object Versions {

    private val NUMERIC = Regex("""^\d+$""")

    fun isComparable(a: String, b: String): Boolean = parse(a) != null && parse(b) != null

    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        if (pa == null || pb == null) return a.compareTo(b, ignoreCase = true)

        val n = maxOf(pa.numbers.size, pb.numbers.size)
        for (i in 0 until n) {
            val x = pa.numbers.getOrElse(i) { 0L }
            val y = pb.numbers.getOrElse(i) { 0L }
            if (x != y) return x.compareTo(y)
        }
        // 都没有预发布段 -> 相等; 只有一边有 -> 有的那边小。
        if (pa.pre.isEmpty() && pb.pre.isEmpty()) return 0
        if (pa.pre.isEmpty()) return 1
        if (pb.pre.isEmpty()) return -1

        val m = maxOf(pa.pre.size, pb.pre.size)
        for (i in 0 until m) {
            val x = pa.pre.getOrNull(i) ?: return -1
            val y = pb.pre.getOrNull(i) ?: return 1
            val xn = NUMERIC.matches(x)
            val yn = NUMERIC.matches(y)
            val c = when {
                xn && yn -> x.toLong().compareTo(y.toLong())
                xn -> -1 // 数字段 < 非数字段
                yn -> 1
                else -> x.compareTo(y, ignoreCase = true)
            }
            if (c != 0) return c
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    private class Parsed(val numbers: List<Long>, val pre: List<String>)

    private fun parse(version: String): Parsed? {
        val v = version.trim()
        if (v.isEmpty()) return null
        // 构建元数据 (`+sha`) 不参与比较, 直接丢掉。
        val core = v.substringBefore('+')
        val head = core.substringBefore('-')
        val pre = core.substringAfter('-', "").takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
        val numbers = head.split('.').map { it.toLongOrNull() ?: return null }
        if (numbers.isEmpty()) return null
        return Parsed(numbers, pre)
    }
}
