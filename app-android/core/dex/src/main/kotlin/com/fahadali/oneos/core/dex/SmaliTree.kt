package com.fahadali.oneos.core.dex

import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.iface.ClassDef
import java.io.StringWriter

/**
 * In-memory Smali source tree for selectively disassembled classes.
 *
 * Uses Baksmali 3.0.10 configured identically to the server-side `baksmali d` invocation
 * (`localsDirective = true`, `sequentialLabels = true`, `debugInfo = true`,
 * `parameterRegisters = true`, `implicitReferences = false`).
 *
 * Provides copy-on-write transactions ([beginTx]) so that optional patches can roll back
 * cleanly if their structural preconditions do not match.
 */
class SmaliTree private constructor(
    private val classes: LinkedHashMap<String, SmaliEntry>,
) {
    data class SmaliEntry(
        val descriptor: String,
        val dexEntryName: String,
        val originalSource: String,
        val currentSource: String,
        val isNewClass: Boolean = false,
    ) {
        val isModified: Boolean get() = isNewClass || currentSource != originalSource
    }

    val descriptors: Set<String> get() = classes.keys

    fun getEntry(descriptor: String): SmaliEntry? = classes[descriptor]

    fun getSource(descriptor: String): String? = classes[descriptor]?.currentSource

    fun allEntries(): Collection<SmaliEntry> = classes.values

    fun modifiedEntries(): List<SmaliEntry> = classes.values.filter { it.isModified }

    fun modifiedByDexEntry(): Map<String, List<SmaliEntry>> {
        return modifiedEntries().groupBy { it.dexEntryName }
    }

    /**
     * Begins a copy-on-write transaction over the current SmaliTree.
     * Modifications made on [Tx] only take effect when [Tx.commit] is called.
     */
    fun beginTx(): Tx = Tx(this)

    class Tx internal constructor(private val parent: SmaliTree) {
        private val staged = LinkedHashMap<String, SmaliEntry>()
        private var committed = false

        fun descriptors(): Set<String> = parent.descriptors + staged.keys

        fun getSource(descriptor: String): String? {
            return staged[descriptor]?.currentSource ?: parent.getSource(descriptor)
        }

        fun getDexEntryName(descriptor: String): String? {
            return staged[descriptor]?.dexEntryName ?: parent.getEntry(descriptor)?.dexEntryName
        }

        fun putSource(descriptor: String, newSource: String) {
            check(!committed) { "Transaction already committed" }
            val existing = staged[descriptor] ?: parent.getEntry(descriptor)
                ?: throw IllegalArgumentException("Class $descriptor is not loaded in SmaliTree")
            staged[descriptor] = existing.copy(currentSource = newSource)
        }

        /**
         * Adds a brand-new helper class (such as `Lcom/android/server/OneOSGuards;`).
         * [preferredDexEntryName] defaults to `"classes.dex"`; [DexRewriter] will automatically
         * spill new classes to `classes<N+1>.dex` if the target DEX would overflow 65,536 refs.
         */
        fun addNewClass(
            descriptor: String,
            smaliSource: String,
            preferredDexEntryName: String = "classes.dex",
        ) {
            check(!committed) { "Transaction already committed" }
            staged[descriptor] = SmaliEntry(
                descriptor = descriptor,
                dexEntryName = preferredDexEntryName,
                originalSource = "",
                currentSource = smaliSource,
                isNewClass = true,
            )
        }

        fun modifiedCount(): Int {
            return staged.values.count { entry ->
                val orig = parent.getSource(entry.descriptor)
                entry.isNewClass || orig != entry.currentSource
            }
        }

        fun commit() {
            check(!committed) { "Transaction already committed" }
            for ((desc, entry) in staged) {
                parent.classes[desc] = entry
            }
            committed = true
        }
    }

    companion object {
        fun defaultBaksmaliOptions(): BaksmaliOptions {
            return BaksmaliOptions().apply {
                localsDirective = true
                sequentialLabels = true
                debugInfo = true
                parameterRegisters = true
                implicitReferences = false
                accessorComments = false
            }
        }

        fun disassembleClass(
            classDef: ClassDef,
            options: BaksmaliOptions = defaultBaksmaliOptions(),
        ): String {
            val stringWriter = StringWriter(8192)
            BaksmaliWriter(stringWriter, null).use { writer ->
                val classDefinition = ClassDefinition(options, classDef)
                classDefinition.writeTo(writer)
            }
            return stringWriter.toString()
        }

        fun fromMatchedClasses(
            matched: Map<String, MatchedClass>,
            options: BaksmaliOptions = defaultBaksmaliOptions(),
        ): SmaliTree {
            val map = LinkedHashMap<String, SmaliEntry>(matched.size)
            for ((desc, match) in matched) {
                val text = disassembleClass(match.classDef, options)
                map[desc] = SmaliEntry(
                    descriptor = desc,
                    dexEntryName = match.dexEntryName,
                    originalSource = text,
                    currentSource = text,
                    isNewClass = false,
                )
            }
            return SmaliTree(map)
        }
    }
}
