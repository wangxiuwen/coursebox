package com.wangxiuwen.coursebox.core.cx

import android.util.Log
import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "CxArchive"

/**
 * Random-access reader for a `.cx` archive (a renamed zip with STORED-only
 * entries). The point of this class is to never extract objects onto
 * disk — instead we scan the zip's central directory once, record the
 * byte offset + length of every entry's data region, and let callers
 * (e.g. the ExoPlayer DataSource) slice the file at runtime.
 *
 * Format assumptions:
 *   - all entries use compression method 0 (STORED). The packager pins
 *     ZIP_STORED so this holds.
 *   - no ZIP64 (file < 4 GiB, individual entry < 4 GiB). When we add the
 *     multi-part .cx.part0/1/... split, each part stays well under 4 GiB
 *     so we don't need ZIP64 handling.
 *   - filenames are UTF-8 (which is what python's zipfile writes by
 *     default).
 *
 * Wire pieces (little-endian):
 *   - EOCD record: signature 0x06054b50, ~22 bytes at end of file
 *       offset 16: central directory offset
 *       offset 10: total entries
 *   - Central directory entry: signature 0x02014b50, variable length
 *       offset 28: filename length
 *       offset 30: extra field length
 *       offset 32: comment length
 *       offset 42: local header offset
 *       offset 46: filename (UTF-8)
 *   - Local file header: signature 0x04034b50, 30+ bytes
 *       offset 26: filename length
 *       offset 28: extra field length
 *       data starts at: localOffset + 30 + filenameLen + extraLen
 */
