package natsuri.util

import java.nio.file.Path

/**
 * 「这个字符串可以当成一个目录/文件名吗」。
 *
 * 归档条目名有 [natsuri.archive.ZipSafety] 把关, 但**归档之外**也有几个字符串最终会变成路径的一段:
 *
 * - 清单里的 `version` -> `installId` -> `.natsuri/releases/<installId>/` 与 `staging/<installId>/`;
 * - 远程插件地址的最后一段 -> `plugins/<文件名>`。
 *
 * 这两处都来自**清单/地址的发布方**, 而不是本机。它们没被检查的话, 一个
 * `"version": "../../../../Documents"` 就能让 natsuri 在解压之前先把那个目录递归删掉 ——
 * 删除发生在 `staging` 的清理那一步, 连"安装失败"都还没轮到。
 *
 * 所以规则和 ZipSafety 一致: **一段就是一段**, 不许有分隔符、不许有 `..`、不许有盘符。
 */
object PathNames {

    class Unsafe(message: String) : IllegalArgumentException(message)

    private val DRIVE_PREFIX = Regex("""^[A-Za-z]:""")

    private val WINDOWS_RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /**
     * 校验一个**单段**名字。不安全就抛 [Unsafe]。
     *
     * @param what 出现在错误信息里的字段名, 例如 "清单的 version"
     */
    fun requireSingleSegment(value: String, what: String): String {
        if (value.isEmpty()) throw Unsafe("$what 是空的")
        if (value.length > 200) throw Unsafe("$what 太长了 (${value.length} 字符)")
        if (value.any { it.code < 0x20 || it.code == 0x7F }) throw Unsafe("$what 含控制字符")
        if (value.contains('/') || value.contains('\\')) throw Unsafe("$what 含路径分隔符: '$value'")
        if (value.contains(':')) throw Unsafe("$what 含 ':': '$value'")
        if (value == "." || value == "..") throw Unsafe("$what 是 '.' 或 '..'")
        if (value.startsWith(".")) throw Unsafe("$what 以 '.' 开头: '$value'")
        if (DRIVE_PREFIX.containsMatchIn(value)) throw Unsafe("$what 含 Windows 盘符: '$value'")
        if (value.substringBefore('.').uppercase() in WINDOWS_RESERVED) {
            throw Unsafe("$what 是 Windows 保留设备名: '$value'")
        }
        if (value.endsWith(' ') || value.endsWith('.')) throw Unsafe("$what 以空格或点结尾: '$value'")
        return value
    }

    /** 上面那条的"只问不抛"版本, 给 `validate()` 收集问题用。 */
    fun problemWith(value: String, what: String): String? = try {
        requireSingleSegment(value, what)
        null
    } catch (e: Unsafe) {
        e.message
    }

    /**
     * 最后一道: 解析之后必须仍在 [base] 之内。
     *
     * 与 [natsuri.archive.ZipSafety.resolveInside] 同理 —— 名字检查过了也要再确认一次解析结果,
     * 两道各挡各的。删除与解压这类不可逆动作之前必须走这一遍。
     */
    fun resolveInside(base: Path, segment: String, what: String): Path {
        val root = base.toAbsolutePath().normalize()
        val resolved = root.resolve(segment).normalize()
        if (!resolved.startsWith(root) || resolved == root) {
            throw Unsafe("$what 解析后落到 $root 之外: '$segment' -> $resolved")
        }
        return resolved
    }
}
