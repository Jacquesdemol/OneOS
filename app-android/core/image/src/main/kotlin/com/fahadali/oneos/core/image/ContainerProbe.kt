package com.fahadali.oneos.core.image

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

enum class ContainerKind(val label: String) {
    RAW_EXT4("raw ext4"),
    RAW_EROFS("raw erofs"),
    ANDROID_SPARSE("sparse"),
    GZIP("gz"),
    XZ("xz"),
    ZIP("zip"),
    SUPER_IMG("super.img"),
    OTA_PAYLOAD("payload.bin"),
    BROTLI_DAT("system.new.dat.br"),
    BOOT_IMG("boot.img"),
    UNKNOWN("unknown"),
}

sealed class ImageRefusal(override val message: String) : Exception(message) {
    class UnsupportedFormat(val kind: ContainerKind, detail: String) : ImageRefusal(detail)
    class CorruptContainer(detail: String) : ImageRefusal(detail)
    class PathTraversal(val entryName: String) : ImageRefusal("Refused unsafe archive path: $entryName")
    class SymlinkEntry(val entryName: String) : ImageRefusal("Refused symbolic link in archive: $entryName")
    class QuotaExceeded(detail: String) : ImageRefusal(detail)
    class AmbiguousArchiveEntries(val candidates: List<String>) :
        ImageRefusal("Archive contains multiple candidate system images: ${candidates.joinToString()}")
    class TruncatedFilesystem(detail: String) : ImageRefusal(detail)
    class ErofsNotSupportedForSession(detail: String) : ImageRefusal(detail)
}

/**
 * Probes container/filesystem magic bytes and entry names according to
 * docs/app/patch-engine.md section 2.1.
 */
object ContainerProbe {
    const val EXT4_SUPER_OFFSET = 1024
    const val EXT4_MAGIC_OFFSET = 1080
    const val EXT4_MAGIC: Int = 0xEF53

    const val EROFS_SUPER_OFFSET = 1024
    const val EROFS_MAGIC: Int = 0xE0F5E1E2.toInt()

    const val SPARSE_MAGIC: Int = 0xED26FF3A.toInt()
    const val LP_METADATA_GEOMETRY_MAGIC: Int = 0x616C4467

    private val FORBIDDEN_PARTITION_NAMES = setOf(
        "vendor.img",
        "boot.img",
        "init_boot.img",
        "vendor_boot.img",
        "dtbo.img",
        "vbmeta.img",
        "vbmeta_system.img",
        "vbmeta_vendor.img",
        "product.img",
        "odm.img",
        "userdata.img",
        "modem.img",
        "optics.img",
        "prism.img",
    )

    fun probeHeader(header: ByteArray, fileName: String = ""): ContainerKind {
        val lowerName = fileName.substringAfterLast('/').lowercase(Locale.ROOT)
        if (lowerName.endsWith(".new.dat.br") || lowerName.endsWith(".new.dat") || lowerName == "system.transfer.list") {
            return ContainerKind.BROTLI_DAT
        }
        if (header.size >= 8 && header.copyOfRange(0, 8).contentEquals("ANDROID!".toByteArray(Charsets.US_ASCII))) {
            return ContainerKind.BOOT_IMG
        }
        if (header.size >= 4 && header.copyOfRange(0, 4).contentEquals("CrAU".toByteArray(Charsets.US_ASCII))) {
            return ContainerKind.OTA_PAYLOAD
        }
        if (header.size >= 4) {
            val u32At0 = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (u32At0 == LP_METADATA_GEOMETRY_MAGIC) {
                return ContainerKind.SUPER_IMG
            }
            if (header.size >= 4100) {
                val u32At4096 = ByteBuffer.wrap(header, 4096, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (u32At4096 == LP_METADATA_GEOMETRY_MAGIC) {
                    return ContainerKind.SUPER_IMG
                }
            }
            if (u32At0 == SPARSE_MAGIC) {
                return ContainerKind.ANDROID_SPARSE
            }
            if (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
            ) {
                return ContainerKind.ZIP
            }
        }
        if (header.size >= 6 &&
            header[0] == 0xFD.toByte() &&
            header[1] == 0x37.toByte() &&
            header[2] == 0x7A.toByte() &&
            header[3] == 0x58.toByte() &&
            header[4] == 0x5A.toByte() &&
            header[5] == 0x00.toByte()
        ) {
            return ContainerKind.XZ
        }
        if (header.size >= 2 && header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()) {
            return ContainerKind.GZIP
        }
        if (header.size >= EXT4_MAGIC_OFFSET + 2) {
            val ext4Magic = ByteBuffer.wrap(header, EXT4_MAGIC_OFFSET, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            if (ext4Magic == EXT4_MAGIC) {
                return ContainerKind.RAW_EXT4
            }
        }
        if (header.size >= EROFS_SUPER_OFFSET + 4) {
            val erofsMagic = ByteBuffer.wrap(header, EROFS_SUPER_OFFSET, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (erofsMagic == EROFS_MAGIC) {
                return ContainerKind.RAW_EROFS
            }
        }
        if (lowerName == "super.img" || lowerName.startsWith("super.img.")) {
            return ContainerKind.SUPER_IMG
        }
        if (lowerName == "payload.bin") {
            return ContainerKind.OTA_PAYLOAD
        }
        return ContainerKind.UNKNOWN
    }

    fun probeFile(file: File): ContainerKind {
        if (!file.isFile) return ContainerKind.UNKNOWN
        val buf = ByteArray(minOf(file.length(), 8192L).toInt())
        file.inputStream().use { input ->
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
        }
        return probeHeader(buf, file.name)
    }

    /**
     * Validates an archive entry name for path traversal, absolute paths,
     * backslashes, and control characters.
     */
    fun validateSafeEntryPath(entryName: String): String {
        if (entryName.isEmpty() || entryName.length > 512) {
            throw ImageRefusal.PathTraversal(entryName)
        }
        if (entryName.startsWith("/") || entryName.contains('\\') || entryName.any { it.code < 0x20 || it.code == 0x7F }) {
            throw ImageRefusal.PathTraversal(entryName)
        }
        val segments = entryName.split('/')
        if (segments.any { it == ".." || it == "." }) {
            throw ImageRefusal.PathTraversal(entryName)
        }
        return entryName
    }

    fun isForbiddenPartitionEntry(entryName: String): Boolean {
        val base = entryName.substringAfterLast('/').lowercase(Locale.ROOT)
        return base in FORBIDDEN_PARTITION_NAMES
    }

    fun readUpTo(input: InputStream, maxBytes: Int): ByteArray {
        val buf = ByteArray(maxBytes)
        var off = 0
        while (off < maxBytes) {
            val n = input.read(buf, off, maxBytes - off)
            if (n < 0) break
            off += n
        }
        return if (off == maxBytes) buf else buf.copyOf(off)
    }
}
