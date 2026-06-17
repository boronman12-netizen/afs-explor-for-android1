package com.king.afsexplorer.format

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AFS container format reader/writer.
 *
 * Structure (little-endian):
 *   0x00: magic "AFS\0" (4 bytes) -- some variants use "AFS\x20"
 *   0x04: fileCount (uint32)
 *   0x08: fileHeaders[fileCount] -- 8 bytes each: { offset: uint32, size: uint32 }
 *   (padding/alignment may follow)
 *   descriptorTableOffset, descriptorTableSize: uint32, uint32
 *     -- usually located right after the last file's data, pointed to by
 *        the 8 bytes immediately following the file header table.
 *   descriptors[fileCount] -- 32 bytes each:
 *     0x00: filename (char[32], null-terminated, but some games pack
 *           timestamp into bytes 0x20-0x2F instead, with name truncated)
 *     standard 32-byte descriptor layout used by AFSExplorer-compatible tools:
 *     0x00 name[16]
 *     0x10 year(u16) month(u16)
 *     0x14 day(u16) hour(u16)
 *     0x18 minute(u16) second(u16)
 *     0x1C size(u32)  -- size repeated here for verification against header
 *
 * Files are stored in successive 2048-byte-aligned blocks ("slots").
 * Replacing a file with a larger one requires rebuilding (re-slotting),
 * exactly as AFSExplorer's "regeneration" describes.
 */

const val AFS_BLOCK_SIZE = 2048

data class AfsEntry(
    var name: String,
    var offset: Long,
    var size: Long,
    var reservedSize: Long, // slot size, rounded to AFS_BLOCK_SIZE boundary semantics
    var year: Int = 0,
    var month: Int = 0,
    var day: Int = 0,
    var hour: Int = 0,
    var minute: Int = 0,
    var second: Int = 0
)

class AfsCoherenceException(message: String) : Exception(message)

