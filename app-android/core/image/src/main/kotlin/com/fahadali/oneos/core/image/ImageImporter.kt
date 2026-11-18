package com.fahadali.oneos.core.image

import com.fahadali.oneos.core.model.Json
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.tukaani.xz.XZInputStream

/**
 * Universal GSI importer (docs/app/patch-engine.md section 2).
 *
 * Unwraps up to 3 container layers (`.zip` with ZIP64, `.xz`, `.gz`, Android sparse `0xED26FF3A`,
 * raw ext4/EROFS), enforces entry count and byte quotas, verifies ZIP entry CRC32 and sparse chunks,
 * writes via [HoleWriter] to a `.part` file with single-pass SHA-256 over logical bytes, probes the
 * resulting filesystem superblock via [FsProbe], and atomically promotes to `images/<sha16>/system.raw`.
 */
object ImageImporter {
    const val SCHEMA = "oneos.image/1"
    const val MAX_LAYERS = 3
    const val DEFAULT_MAX_ARCHIVE_ENTRIES = 64
    const val QUICK_EDGE_BYTES = 1024 * 1024 // 1 MiB head/tail quick identity

    data class QuickIdentity(
        val head1MiBSha256: String,
        val tail1MiBSha256: String,
        val mtime: Long,
    )

    data class ImportedImage(
        val sha256: String,
        val rawFile: File,
        val recordFile: File,
        val logicalBytes: Long,
        val allocatedBytes: Long,
        val holeBlocks: Long,
        val fs: FsInfo,
        val layers: List<String>,
        val sourceName: String,
        val quick: QuickIdentity,
        val sessionReady: Boolean,
        val sessionBlockReason: String? = null,
    ) {
        fun toRecordJson(): String = Json.write(
            linkedMapOf<String, Any?>(
                "schema" to SCHEMA,
                "sha256" to sha256,
                "size" to logicalBytes,
                "allocated" to allocatedBytes,
                "fs" to fs.kind.id,
                "fsUuid" to fs.uuid,
                "quick" to linkedMapOf(
                    "head1MiB" to quick.head1MiBSha256,
                    "tail1MiB" to quick.tail1MiBSha256,
                    "mtime" to quick.mtime,
                ),
                "source" to linkedMapOf(
                    "name" to sourceName,
                    "layers" to layers,
                ),
                "sessionReady" to sessionReady,
                "sessionBlockReason" to sessionBlockReason,
            ),
        )
    }

    /**
     * Imports [sourceFile] into `<stageImagesDir>/<sha16>/system.raw` and writes `image.json`.
     *
     * @param allowErofsSession false by default per patch-engine.md section 2.1 (guest_prepare.sh mounts ext4;
     * EROFS images are imported and inspected, or refused for session activation unless kernel EROFS support is verified).
     */
    fun importImage(
        sourceFile: File,
        stageImagesDir: File,
        maxLogicalBytes: Long = HoleWriter.DEFAULT_MAX_LOGICAL_BYTES,
        maxArchiveEntries: Int = DEFAULT_MAX_ARCHIVE_ENTRIES,
        allowErofsImport: Boolean = true,
        allowErofsSession: Boolean = false,
    ): ImportedImage {
        if (!sourceFile.isFile) {
            throw ImageRefusal.CorruptContainer("Source image file does not exist: ${sourceFile.path}")
        }
        stageImagesDir.mkdirs()
        val tmpDir = File(stageImagesDir, ".tmp-${System.nanoTime()}").apply { mkdirs() }
        val partFile = File(tmpDir, "system.raw.part")
        val layers = mutableListOf<String>()

        try {
            val writeResult = unwrapFromFile(
                file = sourceFile,
                partFile = partFile,
                layers = layers,
                maxLogicalBytes = maxLogicalBytes,
                maxArchiveEntries = maxArchiveEntries,
            )
            val fs = FsProbe.probe(partFile)
            if (fs.kind == FsKind.EROFS && !allowErofsImport) {
                throw ImageRefusal.ErofsNotSupportedForSession(
                    "EROFS guest images are not supported for session mounting on this host (guest_prepare.sh requires ext4)",
                )
            }
            val sessionReady = fs.kind == FsKind.EXT4 || allowErofsSession
            val sessionBlockReason = if (fs.kind == FsKind.EROFS && !allowErofsSession) {
                "EROFS filesystem detected (${fs.uuid}): imported for inspection, but session launch is blocked until EROFS mount support is verified (G13)"
            } else {
                null
            }

            val sha16 = writeResult.sha256.substring(0, 16)
            val targetDir = File(stageImagesDir, sha16).apply { mkdirs() }
            val targetRaw = File(targetDir, "system.raw")
            val targetRecord = File(targetDir, "image.json")

            if (targetRaw.isFile && targetRaw.length() == writeResult.logicalBytes) {
                partFile.delete()
            } else {
                if (!partFile.renameTo(targetRaw)) {
                    partFile.copyTo(targetRaw, overwrite = true)
                    partFile.delete()
                }
            }

            val quick = computeQuickIdentity(targetRaw)
            val imported = ImportedImage(
                sha256 = writeResult.sha256,
                rawFile = targetRaw,
                recordFile = targetRecord,
                logicalBytes = writeResult.logicalBytes,
                allocatedBytes = writeResult.allocatedBytes,
                holeBlocks = writeResult.holeBlocks,
                fs = fs,
                layers = layers.toList(),
                sourceName = sourceFile.name,
                quick = quick,
                sessionReady = sessionReady,
                sessionBlockReason = sessionBlockReason,
            )
            targetRecord.writeText(imported.toRecordJson())
            return imported
        } finally {
            partFile.delete()
            tmpDir.deleteRecursively()
        }
    }

