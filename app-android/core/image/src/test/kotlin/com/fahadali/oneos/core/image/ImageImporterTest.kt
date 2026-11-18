package com.fahadali.oneos.core.image

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

class ImageImporterTest {

    private fun buildSyntheticExt4Image(totalBlocks: Int = 16, blockSize: Int = 4096): ByteArray {
        val raw = ByteArray(totalBlocks * blockSize)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        // Superblock at offset 1024
        bb.putInt(1024 + 0x00, 128) // s_inodes_count
        bb.putInt(1024 + 0x04, totalBlocks) // s_blocks_count_lo
        val logBlockSize = when (blockSize) {
            1024 -> 0
            2048 -> 1
            4096 -> 2
            else -> error("bad blockSize")
        }
        bb.putInt(1024 + 0x18, logBlockSize)
        bb.putShort(1024 + 0x38, ContainerProbe.EXT4_MAGIC.toShort())
        // UUID at 1024 + 0x68
        for (i in 0 until 16) {
            raw[1024 + 0x68 + i] = (i + 1).toByte()
        }
        // Volume name "system" at 1024 + 0x78
        val vol = "system".toByteArray(Charsets.UTF_8)
        System.arraycopy(vol, 0, raw, 1024 + 0x78, vol.size)
        // Put non-zero payload in block 2, leave blocks 3..14 all-zero (holes), put non-zero in last block
        raw[2 * blockSize + 7] = 0x5A.toByte()
        raw[(totalBlocks - 1) * blockSize + 19] = 0x3C.toByte()
        return raw
    }

    private fun buildSyntheticErofsImage(totalBlocks: Int = 8, blockSize: Int = 4096): ByteArray {
        val raw = ByteArray(totalBlocks * blockSize)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(1024 + 0x00, ContainerProbe.EROFS_MAGIC)
        raw[1024 + 0x0C] = 12.toByte() // 2^12 = 4096
        bb.putInt(1024 + 0x24, totalBlocks)
        return raw
    }