class CxArchive private constructor(
    val file: File,
    private val entries: Map<String, Entry>,
) {

    /** A single entry's location inside the .cx file. */
    @Serializable
    data class Entry(
        val name: String,
        val dataOffset: Long,
        val size: Long,
    )

    fun entryByName(name: String): Entry? = entries[name]

    /** Surfaces every entry — useful for cross-referencing with manifest
     *  resources (path → entry) without re-opening. */
    fun allEntries(): Map<String, Entry> = entries

    /**
     * Read [length] bytes starting at the entry's dataOffset + [innerOffset].
     * Cheap: opens a RandomAccessFile per call (caller can keep one around
     * via [openReader] for hot loops).
     */
    fun readBytes(entry: Entry, innerOffset: Long, length: Int): ByteArray {
        require(innerOffset >= 0)
        require(length >= 0)
        require(innerOffset + length <= entry.size) {
            "out-of-range read on ${entry.name}: $innerOffset+$length > ${entry.size}"
        }
        val out = ByteArray(length)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(entry.dataOffset + innerOffset)
            raf.readFully(out)
        }
        return out
    }

    /** Opens a long-lived [Reader] for one entry. Caller must close it. */
    fun openReader(entry: Entry): Reader = Reader(file, entry)

    class Reader(file: File, val entry: Entry) : AutoCloseable {
        private val raf = RandomAccessFile(file, "r").also { it.seek(entry.dataOffset) }
        var position: Long = 0
            private set

        fun seek(innerOffset: Long) {
            raf.seek(entry.dataOffset + innerOffset)
            position = innerOffset
        }

        fun read(buf: ByteArray, off: Int, len: Int): Int {
            val remaining = entry.size - position
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = raf.read(buf, off, toRead)
            if (n > 0) position += n
            return n
        }

        override fun close() { runCatching { raf.close() } }
    }

    companion object {
        private const val EOCD_SIG = 0x06054b50
        private const val CD_SIG = 0x02014b50
        private const val LOCAL_SIG = 0x04034b50
        private const val EOCD_MAX_COMMENT = 65535
        private const val EOCD_MIN_SIZE = 22

        /**
         * Open and index a .cx file. Walks the central directory once;
         * subsequent reads use the cached offset map.
         */
        fun open(file: File): CxArchive {
            require(file.exists() && file.length() > 0) { "not a file: $file" }
            RandomAccessFile(file, "r").use { raf ->
                val total = raf.length()
                val eocd = findEocd(raf, total) ?: error("EOCD not found — not a zip/.cx?")
                val (cdOffset, cdEntries) = parseEocd(raf, eocd)
                val entries = walkCentralDir(raf, cdOffset, cdEntries)
                Log.i(TAG, "opened ${file.name}: ${entries.size} entries")
                return CxArchive(file, entries)
            }
        }

        /** Scan backward from end-of-file for the EOCD signature. The
         *  comment field is up to 64 KiB so we cap the scan there. */
        private fun findEocd(raf: RandomAccessFile, total: Long): Long? {
            val scanFrom = maxOf(0L, total - EOCD_MIN_SIZE - EOCD_MAX_COMMENT)
            val len = (total - scanFrom).toInt()
            val buf = ByteArray(len)
            raf.seek(scanFrom)
            raf.readFully(buf)
            // EOCD must be at least 22 bytes from end
            for (i in len - EOCD_MIN_SIZE downTo 0) {
                if (
                    buf[i] == 0x50.toByte() &&
                    buf[i + 1] == 0x4b.toByte() &&
                    buf[i + 2] == 0x05.toByte() &&
                    buf[i + 3] == 0x06.toByte()
                ) {
                    return scanFrom + i
                }
            }
            return null
        }

        private fun parseEocd(raf: RandomAccessFile, eocdOffset: Long): Pair<Long, Int> {
            raf.seek(eocdOffset)
            val buf = ByteArray(EOCD_MIN_SIZE)
            raf.readFully(buf)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            require(bb.int == EOCD_SIG) { "bad EOCD signature" }
            bb.position(10)
            val totalEntries = bb.short.toInt() and 0xffff
            bb.position(16)
            val cdOffset = bb.int.toLong() and 0xffffffffL
            return cdOffset to totalEntries
        }

        private fun walkCentralDir(
            raf: RandomAccessFile,
            cdOffset: Long,
            cdEntries: Int,
        ): Map<String, Entry> {
            val map = LinkedHashMap<String, Entry>(cdEntries)
            raf.seek(cdOffset)
            repeat(cdEntries) {
                val cdHeader = ByteArray(46)
                raf.readFully(cdHeader)
                val bb = ByteBuffer.wrap(cdHeader).order(ByteOrder.LITTLE_ENDIAN)
                require(bb.int == CD_SIG) { "bad CD signature at entry index" }
                bb.position(10)
                val method = bb.short.toInt() and 0xffff
                bb.position(20)
                var compressedSize = bb.int.toLong() and 0xffffffffL
                var uncompressedSize = bb.int.toLong() and 0xffffffffL
                bb.position(28)
                val filenameLen = bb.short.toInt() and 0xffff
                val extraLen = bb.short.toInt() and 0xffff
                val commentLen = bb.short.toInt() and 0xffff
                bb.position(42)
                var localHeaderOffset = bb.int.toLong() and 0xffffffffL

                val name = ByteArray(filenameLen).also { raf.readFully(it) }.toString(Charsets.UTF_8)

                // ZIP64 extended-info extra field: when the uint32 size or
                // offset is 0xFFFFFFFF, the real value lives in the extra
                // block. Python's zipfile writes the offset into ZIP64 the
                // moment header_offset > 2 GiB (signed-int boundary) — so
                // any pack > 2 GB needs this even when the absolute offset
                // would still fit in uint32.
                if (extraLen > 0) {
                    val extra = ByteArray(extraLen).also { raf.readFully(it) }
                    parseZip64Extra(
                        extra,
                        wantUncompressed = uncompressedSize == 0xffffffffL,
                        wantCompressed = compressedSize == 0xffffffffL,
                        wantOffset = localHeaderOffset == 0xffffffffL,
                    )?.let { (u, c, o) ->
                        if (u != null) uncompressedSize = u
                        if (c != null) compressedSize = c
                        if (o != null) localHeaderOffset = o
                    }
                }
                if (commentLen > 0) raf.skipBytes(commentLen)

                if (method != 0) {
                    Log.w(TAG, "$name uses compression $method; skipping (only STORED supported)")
                    return@repeat
                }
                if (compressedSize != uncompressedSize) {
                    Log.w(TAG, "$name has compressed != uncompressed; corrupt?")
                    return@repeat
                }
                // Park current position so we can read the local header
                // without losing our place in the central directory walk.
                val resume = raf.filePointer
                raf.seek(localHeaderOffset)
                val lh = ByteArray(30)
                raf.readFully(lh)
                val lhbb = ByteBuffer.wrap(lh).order(ByteOrder.LITTLE_ENDIAN)
                require(lhbb.int == LOCAL_SIG) { "bad local header for $name" }
                lhbb.position(26)
                val lhFilenameLen = lhbb.short.toInt() and 0xffff
                val lhExtraLen = lhbb.short.toInt() and 0xffff
                val dataOffset = localHeaderOffset + 30 + lhFilenameLen + lhExtraLen
                raf.seek(resume)

                map[name] = Entry(name, dataOffset, compressedSize)
            }
            return map
        }

        /**
         * Pull (uncompressedSize, compressedSize, localHeaderOffset) out of
         * a ZIP64 extra block — but only the fields the caller asked for
         * (i.e. the ones whose uint32 was 0xFFFFFFFF). Order in the block
         * is fixed by the spec: uncompressed, compressed, offset.
         *
         * Returns null if no ZIP64 extra was found.
         */
        private fun parseZip64Extra(
            extra: ByteArray,
            wantUncompressed: Boolean,
            wantCompressed: Boolean,
            wantOffset: Boolean,
        ): Triple<Long?, Long?, Long?>? {
            val bb = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN)
            while (bb.remaining() >= 4) {
                val id = bb.short.toInt() and 0xffff
                val size = bb.short.toInt() and 0xffff
                if (id != 0x0001) {
                    bb.position(bb.position() + size)
                    continue
                }
                val start = bb.position()
                var u: Long? = null
                var c: Long? = null
                var o: Long? = null
                if (wantUncompressed && bb.position() - start + 8 <= size) u = bb.long
                if (wantCompressed && bb.position() - start + 8 <= size) c = bb.long
                if (wantOffset && bb.position() - start + 8 <= size) o = bb.long
                return Triple(u, c, o)
            }
            return null
        }
    }
}
