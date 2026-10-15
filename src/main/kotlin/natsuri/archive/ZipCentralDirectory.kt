package natsuri.archive

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * 手工读 ZIP 的中央目录。
 *
 * 为什么不用 `java.util.zip.ZipFile`: 它的 [java.util.zip.ZipEntry] **不暴露 external attributes**,
 * 而符号链接条目正是靠那 4 个字节的高 16 位 (Unix 的 `st_mode`, `S_IFLNK = 0xA000`) 标出来的。
 * 少了这一项就无法在解压前判断"这个条目本来是个链接"。
 *
 * natsuri 从不创建符号链接 (所有条目一律当成普通文件写出), 所以链接**逃不出**目标目录;
 * 但一个正常的 natsu 发行包里根本不该有链接条目, 出现就说明这个包不是预期的东西 —— 该拒绝而不是照单全收。
 *
 * 解析失败不致命 (Zip64 的边角、非常规写法都可能让这里读不动), 那时降级成一条警告:
 * 真正的安全边界在 [ZipSafety] 与"绝不创建链接"这两条上, 这里是加一道能提前发现异常的检查。
 */
object ZipCentralDirectory {

    data class Entry(
        val name: String,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val externalAttributes: Long,
        val madeByHost: Int,
        val method: Int,
        val flags: Int,
    ) {
        /** 通用位标志 bit 0 = 加密, bit 6 = 强加密。加密条目解不出来, 也不该出现在发行包里。 */
        val isEncrypted: Boolean
            get() = (flags and 0x0001) != 0 || (flags and 0x0040) != 0

        /** Unix 主机写的条目才有 st_mode; 高 4 位 `0xA000` 是 `S_IFLNK`。 */
        val isSymlink: Boolean
            get() = madeByHost == HOST_UNIX && ((externalAttributes ushr 16) and 0xF000L) == 0xA000L
    }

    /** ZIP 规范给未置 UTF-8 标志的条目名规定的编码。JDK 带 IBM437; 万一没有就退回 ISO-8859-1 (逐字节)。 */
    private val CP437: java.nio.charset.Charset =
        runCatching { java.nio.charset.Charset.forName("IBM437") }.getOrElse { StandardCharsets.ISO_8859_1 }

    private const val HOST_UNIX = 3
    private const val EOCD_SIG = 0x06054b50
    private const val EOCD64_LOCATOR_SIG = 0x07064b50
    private const val EOCD64_SIG = 0x06064b50
    private const val CDFH_SIG = 0x02014b50

    class ParseFailure(message: String) : Exception(message)

    /**
     * 读中央目录。
     *
     * 任何解析期的异常都统一成 [ParseFailure] —— 调用方按"没检查成"降级处理它,
     * 而 `IndexOutOfBounds` / `NegativeArraySize` / `OutOfMemory` 之类会一路冒到顶,
     * 把安装变成一句看不懂的堆栈。畸形归档正是最容易触发那些的东西。
     */
    fun read(zip: Path): List<Entry> = try {
        readUnprotected(zip)
    } catch (e: ParseFailure) {
        throw e
    } catch (e: Exception) {
        throw ParseFailure("中央目录解析出错: ${e.javaClass.simpleName}: ${e.message}")
    } catch (e: OutOfMemoryError) {
        throw ParseFailure("中央目录声明的规模过大, 拒绝解析")
    }

    private fun readUnprotected(zip: Path): List<Entry> = RandomAccessFile(zip.toFile(), "r").use { file ->
        val fileLength = file.length()
        if (fileLength < 22) throw ParseFailure("文件太短, 不是 ZIP")

        val tailLength = minOf(fileLength, (0xFFFF + 22).toLong()).toInt()
        val tail = ByteArray(tailLength)
        file.seek(fileLength - tailLength)
        file.readFully(tail)
        val tailBuffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)

        var eocd = -1
        for (i in tailLength - 22 downTo 0) {
            if (tailBuffer.getInt(i) == EOCD_SIG) {
                eocd = i
                break
            }
        }
        if (eocd < 0) throw ParseFailure("找不到 ZIP 的中央目录结束记录 (EOCD)")