    /**
     * Verifies the quick identity (1 MiB head, 1 MiB tail, logical size, and absence of `.part` files)
     * in milliseconds before session launch (R8 in patch-engine.md section 10).
     */
    fun verifyQuickIdentity(rawFile: File, expectedSize: Long, expectedQuick: QuickIdentity): Boolean {
        if (!rawFile.isFile || rawFile.length() != expectedSize) return false
        val parent = rawFile.parentFile
        if (parent != null && parent.listFiles()?.any { it.name.endsWith(".part") } == true) {
            return false
        }
        val actual = computeQuickIdentity(rawFile)
        return actual.head1MiBSha256 == expectedQuick.head1MiBSha256 &&
            actual.tail1MiBSha256 == expectedQuick.tail1MiBSha256
    }

    fun computeQuickIdentity(rawFile: File): QuickIdentity {
        val len = rawFile.length()
        val edge = minOf(len, QUICK_EDGE_BYTES.toLong()).toInt()
        val headBuf = ByteArray(edge)
        val tailBuf = ByteArray(edge)
        RandomAccessFile(rawFile, "r").use { raf ->
            raf.seek(0L)
            raf.readFully(headBuf)
            raf.seek(maxOf(0L, len - edge))
            raf.readFully(tailBuf)
        }
        return QuickIdentity(
            head1MiBSha256 = sha256Hex(headBuf),
            tail1MiBSha256 = sha256Hex(tailBuf),
            mtime = rawFile.lastModified() / 1000L,
        )
    }

    private fun unwrapFromFile(
        file: File,
        partFile: File,
        layers: MutableList<String>,
        maxLogicalBytes: Long,
        maxArchiveEntries: Int,
    ): HoleWriter.Result {
        val kind = ContainerProbe.probeFile(file)
        return when (kind) {
            ContainerKind.SUPER_IMG -> throw ImageRefusal.UnsupportedFormat(
                kind,
                "super.img dynamic partition container is not supported directly; extract system.img first",
            )
            ContainerKind.OTA_PAYLOAD -> throw ImageRefusal.UnsupportedFormat(
                kind,
                "OTA payload.bin is not supported; extract system.img first",
            )
            ContainerKind.BROTLI_DAT -> throw ImageRefusal.UnsupportedFormat(
                kind,
                "Brotli/sdat transfer list images (system.new.dat.br) are not supported",
            )
            ContainerKind.BOOT_IMG -> throw ImageRefusal.UnsupportedFormat(
                kind,
                "Boot or vendor image is not a valid Android system image",
            )
            ContainerKind.ZIP -> {
                layers += ContainerKind.ZIP.label
                extractFromZip(file, partFile, layers, maxLogicalBytes, maxArchiveEntries)
            }
            else -> {
                file.inputStream().buffered(64 * 1024).use { stream ->
                    unwrapStream(stream, file.name, partFile, layers, maxLogicalBytes, depth = 0)
                }
            }
        }
    }

