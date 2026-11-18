package com.fahadali.oneos.core.dex

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry

/**
 * Repacks a framework JAR (`services.jar`, `framework.jar`, `oplus-services.jar`) deterministically:
 * - Unmodified entries are copied raw from the original JAR.
 * - Modified and newly spilled `classesN.dex` entries are written `STORED` (uncompressed)
 *   with 4-byte alignment so ART `dex2oat` and `vdex` mmap directly from the archive.
 * - Fixed timestamp (`DOS_EPOCH_MS`) ensures reproducible SHA-256 outputs.
 */
object JarWriter {
    private const val DOS_EPOCH_MS = 315532800000L // 1980-01-01T00:00:00Z
    private const val DEX_ALIGNMENT = 4

    fun writeUpdatedJar(
        sourceJar: File,
        modifiedDexBytes: Map<String, ByteArray>,
        outputJar: File,
    ) {
        val tempFile = File(outputJar.parentFile, "${outputJar.name}.part")
        try {
            ZipFile.builder().setFile(sourceJar).get().use { srcZip ->
                ZipArchiveOutputStream(tempFile).use { zos ->
                    val writtenNames = HashSet<String>()
                    val entries = srcZip.entries
                    while (entries.hasMoreElements()) {
                        val srcEntry = entries.nextElement()
                        val name = srcEntry.name
                        val replacement = modifiedDexBytes[name]
                        if (replacement != null) {
                            writeStoredDexEntry(zos, name, replacement)
                            writtenNames.add(name)
                        } else if (DexJar.isDexEntryName(name)) {
                            val rawBytes = srcZip.getInputStream(srcEntry).use { it.readBytes() }
                            writeStoredDexEntry(zos, name, rawBytes)
                            writtenNames.add(name)
                        } else {
                            val copyEntry = ZipArchiveEntry(srcEntry)
                            copyEntry.time = DOS_EPOCH_MS
                            srcZip.getRawInputStream(srcEntry).use { rawStream ->
                                zos.addRawArchiveEntry(copyEntry, rawStream)
                            }
                            writtenNames.add(name)
                        }
                    }

                    // Write any newly spilled classes<N+1>.dex entries in numeric order
                    val newDexNames = modifiedDexBytes.keys
                        .filter { it !in writtenNames }
                        .sortedBy { DexJar.dexIndex(it) }
                    for (newName in newDexNames) {
                        writeStoredDexEntry(zos, newName, modifiedDexBytes.getValue(newName))
                    }
                    zos.finish()
                }
            }
            if (outputJar.exists()) {
                outputJar.delete()
            }
            if (!tempFile.renameTo(outputJar)) {
                tempFile.copyTo(outputJar, overwrite = true)
                tempFile.delete()
            }
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }
    }

    private class AlignedZipArchiveEntry(name: String, align: Int) : ZipArchiveEntry(name) {
        init {
            setAlignment(align)
        }
    }

    private fun writeStoredDexEntry(
        zos: ZipArchiveOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        val crc = CRC32().apply { update(bytes) }.value
        val entry = AlignedZipArchiveEntry(name, DEX_ALIGNMENT).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc
            time = DOS_EPOCH_MS
        }
        zos.putArchiveEntry(entry)
        zos.write(bytes)
        zos.closeArchiveEntry()
    }
}