        var totalEntries = (tailBuffer.getShort(eocd + 10).toInt() and 0xFFFF).toLong()
        var cdSize = tailBuffer.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        var cdOffset = tailBuffer.getInt(eocd + 16).toLong() and 0xFFFFFFFFL

        // Zip64: 三个字段任一被写成全 1 就说明真值在 Zip64 的记录里。
        if (totalEntries == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            val locator = eocd - 20
            if (locator < 0 || tailBuffer.getInt(locator) != EOCD64_LOCATOR_SIG) {
                throw ParseFailure("需要 Zip64 但找不到 Zip64 EOCD 定位记录")
            }
            val eocd64Offset = tailBuffer.getLong(locator + 8)
            if (eocd64Offset < 0 || eocd64Offset + 56 > fileLength) throw ParseFailure("Zip64 EOCD 偏移越界")
            val header = ByteArray(56)
            file.seek(eocd64Offset)
            file.readFully(header)
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            if (h.getInt(0) != EOCD64_SIG) throw ParseFailure("Zip64 EOCD 记录签名不对")
            totalEntries = h.getLong(32)
            cdSize = h.getLong(40)
            cdOffset = h.getLong(48)
        }

        if (cdOffset < 0 || cdSize < 0 || cdOffset + cdSize > fileLength) throw ParseFailure("中央目录范围越界")
        if (cdSize > 256L * 1024 * 1024) throw ParseFailure("中央目录大得离谱 ($cdSize 字节), 拒绝解析")

        val cd = ByteArray(cdSize.toInt())
        file.seek(cdOffset)
        file.readFully(cd)
        val buffer = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)

        val entries = ArrayList<Entry>(minOf(totalEntries, 100_000L).toInt())
        var pos = 0
        while (pos + 46 <= cd.size) {
            if (buffer.getInt(pos) != CDFH_SIG) break
            val madeBy = buffer.getShort(pos + 4).toInt() and 0xFFFF
            val flags = buffer.getShort(pos + 8).toInt() and 0xFFFF
            val method = buffer.getShort(pos + 10).toInt() and 0xFFFF
            val compressed = buffer.getInt(pos + 20).toLong() and 0xFFFFFFFFL
            val uncompressed = buffer.getInt(pos + 24).toLong() and 0xFFFFFFFFL
            val nameLength = buffer.getShort(pos + 28).toInt() and 0xFFFF
            val extraLength = buffer.getShort(pos + 30).toInt() and 0xFFFF
            val commentLength = buffer.getShort(pos + 32).toInt() and 0xFFFF
            val externalAttributes = buffer.getInt(pos + 38).toLong() and 0xFFFFFFFFL
            val nameStart = pos + 46
            if (nameStart + nameLength > cd.size) throw ParseFailure("条目名越界")
            // bit 11 (0x800) = 条目名是 UTF-8; 没置位的按 ZIP 规范是 CP437。
            // 这里只用这个名字做安全检查与比对, 真正的解压仍由 java.util.zip 按它自己的规则处理 ——
            // 两边对同一个字节串可能解出不同的字符串, 所以下面的检查是"两种解读都必须安全"。
            val charset = if (flags and 0x800 != 0) StandardCharsets.UTF_8 else CP437
            val name = String(cd, nameStart, nameLength, charset)
            entries += Entry(name, compressed, uncompressed, externalAttributes, madeBy shr 8, method, flags)
            pos = nameStart + nameLength + extraLength + commentLength
        }
        /*
         * 条目数对不上就**不能当成"检查过了"**。
         *
         * 最典型的来路是带前导数据的归档 (自解压包、拼接文件): EOCD 找得到, 但里面的 cdOffset 是
         * 相对 ZIP 数据起点而不是文件起点的, 于是 seek 到的地方不是中央目录, 第一条签名就对不上、
         * 循环立刻退出 —— 返回一个空列表, 而调用方会把它报成"已检查 0 个条目, 无符号链接"。
         * 那句话是假的: 什么都没检查。
         */
        if (entries.size.toLong() != totalEntries) {
            throw ParseFailure("中央目录只读出 ${entries.size} 条, EOCD 说有 $totalEntries 条 (归档前面可能有额外数据)")
        }
        entries
    }
}