    private fun buildSparseFromExt4(rawExt4: ByteArray, blockSize: Int = 4096): ByteArray {
        // Chunk 0: RAW (block 0: contains superblock)
        // Chunk 1: DONT_CARE (block 1: all zeros)
        // Chunk 2: RAW (block 2: non-zero)
        // Chunk 3: FILL 0x00000000 (blocks 3..14: 12 blocks)
        // Chunk 4: RAW (block 15: non-zero)
        // Chunk 5: CRC32
        val crc = CRC32().apply { update(rawExt4) }.value.toInt()
        val out = ByteArrayOutputStream()
        val hdr = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(SparseReader.SPARSE_HEADER_MAGIC)
        hdr.putShort(1) // major
        hdr.putShort(0) // minor
        hdr.putShort(28) // file_hdr_sz
        hdr.putShort(12) // chunk_hdr_sz
        hdr.putInt(blockSize)
        hdr.putInt(rawExt4.size / blockSize) // total_blks = 16
        hdr.putInt(6) // total_chunks = 6
        hdr.putInt(crc)
        out.write(hdr.array())

        fun writeChunkHeader(type: Int, chunkBlks: Int, payloadLen: Int) {
            val cb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            cb.putShort(type.toShort())
            cb.putShort(0)
            cb.putInt(chunkBlks)
            cb.putInt(12 + payloadLen)
            out.write(cb.array())
        }

        // Chunk 0: RAW block 0
        writeChunkHeader(SparseReader.CHUNK_TYPE_RAW, 1, blockSize)
        out.write(rawExt4, 0, blockSize)
        // Chunk 1: DONT_CARE block 1
        writeChunkHeader(SparseReader.CHUNK_TYPE_DONT_CARE, 1, 0)
        // Chunk 2: RAW block 2
        writeChunkHeader(SparseReader.CHUNK_TYPE_RAW, 1, blockSize)
        out.write(rawExt4, 2 * blockSize, blockSize)
        // Chunk 3: FILL 0 for blocks 3..14 (12 blocks)
        writeChunkHeader(SparseReader.CHUNK_TYPE_FILL, 12, 4)
        out.write(ByteArray(4))
        // Chunk 4: RAW block 15
        writeChunkHeader(SparseReader.CHUNK_TYPE_RAW, 1, blockSize)
        out.write(rawExt4, 15 * blockSize, blockSize)
        // Chunk 5: CRC32
        writeChunkHeader(SparseReader.CHUNK_TYPE_CRC32, 0, 4)
        val crcBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc).array()
        out.write(crcBytes)

        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun importsRawSparseGzipXzAndZipWithIdenticalLogicalSha256() {
        val dir = Files.createTempDirectory("oneos-image-test").toFile()
        try {
            val rawExt4 = buildSyntheticExt4Image()
            val expectedSha = sha256(rawExt4)
            val sparseBytes = buildSparseFromExt4(rawExt4)

            // 1. Raw ext4
            val rawFile = File(dir, "system.img").apply { writeBytes(rawExt4) }
            val importedRaw = ImageImporter.importImage(rawFile, File(dir, "stage-raw"))
            assertEquals(expectedSha, importedRaw.sha256)
            assertEquals(rawExt4.size.toLong(), importedRaw.logicalBytes)
            assertEquals(FsKind.EXT4, importedRaw.fs.kind)
            assertEquals("01020304-0506-0708-090a-0b0c0d0e0f10", importedRaw.fs.uuid)
            assertEquals("system", importedRaw.fs.volumeName)
            assertTrue(importedRaw.holeBlocks >= 13L)
            assertTrue(importedRaw.sessionReady)
            assertTrue(ImageImporter.verifyQuickIdentity(importedRaw.rawFile, importedRaw.logicalBytes, importedRaw.quick))

            // 2. Android sparse
            val sparseFile = File(dir, "sparse.img").apply { writeBytes(sparseBytes) }
            val importedSparse = ImageImporter.importImage(sparseFile, File(dir, "stage-sparse"))
            assertEquals(expectedSha, importedSparse.sha256)
            assertEquals(rawExt4.size.toLong(), importedSparse.logicalBytes)
            assertEquals(listOf("sparse"), importedSparse.layers)

            // 3. GZIP wrapping sparse
            val gzFile = File(dir, "system.img.gz")
            GZIPOutputStream(FileOutputStream(gzFile)).use { it.write(sparseBytes) }
            val importedGz = ImageImporter.importImage(gzFile, File(dir, "stage-gz"))
            assertEquals(expectedSha, importedGz.sha256)
            assertEquals(listOf("gz", "sparse"), importedGz.layers)

            // 4. XZ wrapping raw ext4
            val xzFile = File(dir, "system.img.xz")
            XZOutputStream(FileOutputStream(xzFile), LZMA2Options(1)).use { it.write(rawExt4) }
            val importedXz = ImageImporter.importImage(xzFile, File(dir, "stage-xz"))
            assertEquals(expectedSha, importedXz.sha256)
            assertEquals(listOf("xz", "raw ext4"), importedXz.layers)

            // 5. ZIP64 wrapping sparse system.img alongside vendor.img
            val zipFile = File(dir, "rom.zip")
            ZipArchiveOutputStream(zipFile).use { zos ->
                zos.setUseZip64(Zip64Mode.Always)
                val vendorEntry = ZipArchiveEntry("vendor.img")
                zos.putArchiveEntry(vendorEntry)
                zos.write(ByteArray(4096))
                zos.closeArchiveEntry()

                val sysEntry = ZipArchiveEntry("images/system.img")
                zos.putArchiveEntry(sysEntry)
                zos.write(sparseBytes)
                zos.closeArchiveEntry()
            }
            val importedZip = ImageImporter.importImage(zipFile, File(dir, "stage-zip"))
            assertEquals(expectedSha, importedZip.sha256)
            assertEquals(listOf("zip", "sparse"), importedZip.layers)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalSymlinksBombsCorruptSparseTruncatedFsAndUnsupportedContainers() {
        val dir = Files.createTempDirectory("oneos-image-neg").toFile()
        try {
            val rawExt4 = buildSyntheticExt4Image()

            // 1. Path traversal in ZIP
            val traversalZip = File(dir, "traversal.zip")
            ZipArchiveOutputStream(traversalZip).use { zos ->
                zos.putArchiveEntry(ZipArchiveEntry("../evil/system.img"))
                zos.write(rawExt4)
                zos.closeArchiveEntry()
            }
            try {
                ImageImporter.importImage(traversalZip, File(dir, "s1"))
                fail("Expected PathTraversal")
            } catch (_: ImageRefusal.PathTraversal) {
            }

            // 2. Symlink entry in ZIP
            val symlinkZip = File(dir, "symlink.zip")
            ZipArchiveOutputStream(symlinkZip).use { zos ->
                val e = ZipArchiveEntry("system.img").apply {
                    unixMode = UnixStat.LINK_FLAG or 0b111_111_111
                }
                zos.putArchiveEntry(e)
                zos.write("/data/system/packages.xml".toByteArray())
                zos.closeArchiveEntry()
            }
            try {
                ImageImporter.importImage(symlinkZip, File(dir, "s2"))
                fail("Expected SymlinkEntry")
            } catch (_: ImageRefusal.SymlinkEntry) {
            }

            // 3. Quota exceeded (decompression bomb)
            val rawFile = File(dir, "raw.img").apply { writeBytes(rawExt4) }
            try {
                ImageImporter.importImage(rawFile, File(dir, "s3"), maxLogicalBytes = 16 * 1024L)
                fail("Expected QuotaExceeded")
            } catch (_: ImageRefusal.QuotaExceeded) {
            }
            // Ensure no leftover .part files remain
            assertTrue(File(dir, "s3").walkTopDown().none { it.name.endsWith(".part") })

            // 4. Truncated ext4 filesystem
            val truncated = rawExt4.copyOf(rawExt4.size - 4096)
            val truncFile = File(dir, "trunc.img").apply { writeBytes(truncated) }
            try {
                ImageImporter.importImage(truncFile, File(dir, "s4"))
                fail("Expected TruncatedFilesystem")
            } catch (_: ImageRefusal.TruncatedFilesystem) {
            }

            // 5. super.img, payload.bin, system.new.dat.br, boot.img refusals
            val superFile = File(dir, "super.img").apply {
                val b = ByteArray(8192)
                ByteBuffer.wrap(b, 4096, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(ContainerProbe.LP_METADATA_GEOMETRY_MAGIC)
                writeBytes(b)
            }
            try {
                ImageImporter.importImage(superFile, File(dir, "s5"))
                fail("Expected UnsupportedFormat for super.img")
            } catch (e: ImageRefusal.UnsupportedFormat) {
                assertEquals(ContainerKind.SUPER_IMG, e.kind)
            }

            val payloadFile = File(dir, "payload.bin").apply {
                writeBytes("CrAU\u0000\u0000\u0000\u0002".toByteArray(Charsets.US_ASCII))
            }
            try {
                ImageImporter.importImage(payloadFile, File(dir, "s6"))
                fail("Expected UnsupportedFormat for payload.bin")
            } catch (e: ImageRefusal.UnsupportedFormat) {
                assertEquals(ContainerKind.OTA_PAYLOAD, e.kind)
            }

            val brFile = File(dir, "system.new.dat.br").apply { writeBytes(ByteArray(32)) }
            try {
                ImageImporter.importImage(brFile, File(dir, "s7"))
                fail("Expected UnsupportedFormat for system.new.dat.br")
            } catch (e: ImageRefusal.UnsupportedFormat) {
                assertEquals(ContainerKind.BROTLI_DAT, e.kind)
            }

            // 6. EROFS image: imported for inspection, but sessionReady is false unless allowErofsSession=true
            val erofsFile = File(dir, "erofs.img").apply { writeBytes(buildSyntheticErofsImage()) }
            val erofsImported = ImageImporter.importImage(erofsFile, File(dir, "s8"))
            assertEquals(FsKind.EROFS, erofsImported.fs.kind)
            assertFalse(erofsImported.sessionReady)
            assertNotNull(erofsImported.sessionBlockReason)
        } finally {
            dir.deleteRecursively()
        }
    }
}
