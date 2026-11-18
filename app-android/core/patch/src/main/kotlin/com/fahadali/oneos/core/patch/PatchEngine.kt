package com.fahadali.oneos.core.patch

import com.fahadali.oneos.core.dex.DexChecks
import com.fahadali.oneos.core.dex.DexJar
import com.fahadali.oneos.core.dex.DexRewriter
import com.fahadali.oneos.core.dex.JarWriter
import com.fahadali.oneos.core.dex.RoundTripCheck
import com.fahadali.oneos.core.dex.SelectorScan
import com.fahadali.oneos.core.dex.SmaliTree
import com.fahadali.oneos.core.model.Json
import java.io.File
import java.security.MessageDigest

/**
 * Transactional on-device patch engine (`PatchPlan`, `PlanResolver`, `PatchEngine`,
 * and `GuardManifestWriter`) implementing `docs/app/patch-engine.md` §§3, 9, 10.
 */
data class PlannedPatchSpec(
    val entryId: String,
    val handler: String,
    val ruleVersion: Int,
    val isSafety: Boolean,
    val args: Map<String, String> = emptyMap(),
    val onAnchorMissing: OnAnchorMissing = if (isSafety) OnAnchorMissing.REFUSE_SESSION else OnAnchorMissing.SKIP_WARN,
)

data class PlannedJarStep(
    val id: String,
    val inputRelativePath: String,
    val outputFileName: String,
    val required: Boolean,
    val patches: List<PlannedPatchSpec>,
)

data class PatchPlan(
    val schema: String = "oneos.patchplan/1",
    val imageSha256: String,
    val guestFamily: String,
    val guestSdk: Int,
    val hostFingerprint: String,
    val hostServicesJarSha256: String,
    val installdTableSha256: String,
    val jarSteps: List<PlannedJarStep>,
)

data class BuiltJarOutput(
    val outputFileName: String,
    val sha256: String,
    val inputSha256: String,
    val replacedDexEntries: List<String>,
    val patchReports: List<PatchReport>,
)

data class GuardCheckResult(
    val id: String,
    val ok: Boolean,
    val detail: String = "",
)

data class GuardManifest(
    val schema: String = "oneos.guards/1",
    val imageSha256: String,
    val hostFingerprint: String,
    val hostServicesJarSha256: String,
    val installdTableSha256: String,
    val jars: List<BuiltJarOutput>,
    val checks: List<GuardCheckResult>,
) {
    fun toJson(): String = Json.write(
        linkedMapOf<String, Any?>(
            "schema" to schema,
            "imageSha256" to imageSha256,
            "hostFingerprint" to hostFingerprint,
            "hostServicesJarSha256" to hostServicesJarSha256,
            "installdTableSha256" to installdTableSha256,
            "jars" to jars.map { j ->
                linkedMapOf(
                    "outputFileName" to j.outputFileName,
                    "sha256" to j.sha256,
                    "inputSha256" to j.inputSha256,
                    "replacedDexEntries" to j.replacedDexEntries,
                    "patchReports" to j.patchReports.map { r ->
                        linkedMapOf(
                            "entryId" to r.entryId,
                            "handler" to r.handler,
                            "applied" to r.applied,
                            "counts" to r.counts,
                            "reason" to r.reason,
                        )
                    },
                )
            },
            "checks" to checks.map { c ->
                linkedMapOf(
                    "id" to c.id,
                    "ok" to c.ok,
                    "detail" to c.detail,
                )
            },
        ),
    )
}

object PatchRegistry {
    private val patchesByHandler: Map<String, SmaliPatch> = listOf(
        DisplayModeSpecsPatch,
        VoldKeysPatch,
        SkipHostApexServicesPatch,
        PowerGuardsPatch,
        StorageGuardsPatch,
        InstalldAbiPatch,
        SysRqPatch,
        NoWeaverPatch,
        NoBiometricCleanupPatch,
        NoGatekeeperPatch,
        PdbReadonlyPatch,
        NoApexInstallsPatch,
        AppIdOffsetPatch,
        KsuManagerAppIdPatch,
        NoNewUsersPatch,
        NoWipeSecretsPatch,
        NoUpdateEnginePatch,
        OwnIdmapPatch,
        OplusWaitForServicePatch,
        OplusBootHangsPatch,
        BacklightCurvePatch,
        WebviewZygoteSafePatch,
    ).associateBy { it.handler }

    fun get(handler: String): SmaliPatch {
        return patchesByHandler[handler]
            ?: throw IllegalArgumentException("Unknown SmaliPatch handler: $handler")
    }

    fun defaultServicesJarPatches(isColorOs: Boolean): List<PlannedPatchSpec> {
        val list = mutableListOf<PlannedPatchSpec>()
        if (isColorOs) {
            list += spec(DisplayModeSpecsPatch)
        }
        list += listOf(
            spec(VoldKeysPatch),
            spec(SkipHostApexServicesPatch),
            spec(PowerGuardsPatch),
            spec(StorageGuardsPatch, mapOf("jar" to "services.jar")),
            spec(InstalldAbiPatch, mapOf("mode" to "proxy")),
            spec(SysRqPatch, mapOf("jar" to "services.jar")),
            spec(NoWeaverPatch),
            spec(NoBiometricCleanupPatch),
            spec(NoGatekeeperPatch),
            spec(PdbReadonlyPatch),
            spec(NoApexInstallsPatch),
            spec(AppIdOffsetPatch),
            spec(KsuManagerAppIdPatch),
            spec(NoNewUsersPatch),
            spec(OwnIdmapPatch),
            spec(NoWipeSecretsPatch),
            spec(NoUpdateEnginePatch),
            spec(OplusWaitForServicePatch, mapOf("only" to "DefragService")),
        )
        return list
    }

