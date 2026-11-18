package com.fahadali.oneos.core.dex

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.Closeable
import java.io.File

/**
 * Lazy reader for multi-DEX framework JARs (services.jar, framework.jar, oplus-services.jar).
 *
 * Parses only the DexBackedDexFile header and index tables on open; bytecode and debug info
 * are parsed lazily per-class when requested by [SelectorScan] or [SmaliTree].
 */
class DexJar(val file: File, val apiLevel: Int = 35) : Closeable {
    private val zipFile: ZipFile = ZipFile.builder().setFile(file).get()

    val dexEntries: List<String>

    private val loadedDexFiles = LinkedHashMap<String, DexBackedDexFile>()
    private val rawDexBytes = LinkedHashMap<String, ByteArray>()

    init {
        val entries = mutableListOf<String>()
        val enumeration = zipFile.entries
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            if (!entry.isDirectory && isDexEntryName(entry.name)) {
                entries.add(entry.name)
            }
        }
        entries.sortWith(Comparator { a, b ->
            dexIndex(a).compareTo(dexIndex(b))
        })
        require(entries.isNotEmpty()) { "No classes*.dex entries found in ${file.name}" }
        dexEntries = entries
    }

    fun getDexBytes(entryName: String): ByteArray {
        return rawDexBytes.getOrPut(entryName) {
            val entry = zipFile.getEntry(entryName)
                ?: throw IllegalArgumentException("Missing DEX entry $entryName in ${file.name}")
            zipFile.getInputStream(entry).use { it.readBytes() }
        }
    }

    fun getDexFile(entryName: String): DexBackedDexFile {
        return loadedDexFiles.getOrPut(entryName) {
            val bytes = getDexBytes(entryName)
            val opcodes = resolveOpcodes(bytes, apiLevel)
            DexBackedDexFile(opcodes, bytes)
        }
    }

    fun allDexFiles(): Map<String, DexBackedDexFile> {
        for (name in dexEntries) {
            getDexFile(name)
        }
        return loadedDexFiles
    }

    fun nextAvailableDexEntryName(existingNames: Set<String> = dexEntries.toSet()): String {
        var maxIdx = 1
        for (name in existingNames) {
            val idx = dexIndex(name)
            if (idx > maxIdx) maxIdx = idx
        }
        return "classes${maxIdx + 1}.dex"
    }

    override fun close() {
        zipFile.close()
    }

    companion object {
        private val DEX_NAME_REGEX = Regex("""^classes(\d*)\.dex$""")

        fun isDexEntryName(name: String): Boolean = DEX_NAME_REGEX.matches(name)

        fun dexIndex(name: String): Int {
            val match = DEX_NAME_REGEX.matchEntire(name) ?: return Int.MAX_VALUE
            val num = match.groupValues[1]
            return if (num.isEmpty()) 1 else num.toIntOrNull() ?: Int.MAX_VALUE
        }

        fun resolveOpcodes(dexBytes: ByteArray, fallbackApi: Int = 35): Opcodes {
            if (dexBytes.size >= 8 &&
                dexBytes[0] == 'd'.code.toByte() &&
                dexBytes[1] == 'e'.code.toByte() &&
                dexBytes[2] == 'x'.code.toByte() &&
                dexBytes[3] == '\n'.code.toByte()
            ) {
                val versionStr = String(dexBytes, 4, 3, Charsets.US_ASCII)
                val version = versionStr.toIntOrNull()
                if (version != null && version in 35..41) {
                    return try {
                        Opcodes.forDexVersion(version)
                    } catch (_: Exception) {
                        Opcodes.forApi(fallbackApi)
                    }
                }
            }
            return Opcodes.forApi(fallbackApi)
        }
    }
}
