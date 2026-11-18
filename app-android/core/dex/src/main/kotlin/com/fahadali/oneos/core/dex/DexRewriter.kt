package com.fahadali.oneos.core.dex

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.io.File
import java.nio.file.Files

/**
 * Reassembles modified Smali sources using `com.android.tools.smali.smali.Smali`
 * and rewrites only the affected `classesN.dex` entries via [DexPool].
 *
 * Handles 64K method/type/field index overflow by spilling newly added helper classes
 * (`OneOS*`) into a new `classes<N+1>.dex` when the target DEX is near capacity.
 */
object Reassembler {

    /**
     * Assembles a collection of Smali source strings into a [DexBackedDexFile].
     * Uses `apiLevel = 28` (DEX version `039`) by default to match `smali a -a 28` in `make-overlays.sh`.
     */
    fun assembleSmaliSources(
        smaliSources: Collection<String>,
        apiLevel: Int = 28,
    ): DexBackedDexFile {
        require(smaliSources.isNotEmpty()) { "Cannot assemble empty Smali source list" }
        val tempDir = Files.createTempDirectory("oneos-smali-").toFile()
        val tempDex = File(tempDir, "assembled.dex")
        try {
            val smaliFiles = smaliSources.mapIndexed { idx, src ->
                File(tempDir, "Class_$idx.smali").apply {
                    writeText(src, Charsets.UTF_8)
                }
            }
            val options = SmaliOptions().apply {
                this.apiLevel = apiLevel
                outputDexFile = tempDex.absolutePath
                jobs = 1
            }
            val ok = Smali.assemble(options, smaliFiles.map { it.absolutePath })
            if (!ok || !tempDex.exists()) {
                throw IllegalStateException("Smali assembly failed for ${smaliSources.size} classes")
            }
            val bytes = tempDex.readBytes()
            return DexBackedDexFile(Opcodes.forApi(apiLevel), bytes)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

object DexRewriter {
    private const val MAX_DEX_REFS = 65536
    private const val SAFE_SPILL_THRESHOLD = 64500

    /**
     * Rewrites all modified DEX entries in [jar] using the modified and newly added classes
     * in [tree], returning a map of `entryName -> newDexBytes`.
     *
     * Unmodified `classesN.dex` entries are not included in the returned map so [JarWriter]
     * can copy their raw bytes directly without re-encoding.
     */
    fun rewriteModifiedDexes(
        jar: DexJar,
        tree: SmaliTree,
        apiLevel: Int = 28,
    ): Map<String, ByteArray> {
        val modifiedByDex = tree.modifiedByDexEntry()
        if (modifiedByDex.isEmpty()) return emptyMap()

        val result = LinkedHashMap<String, ByteArray>()
        val spillClasses = mutableListOf<ClassDef>()

        for ((dexEntryName, entries) in modifiedByDex) {
            val assembledDex = Reassembler.assembleSmaliSources(
                entries.map { it.currentSource },
                apiLevel = apiLevel,
            )
            val assembledByType = assembledDex.classes.associateBy { it.type }

            val originalDex = if (jar.dexEntries.contains(dexEntryName)) {
                jar.getDexFile(dexEntryName)
            } else {
                null
            }

            if (originalDex == null) {
                // Brand new DEX entry
                result[dexEntryName] = writeDexPool(assembledDex.classes, apiLevel)
                continue
            }

            // Separate in-place replacements from brand-new helper classes
            val newHelperEntries = entries.filter { it.isNewClass }
            val originalMethodCount = originalDex.methodSection.size
            val originalTypeCount = originalDex.typeSection.size

            val shouldSpillNewClasses = newHelperEntries.isNotEmpty() &&
                (originalMethodCount >= SAFE_SPILL_THRESHOLD || originalTypeCount >= SAFE_SPILL_THRESHOLD)

            val classesForThisDex = ArrayList<ClassDef>(originalDex.classes.size + newHelperEntries.size)
            for (origClass in originalDex.classes) {
                val replacement = assembledByType[origClass.type]
                classesForThisDex.add(replacement ?: origClass)
            }

            for (newEntry in newHelperEntries) {
                val newClassDef = assembledByType[newEntry.descriptor]
                    ?: throw IllegalStateException("Assembled DEX missing new class ${newEntry.descriptor}")
                if (shouldSpillNewClasses) {
                    spillClasses.add(newClassDef)
                } else {
                    classesForThisDex.add(newClassDef)
                }
            }

            try {
                result[dexEntryName] = writeDexPool(classesForThisDex, apiLevel)
            } catch (e: Exception) {
                if (newHelperEntries.isNotEmpty() && !shouldSpillNewClasses) {
                    // Retry by spilling new helper classes into a new classes<N+1>.dex
                    val withoutNew = classesForThisDex.filterNot { cls ->
                        newHelperEntries.any { it.descriptor == cls.type }
                    }
                    result[dexEntryName] = writeDexPool(withoutNew, apiLevel)
                    for (newEntry in newHelperEntries) {
                        spillClasses.add(assembledByType.getValue(newEntry.descriptor))
                    }
                } else {
                    throw e
                }
            }
        }

        if (spillClasses.isNotEmpty()) {
            val existingNames = jar.dexEntries.toSet() + result.keys
            val spillEntryName = jar.nextAvailableDexEntryName(existingNames)
            result[spillEntryName] = writeDexPool(spillClasses, apiLevel)
        }

        return result
    }

    fun writeDexPool(classes: Collection<ClassDef>, apiLevel: Int = 28): ByteArray {
        val pool = DexPool(Opcodes.forApi(apiLevel))
        for (classDef in classes) {
            pool.internClass(classDef)
        }
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.data
    }
}
