package natsuri.archive

import java.nio.file.Path

/** 归档里发现的不安全条目。带上原始条目名 —— 用户要能看出是哪一条。 */
class UnsafeArchiveException(message: String) : Exception(message)

/**
 * 归档条目名的安全化。
 *
 * 这一层挡的是"解压出来的文件落到目标目录之外"。**每一条规则都对应一种真实见过的写法**,
 * 不是凑数:
 *
 * | 写法 | 结果 |
 * |---|---|
 * | `../../evil.sh` | 逃出目标目录 |
 * | `/etc/cron.d/x` | 绝对路径 (Unix) |
 * | `C:\Windows\x` / `C:x` | 绝对路径与盘符相对路径 (Windows) |
 * | `\\\\server\\share\\x` | UNC |
 * | `a\..\..\b` | Windows 上 `\` 也是分隔符, 在 Unix 上却是普通字符 —— 同一个包在两个系统上行为不同 |
 * | `x.txt:stream` | NTFS 备用数据流 |
 * | `CON` / `LPT1` | Windows 保留设备名 |
 * | 名字里带 `\u0000` / 控制字符 | 底层 API 的解析歧义 |
 *
 * 判断分两道: **先看字面**(上表), **再看解析结果**(规范化后必须仍在目标目录下)。
 * 只做第二道是不够的 —— `startsWith` 在字符串上做会被 `/target-evil` 这种前缀骗过去,
 * 所以第二道一定要在 [Path] 上做。
 */
object ZipSafety {

    private val WINDOWS_RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    private val DRIVE_PREFIX = Regex("""^[A-Za-z]:""")

    /**
     * 校验一个条目名并返回规范化的相对路径 (以 `/` 分隔)。不安全就抛 [UnsafeArchiveException]。
     *
     * @param name ZIP 里的原始条目名
     */
    fun sanitize(name: String): String {
        if (name.isEmpty()) throw UnsafeArchiveException("归档里有空条目名")
        if (name.any { it.code < 0x20 || it.code == 0x7F }) {
            throw UnsafeArchiveException("条目名含控制字符: ${escape(name)}")
        }
        if (name.contains('\\')) {
            // 不做"把 \ 当分隔符再检查"的翻译: 同一个包在 Windows 与 Linux 上会解出不同的路径,
            // 这种包本身就不该被信任。合法的 ZIP 规定用 `/`。
            throw UnsafeArchiveException("条目名含反斜杠 (ZIP 规定用 '/'): ${escape(name)}")
        }
        if (name.startsWith("/")) throw UnsafeArchiveException("条目名是绝对路径: ${escape(name)}")
        if (DRIVE_PREFIX.containsMatchIn(name)) {
            throw UnsafeArchiveException("条目名含 Windows 盘符: ${escape(name)}")
        }

        val segments = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) throw UnsafeArchiveException("条目名规范化后是空的: ${escape(name)}")
        for (segment in segments) {
            if (segment == "..") throw UnsafeArchiveException("条目名越出归档根目录: ${escape(name)}")
            if (segment.contains(':')) throw UnsafeArchiveException("条目名含 ':' (NTFS 备用数据流): ${escape(name)}")
            val base = segment.substringBefore('.').uppercase()
            if (base in WINDOWS_RESERVED) {
                throw UnsafeArchiveException("条目名含 Windows 保留设备名 '$segment': ${escape(name)}")
            }
            if (segment.endsWith(' ') || (segment.endsWith('.') && segment != "..")) {
                // Windows 会悄悄把结尾的空格/点去掉, 于是两个不同的条目落到同一个文件上。
                throw UnsafeArchiveException("条目名以空格或点结尾: ${escape(name)}")
            }
        }
        return segments.joinToString("/")
    }

    /**
     * 第二道: 解析之后必须仍在 [targetDir] 之内。
     *
     * 用 [Path.normalize] 而不是 `toRealPath` —— 后者要求文件已存在, 而我们正要创建它;
     * 而且 `toRealPath` 会跟随符号链接, 反而给了"目标目录里先有个指向别处的链接"这种攻击可乘之机。
     * 目标目录本身在调用前已经规范化过。
     */
    fun resolveInside(targetDir: Path, relative: String): Path {
        val base = targetDir.toAbsolutePath().normalize()
        val resolved = base.resolve(relative).normalize()
        if (!resolved.startsWith(base)) {
            throw UnsafeArchiveException("条目解析后落到目标目录之外: $relative -> $resolved")
        }
        return resolved
    }

    private fun escape(name: String): String =
        name.map { if (it.code < 0x20 || it.code == 0x7F) "\\u%04x".format(it.code) else it.toString() }.joinToString("")
}