    private fun extractFromZip(
        zipSource: File,
        partFile: File,
        layers: MutableList<String>,
        maxLogicalBytes: Long,
        maxArchiveEntries: Int,
    ): HoleWriter.Result {
        ZipFile.builder().setFile(zipSource).get().use { zip ->
            val allEntries = zip.entries.toList()
            if (allEntries.size > maxArchiveEntries) {
                throw ImageRefusal.QuotaExceeded(
                    "ZIP archive contains ${allEntries.size} entries, exceeding maximum limit of $maxArchiveEntries",
                )
            }
            for (entry in allEntries) {
                ContainerProbe.validateSafeEntryPath(entry.name)
                if (entry.isUnixSymlink) {
                    throw ImageRefusal.SymlinkEntry(entry.name)
                }
                val lower = entry.name.substringAfterLast('/').lowercase()
                if (lower.endsWith(".new.dat.br") || lower.endsWith(".new.dat") || lower == "system.transfer.list") {
                    throw ImageRefusal.UnsupportedFormat(
                        ContainerKind.BROTLI_DAT,
                        "ZIP archive contains Brotli/sdat transfer list entry (${entry.name}); not supported",
                    )
                }
                if (lower == "payload.bin") {
                    throw ImageRefusal.UnsupportedFormat(
                        ContainerKind.OTA_PAYLOAD,
                        "ZIP archive contains OTA payload.bin; extract system.img first",
                    )
                }
                if (lower == "super.img" || lower.startsWith("super.img.")) {
                    throw ImageRefusal.UnsupportedFormat(
                        ContainerKind.SUPER_IMG,
                        "ZIP archive contains super.img; extract system.img first",
                    )
                }
            }

            val fileEntries = allEntries.filter { !it.isDirectory }
            val exactSystemImg = fileEntries.filter {
                it.name.substringAfterLast('/').equals("system.img", ignoreCase = true)
            }

            val chosenEntry: ZipArchiveEntry = when {
                exactSystemImg.size == 1 -> exactSystemImg.single()
                exactSystemImg.size > 1 -> throw ImageRefusal.AmbiguousArchiveEntries(exactSystemImg.map { it.name })
                else -> {
                    val imgCandidates = fileEntries.filter { entry ->
                        val base = entry.name.substringAfterLast('/').lowercase()
                        (base.endsWith(".img") || base.endsWith(".raw") || base.endsWith(".gz") || base.endsWith(".xz")) &&
                            !ContainerProbe.isForbiddenPartitionEntry(entry.name)
                    }.filter { entry ->
                        val header = zip.getInputStream(entry).use { ContainerProbe.readUpTo(it, 4100) }
                        val k = ContainerProbe.probeHeader(header, entry.name)
                        k in setOf(
                            ContainerKind.RAW_EXT4,
                            ContainerKind.RAW_EROFS,
                            ContainerKind.ANDROID_SPARSE,
                            ContainerKind.GZIP,
                            ContainerKind.XZ,
                        )
                    }
                    when {
                        imgCandidates.isEmpty() -> throw ImageRefusal.UnsupportedFormat(
                            ContainerKind.UNKNOWN,
                            "ZIP archive contains no valid system image entry",
                        )
                        imgCandidates.size > 1 -> throw ImageRefusal.AmbiguousArchiveEntries(imgCandidates.map { it.name })
                        else -> imgCandidates.single()
                    }
                }
            }

            val uncompressedSize = chosenEntry.size
            if (uncompressedSize > maxLogicalBytes) {
                throw ImageRefusal.QuotaExceeded(
                    "ZIP entry ${chosenEntry.name} size ($uncompressedSize B) exceeds maximum quota ($maxLogicalBytes B)",
                )
            }

            val expectedCrc = chosenEntry.crc
            val crc32 = CRC32()
            zip.getInputStream(chosenEntry).use { rawEntryStream ->
                val crcStream = object : InputStream() {
                    override fun read(): Int {
                        val b = rawEntryStream.read()
                        if (b >= 0) crc32.update(b)
                        return b
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = rawEntryStream.read(b, off, len)
                        if (n > 0) crc32.update(b, off, n)
                        return n
                    }
                }
                val res = unwrapStream(
                    stream = BufferedInputStream(crcStream, 64 * 1024),
                    fileName = chosenEntry.name,
                    partFile = partFile,
                    layers = layers,
                    maxLogicalBytes = maxLogicalBytes,
                    depth = 1,
                )
                // Consume any trailing stream bytes so CRC covers the complete entry.
                val tailScratch = ByteArray(4096)
                while (crcStream.read(tailScratch) >= 0) {
                    // drain
                }
                if (expectedCrc >= 0L && crc32.value != expectedCrc) {
                    throw ImageRefusal.CorruptContainer(
                        "ZIP entry CRC32 mismatch for ${chosenEntry.name}: expected 0x${expectedCrc.toString(16)}, got 0x${crc32.value.toString(16)}",
                    )
                }
                return res
            }
        }
    }

