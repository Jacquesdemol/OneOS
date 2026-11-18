package com.fahadali.oneos.core.dex

import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Declarative class selector that scans DexBackedDexFile index tables and bytecode
 * without disassembling unmodified classes to text.
 */
sealed class ClassSelector {
    /** Matches class descriptors ending with [suffix], e.g. "/PackageManagerService;" or "DisplayModeDirector$DesiredDisplayModeSpecs;". */
    data class ClassSuffix(val suffix: String) : ClassSelector() {
        val normalizedSuffix: String = when {
            suffix.endsWith(";") -> suffix
            else -> "$suffix;"
        }
    }

    /** Matches an exact class descriptor, e.g. "Lcom/android/server/Watchdog;". */
    data class ExactClass(val descriptor: String) : ClassSelector()

    /** Matches classes whose descriptor contains [substring]. */
    data class ClassContains(val substring: String) : ClassSelector()

    /**
     * Matches classes that invoke a method whose defining class (if non-null) and method name
     * match [ownerContains] and [methodName].
     */
    data class Invokes(
        val methodName: String,
        val ownerContains: String? = null,
    ) : ClassSelector()

    /** Matches classes referencing a string constant equal to or containing [value]. */
    data class ConstString(
        val value: String,
        val substringMatch: Boolean = false,
    ) : ClassSelector()

    /** Matches classes defining a method with [methodName]. */
    data class DefinesMethod(val methodName: String) : ClassSelector()

    /** Matches classes accessing a field with [fieldName] (and optional [ownerContains]). */
    data class FieldAccess(
        val fieldName: String,
        val ownerContains: String? = null,
    ) : ClassSelector()

    /** Matches any class matched by [left] OR [right]. */
    data class Or(val left: ClassSelector, val right: ClassSelector) : ClassSelector()

    /** Matches any class matched by BOTH [left] AND [right]. */
    data class And(val left: ClassSelector, val right: ClassSelector) : ClassSelector()
}

data class MatchedClass(
    val dexEntryName: String,
    val descriptor: String,
    val classDef: DexBackedClassDef,
)

object SelectorScan {

    /**
     * Scans all `classes*.dex` in [jar] and returns matching classes grouped by descriptor.
     * Pre-filters each DEX using string/method/field index tables before walking instructions.
     */
    fun scan(jar: DexJar, selectors: Collection<ClassSelector>): Map<String, MatchedClass> {
        if (selectors.isEmpty()) return emptyMap()
        val combined = selectors.reduce { acc, next -> ClassSelector.Or(acc, next) }
        val results = LinkedHashMap<String, MatchedClass>()

        for (entryName in jar.dexEntries) {
            val dex = jar.getDexFile(entryName)
            if (!dexMayContainMatch(dex, combined)) continue
            for (classDef in dex.classes) {
                if (matchesClass(classDef, combined)) {
                    results[classDef.type] = MatchedClass(entryName, classDef.type, classDef)
                }
            }
        }
        return results
    }

    /**
     * Fast DEX-level index pre-filter: if a selector requires a string, method name, or field name
     * that does not appear anywhere in the DEX's string/method/field tables, skip the entire DEX.
     */
    fun dexMayContainMatch(dex: DexBackedDexFile, selector: ClassSelector): Boolean {
        return when (selector) {
            is ClassSelector.ClassSuffix -> true
            is ClassSelector.ExactClass -> true
            is ClassSelector.ClassContains -> true
            is ClassSelector.DefinesMethod -> dexHasString(dex, selector.methodName, exact = true)
            is ClassSelector.Invokes -> dexHasString(dex, selector.methodName, exact = true)
            is ClassSelector.FieldAccess -> dexHasString(dex, selector.fieldName, exact = true)
            is ClassSelector.ConstString -> dexHasString(dex, selector.value, exact = !selector.substringMatch)
            is ClassSelector.Or -> dexMayContainMatch(dex, selector.left) || dexMayContainMatch(dex, selector.right)
            is ClassSelector.And -> dexMayContainMatch(dex, selector.left) && dexMayContainMatch(dex, selector.right)
        }
    }

    private fun dexHasString(dex: DexBackedDexFile, target: String, exact: Boolean): Boolean {
        val stringSection = dex.stringSection
        val count = stringSection.size
        if (exact) {
            // Binary search in sorted DEX string table
            var low = 0
            var high = count - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val cmp = stringSection[mid].compareTo(target)
                when {
                    cmp < 0 -> low = mid + 1
                    cmp > 0 -> high = mid - 1
                    else -> return true
                }
            }
            return false
        } else {
            for (i in 0 until count) {
                if (stringSection[i].contains(target)) return true
            }
            return false
        }
    }

    fun matchesClass(classDef: DexBackedClassDef, selector: ClassSelector): Boolean {
        return when (selector) {
            is ClassSelector.ClassSuffix -> classDef.type.endsWith(selector.normalizedSuffix)
            is ClassSelector.ExactClass -> classDef.type == selector.descriptor
            is ClassSelector.ClassContains -> classDef.type.contains(selector.substring)
            is ClassSelector.DefinesMethod -> classDef.methods.any { it.name == selector.methodName }
            is ClassSelector.Invokes -> classInvokes(classDef, selector.methodName, selector.ownerContains)
            is ClassSelector.ConstString -> classHasConstString(classDef, selector.value, selector.substringMatch)
            is ClassSelector.FieldAccess -> classAccessesField(classDef, selector.fieldName, selector.ownerContains)
            is ClassSelector.Or -> matchesClass(classDef, selector.left) || matchesClass(classDef, selector.right)
            is ClassSelector.And -> matchesClass(classDef, selector.left) && matchesClass(classDef, selector.right)
        }
    }

    private fun classInvokes(
        classDef: DexBackedClassDef,
        methodName: String,
        ownerContains: String?,
    ): Boolean {
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            for (insn in impl.instructions) {
                if (insn is ReferenceInstruction) {
                    val ref = insn.reference
                    if (ref is MethodReference && ref.name == methodName) {
                        if (ownerContains == null || ref.definingClass.contains(ownerContains)) {
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    private fun classHasConstString(
        classDef: DexBackedClassDef,
        value: String,
        substringMatch: Boolean,
    ): Boolean {
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            for (insn in impl.instructions) {
                if (insn is ReferenceInstruction) {
                    val ref = insn.reference
                    if (ref is StringReference) {
                        val s = ref.string
                        if (if (substringMatch) s.contains(value) else s == value) {
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    private fun classAccessesField(
        classDef: DexBackedClassDef,
        fieldName: String,
        ownerContains: String?,
    ): Boolean {
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            for (insn in impl.instructions) {
                if (insn is ReferenceInstruction) {
                    val ref = insn.reference
                    if (ref is FieldReference && ref.name == fieldName) {
                        if (ownerContains == null || ref.definingClass.contains(ownerContains)) {
                            return true
                        }
                    }
                }
            }
        }
        return false
    }
}
