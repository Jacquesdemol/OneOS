package com.fahadali.oneos.core.image

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class FsKind(val id: String) {
    EXT4("ext4"),
    EROFS("erofs"),
}

data class FsInfo(
    val kind: FsKind,
    val blockSize: Int,
    val blockCount: Long,
    val filesystemBytes: Long,
    val uuid: String,
    val volumeName: String,
    val roCompatSharedBlocks: Boolean = false,
)

/**
 * Inspects and validates ext4 (`0xEF53` at 1080) and EROFS (`0xE0F5E1E2` at 1024)
 * superblocks in raw system images (docs/app/patch-engine.md section 2.3).
 */
object FsProbe {
    private const val EXT4_INCOMPAT_64BIT = 0x0080
    private const val EXT4_RO_COMPAT_SHARED_BLOCKS = 0x4000

    fun probe(rawImage: File): FsInfo {
        val fileLen = rawImage.length()
        if (fileLen < 2048L) {
            throw ImageRefusal.TruncatedFilesystem("Image file too small for superblock ($fileLen bytes)")
        }
        val sb = ByteArray(1024)
        RandomAccessFile(rawImage, "r").use { raf ->
            raf.seek(1024L)
            raf.readFully(sb)
        }
        return parseSuperblock(sb, fileLen)
    }

    fun parseSuperblock(superblockAt1024: ByteArray, fileLength: Long): FsInfo {
        if (superblockAt1024.size < 1024) {
            throw ImageRefusal.TruncatedFilesystem("Superblock buffer shorter than 1024 bytes")
        }
        val bb = ByteBuffer.wrap(superblockAt1024).order(ByteOrder.LITTLE_ENDIAN)
        val ext4Magic = bb.getShort(0x38).toInt() and 0xFFFF
        if (ext4Magic == ContainerProbe.EXT4_MAGIC) {
            val inodesCount = bb.getInt(0x00).toLong() and 0xFFFFFFFFL
            val blocksLo = bb.getInt(0x04).toLong() and 0xFFFFFFFFL
            val logBlockSize = bb.getInt(0x18)
            if (logBlockSize !in 0..6) {
                throw ImageRefusal.CorruptContainer("Invalid ext4 s_log_block_size: $logBlockSize")
            }
            val blockSize = 1024 shl logBlockSize
            val featureIncompat = bb.getInt(0x60)
            val featureRoCompat = bb.getInt(0x64)
            val blocksHi = if ((featureIncompat and EXT4_INCOMPAT_64BIT) != 0) {
                bb.getInt(0x150).toLong() and 0xFFFFFFFFL
            } else {
                0L
            }
            val totalBlocks = (blocksHi shl 32) or blocksLo
            if (inodesCount <= 0L || totalBlocks <= 0L) {
                throw ImageRefusal.CorruptContainer("Invalid ext4 superblock counts (inodes=$inodesCount, blocks=$totalBlocks)")
            }
            val fsBytes = totalBlocks * blockSize.toLong()
            if (fsBytes > fileLength) {
                throw ImageRefusal.TruncatedFilesystem(
                    "Truncated ext4 filesystem: superblock requires $fsBytes bytes ($totalBlocks * $blockSize), file has $fileLength bytes",
                )
            }
            val uuid = formatUuid(superblockAt1024, 0x68)
            val volBytes = superblockAt1024.copyOfRange(0x78, 0x88)
            val nul = volBytes.indexOf(0.toByte()).let { if (it < 0) volBytes.size else it }
            val volumeName = String(volBytes, 0, nul, Charsets.UTF_8).trim()
            val sharedBlocks = (featureRoCompat and EXT4_RO_COMPAT_SHARED_BLOCKS) != 0
            return FsInfo(
                kind = FsKind.EXT4,
                blockSize = blockSize,
                blockCount = totalBlocks,
                filesystemBytes = fsBytes,
                uuid = uuid,
                volumeName = volumeName,
                roCompatSharedBlocks = sharedBlocks,
            )
        }

        val erofsMagic = bb.getInt(0x00)
        if (erofsMagic == ContainerProbe.EROFS_MAGIC) {
            val blkszbits = superblockAt1024[0x0C].toInt() and 0xFF
            if (blkszbits !in 9..16) {
                throw ImageRefusal.CorruptContainer("Invalid EROFS blkszbits: $blkszbits")
            }
            val blockSize = 1 shl blkszbits
            val blocks = bb.getInt(0x24).toLong() and 0xFFFFFFFFL
            if (blocks <= 0L) {
                throw ImageRefusal.CorruptContainer("Invalid EROFS block count: $blocks")
            }
            val fsBytes = blocks * blockSize.toLong()
            if (fsBytes > fileLength) {
                throw ImageRefusal.TruncatedFilesystem(
                    "Truncated EROFS filesystem: superblock requires $fsBytes bytes ($blocks * $blockSize), file has $fileLength bytes",
                )
            }
            val uuid = formatUuid(superblockAt1024, 0x30)
            val volBytes = superblockAt1024.copyOfRange(0x40, 0x50)
            val nul = volBytes.indexOf(0.toByte()).let { if (it < 0) volBytes.size else it }
            val volumeName = String(volBytes, 0, nul, Charsets.UTF_8).trim()
            return FsInfo(
                kind = FsKind.EROFS,
                blockSize = blockSize,
                blockCount = blocks,
                filesystemBytes = fsBytes,
                uuid = uuid,
                volumeName = volumeName,
            )
        }

        throw ImageRefusal.UnsupportedFormat(
            ContainerKind.UNKNOWN,
            "Unrecognized filesystem superblock (expected ext4 0xEF53 or EROFS 0xE0F5E1E2)",
        )
    }

    private fun formatUuid(buf: ByteArray, offset: Int): String {
        val hex = (offset until offset + 16).joinToString("") { "%02x".format(buf[it]) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }
}