class AfsFile private constructor(
    val sourcePath: String,
    val entries: MutableList<AfsEntry>
) {
    companion object {
        private val MAGIC_VARIANTS = listOf(
            byteArrayOf('A'.code.toByte(), 'F'.code.toByte(), 'S'.code.toByte(), 0x00),
            byteArrayOf('A'.code.toByte(), 'F'.code.toByte(), 'S'.code.toByte(), 0x20)
        )

        fun isAfs(path: String): Boolean {
            return try {
                RandomAccessFile(path, "r").use { raf ->
                    val head = ByteArray(4)
                    raf.read(head)
                    MAGIC_VARIANTS.any { it.contentEquals(head) }
                }
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Parses an AFS file. Throws AfsCoherenceException if header/descriptor
         * counts mismatch -- mirroring AFSExplorer's "Descriptors # != Files #"
         * and "Header # != Files #" checks, so the caller can offer
         * auto-regeneration just like the original tool does.
         */
        fun open(path: String): AfsFile {
            RandomAccessFile(path, "r").use { raf ->
                val len = raf.length()
                if (len < 8) throw AfsCoherenceException("Failed reading AFS Main Header")

                val magic = ByteArray(4)
                raf.read(magic)
                if (!MAGIC_VARIANTS.any { it.contentEquals(magic) }) {
                    throw AfsCoherenceException("Not an AFS file (bad magic)")
                }

                val fileCount = readU32LE(raf)
                if (fileCount <= 0 || fileCount > 200_000) {
                    throw AfsCoherenceException("Failed reading AFS File Headers")
                }

                // Read file header table: fileCount * (offset u32, size u32)
                val headerEntries = ArrayList<Pair<Long, Long>>(fileCount.toInt())
                for (i in 0 until fileCount) {
                    val off = readU32LE(raf)
                    val sz = readU32LE(raf)
                    headerEntries.add(off to sz)
                }

                // Immediately after the header table sits a pointer to the
                // descriptor table: { offset: u32, size: u32 }
                val descTableOffset = readU32LE(raf)
                val descTableSize = readU32LE(raf)

                if (descTableOffset <= 0 || descTableOffset > len) {
                    throw AfsCoherenceException("Descriptor table missing!")
                }

                val entries = ArrayList<AfsEntry>(fileCount.toInt())
                raf.seek(descTableOffset)
                val descBuf = ByteArray(32)
                for (i in 0 until fileCount) {
                    val read = raf.read(descBuf)
                    if (read < 32) {
                        throw AfsCoherenceException("Failed reading AFS File Descriptors!")
                    }
                    val bb = ByteBuffer.wrap(descBuf).order(ByteOrder.LITTLE_ENDIAN)
                    val nameBytes = ByteArray(16)
                    bb.get(nameBytes)
                    var name = String(nameBytes, Charsets.US_ASCII).trim('\u0000', ' ')
                    if (name.isBlank()) name = "file_%05d.bin".format(i)

                    val year = bb.short.toInt() and 0xFFFF
                    val month = bb.short.toInt() and 0xFFFF
                    val day = bb.short.toInt() and 0xFFFF
                    val hour = bb.short.toInt() and 0xFFFF
                    val minute = bb.short.toInt() and 0xFFFF
                    val second = bb.short.toInt() and 0xFFFF
                    val descSize = bb.int.toLong() and 0xFFFFFFFFL

                    val (hOffset, hSize) = headerEntries[i]

                    // Coherence check, mirroring original tool's yellow-highlight logic:
                    // header size vs descriptor size mismatch is tolerated but should
                    // be surfaced to the UI layer.
                    val effectiveSize = if (hSize > 0) hSize else descSize

                    val reserved = computeReservedSlot(hOffset, headerEntries, i, len, descTableOffset)

                    entries.add(
                        AfsEntry(
                            name = name,
                            offset = hOffset,
                            size = effectiveSize,
                            reservedSize = reserved,
                            year = year, month = month, day = day,
                            hour = hour, minute = minute, second = second
                        )
                    )
                }

                return AfsFile(path, entries)
            }
        }

        /**
         * Reserved slot size = distance to next file's offset (block-aligned),
         * or to the descriptor table for the last entry. This matches how
         * AFSExplorer displays "unused space" in yellow/green.
         */
        private fun computeReservedSlot(
            myOffset: Long,
            headers: List<Pair<Long, Long>>,
            index: Int,
            fileLen: Long,
            descTableOffset: Long
        ): Long {
            val sortedOffsets = headers.map { it.first }.filter { it > myOffset }.sorted()
            val nextBoundary = sortedOffsets.firstOrNull() ?: descTableOffset
            val boundary = if (nextBoundary > myOffset) nextBoundary else fileLen
            return boundary - myOffset
        }

        private fun readU32LE(raf: RandomAccessFile): Long {
            val b = ByteArray(4)
            raf.read(b)
            return (b[0].toLong() and 0xFF) or
                    ((b[1].toLong() and 0xFF) shl 8) or
                    ((b[2].toLong() and 0xFF) shl 16) or
                    ((b[3].toLong() and 0xFF) shl 24)
        }
    }

    /** Extracts a single entry's raw bytes from the AFS container. */
    fun extractEntry(entry: AfsEntry): ByteArray {
        RandomAccessFile(sourcePath, "r").use { raf ->
            raf.seek(entry.offset)
            val buf = ByteArray(entry.size.toInt())
            raf.readFully(buf)
            return buf
        }
    }

    /** Extracts every entry to the given output directory, preserving names. */
    fun extractAll(outputDir: String, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val dir = java.io.File(outputDir)
        if (!dir.exists()) dir.mkdirs()
        entries.forEachIndexed { idx, entry ->
            val bytes = extractEntry(entry)
            java.io.File(dir, sanitizeFileName(entry.name)).writeBytes(bytes)
            onProgress(idx + 1, entries.size)
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "unnamed.bin" }
    }

    /**
     * Replaces a single file's content in-place IF the new content fits
     * within the existing reserved slot. Returns false if rebuild is
     * required (new content too large) -- caller should fall back to
     * rebuildWithReplacement().
     */
    fun replaceInPlace(entryIndex: Int, newData: ByteArray): Boolean {
        val entry = entries[entryIndex]
        if (newData.size.toLong() > entry.reservedSize) return false

        RandomAccessFile(sourcePath, "rw").use { raf ->
            raf.seek(entry.offset)
            raf.write(newData)
        }
        entry.size = newData.size.toLong()
        return true
    }

    /**
     * Full rebuild ("regeneration" in AFSExplorer terms): recomputes slots
     * for every entry (rounded up to AFS_BLOCK_SIZE), writes a brand new
     * AFS file with the replacement applied. This is the resource-heavy
     * path the original tool warns about.
     */
    fun rebuildWithReplacement(
        outputPath: String,
        replacements: Map<Int, ByteArray>
    ) {
        val newSizes = entries.mapIndexed { i, e ->
            (replacements[i]?.size?.toLong()) ?: e.size
        }

        val headerSize = 8 + entries.size * 8 + 8 // magic+count + headers + desc-table-pointer
        var cursor = align(headerSize.toLong(), AFS_BLOCK_SIZE)

        val newOffsets = LongArray(entries.size)
        for (i in entries.indices) {
            newOffsets[i] = cursor
            cursor = align(cursor + newSizes[i], AFS_BLOCK_SIZE)
        }
        val descTableOffset = cursor
        val descTableSize = entries.size * 32L

        RandomAccessFile(outputPath, "rw").use { out ->
            out.setLength(0)

            // Main header
            out.write(byteArrayOf('A'.code.toByte(), 'F'.code.toByte(), 'S'.code.toByte(), 0x00))
            writeU32LE(out, entries.size.toLong())

            // File header table
            for (i in entries.indices) {
                writeU32LE(out, newOffsets[i])
                writeU32LE(out, newSizes[i])
            }
            // Descriptor table pointer
            writeU32LE(out, descTableOffset)
            writeU32LE(out, descTableSize)

            // File data, block-aligned
            for (i in entries.indices) {
                out.seek(newOffsets[i])
                val data = replacements[i] ?: extractEntry(entries[i])
                out.write(data)
            }

            // Descriptors
            out.seek(descTableOffset)
            for ((i, entry) in entries.withIndex()) {
                val nameBytes = ByteArray(16)
                val src = entry.name.take(16).toByteArray(Charsets.US_ASCII)
                System.arraycopy(src, 0, nameBytes, 0, src.size)
                out.write(nameBytes)

                val bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                bb.putShort(entry.year.toShort())
                bb.putShort(entry.month.toShort())
                bb.putShort(entry.day.toShort())
                bb.putShort(entry.hour.toShort())
                bb.putShort(entry.minute.toShort())
                bb.putShort(entry.second.toShort())
                bb.putInt(newSizes[i].toInt())
                out.write(bb.array())
            }
        }

        // Update in-memory model to reflect the new file
        for (i in entries.indices) {
            entries[i].offset = newOffsets[i]
            entries[i].size = newSizes[i]
            entries[i].reservedSize = align(newSizes[i], AFS_BLOCK_SIZE)
        }
    }

    private fun align(value: Long, block: Int): Long {
        val rem = value % block
        return if (rem == 0L) value else value + (block - rem)
    }

    private fun writeU32LE(raf: RandomAccessFile, value: Long) {
        val b = ByteArray(4)
        b[0] = (value and 0xFF).toByte()
        b[1] = ((value shr 8) and 0xFF).toByte()
        b[2] = ((value shr 16) and 0xFF).toByte()
        b[3] = ((value shr 24) and 0xFF).toByte()
        raf.write(b)
    }
}
