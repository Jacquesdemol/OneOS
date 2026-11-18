package com.fahadali.oneos.session

import com.fahadali.oneos.core.image.ImageImporter
import com.fahadali.oneos.core.model.InstalldMethod
import com.fahadali.oneos.core.patch.BuiltJarOutput
import com.fahadali.oneos.core.patch.GuardCheckResult
import com.fahadali.oneos.core.patch.GuardManifest
import com.fahadali.oneos.core.patch.PatchContext
import com.fahadali.oneos.core.patch.PatchEngine
import com.fahadali.oneos.core.patch.PatchRegistry
import com.fahadali.oneos.core.safety.GuardVerifier
import com.fahadali.oneos.core.safety.GuestImageInspector
import com.fahadali.oneos.core.safety.HostInstalldTable
import com.fahadali.oneos.core.safety.UidIsolationValidator
import java.io.File

/**
 * End-to-end on-device GSI import, inspection, selective DEX patching, and independent
 * post-patch safety verification pipeline for Samsung Galaxy S22 Ultra (`SM-S908E`, `SM8450`)
 * on One UI 8 (Android 16, SDK 36).
 */
object OnDeviceGsiPipeline {
    data class PipelineOutcome(
        val success: Boolean,
        val imported: ImageImporter.ImportedImage? = null,
        val guestFamily: String? = null,
        val patchedJars: List<BuiltJarOutput> = emptyList(),
        val guardsReportFile: File? = null,
        val error: String? = null,
    )

