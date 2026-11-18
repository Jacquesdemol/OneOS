package com.fahadali.oneos.core.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Structural DEX bytecode verifiers matching `scripts/check-dex.py`:
 * 1. [EmptyTryRangeCheck]: Ensures every `TryBlock` has `codeUnitCount > 0` and stays within
 *    the method's total code units (prevents `DEX_FILE_VERIFY_ERROR` in ART `dex2oat`).
 * 2. [OrphanMoveResultCheck]: Ensures every `move-result*` instruction is immediately preceded
 *    by an `invoke-*` or `filled-new-array*` instruction, is not at offset 0, and is not the target
 *    of any exception handler (prevents `VerifyError: move-result* not after invoke-*` when a patch
 *    replaces an `invoke-*` with `nop` or `const` without removing the following `move-result*`).
 * 3. [RoundTripCheck]: Verifies that `ClassDef -> Baksmali -> Smali -> ClassDef` preserves
 *    method signatures, instruction opcodes, and try/catch block counts.
 */
data class DexViolation(
    val dexEntryName: String,
    val classDescriptor: String,
    val methodSignature: String,
    val checkName: String,
    val detail: String,
)

object EmptyTryRangeCheck {
    fun verify(dexEntryName: String, dexFile: DexBackedDexFile): List<DexViolation> {
        val violations = mutableListOf<DexViolation>()
        for (classDef in dexFile.classes) {
            for (method in classDef.methods) {
                val impl = method.implementation ?: continue
                val totalUnits = impl.instructions.sumOf { it.codeUnits }
                for ((idx, tryBlock) in impl.tryBlocks.withIndex()) {
                    val start = tryBlock.startCodeAddress
                    val count = tryBlock.codeUnitCount
                    if (count <= 0) {
                        violations.add(
                            DexViolation(
                                dexEntryName = dexEntryName,
                                classDescriptor = classDef.type,
                                methodSignature = formatMethod(method),
                                checkName = "EmptyTryRangeCheck",
                                detail = "TryBlock[$idx] at 0x${start.toString(16)} has empty codeUnitCount=$count",
                            )
                        )
                    } else if (start < 0 || start + count > totalUnits) {
                        violations.add(
                            DexViolation(
                                dexEntryName = dexEntryName,
                                classDescriptor = classDef.type,
                                methodSignature = formatMethod(method),
                                checkName = "EmptyTryRangeCheck",
                                detail = "TryBlock[$idx] range [0x${start.toString(16)}..0x${(start + count).toString(16)}) exceeds method codeUnits=0x${totalUnits.toString(16)}",
                            )
                        )
                    }
                }
            }
        }
        return violations
    }
}

object OrphanMoveResultCheck {
    private val MOVE_RESULT_OPCODES = setOf(
        Opcode.MOVE_RESULT,
        Opcode.MOVE_RESULT_WIDE,
        Opcode.MOVE_RESULT_OBJECT,
    )

    fun verify(dexEntryName: String, dexFile: DexBackedDexFile): List<DexViolation> {
        val violations = mutableListOf<DexViolation>()
        for (classDef in dexFile.classes) {
            for (method in classDef.methods) {
                val impl = method.implementation ?: continue
                val handlerAddresses = HashSet<Int>()
                for (tb in impl.tryBlocks) {
                    for (h in tb.exceptionHandlers) {
                        handlerAddresses.add(h.handlerCodeAddress)
                    }
                }

                var codeAddress = 0
                var prevInsn: Instruction? = null
                for (insn in impl.instructions) {
                    if (insn.opcode in MOVE_RESULT_OPCODES) {
                        if (prevInsn == null || !isResultProducer(prevInsn.opcode)) {
                            violations.add(
                                DexViolation(
                                    dexEntryName = dexEntryName,
                                    classDescriptor = classDef.type,
                                    methodSignature = formatMethod(method),
                                    checkName = "OrphanMoveResultCheck",
                                    detail = "${insn.opcode.name} at 0x${codeAddress.toString(16)} is preceded by ${prevInsn?.opcode?.name ?: "<method-start>"} instead of invoke-*/filled-new-array*",
                                )
                            )
                        }
                        if (codeAddress in handlerAddresses) {
                            violations.add(
                                DexViolation(
                                    dexEntryName = dexEntryName,
                                    classDescriptor = classDef.type,
                                    methodSignature = formatMethod(method),
                                    checkName = "OrphanMoveResultCheck",
                                    detail = "${insn.opcode.name} at 0x${codeAddress.toString(16)} is an exception handler target",
                                )
                            )
                        }
                    }
                    codeAddress += insn.codeUnits
                    prevInsn = insn
                }
            }
        }
        return violations
    }

    private fun isResultProducer(opcode: Opcode): Boolean {
        val name = opcode.name.lowercase()
        return name.startsWith("invoke-") || name.startsWith("filled-new-array")
    }
}

object RoundTripCheck {
    /**
     * Verifies that disassembling [classDef] with [SmaliTree] and reassembling with [Reassembler]
     * produces an equivalent class definition (matching methods, instruction opcodes, and try blocks).
     */
    fun verifyClassRoundTrip(classDef: ClassDef, apiLevel: Int = 28): List<String> {
        val smaliText = SmaliTree.disassembleClass(classDef)
        val reassembledDex = Reassembler.assembleSmaliSources(listOf(smaliText), apiLevel = apiLevel)
        val reassembledClass = reassembledDex.classes.firstOrNull { it.type == classDef.type }
            ?: return listOf("Reassembled DEX missing class ${classDef.type}")

        val diffs = mutableListOf<String>()
        val origMethods = classDef.methods.associateBy { formatMethod(it) }
        val newMethods = reassembledClass.methods.associateBy { formatMethod(it) }

        if (origMethods.keys != newMethods.keys) {
            diffs.add("Method set mismatch in ${classDef.type}: orig=${origMethods.keys.size}, new=${newMethods.keys.size}")
            return diffs
        }

        for ((sig, origMethod) in origMethods) {
            val newMethod = newMethods.getValue(sig)
            val origImpl = origMethod.implementation
            val newImpl = newMethod.implementation
            if ((origImpl == null) != (newImpl == null)) {
                diffs.add("Implementation presence mismatch in ${classDef.type}->$sig")
                continue
            }
            if (origImpl != null && newImpl != null) {
                val origOpcodes = origImpl.instructions.map { it.opcode }
                val newOpcodes = newImpl.instructions.map { it.opcode }
                if (origOpcodes != newOpcodes) {
                    diffs.add("Instruction opcode mismatch in ${classDef.type}->$sig: orig=${origOpcodes.size}, new=${newOpcodes.size}")
                }
                if (origImpl.tryBlocks.size != newImpl.tryBlocks.size) {
                    diffs.add("TryBlock count mismatch in ${classDef.type}->$sig: orig=${origImpl.tryBlocks.size}, new=${newImpl.tryBlocks.size}")
                }
            }
        }
        return diffs
    }
}

object DexChecks {
    fun verifyDex(dexEntryName: String, dexFile: DexBackedDexFile): List<DexViolation> {
        return EmptyTryRangeCheck.verify(dexEntryName, dexFile) +
            OrphanMoveResultCheck.verify(dexEntryName, dexFile)
    }

    fun verifyJar(jar: DexJar): List<DexViolation> {
        val all = mutableListOf<DexViolation>()
        for (entryName in jar.dexEntries) {
            all.addAll(verifyDex(entryName, jar.getDexFile(entryName)))
        }
        return all
    }
}

internal fun formatMethod(method: Method): String {
    val params = method.parameterTypes.joinToString("")
    return "${method.name}($params)${method.returnType}"
}
