package com.fahadali.oneos.core.safety

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Inspects critical guest ELF64 binaries (`init`, `surfaceflinger`, `vold`,
 * `libandroid_servers.so`, `libc.so`) for:
 * 1. Valid ELF64 little-endian `EM_AARCH64` (`e_machine == 183`) headers.
 * 2. `.note.gnu.property` (`NT_GNU_PROPERTY_TYPE_0`, `GNU_PROPERTY_AARCH64_FEATURE_1_AND`)
 *    BTI (`0x1`) and PAC (`0x2`) flags.
 * 3. `.text` instruction scan for ARMv8/ARMv9 optional ISA extensions (`SVE`, `SVE2`, `SME`,
 *    `BF16`, `I8MM`, `MOPS`, `LSE`) and comparison against host `/proc/cpuinfo` `Features`.
 *
 * Refuses any guest image whose required ISA features exceed the host SoC's capabilities
 * (such as `SME` or `MOPS` on Snapdragon 8 Gen 1 `SM8450`).
 */
object GuestImageInspector {
    const val EM_AARCH64 = 183
    const val ELFCLASS64 = 2
    const val ELFDATA2LSB = 1
    const val SHT_PROGBITS = 1
    const val SHT_NOTE = 7
    const val SHF_EXECINSTR = 0x4L
    const val NT_GNU_PROPERTY_TYPE_0 = 5
    const val GNU_PROPERTY_AARCH64_FEATURE_1_AND = 0xc0000000.toInt()
    const val GNU_PROPERTY_AARCH64_FEATURE_1_BTI = 0x1
    const val GNU_PROPERTY_AARCH64_FEATURE_1_PAC = 0x2

    /**
     * Known Snapdragon 8 Gen 1 (`SM8450`, Cortex-X2 / Cortex-A710 / Cortex-A510, ARMv9.0-A)
     * `/proc/cpuinfo` feature flags on Samsung One UI 8.
     */
    val SM8450_CPU_FEATURES: Set<String> = setOf(
        "fp", "asimd", "evtstrm", "aes", "pmull", "sha1", "sha2", "crc32",
        "atomics", "fphp", "asimdhp", "cpuid", "asimdrdm", "jscvt", "fcma",
        "lrcpc", "dcpop", "sha3", "sm3", "sm4", "asimddp", "sha512", "sve",
        "asimdfhm", "dit", "uscat", "ilrcpc", "flagm", "ssbs", "sb", "paca",
        "pacg", "dcpodp", "sve2", "sveaes", "svepmull", "svebitperm",
        "svesha3", "svesm4", "flagm2", "frint", "svei8mm", "svebf16", "i8mm",
        "bf16", "dgh", "bti",
    )

    enum class IsaExtension(val cpuinfoFlag: String) {
        LSE("atomics"),
        SVE("sve"),
        SVE2("sve2"),
        SME("sme"),
        BF16("bf16"),
        I8MM("i8mm"),
        MOPS("mops"),
        BTI("bti"),
        PAC("paca"),
    }

    data class ElfInspection(
        val name: String,
        val validElf64Aarch64: Boolean,
        val btiEnabled: Boolean,
        val pacEnabled: Boolean,
        val detectedExtensions: Set<IsaExtension>,
        val unsupportedOnHost: Set<IsaExtension>,
        val error: String? = null,
    )

    data class ImageIsaReport(
        val compatible: Boolean,
        val hostFeatures: Set<String>,
        val binaries: List<ElfInspection>,
        val refusalReasons: List<String>,
    )

