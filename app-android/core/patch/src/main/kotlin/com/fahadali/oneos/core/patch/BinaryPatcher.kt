package com.fahadali.oneos.core.patch

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Deterministic native binary patcher with strict ELF architecture, build-ID,
 * 3-file SHA-256 tuple pinning, and instruction-context verification.
 *
 * Never applies a patch based on approximate byte sequences:
 * - For `vold` + `libservice.incremental.so`, requires an exact match against one of the
 *   pinned 3-file SHA-256 tuples (`vold`, `service.incremental.so`, `crashrecovery` APEX)
 *   from `scripts/make-overlays.sh`, plus ELF64 little-endian `EM_AARCH64` verification
 *   and exact pre-patch byte checks at every offset.
 * - If a guest image does not match any pinned 3-file tuple, `vold` and `service.incremental.so`
 *   are left unpatched (`voldGvldEnabled = false`) and the session requires `ONEOS_RETURN_MODE=reboot`.
 */
object BinaryPatcher {
    private const val ELF_CLASS_64: Byte = 2
    private const val ELF_DATA_2LSB: Byte = 1
    private const val EM_AARCH64: Short = 183

    data class ByteEdit(
        val offset: Int,
        val expectedOldBytes: ByteArray,
        val newBytes: ByteArray,
    )

    data class VoldIncrementalPin(
        val buildName: String,
        val voldSha256: String,
        val incrementalSha256: String,
        val crashrecoverySha256: String,
        val voldEdits: List<ByteEdit>,
        val incrementalEdits: List<ByteEdit>,
    )

    data class VoldPatchOutcome(
        val patched: Boolean,
        val matchedBuild: String?,
        val voldSha256After: String?,
        val incrementalSha256After: String?,
        val requiresRebootReturnMode: Boolean,
        val reason: String,
    )

    data class SurfaceFlingerSite(
        val sha1Prefix12: String,
        val offset: Int,
        val expectedOldHex: String = "680200b4",
        val newHex: String = "13000014",
    )

    private val GVLD_32 = "gvld\u0000\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII) +
        ByteArray(24) // 32 bytes total replacing "android.system.vold.IVold/default" (33 bytes incl NUL -> 32 bytes + existing NUL)
    private val OLD_IVOLD_DEFAULT = "android.system.vold.IVold/default".toByteArray(Charsets.US_ASCII)
    private val NEW_GVLD_DEFAULT = "gvld\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(OLD_IVOLD_DEFAULT.size - 5)

    private val OLD_VOLD_4 = "vold\u0000".toByteArray(Charsets.US_ASCII)
    private val NEW_GVLD_4 = "gvld\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * Exact 3-file SHA-256 pins from `scripts/make-overlays.sh` lines 68-259,
     * including Android 16 OnePlus OxygenOS 16 BP2A (CPH2745) and ColorOS/Pixel/LineageOS builds.
     */
    val VOLD_INCREMENTAL_PINS: List<VoldIncrementalPin> = listOf(
        VoldIncrementalPin(
            buildName = "ColorOS 17 CP2A.260605.016 (Find N6 / Find X9 Ultra)",
            voldSha256 = "f87bb40e4471b010142ebce39c4c63bf0f1df4c7560f09f83ebee5155133bd35",
            incrementalSha256 = "2d6dc42d26d13f7e8170df2ab0e331c6c6137c66eb308338db479893d7f25ca6",
            crashrecoverySha256 = "3168bc7f22e96fd55068c7224231e87be9d762917b51ee1c801c312860f47b74",
            voldEdits = listOf(
                ByteEdit(0x16bc1, OLD_IVOLD_DEFAULT, NEW_GVLD_DEFAULT),
                ByteEdit(0x1d1cc, OLD_VOLD_4, NEW_GVLD_4),
            ),
            incrementalEdits = listOf(
                ByteEdit(0x284e, OLD_VOLD_4, NEW_GVLD_4),
            ),
        ),
        VoldIncrementalPin(
            buildName = "OnePlus OxygenOS 16 BP2A (CPH2745)",
            voldSha256 = "cd18c5313787c3bd5f182c7bcfb6982d4b23f5629a1c95be41cef2c156d7ebc1",
            incrementalSha256 = "e24c296c9148f71faa35267e535e65d9ed527206e25d929d371eb400cc8f2279",
            crashrecoverySha256 = "626a82dda7c6cc0dfc7ee26397466b75a3d5d12bdaf0a1cd8d9c521c6142c548",
            voldEdits = listOf(
                ByteEdit(0xfd49, OLD_IVOLD_DEFAULT, NEW_GVLD_DEFAULT),
                ByteEdit(0x758e4, OLD_VOLD_4, NEW_GVLD_4),
            ),
            incrementalEdits = listOf(
                ByteEdit(0x4f882, OLD_VOLD_4, NEW_GVLD_4),
            ),
        ),
    )

