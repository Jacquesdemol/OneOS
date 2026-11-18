package com.fahadali.oneos.core.image

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Streaming Android sparse image (`0xED26FF3A`) decoder (docs/app/patch-engine.md section 2.1).
 *
 * Supports:
 * - CHUNK_TYPE_RAW (0xCAC1)
 * - CHUNK_TYPE_FILL (0xCAC2)
 * - CHUNK_TYPE_DONT_CARE (0xCAC3)
 * - CHUNK_TYPE_CRC32 (0xCAC4)
 *
 * Zero-valued FILL chunks and DONT_CARE chunks are emitted via [HoleSink.writeZeroBlocks]
 * so [HoleWriter] turns them into sparse holes on disk while still hashing their logical zero bytes.
 */
object SparseReader {
    const val SPARSE_HEADER_MAGIC: Int = 0xED26FF3A.toInt()
    const val CHUNK_TYPE_RAW: Int = 0xCAC1
    const val CHUNK_TYPE_FILL: Int = 0xCAC2
    const val CHUNK_TYPE_DONT_CARE: Int = 0xCAC3
    const val CHUNK_TYPE_CRC32: Int = 0xCAC4

    data class SparseHeader(
        val majorVersion: Int,
        val minorVersion: Int,
        val fileHdrSz: Int,
        val chunkHdrSz: Int,
        val blkSz: Int,
        val totalBlks: Long,
        val totalChunks: Long,
        val imageChecksum: Long,
    ) {
        val logicalBytes: Long get() = totalBlks * blkSz.toLong()
    }

    interface HoleSink {
        fun writeBytes(buf: ByteArray, offset: Int, length: Int)
        fun writeZeroBytes(count: Long)
        fun finish(expectedLogicalSize: Long)
    }

    fun parseHeader(headerBytes: ByteArray): SparseHeader {
        if (headerBytes.size < 28) {
            throw ImageRefusal.CorruptContainer("Sparse image header truncated (${headerBytes.size} < 28 bytes)")
        }
        val bb = ByteBuffer.wrap(headerBytes, 0, 28).order(ByteOrder.LITTLE_ENDIAN)
        val magic = bb.int
        if (magic != SPARSE_HEADER_MAGIC) {
            throw ImageRefusal.CorruptContainer("Invalid sparse header magic: 0x${magic.toUInt().toString(16)}")
        }
        val major = bb.short.toInt() and 0xFFFF
        val minor = bb.short.toInt() and 0xFFFF
        val fileHdrSz = bb.short.toInt() and 0xFFFF
        val chunkHdrSz = bb.short.toInt() and 0xFFFF
        val blkSz = bb.int
        val totalBlks = bb.int.toLong() and 0xFFFFFFFFL
        val totalChunks = bb.int.toLong() and 0xFFFFFFFFL
        val imageChecksum = bb.int.toLong() and 0xFFFFFFFFL

        if (major != 1) {
            throw ImageRefusal.CorruptContainer("Unsupported sparse major version: $major")
        }
        if (fileHdrSz < 28 || fileHdrSz > 4096) {
            throw ImageRefusal.CorruptContainer("Invalid sparse file_hdr_sz: $fileHdrSz")
        }
        if (chunkHdrSz < 12 || chunkHdrSz > 4096) {
            throw ImageRefusal.CorruptContainer("Invalid sparse chunk_hdr_sz: $chunkHdrSz")
        }
        if (blkSz < 4096 || blkSz % 4096 != 0 || blkSz > 65536) {
            throw ImageRefusal.CorruptContainer("Invalid sparse block size: $blkSz (must be a multiple of 4096)")
        }
        if (totalBlks <= 0L) {
            throw ImageRefusal.CorruptContainer("Invalid sparse total_blks: $totalBlks")
        }
        return SparseHeader(
            majorVersion = major,
            minorVersion = minor,
            fileHdrSz = fileHdrSz,
            chunkHdrSz = chunkHdrSz,
            blkSz = blkSz,
            totalBlks = totalBlks,
            totalChunks = totalChunks,
            imageChecksum = imageChecksum,
        )
    }