    /**
     * Parses `/proc/cpuinfo` text and returns the set of normalized lowercase feature tokens.
     */
    fun parseCpuInfoFeatures(cpuInfoText: String): Set<String> {
        val features = mutableSetOf<String>()
        for (line in cpuInfoText.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Features", ignoreCase = true) && ':' in trimmed) {
                val tokens = trimmed.substringAfter(':').trim().split(Regex("\\s+"))
                for (t in tokens) {
                    if (t.isNotBlank()) features += t.lowercase()
                }
            }
        }
        return features
    }

    /**
     * Inspects a single ELF64 binary byte array and checks its required ISA extensions against
     * [hostFeatures].
     */
    fun inspectElf(
        name: String,
        bytes: ByteArray,
        hostFeatures: Set<String> = SM8450_CPU_FEATURES,
    ): ElfInspection {
        if (bytes.size < 64) {
            return ElfInspection(name, false, false, false, emptySet(), emptySet(), "File too small for ELF64 header")
        }
        if (bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            return ElfInspection(name, false, false, false, emptySet(), emptySet(), "Invalid ELF magic")
        }
        val elfClass = bytes[4].toInt() and 0xFF
        val elfData = bytes[5].toInt() and 0xFF
        if (elfClass != ELFCLASS64 || elfData != ELFDATA2LSB) {
            return ElfInspection(name, false, false, false, emptySet(), emptySet(), "Not ELF64 little-endian (class=$elfClass data=$elfData)")
        }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val eMachine = buf.getShort(18).toInt() and 0xFFFF
        if (eMachine != EM_AARCH64) {
            return ElfInspection(name, false, false, false, emptySet(), emptySet(), "Not EM_AARCH64 (e_machine=$eMachine)")
        }

        val eShoff = buf.getLong(40)
        val eShentsize = buf.getShort(58).toInt() and 0xFFFF
        val eShnum = buf.getShort(60).toInt() and 0xFFFF

        var bti = false
        var pac = false
        val extensions = mutableSetOf<IsaExtension>()

        if (eShoff > 0 && eShentsize >= 64 && eShnum > 0 && eShoff + eShnum.toLong() * eShentsize <= bytes.size) {
            for (i in 0 until eShnum) {
                val shPos = (eShoff + i.toLong() * eShentsize).toInt()
                val shType = buf.getInt(shPos + 4)
                val shFlags = buf.getLong(shPos + 8)
                val shOffset = buf.getLong(shPos + 24)
                val shSize = buf.getLong(shPos + 32)
                if (shOffset < 0 || shSize <= 0 || shOffset + shSize > bytes.size) continue

                if (shType == SHT_NOTE) {
                    val (noteBti, notePac) = parseGnuPropertyNote(bytes, shOffset.toInt(), shSize.toInt())
                    if (noteBti) bti = true
                    if (notePac) pac = true
                } else if (shType == SHT_PROGBITS && (shFlags and SHF_EXECINSTR) != 0L) {
                    scanExecutableInstructions(bytes, shOffset.toInt(), shSize.toInt(), extensions)
                }
            }
        }

        if (bti) extensions += IsaExtension.BTI
        if (pac) extensions += IsaExtension.PAC

        val unsupported = extensions.filterTo(mutableSetOf()) { ext ->
            ext.cpuinfoFlag !in hostFeatures
        }

        return ElfInspection(
            name = name,
            validElf64Aarch64 = true,
            btiEnabled = bti,
            pacEnabled = pac,
            detectedExtensions = extensions,
            unsupportedOnHost = unsupported,
            error = if (unsupported.isNotEmpty()) {
                "$name requires unsupported ISA extensions on host: ${unsupported.joinToString { it.name }}"
            } else {
                null
            },
        )
    }

    /**
     * Inspects critical binaries in [systemRoot] (`bin/init`, `bin/surfaceflinger`, `bin/vold`,
     * `lib64/libandroid_servers.so`, `lib64/libc.so`) against [hostFeatures].
     */
    fun inspectSystemRoot(
        systemRoot: File,
        hostFeatures: Set<String> = SM8450_CPU_FEATURES,
    ): ImageIsaReport {
        val criticalPaths = listOf(
            "bin/init",
            "bin/surfaceflinger",
            "bin/vold",
            "lib64/libandroid_servers.so",
            "lib64/libc.so",
        )
        val inspections = mutableListOf<ElfInspection>()
        val refusals = mutableListOf<String>()

        for (rel in criticalPaths) {
            val f = File(systemRoot, rel)
            if (!f.isFile) continue
            val inspection = inspectElf(rel, f.readBytes(), hostFeatures)
            inspections += inspection
            if (!inspection.validElf64Aarch64 || inspection.error != null) {
                refusals += (inspection.error ?: "$rel failed ELF64 validation")
            }
        }

        return ImageIsaReport(
            compatible = refusals.isEmpty(),
            hostFeatures = hostFeatures,
            binaries = inspections,
            refusalReasons = refusals,
        )
    }

    private fun parseGnuPropertyNote(bytes: ByteArray, offset: Int, size: Int): Pair<Boolean, Boolean> {
        var bti = false
        var pac = false
        val buf = ByteBuffer.wrap(bytes, offset, size).order(ByteOrder.LITTLE_ENDIAN)
        var pos = offset
        val limit = offset + size
        while (pos + 12 <= limit) {
            val namesz = buf.getInt(pos)
            val descsz = buf.getInt(pos + 4)
            val type = buf.getInt(pos + 8)
            pos += 12
            val alignedName = (namesz + 3) and -4
            val alignedDesc = (descsz + 3) and -4
            if (namesz < 0 || descsz < 0 || pos + alignedName + alignedDesc > limit) break

            val isGnu = namesz >= 4 &&
                bytes[pos] == 'G'.code.toByte() &&
                bytes[pos + 1] == 'N'.code.toByte() &&
                bytes[pos + 2] == 'U'.code.toByte() &&
                bytes[pos + 3] == 0.toByte()

            val descStart = pos + alignedName
            if (isGnu && type == NT_GNU_PROPERTY_TYPE_0) {
                var dPos = descStart
                val dEnd = descStart + descsz
                while (dPos + 8 <= dEnd) {
                    val prType = buf.getInt(dPos)
                    val prDatasz = buf.getInt(dPos + 4)
                    dPos += 8
                    if (prDatasz < 0 || dPos + prDatasz > dEnd) break
                    if (prType == GNU_PROPERTY_AARCH64_FEATURE_1_AND && prDatasz >= 4) {
                        val feat = buf.getInt(dPos)
                        if ((feat and GNU_PROPERTY_AARCH64_FEATURE_1_BTI) != 0) bti = true
                        if ((feat and GNU_PROPERTY_AARCH64_FEATURE_1_PAC) != 0) pac = true
                    }
                    dPos += (prDatasz + 7) and -8
                }
            }
            pos = descStart + alignedDesc
        }
        return bti to pac
    }

    private fun scanExecutableInstructions(
        bytes: ByteArray,
        offset: Int,
        size: Int,
        out: MutableSet<IsaExtension>,
    ) {
        val buf = ByteBuffer.wrap(bytes, offset, size).order(ByteOrder.LITTLE_ENDIAN)
        val end = offset + (size and -4)
        var pos = offset
        while (pos < end) {
            val insn = buf.getInt(pos)
            // SME: smstart / smstop (MSR S0_3_C4_C7_3 / S0_3_C4_C6_3 -> 0xd503477f / 0xd503467f)
            // or RDSVL (0x04bf5800 mask 0xfffff800)
            if (insn == 0xd503477f.toInt() || insn == 0xd503467f.toInt() ||
                (insn and 0xfffff800.toInt()) == 0x04bf5800
            ) {
                out += IsaExtension.SME
            }
            // MOPS: CPYFP / CPYFM / CPYFE / SETP / SETM / SETE (FEAT_MOPS encoding 0x19000400 / 0x19df0400)
            if ((insn and 0x3b200c00) == 0x19000400 && (insn and 0x80000000.toInt()) == 0) {
                out += IsaExtension.MOPS
            }
            // SVE RDVL Xd, #imm (0x04bf5000 mask 0xfffff800) or PTRUE (0x2518e000 mask 0xff3fe000)
            if ((insn and 0xfffff800.toInt()) == 0x04bf5000 ||
                (insn and 0xff3fe000.toInt()) == 0x2518e000
            ) {
                out += IsaExtension.SVE
            }
            // BF16: BFDOT (0x2e40fc00 / 0x6e40fc00) or BFMMLA (0x6e40ec00)
            if ((insn and 0xbfe0fc00.toInt()) == 0x2e40fc00 ||
                (insn and 0xffe0fc00.toInt()) == 0x6e40ec00
            ) {
                out += IsaExtension.BF16
            }
            // I8MM: SMMLA (0x4e80a400), UMMLA (0x6e80a400), USMMLA (0x4e80a800)
            if ((insn and 0xdfe0fc00.toInt()) == 0x4e80a400 ||
                (insn and 0xffe0fc00.toInt()) == 0x4e80a800
            ) {
                out += IsaExtension.I8MM
            }
            // LSE atomics: CAS / LDADD (0x38200000 or 0xc8a07c00)
            if ((insn and 0x3fe0fc00) == 0x08a07c00) {
                out += IsaExtension.LSE
            }
            pos += 4
        }
    }
}