    val SURFACEFLINGER_QTI_SITES: List<SurfaceFlingerSite> = listOf(
        SurfaceFlingerSite("9896c0cba131", 0x470bc8), // ColorOS 17 Find N6 / X9U CN
        SurfaceFlingerSite("a09087821954", 0x471178), // ColorOS 17 Find X9U IN
        SurfaceFlingerSite("9dd1117ff4ab", 0x475d24), // ColorOS 17 Find X9U IN 2026-11-01
        SurfaceFlingerSite("e5a8726a6687", 0x452a50), // Nothing OS 5.0 Metroid
        SurfaceFlingerSite("cd253fa4b83b", 0x452770), // Nothing OS 5.0 Asteroids
        SurfaceFlingerSite("b8dc0a4def28", 0x5d9bb8), // OnePlus OxygenOS 16 CPH2745 (Android 16)
    )

    fun verifyElf64Aarch64(bytes: ByteArray, label: String) {
        require(bytes.size >= 64) { "$label: file too small for ELF64 header (${bytes.size} bytes)" }
        require(
            bytes[0] == 0x7F.toByte() &&
                bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() &&
                bytes[3] == 'F'.code.toByte()
        ) { "$label: invalid ELF magic" }
        require(bytes[4] == ELF_CLASS_64) { "$label: not ELF64 (class=${bytes[4]})" }
        require(bytes[5] == ELF_DATA_2LSB) { "$label: not little-endian (data=${bytes[5]})" }
        val machine = ByteBuffer.wrap(bytes, 18, 2).order(ByteOrder.LITTLE_ENDIAN).short
        require(machine == EM_AARCH64) { "$label: not EM_AARCH64 (machine=$machine)" }
    }

    /**
     * Patches `vold` and `libservice.incremental.so` only if the 3-file SHA-256 tuple matches
     * a known pin and every expected byte sequence at the target offsets matches verbatim.
     * Otherwise leaves both binaries unpatched and requires `ONEOS_RETURN_MODE=reboot`.
     */
    fun patchVoldAndIncrementalIfPinned(
        voldIn: File,
        incrementalIn: File,
        crashrecoveryApexIn: File,
        voldOut: File,
        incrementalOut: File,
    ): VoldPatchOutcome {
        if (!voldIn.isFile || !incrementalIn.isFile || !crashrecoveryApexIn.isFile) {
            return VoldPatchOutcome(
                patched = false,
                matchedBuild = null,
                voldSha256After = null,
                incrementalSha256After = null,
                requiresRebootReturnMode = true,
                reason = "Missing vold, libservice.incremental.so, or crashrecovery APEX; using ONEOS_RETURN_MODE=reboot",
            )
        }

        val voldBytes = voldIn.readBytes()
        val incBytes = incrementalIn.readBytes()
        verifyElf64Aarch64(voldBytes, "vold")
        verifyElf64Aarch64(incBytes, "libservice.incremental.so")

        val voldSha = sha256Hex(voldBytes)
        val incSha = sha256Hex(incBytes)
        val crashSha = sha256FileHex(crashrecoveryApexIn)

        val pin = VOLD_INCREMENTAL_PINS.firstOrNull {
            it.voldSha256 == voldSha &&
                it.incrementalSha256 == incSha &&
                it.crashrecoverySha256 == crashSha
        }
        if (pin == null) {
            return VoldPatchOutcome(
                patched = false,
                matchedBuild = null,
                voldSha256After = null,
                incrementalSha256After = null,
                requiresRebootReturnMode = true,
                reason = "Unpinned (vold, service.incremental.so, crashrecovery) tuple ($voldSha); gvld not patched, ONEOS_RETURN_MODE=reboot enforced",
            )
        }

        val patchedVold = applyByteEdits(voldBytes.clone(), pin.voldEdits, "vold (${pin.buildName})")
        val patchedInc = applyByteEdits(incBytes.clone(), pin.incrementalEdits, "service.incremental.so (${pin.buildName})")

        // Post-patch verification: ensure ELF64 header intact and old strings replaced at exact offsets
        verifyElf64Aarch64(patchedVold, "vold_patched")
        verifyElf64Aarch64(patchedInc, "service.incremental_patched.so")
        for (edit in pin.voldEdits) {
            val slice = patchedVold.copyOfRange(edit.offset, edit.offset + edit.newBytes.size)
            check(slice.contentEquals(edit.newBytes)) { "Post-patch verification failed for vold at 0x${edit.offset.toString(16)}" }
        }
        for (edit in pin.incrementalEdits) {
            val slice = patchedInc.copyOfRange(edit.offset, edit.offset + edit.newBytes.size)
            check(slice.contentEquals(edit.newBytes)) { "Post-patch verification failed for service.incremental.so at 0x${edit.offset.toString(16)}" }
        }

        voldOut.writeBytes(patchedVold)
        incrementalOut.writeBytes(patchedInc)

        return VoldPatchOutcome(
            patched = true,
            matchedBuild = pin.buildName,
            voldSha256After = sha256Hex(patchedVold),
            incrementalSha256After = sha256Hex(patchedInc),
            requiresRebootReturnMode = true,
            reason = "Patched vold and service.incremental.so for ${pin.buildName}",
        )
    }