    /**
     * Runs the complete fail-closed on-device GSI pipeline:
     * 1. Probes and unpacks [inputImageFile] into [stageImagesDir] (`ImageImporter`).
     * 2. Inspects mounted/extracted [guestSystemRoot] ELF64 binaries (`GuestImageInspector`)
     *    against [hostCpuFeatures].
     * 3. Validates the derived [hostInstalldMethods] for the guest SDK (`36` on One UI 8).
     * 4. Runs `PatchEngine` across `services.jar` (and `oplus-services.jar` when present).
     * 5. Runs `GuardVerifier` across all patched and unpatched `SYSTEMSERVERCLASSPATH` JARs,
     *    checks `BOOTCLASSPATH` shadowing, and writes `guards.json`, `guards.txt`, and
     *    `services_patched.jar.guards.txt`.
     */
    fun execute(
        inputImageFile: File,
        stageImagesDir: File,
        guestSystemRoot: File,
        overlaysOutDir: File,
        hostInstalldMethods: List<InstalldMethod>,
        hostFingerprint: String = "",
        hostServicesJarSha256: String = "",
        installdTableSha256: String = "",
        guestSdk: Int = 36,
        hostCpuFeatures: Set<String> = GuestImageInspector.SM8450_CPU_FEATURES,
        ksuManagerAppId: Int? = null,
    ): PipelineOutcome {
        // 1. Validate KernelSU manager App ID if present
        if (ksuManagerAppId != null) {
            val ksuProblems = UidIsolationValidator.validateKernelSuManagerAppId(ksuManagerAppId)
            if (ksuProblems.isNotEmpty()) {
                return PipelineOutcome(success = false, error = ksuProblems.joinToString("; "))
            }
        }

        // 2. Validate host installd table for target SDK
        val tableProblems = HostInstalldTable.validateMethodCountForSdk(guestSdk, hostInstalldMethods)
        if (tableProblems.isNotEmpty()) {
            return PipelineOutcome(success = false, error = tableProblems.joinToString("; "))
        }

        // 3. Probe and import image container
        val imported = try {
            ImageImporter.importImage(inputImageFile, stageImagesDir)
        } catch (e: Exception) {
            return PipelineOutcome(success = false, error = "Image import failed: ${e.message}")
        }

        // 4. Inspect guest ELF64 binaries and ARM64 ISA extensions against host CPU features
        val isaReport = GuestImageInspector.inspectSystemRoot(guestSystemRoot, hostCpuFeatures)
        if (!isaReport.compatible) {
            return PipelineOutcome(
                success = false,
                imported = imported,
                error = "Incompatible guest binary ISA: ${isaReport.refusalReasons.joinToString("; ")}",
            )
        }

        // 5. Run selective DEX PatchEngine across services.jar and oplus-services.jar
        overlaysOutDir.mkdirs()
        val oplusJar = File(guestSystemRoot, "framework/oplus-services.jar")
            .takeIf { it.isFile }
            ?: File(guestSystemRoot, "system_ext/framework/oplus-services.jar").takeIf { it.isFile }
        val isColorOs = oplusJar != null
        val family = if (isColorOs) "coloros" else "aosp"

        val ctx = PatchContext(
            hostInstalldMethods = hostInstalldMethods.associateBy { it.name },
            guestSdk = guestSdk,
            guestFamily = family,
        )

        val builtJars = mutableListOf<BuiltJarOutput>()
        try {
            val servicesJar = File(guestSystemRoot, "framework/services.jar")
            if (!servicesJar.isFile) {
                return PipelineOutcome(
                    success = false,
                    imported = imported,
                    guestFamily = family,
                    error = "Missing framework/services.jar in guest system image",
                )
            }
            builtJars += PatchEngine.patchJar(
                sourceJar = servicesJar,
                outputJar = File(overlaysOutDir, "services_patched.jar"),
                patches = PatchRegistry.defaultServicesJarPatches(isColorOs),
                ctx = ctx,
            )
            if (oplusJar != null) {
                builtJars += PatchEngine.patchJar(
                    sourceJar = oplusJar,
                    outputJar = File(overlaysOutDir, "oplus-services_patched.jar"),
                    patches = PatchRegistry.defaultOplusServicesJarPatches(),
                    ctx = ctx,
                )
            }
        } catch (e: Exception) {
            return PipelineOutcome(
                success = false,
                imported = imported,
                guestFamily = family,
                error = "PatchEngine failed closed: ${e.message}",
            )
        }

        // 6. Discover all SYSTEMSERVERCLASSPATH JARs, substituting patched overlays
        val discoveredJars = GuardVerifier.discoverSystemServerJars(guestSystemRoot)
        val patchedByOriginalName = builtJars.associate { b ->
            val origName = b.outputFileName.replace("_patched.jar", ".jar")
            origName to File(overlaysOutDir, b.outputFileName)
        }
        val jarsToVerify = if (discoveredJars.isNotEmpty()) {
            discoveredJars.map { orig -> patchedByOriginalName[orig.name] ?: orig }
        } else {
            builtJars.map { File(overlaysOutDir, it.outputFileName) }
        }

        val verification = GuardVerifier.verifyJars(jarsToVerify)
        if (!verification.passed) {
            return PipelineOutcome(
                success = false,
                imported = imported,
                guestFamily = family,
                error = "GuardVerifier refused patched output: ${verification.violations.joinToString("; ")}",
            )
        }

        val guardsFile = File(overlaysOutDir, "services_patched.jar.guards.txt")
        guardsFile.writeText(verification.guardsReport)

        PatchEngine.writeGuardManifests(
            stageDir = overlaysOutDir,
            manifest = GuardManifest(
                imageSha256 = imported.sha256,
                hostFingerprint = hostFingerprint,
                hostServicesJarSha256 = hostServicesJarSha256,
                installdTableSha256 = installdTableSha256,
                jars = builtJars,
                checks = listOf(
                    GuardCheckResult(
                        id = "GuardVerifier",
                        ok = true,
                        detail = "scannedJars=${verification.scannedJars.size} classes=${verification.scannedClasses} methods=${verification.scannedMethods}",
                    ),
                ),
            ),
        )

        return PipelineOutcome(
            success = true,
            imported = imported,
            guestFamily = family,
            patchedJars = builtJars,
            guardsReportFile = guardsFile,
        )
    }
}
