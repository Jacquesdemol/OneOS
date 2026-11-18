package com.fahadali.oneos.core.image

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Writes a raw image to `<target>.part` while turning all-zero 4 KiB blocks into
 * filesystem holes (`position(pos + 4096)` + `truncate(logicalSize)`), and computing
 * the single-pass SHA-256 over all logical bytes (zeros included), matching
 * `sha256sum system.raw` (docs/app/patch-engine.md section 2.2).
 */
class HoleWriter(
    private val partFile: File,
    private val maxLogicalBytes: Long = DEFAULT_MAX_LOGICAL_BYTES,
) : SparseReader.HoleSink, Closeable {

    companion object {
        const val BLOCK_SIZE = 4096
        const val DEFAULT_MAX_LOGICAL_BYTES: Long = 24L * 1024L * 1024L * 1024L // 24 GiB
    }

    data class Result(
        val sha256: String,
        val logicalBytes: Long,
        val allocatedBytes: Long,
        val holeBlocks: Long,
    )

    private val raf = RandomAccessFile(partFile, "rw").apply { setLength(0) }
    private val channel = raf.channel
    private val digest = MessageDigest.getInstance("SHA-256")
    private val blockBuf = ByteArray(BLOCK_SIZE)
    private var blockFill = 0
    private val zeroBlock = ByteArray(BLOCK_SIZE)

    private var logicalBytesWritten = 0L
    private var allocatedBytesWritten = 0L
    private var holeBlocksCount = 0L
    private var finished = false

    override fun writeBytes(buf: ByteArray, offset: Int, length: Int) {
        check(!finished) { "HoleWriter already finished" }
        ensureQuota(length.toLong())
        var pos = offset
        var rem = length
        while (rem > 0) {
            if (blockFill == 0 && rem >= BLOCK_SIZE) {
                processFullBlock(buf, pos)
                pos += BLOCK_SIZE
                rem -= BLOCK_SIZE
            } else {
                val copy = minOf(rem, BLOCK_SIZE - blockFill)
                System.arraycopy(buf, pos, blockBuf, blockFill, copy)
                blockFill += copy
                pos += copy
                rem -= copy
                if (blockFill == BLOCK_SIZE) {
                    processFullBlock(blockBuf, 0)
                    blockFill = 0
                }
            }
        }
    }

    override fun writeZeroBytes(count: Long) {
        check(!finished) { "HoleWriter already finished" }
        require(count >= 0L) { "Negative zero byte count: $count" }
        if (count == 0L) return
        ensureQuota(count)

        var rem = count
        if (blockFill > 0) {
            val fill = minOf(rem, (BLOCK_SIZE - blockFill).toLong()).toInt()
            java.util.Arrays.fill(blockBuf, blockFill, blockFill + fill, 0.toByte())
            blockFill += fill
            rem -= fill
            if (blockFill == BLOCK_SIZE) {
                processFullBlock(blockBuf, 0)
                blockFill = 0
            }
        }

        val fullZeroBlocks = rem / BLOCK_SIZE
        if (fullZeroBlocks > 0) {
            val skipBytes = fullZeroBlocks * BLOCK_SIZE
            for (i in 0 until fullZeroBlocks) {
                digest.update(zeroBlock, 0, BLOCK_SIZE)
            }
            channel.position(channel.position() + skipBytes)
            logicalBytesWritten += skipBytes
            holeBlocksCount += fullZeroBlocks
            rem -= skipBytes
        }

        if (rem > 0) {
            val tail = rem.toInt()
            java.util.Arrays.fill(blockBuf, 0, tail, 0.toByte())
            blockFill = tail
        }
    }

    override fun finish(expectedLogicalSize: Long) {
        if (finished) return
        if (blockFill > 0) {
            digest.update(blockBuf, 0, blockFill)
            writeFully(blockBuf, 0, blockFill)
            logicalBytesWritten += blockFill
            allocatedBytesWritten += blockFill
            blockFill = 0
        }
        if (expectedLogicalSize >= 0L && logicalBytesWritten != expectedLogicalSize) {
            throw ImageRefusal.CorruptContainer(
                "Logical size mismatch: wrote $logicalBytesWritten bytes, expected $expectedLogicalSize bytes",
            )
        }
        // Ensure trailing holes materialize the full logical size via ftruncate.
        if (channel.size() < logicalBytesWritten) {
            raf.setLength(logicalBytesWritten)
        }
        channel.force(true)
        finished = true
    }

    fun finishResult(expectedLogicalSize: Long = -1L): Result {
        finish(expectedLogicalSize)
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return Result(
            sha256 = sha,
            logicalBytes = logicalBytesWritten,
            allocatedBytes = allocatedBytesWritten,
            holeBlocks = holeBlocksCount,
        )
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            raf.close()
        }
    }

    private fun ensureQuota(additional: Long) {
        val next = logicalBytesWritten + blockFill + additional
        if (next > maxLogicalBytes) {
            throw ImageRefusal.QuotaExceeded(
                "Unpacked image exceeds maximum size quota ($next > $maxLogicalBytes bytes)",
            )
        }
    }

    private fun processFullBlock(src: ByteArray, offset: Int) {
        digest.update(src, offset, BLOCK_SIZE)
        if (isAllZero(src, offset, BLOCK_SIZE)) {
            channel.position(channel.position() + BLOCK_SIZE)
            holeBlocksCount++
        } else {
            writeFully(src, offset, BLOCK_SIZE)
            allocatedBytesWritten += BLOCK_SIZE
        }
        logicalBytesWritten += BLOCK_SIZE
    }

    private fun isAllZero(buf: ByteArray, offset: Int, length: Int): Boolean {
        for (i in offset until offset + length) {
            if (buf[i].toInt() != 0) return false
        }
        return true
    }

    private fun writeFully(buf: ByteArray, offset: Int, length: Int) {
        val bb = ByteBuffer.wrap(buf, offset, length)
        while (bb.hasRemaining()) {
            channel.write(bb)
        }
    }
}