    fun decode(
        input: InputStream,
        sink: HoleSink,
        maxLogicalBytes: Long,
        verifyCrc: Boolean = true,
    ): SparseHeader {
        val baseHdr = readExact(input, 28, "sparse header")
        val hdr = parseHeader(baseHdr)
        if (hdr.logicalBytes > maxLogicalBytes) {
            throw ImageRefusal.QuotaExceeded(
                "Sparse image logical size (${hdr.logicalBytes} B) exceeds maximum quota ($maxLogicalBytes B)",
            )
        }
        if (hdr.fileHdrSz > 28) {
            skipExact(input, (hdr.fileHdrSz - 28).toLong(), "sparse header extra bytes")
        }

        val crc = if (verifyCrc) CRC32() else null
        val zeroBlock = if (verifyCrc) ByteArray(4096) else null
        val ioBuf = ByteArray(64 * 1024)
        var blocksWritten = 0L

        for (chunkIdx in 0 until hdr.totalChunks) {
            val chunkHdrBytes = readExact(input, 12, "chunk $chunkIdx header")
            if (hdr.chunkHdrSz > 12) {
                skipExact(input, (hdr.chunkHdrSz - 12).toLong(), "chunk $chunkIdx header extra")
            }
            val cb = ByteBuffer.wrap(chunkHdrBytes).order(ByteOrder.LITTLE_ENDIAN)
            val chunkType = cb.short.toInt() and 0xFFFF
            cb.short // reserved1
            val chunkSzBlks = cb.int.toLong() and 0xFFFFFFFFL
            val totalSz = cb.int.toLong() and 0xFFFFFFFFL
            val payloadBytes = totalSz - hdr.chunkHdrSz
            if (payloadBytes < 0) {
                throw ImageRefusal.CorruptContainer("Chunk $chunkIdx has total_sz ($totalSz) < chunk_hdr_sz (${hdr.chunkHdrSz})")
            }
            val outBytes = chunkSzBlks * hdr.blkSz.toLong()
            if (blocksWritten + chunkSzBlks > hdr.totalBlks) {
                throw ImageRefusal.CorruptContainer(
                    "Chunk $chunkIdx exceeds declared total_blks (${blocksWritten + chunkSzBlks} > ${hdr.totalBlks})",
                )
            }

            when (chunkType) {
                CHUNK_TYPE_RAW -> {
                    if (payloadBytes != outBytes) {
                        throw ImageRefusal.CorruptContainer(
                            "RAW chunk $chunkIdx payload size ($payloadBytes) != expected block bytes ($outBytes)",
                        )
                    }
                    var remaining = outBytes
                    while (remaining > 0) {
                        val toRead = minOf(remaining, ioBuf.size.toLong()).toInt()
                        val n = input.read(ioBuf, 0, toRead)
                        if (n < 0) {
                            throw ImageRefusal.CorruptContainer("Unexpected EOF in RAW chunk $chunkIdx ($remaining bytes remaining)")
                        }
                        crc?.update(ioBuf, 0, n)
                        sink.writeBytes(ioBuf, 0, n)
                        remaining -= n
                    }
                    blocksWritten += chunkSzBlks
                }

                CHUNK_TYPE_FILL -> {
                    if (payloadBytes != 4L) {
                        throw ImageRefusal.CorruptContainer("FILL chunk $chunkIdx payload size ($payloadBytes) != 4")
                    }
                    val fillBytes = readExact(input, 4, "FILL chunk $chunkIdx value")
                    val fillVal = ByteBuffer.wrap(fillBytes).order(ByteOrder.LITTLE_ENDIAN).int
                    if (fillVal == 0) {
                        sink.writeZeroBytes(outBytes)
                        if (crc != null && zeroBlock != null) {
                            var rem = outBytes
                            while (rem > 0) {
                                val step = minOf(rem, zeroBlock.size.toLong()).toInt()
                                crc.update(zeroBlock, 0, step)
                                rem -= step
                            }
                        }
                    } else {
                        val pattern = ByteArray(hdr.blkSz)
                        val pb = ByteBuffer.wrap(pattern).order(ByteOrder.LITTLE_ENDIAN)
                        while (pb.hasRemaining()) pb.putInt(fillVal)
                        for (b in 0 until chunkSzBlks) {
                            crc?.update(pattern, 0, pattern.size)
                            sink.writeBytes(pattern, 0, pattern.size)
                        }
                    }
                    blocksWritten += chunkSzBlks
                }

                CHUNK_TYPE_DONT_CARE -> {
                    if (payloadBytes != 0L) {
                        throw ImageRefusal.CorruptContainer("DONT_CARE chunk $chunkIdx has non-zero payload ($payloadBytes)")
                    }
                    sink.writeZeroBytes(outBytes)
                    if (crc != null && zeroBlock != null) {
                        var rem = outBytes
                        while (rem > 0) {
                            val step = minOf(rem, zeroBlock.size.toLong()).toInt()
                            crc.update(zeroBlock, 0, step)
                            rem -= step
                        }
                    }
                    blocksWritten += chunkSzBlks
                }

                CHUNK_TYPE_CRC32 -> {
                    if (payloadBytes != 4L) {
                        throw ImageRefusal.CorruptContainer("CRC32 chunk $chunkIdx payload size ($payloadBytes) != 4")
                    }
                    val crcBytes = readExact(input, 4, "CRC32 chunk $chunkIdx value")
                    val expectedCrc = ByteBuffer.wrap(crcBytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    if (crc != null && crc.value != expectedCrc) {
                        throw ImageRefusal.CorruptContainer(
                            "Sparse CRC32 chunk mismatch at chunk $chunkIdx: expected 0x${expectedCrc.toString(16)}, got 0x${crc.value.toString(16)}",
                        )
                    }
                }

                else -> throw ImageRefusal.CorruptContainer("Unknown sparse chunk type: 0x${chunkType.toString(16)}")
            }
        }

        if (blocksWritten != hdr.totalBlks) {
            throw ImageRefusal.CorruptContainer(
                "Sparse image block count mismatch: wrote $blocksWritten blocks, header declared ${hdr.totalBlks}",
            )
        }
        if (crc != null && hdr.imageChecksum != 0L && crc.value != hdr.imageChecksum) {
            throw ImageRefusal.CorruptContainer(
                "Sparse header checksum mismatch: expected 0x${hdr.imageChecksum.toString(16)}, got 0x${crc.value.toString(16)}",
            )
        }
        sink.finish(hdr.logicalBytes)
        return hdr
    }

    private fun readExact(input: InputStream, length: Int, label: String): ByteArray {
        val out = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(out, off, length - off)
            if (n < 0) {
                throw ImageRefusal.CorruptContainer(
                    "Unexpected EOF while reading $label (got $off of $length bytes)",
                )
            }
            off += n
        }
        return out
    }

    private fun skipExact(input: InputStream, count: Long, label: String) {
        var remaining = count
        val scratch = ByteArray(minOf(count, 4096L).toInt())
        while (remaining > 0) {
            val step = minOf(remaining, scratch.size.toLong()).toInt()
            val n = input.read(scratch, 0, step)
            if (n < 0) throw EOFException("Unexpected EOF skipping $label")
            remaining -= n
        }
    }
}