    private fun unwrapStream(
        stream: InputStream,
        fileName: String,
        partFile: File,
        layers: MutableList<String>,
        maxLogicalBytes: Long,
        depth: Int,
    ): HoleWriter.Result {
        if (depth > MAX_LAYERS) {
            throw ImageRefusal.CorruptContainer("Container nesting exceeds maximum depth of $MAX_LAYERS: $layers")
        }
        val pb = PushbackInputStream(stream, 8192)
        val header = ContainerProbe.readUpTo(pb, 8192)
        if (header.isNotEmpty()) {
            pb.unread(header, 0, header.size)
        }
        val kind = ContainerProbe.probeHeader(header, fileName)
        return when (kind) {
            ContainerKind.SUPER_IMG -> throw ImageRefusal.UnsupportedFormat(
                kind,
                "super.img dynamic partition container is not supported directly; extract system.img first",
            )
            ContainerKind.OTA_PAYLOAD -> throw ImageRefusal.UnsupportedFormat(
                kind,
                "OTA payload.bin is not supported; extract system.img first",
            )
            ContainerKind.BROTLI_DAT -> throw ImageRefusal.UnsupportedFormat(
                kind,
                "Brotli/sdat transfer list images (system.new.dat.br) are not supported",
            )
            ContainerKind.BOOT_IMG -> throw ImageRefusal.UnsupportedFormat(
                kind,
                "Boot or vendor image is not a valid Android system image",
            )
            ContainerKind.GZIP -> {
                layers += ContainerKind.GZIP.label
                GZIPInputStream(pb, 64 * 1024).use { gz ->
                    unwrapStream(gz, fileName.removeSuffix(".gz"), partFile, layers, maxLogicalBytes, depth + 1)
                }
            }
            ContainerKind.XZ -> {
                layers += ContainerKind.XZ.label
                XZInputStream(pb).use { xz ->
                    unwrapStream(xz, fileName.removeSuffix(".xz"), partFile, layers, maxLogicalBytes, depth + 1)
                }
            }
            ContainerKind.ANDROID_SPARSE -> {
                layers += ContainerKind.ANDROID_SPARSE.label
                HoleWriter(partFile, maxLogicalBytes).use { writer ->
                    val hdr = SparseReader.decode(pb, writer, maxLogicalBytes, verifyCrc = true)
                    writer.finishResult(hdr.logicalBytes)
                }
            }
            ContainerKind.RAW_EXT4, ContainerKind.RAW_EROFS -> {
                layers += kind.label
                HoleWriter(partFile, maxLogicalBytes).use { writer ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = pb.read(buf)
                        if (n < 0) break
                        writer.writeBytes(buf, 0, n)
                    }
                    writer.finishResult()
                }
            }
            ContainerKind.ZIP -> throw ImageRefusal.CorruptContainer("Nested ZIP inside stream is not supported")
            ContainerKind.UNKNOWN -> throw ImageRefusal.UnsupportedFormat(
                ContainerKind.UNKNOWN,
                "Unrecognized image format in $fileName",
            )
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