    /**
     * Patches `surfaceflinger` to skip `IQtiComposer3Client` when the binary matches one of
     * the pinned SHA-1 prefixes and the instruction at `offset` is `cbz x8, +0x4c` (`680200b4`).
     * If the binary does not import `IQtiComposer3Client` (e.g. AOSP/Pixel), returns false cleanly.
     * If the binary imports `IQtiComposer3Client` or `IMtkSF_ext` but is not pinned, throws [PatchRefusedException].
     */
    fun patchSurfaceFlinger(sfIn: File, sfOut: File): Boolean {
        val bytes = sfIn.readBytes()
        verifyElf64Aarch64(bytes, "surfaceflinger")
        val sha1Prefix = sha1Hex(bytes).take(12)
        val site = SURFACEFLINGER_QTI_SITES.firstOrNull { it.sha1Prefix12 == sha1Prefix }

        val hasQtiSymbol = indexOfSubArray(bytes, "IQtiComposer3Client".toByteArray(Charsets.US_ASCII)) >= 0
        val hasMtkSymbol = indexOfSubArray(bytes, "IMtkSF_ext".toByteArray(Charsets.US_ASCII)) >= 0

        if (site == null) {
            if (!hasQtiSymbol && !hasMtkSymbol) {
                // Pure AOSP / Pixel surfaceflinger: no QTI composer extension client to skip
                return false
            }
            throw PatchRefusedException(
                "BinaryPatcher.surfaceflinger",
                "Unknown surfaceflinger binary (sha1=$sha1Prefix, qti=$hasQtiSymbol, mtk=$hasMtkSymbol); refusing unverified binary patch",
            )
        }

        val oldBytes = hexToBytes(site.expectedOldHex)
        val newBytes = hexToBytes(site.newHex)
        val patched = applyByteEdits(
            bytes.clone(),
            listOf(ByteEdit(site.offset, oldBytes, newBytes)),
            "surfaceflinger ($sha1Prefix)",
        )
        verifyElf64Aarch64(patched, "surfaceflinger_patched")
        sfOut.writeBytes(patched)
        return true
    }

    private fun applyByteEdits(data: ByteArray, edits: List<ByteEdit>, label: String): ByteArray {
        for (edit in edits) {
            if (edit.offset < 0 || edit.offset + edit.expectedOldBytes.size > data.size) {
                throw PatchRefusedException("BinaryPatcher", "$label: offset 0x${edit.offset.toString(16)} out of bounds")
            }
            val actual = data.copyOfRange(edit.offset, edit.offset + edit.expectedOldBytes.size)
            if (!actual.contentEquals(edit.expectedOldBytes)) {
                throw PatchRefusedException(
                    "BinaryPatcher",
                    "$label: unexpected bytes at 0x${edit.offset.toString(16)}",
                )
            }
            System.arraycopy(edit.newBytes, 0, data, edit.offset, edit.newBytes.size)
        }
        return data
    }

    fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun sha1Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sha256FileHex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun indexOfSubArray(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