    fun defaultOplusServicesJarPatches(): List<PlannedPatchSpec> {
        return listOf(
            spec(OplusWaitForServicePatch),
            spec(OplusBootHangsPatch),
            spec(StorageGuardsPatch, mapOf("jar" to "oplus-services.jar")),
            spec(SysRqPatch, mapOf("jar" to "oplus-services.jar")),
            spec(NoWipeSecretsPatch),
            spec(InstalldAbiPatch, mapOf("mode" to "callers")),
            spec(BacklightCurvePatch),
        )
    }

    private fun spec(patch: SmaliPatch, args: Map<String, String> = emptyMap()): PlannedPatchSpec {
        return PlannedPatchSpec(
            entryId = patch.entryId,
            handler = patch.handler,
            ruleVersion = patch.ruleVersion,
            isSafety = patch.isSafety,
            args = args,
            onAnchorMissing = patch.defaultOnAnchorMissing,
        )
    }
}

object PatchEngine {

    /**
     * Executes a single [PlannedJarStep] against [sourceJar], writing the patched JAR to [outputJar].
     *
     * Pipeline:
     * 1. Collects all [ClassSelector]s across planned patches.
     * 2. Runs [SelectorScan] and builds an in-memory [SmaliTree].
     * 3. Executes each patch inside a copy-on-write [SmaliTree.Tx] transaction.
     *    - If a safety patch fails, throws [PatchRefusedException] immediately (fail-closed).
     *    - If a non-safety patch fails with `SKIP` or `SKIP_WARN`, rolls back its transaction and records `applied = false`.
     * 4. Rewrites only modified `classesN.dex` via [DexRewriter].
     * 5. Verifies modified DEXes with [DexChecks] (`EmptyTryRangeCheck` and `OrphanMoveResultCheck`).
     * 6. Writes 4-byte aligned STORED JAR via [JarWriter].
     */
    fun patchJar(
        sourceJar: File,
        outputJar: File,
        patches: List<PlannedPatchSpec>,
        ctx: PatchContext,
    ): BuiltJarOutput {
        val inputSha = sha256File(sourceJar)
        val reports = mutableListOf<PatchReport>()

        val resolvedPatches = patches.map { it to PatchRegistry.get(it.handler) }
        val allSelectors = resolvedPatches.flatMap { (spec, patch) -> patch.selectors(spec.args) }

        var replacedEntries: List<String>
        DexJar(sourceJar).use { dexJar ->
            val matchedClasses = SelectorScan.scan(dexJar, allSelectors)
            val tree = SmaliTree.fromMatchedClasses(matchedClasses)

            for ((spec, patch) in resolvedPatches) {
                val tx = tree.beginTx()
                try {
                    val report = patch.apply(tx, spec.args, ctx)
                    tx.commit()
                    reports.add(report)
                } catch (e: PatchRefusedException) {
                    if (spec.isSafety || spec.onAnchorMissing == OnAnchorMissing.REFUSE_SESSION || spec.onAnchorMissing == OnAnchorMissing.BLOCK_IMAGE) {
                        throw e
                    }
                    // Roll back tx by not calling tx.commit()
                    reports.add(
                        PatchReport(
                            entryId = spec.entryId,
                            handler = spec.handler,
                            applied = false,
                            reason = e.message,
                        )
                    )
                }
            }

            val modifiedDexMap = DexRewriter.rewriteModifiedDexes(dexJar, tree)
            replacedEntries = modifiedDexMap.keys.sortedBy { DexJar.dexIndex(it) }
            JarWriter.writeUpdatedJar(sourceJar, modifiedDexMap, outputJar)
        }

        // Verify structural DEX integrity (EmptyTryRangeCheck + OrphanMoveResultCheck)
        DexJar(outputJar).use { outDexJar ->
            val violations = DexChecks.verifyJar(outDexJar)
            if (violations.isNotEmpty()) {
                outputJar.delete()
                throw PatchRefusedException(
                    "DexChecks",
                    "Patched ${outputJar.name} failed bytecode verification: ${violations.first()}",
                )
            }
        }

        val outputSha = sha256File(outputJar)
        return BuiltJarOutput(
            outputFileName = outputJar.name,
            sha256 = outputSha,
            inputSha256 = inputSha,
            replacedDexEntries = replacedEntries,
            patchReports = reports,
        )
    }

    /**
     * Atomically writes `guards.json` and `guards.txt` last during build activation
     * (`docs/app/patch-engine.md` §9.3), so an interrupted build leaves no `guards.txt`
     * and `guest_prepare.sh` refuses to start.
     */
    fun writeGuardManifests(
        stageDir: File,
        manifest: GuardManifest,
    ) {
        val guardsJsonFile = File(stageDir, "guards.json")
        val guardsTxtFile = File(stageDir, "guards.txt")
        guardsJsonFile.delete()
        guardsTxtFile.delete()

        val jsonTmp = File(stageDir, "guards.json.tmp")
        jsonTmp.writeText(manifest.toJson(), Charsets.UTF_8)
        jsonTmp.renameTo(guardsJsonFile)

        val txtTmp = File(stageDir, "guards.txt.tmp")
        val txtContent = buildString {
            appendLine("# Guards verified by OneOS PatchEngine (schema ${manifest.schema})")
            appendLine("# image: ${manifest.imageSha256}")
            appendLine("# host: ${manifest.hostFingerprint}")
            for (jar in manifest.jars) {
                appendLine("${jar.sha256}  ${jar.outputFileName}")
            }
        }
        txtTmp.writeText(txtContent, Charsets.UTF_8)
        txtTmp.renameTo(guardsTxtFile)
    }

    fun sha256File(file: File): String {
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
}
