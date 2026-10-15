package natsuri.util

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * SHA-256。
 *
 * 说明清楚它是什么: **内容校验**, 不是**来源证明**。它保证"我拿到的字节和清单里写的一致",
 * 前提是清单本身可信 —— 而清单的可信度来自 natsuri.json 里配置的源地址与 HTTPS,
 * 不来自这个摘要。文档里不要把它称作"发布者签名"。
 */
object Hashes {

    fun sha256(path: Path): String = Files.newInputStream(path).use { sha256(it) }

    fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"

    /** 十六进制摘要的宽松比较: 大小写无关, 去掉空白。 */
    fun matches(expected: String, actual: String): Boolean =
        expected.trim().equals(actual.trim(), ignoreCase = true)

    fun isSha256Hex(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
