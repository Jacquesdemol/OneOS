package com.fahadali.oneos.core.patch

import com.fahadali.oneos.core.image.FsKind
import com.fahadali.oneos.core.image.FsProbe
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.security.MessageDigest

/**
 * Extracts guest APEX payloads (`apex_payload.img`) from `.apex` and compressed `.capex`
 * packages inside a mounted guest image, matching `make-overlays.sh` lines 1676-1705
 * and `docs/app/patch-engine.md` §5.
 */
object ApexPayloadExtractor {

    val DEFAULT_MODULES = listOf(
        "art", "i18n", "runtime", "sdkext", "nfcservices", "tethering",
        "crashrecovery", "cellbroadcast", "extservices", "permission",
        "wifi", "appsearch", "mediaprovider", "adservices", "rkpd",
        "healthfitness", "ondevicepersonalization", "configinfrastructure", "bt",
    )

    val TELECOM_MISSING_EXTRA_MODULES = listOf("telephonycore", "conscrypt", "profiling")

    data class ExtractedApexPayload(
        val shortName: String,
        val manifestName: String,
        val version: Long,
        val sourceApexName: String,
        val outputFileName: String,
        val filesystem: FsKind,
        val sizeBytes: Long,
        val sha256: String,
        val requireNativeLibs: List<String> = emptyList(),
    )

    data class ApexManifestInfo(
        val name: String,
        val version: Long,
        val requireNativeLibs: List<String> = emptyList(),
    )

    fun extractPayloadsFromMountedImage(
        mountedRoot: File,
        outputDir: File,
        modules: List<String> = resolveModuleList(mountedRoot),
    ): List<ExtractedApexPayload> {
        outputDir.mkdirs()
        val apexDir = File(mountedRoot, "system/apex")
        if (!apexDir.isDirectory) return emptyList()

        val results = mutableListOf<ExtractedApexPayload>()
        for (shortName in modules) {
            val candidateNames = listOf(
                "com.android.$shortName.apex",
                "com.android.$shortName.capex",
                "com.google.android.$shortName.apex",
                "com.google.android.${shortName}_compressed.apex",
                "com.google.android.${shortName}_tplus_compressed.apex",
            )
            val apexFile = candidateNames.map { File(apexDir, it) }.firstOrNull { it.isFile && it.length() > 0L }
                ?: continue
            val extracted = extractSingleApex(shortName, apexFile, outputDir)
            if (extracted != null) {
                results.add(extracted)
            }
        }
        return results
    }

    fun resolveModuleList(mountedRoot: File): List<String> {
        val telecomJar = File(mountedRoot, "system/framework/framework-telecom.jar")
        return if (telecomJar.isFile) {
            DEFAULT_MODULES
        } else {
            DEFAULT_MODULES + TELECOM_MISSING_EXTRA_MODULES
        }
    }

    fun extractSingleApex(
        shortName: String,
        apexOrCapexFile: File,
        outputDir: File,
    ): ExtractedApexPayload? {
        outputDir.mkdirs()
        val tempInnerApex = File(outputDir, ".tmp_${shortName}_original.apex")
        val payloadOut = File(outputDir, "${shortName}_payload.img")
        val payloadPart = File(outputDir, "${shortName}_payload.img.part")

        try {
            val effectiveApex = ZipFile.builder().setFile(apexOrCapexFile).get().use { outerZip ->
                val origEntry = outerZip.getEntry("original_apex")
                if (origEntry != null) {
                    outerZip.getInputStream(origEntry).use { input ->
                        tempInnerApex.outputStream().use { out -> input.copyTo(out) }
                    }
                    tempInnerApex
                } else {
                    apexOrCapexFile
                }
            }

            var manifestInfo = ApexManifestInfo("com.android.$shortName", 0L)
            ZipFile.builder().setFile(effectiveApex).get().use { apexZip ->
                val pbEntry = apexZip.getEntry("apex_manifest.pb")
                if (pbEntry != null) {
                    val pbBytes = apexZip.getInputStream(pbEntry).use { it.readBytes() }
                    manifestInfo = parseApexManifestPb(pbBytes, defaultName = "com.android.$shortName")
                }
                val payloadEntry = apexZip.getEntry("apex_payload.img") ?: return null
                val md = MessageDigest.getInstance("SHA-256")
                var totalBytes = 0L
                apexZip.getInputStream(payloadEntry).use { input ->
                    payloadPart.outputStream().use { out ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            totalBytes += n
                        }
                        out.fd.sync()
                    }
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                val fsInfo = FsProbe.probe(payloadPart)
                if (payloadOut.exists()) payloadOut.delete()
                if (!payloadPart.renameTo(payloadOut)) {
                    payloadPart.copyTo(payloadOut, overwrite = true)
                    payloadPart.delete()
                }
                return ExtractedApexPayload(
                    shortName = shortName,
                    manifestName = manifestInfo.name,
                    version = manifestInfo.version,
                    sourceApexName = apexOrCapexFile.name,
                    outputFileName = payloadOut.name,
                    filesystem = fsInfo.kind,
                    sizeBytes = totalBytes,
                    sha256 = sha,
                    requireNativeLibs = manifestInfo.requireNativeLibs,
                )
            }
        } finally {
            tempInnerApex.delete()
            payloadPart.delete()
        }
    }

    /**
     * Parses `apex_manifest.pb` wire format (field 1: string `name`, field 2: int64 `version`,
     * field 8: repeated string `requireNativeLibs`), matching `make-overlays.sh` lines 2067-2096.
     */
    fun parseApexManifestPb(bytes: ByteArray, defaultName: String = ""): ApexManifestInfo {
        var i = 0
        var name = defaultName
        var version = 0L
        val requireNativeLibs = mutableListOf<String>()

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (i < bytes.size) {
                val b = bytes[i].toInt() and 0xFF
                i++
                result = result or ((b.toLong() and 0x7FL) shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return result
        }

        while (i < bytes.size) {
            val tag = readVarint()
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 7L).toInt()
            when (wireType) {
                0 -> {
                    val v = readVarint()
                    if (fieldNumber == 2) version = v
                }
                1 -> i += 8
                2 -> {
                    val len = readVarint().toInt()
                    if (len < 0 || i + len > bytes.size) break
                    val str = String(bytes, i, len, Charsets.UTF_8)
                    i += len
                    if (fieldNumber == 1) name = str
                    else if (fieldNumber == 8) requireNativeLibs.add(str)
                }
                5 -> i += 4
                else -> break
            }
        }
        return ApexManifestInfo(name, version, requireNativeLibs)
    }
}
