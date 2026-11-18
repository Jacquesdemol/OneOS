package com.fahadali.oneos.core.patch

import com.fahadali.oneos.core.dex.ClassSelector
import com.fahadali.oneos.core.dex.SmaliTree

/**
 * Core boot, Samsung host compatibility, and guest OEM (ColorOS/OxygenOS/Pixel/LineageOS)
 * Smali patches matching `scripts/make-overlays.sh` and `docs/app/patch-engine.md`.
 */
object DisplayModeSpecsPatch : SmaliPatch {
    override val entryId = "coloros.display_mode_fallback"
    override val handler = "DisplayModeSpecsPatch"
    override val ruleVersion = 1
    override val isSafety = false
    override val defaultOnAnchorMissing = OnAnchorMissing.BLOCK_IMAGE

    private const val DS = "Landroid/view/SurfaceControl\$DesiredDisplayModeSpecs;"
    private const val RS = "Landroid/view/SurfaceControl\$RefreshRateRanges;"
    private const val R = "Landroid/view/SurfaceControl\$RefreshRateRange;"
    private const val ANCHOR = "Desired display mode specs from SurfaceFlinger are null"

    private val OLD_BLOCK = Regex(
        """    const-string ([vp]\d+), "Desired display mode specs from SurfaceFlinger are null"\n\n    invoke-static \{v0, \1\}, Landroid/util/Slog;->w\(Ljava/lang/String;Ljava/lang/String;\)I\n\n(?:    \.line \d+\n)?    return-void\n"""
    )

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(ClassSelector.ConstString(ANCHOR))
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val desc = tx.descriptors().firstOrNull { tx.getSource(it)?.contains(ANCHOR) == true }
            ?: throw PatchRefusedException(handler, "LocalDisplayAdapter with '$ANCHOR' not found")
        val src = tx.getSource(desc)!!
        val matches = OLD_BLOCK.findAll(src).toList()
        if (matches.size != 1) {
            throw PatchRefusedException(handler, "$desc: expected null mode specs block once, found ${matches.size}")
        }

        val rangesToInfinity = buildString {
            for (ranges in listOf("primaryRanges", "appRequestRanges")) {
                for (kind in listOf("physical", "render")) {
                    appendLine("    iget-object v1, v8, $DS->$ranges:$RS")
                    appendLine()
                    appendLine("    iget-object v1, v1, $RS->$kind:$R")
                    appendLine()
                    appendLine("    iput v2, v1, $R->max:F")
                    appendLine()
                }
            }
        }

        val replacement = """
            |    const-string v1, "OneOS: desired display mode specs from SurfaceFlinger are null, using the active mode"
            |
            |    invoke-static {v0, v1}, Landroid/util/Slog;->w(Ljava/lang/String;Ljava/lang/String;)I
            |
            |    new-instance v8, $DS
            |
            |    invoke-direct {v8}, $DS-><init>()V
            |
            |    iput-object v3, v8, $DS->displayToken:Landroid/os/IBinder;
            |
            |    iget v1, v7, Landroid/view/SurfaceControl${'$'}DynamicDisplayInfo;->activeDisplayModeId:I
            |
            |    iput v1, v8, $DS->defaultMode:I
            |
            |    const v2, 0x7f800000
            |
            |$rangesToInfinity
        """.trimMargin()

        tx.putSource(desc, OLD_BLOCK.replaceFirst(src, replacement))
        return PatchReport(
            entryId = entryId,
            handler = handler,
            applied = true,
            sites = listOf(PatchSite(desc, "tryConnectDisplayLocked", "falls back to active mode when SF mode specs are null")),
            counts = mapOf("displayModeFallback" to 1),
        )
    }
}

object SkipHostApexServicesPatch : SmaliPatch {
    override val entryId = "samsung.skip_host_apex_services"
    override val handler = "SkipHostApexServicesPatch"
    override val ruleVersion = 1
    override val isSafety = false

    private const val SYS_SERVER = "Lcom/android/server/SystemServer;"
    private val START_SERVICE_FROM_JAR = Regex(
        """^\s*invoke-virtual(?:/range)? \{([^}]*)\}, Lcom/android/server/SystemServiceManager;->startServiceFromJar\(Ljava/lang/String;Ljava/lang/String;\)Lcom/android/server/SystemService;"""
    )

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(
            ClassSelector.ExactClass(SYS_SERVER),
            ClassSelector.Invokes("startServiceFromJar", "Lcom/android/server/SystemServiceManager;"),
        )
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val src = tx.getSource(SYS_SERVER)
            ?: throw PatchRefusedException(handler, "$SYS_SERVER not found")
        val lines = src.split("\n").toMutableList()
        var patched = 0

        for (i in lines.indices) {
            val m = START_SERVICE_FROM_JAR.find(lines[i]) ?: continue
            val regs = m.groupValues[1].split(",").map { it.trim() }
            if (regs.size == 3) {
                val serviceReg = regs[1]
                val guardLabel = ":oneos_skip_samsung_apex_$patched"
                var afterIdx = i + 1
                while (afterIdx < lines.size && (lines[afterIdx].trim().isEmpty() || lines[afterIdx].trim().startsWith("move-result"))) {
                    afterIdx++
                }
                lines[i] = buildString {
                    appendLine("    # OneOS: skip host Samsung APEX system services (e.g. com.samsung.* lifeguard)")
                    appendLine("    const-string v0, \"com.samsung.\"")
                    appendLine("    invoke-virtual {$serviceReg, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z")
                    appendLine("    move-result v0")
                    appendLine("    if-nez v0, $guardLabel")
                    append(lines[i])
                }
                lines.add(afterIdx, "    $guardLabel")
                patched++
            }
        }

        if (patched == 0) {
            throw PatchRefusedException(handler, "$SYS_SERVER: startServiceFromJar call site not found")
        }
        tx.putSource(SYS_SERVER, lines.joinToString("\n"))
        return PatchReport(
            entryId = entryId,
            handler = handler,
            applied = true,
            sites = listOf(PatchSite(SYS_SERVER, "startOtherServices", "skips com.samsung.* APEX services")),
            counts = mapOf("startServiceFromJar" to patched),
        )
    }
}

object OwnIdmapPatch : SmaliPatch {
    override val entryId = "core.guest_idmap2d"
    override val handler = "OwnIdmapPatch"
    override val ruleVersion = 1
    override val isSafety = false

    private const val IDMAP_DAEMON = "Lcom/android/server/om/IdmapDaemon;"
    private val SYSTEM_PROP_SET = Regex(
        """^\s*invoke-static \{[^}]*\}, Landroid/os/SystemProperties;->set\(Ljava/lang/String;Ljava/lang/String;\)V"""
    )

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(ClassSelector.ExactClass(IDMAP_DAEMON))
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val src = tx.getSource(IDMAP_DAEMON)
            ?: throw PatchRefusedException(handler, "$IDMAP_DAEMON not found")
        val lines = src.split("\n").toMutableList()
        var dropped = 0
        for (i in lines.indices) {
            if (SYSTEM_PROP_SET.containsMatchIn(lines[i])) {
                SmaliEditUtils.dropInvokeAndResult(lines, i, "OneOS: guest_start.sh starts guest idmap2d; do not stop/start host idmap2d")
                dropped++
            }
        }
        if (dropped == 0) {
            throw PatchRefusedException(handler, "$IDMAP_DAEMON: no SystemProperties.set calls found")
        }
        tx.putSource(IDMAP_DAEMON, lines.joinToString("\n"))
        return PatchReport(
            entryId = entryId,
            handler = handler,
            applied = true,
            sites = listOf(PatchSite(IDMAP_DAEMON, "connect/stopIdmap2d", "dropped $dropped ctl.start/ctl.stop calls")),
            counts = mapOf("idmapCtlCalls" to dropped),
        )
    }
}

object PdbReadonlyPatch : SmaliPatch {
    override val entryId = "safety.pdb_readonly"
    override val handler = "PdbReadonlyPatch"
    override val ruleVersion = 1
    override val isSafety = true

    private const val PDB = "Lcom/android/server/pdb/PersistentDataBlockService;"

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(ClassSelector.ExactClass(PDB))
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val src = tx.getSource(PDB)
            ?: throw PatchRefusedException(handler, "$PDB not found")
        val lines = src.split("\n").toMutableList()
        val methods = SmaliEditUtils.findMethods(lines, PDB)
        val targets = methods.entries.filter { (sig, _) ->
            sig.startsWith("doSetOemUnlockEnabledLocked(") ||
                sig.startsWith("formatPartitionLocked(") ||
                sig.startsWith("enforceChecksumValidity(")
        }.flatMap { it.value }

        val sites = mutableListOf<PatchSite>()
        for (span in targets.sortedByDescending { it.startLine }) {
            if (span.signature.endsWith(")Z")) {
                SmaliEditUtils.ensureLocalsAtLeast(lines, span, 1)
                lines.add(span.bodyStartLine, "    # OneOS: FRP partition is read-only\n    const/4 v0, 0x1\n    return v0")
            } else if (span.signature.endsWith(")V")) {
                lines.add(span.bodyStartLine, "    # OneOS: FRP partition is read-only\n    return-void")
            }
            sites.add(PatchSite(PDB, span.signature, "read-only FRP guard"))
        }
        tx.putSource(PDB, lines.joinToString("\n"))
        return PatchReport(entryId, handler, true, sites, mapOf("pdbGuarded" to sites.size))
    }
}

object OplusWaitForServicePatch : SmaliPatch {
    override val entryId = "coloros.wait_for_service"
    override val handler = "OplusWaitForServicePatch"
    override val ruleVersion = 1
    override val isSafety = false

    val KEEP_SERVICES = setOf("installd", "cotaservice")
    private val WAIT_CALL = Regex(
        """^(\s*invoke-static \{[^}]*\}, Landroid/os/ServiceManager;->)waitForService(\(Ljava/lang/String;\)Landroid/os/IBinder;)$"""
    )
    private val CONST_STRING = Regex("""^\s*const-string(?:/jumbo)? [vp]\d+, "([^"]+)"""")

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(ClassSelector.Invokes("waitForService", "Landroid/os/ServiceManager;"))
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val onlyServices = args["only"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val sites = mutableListOf<PatchSite>()
        var replaced = 0

        for (desc in tx.descriptors()) {
            val src = tx.getSource(desc) ?: continue
            val lines = src.split("\n").toMutableList()
            var dirty = false

            for (i in lines.indices) {
                val m = WAIT_CALL.matchEntire(lines[i]) ?: continue
                // Look back up to 15 lines for nearest const-string service name
                var svcName: String? = null
                for (k in (i - 1) downTo maxOf(0, i - 15)) {
                    val cm = CONST_STRING.find(lines[k])
                    if (cm != null) {
                        svcName = cm.groupValues[1]
                        break
                    }
                }
                if (svcName in KEEP_SERVICES) continue
                if (onlyServices != null && svcName !in onlyServices) continue

                lines[i] = "${m.groupValues[1]}checkService${m.groupValues[2]}"
                dirty = true
                replaced++
                sites.add(PatchSite(desc, svcName ?: "<dynamic>", "waitForService -> checkService"))
            }

            if (dirty) {
                tx.putSource(desc, lines.joinToString("\n"))
            }
        }

        return PatchReport(
            entryId = entryId,
            handler = handler,
            applied = replaced > 0,
            sites = sites,
            counts = mapOf("waitForServiceReplaced" to replaced),
        )
    }
}

object OplusBootHangsPatch : SmaliPatch {
    override val entryId = "coloros.boot_hangs"
    override val handler = "OplusBootHangsPatch"
    override val ruleVersion = 1
    override val isSafety = false

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(
            ClassSelector.ClassContains("GameGpuControlPanel"),
            ClassSelector.ClassContains("AppErrorsExtImpl"),
            ClassSelector.ConstString("dumpenvironment"),
            ClassSelector.ConstString("gettpinfo"),
        )
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val sites = mutableListOf<PatchSite>()
        var edits = 0
        for (desc in tx.descriptors()) {
            val src = tx.getSource(desc) ?: continue
            val lines = src.split("\n").toMutableList()
            var dirty = false
            for (i in lines.indices) {
                if (lines[i].contains("\"dumpenvironment\"") || lines[i].contains("\"gettpinfo\"")) {
                    // Neutralize ctl.start of missing OPPO vendor services
                    lines[i] = lines[i]
                        .replace("\"dumpenvironment\"", "\"oneos.noop\"")
                        .replace("\"gettpinfo\"", "\"oneos.noop\"")
                    dirty = true
                    edits++
                    sites.add(PatchSite(desc, "ctl.start", "neutralized missing OPPO init service"))
                }
            }
            if (dirty) tx.putSource(desc, lines.joinToString("\n"))
        }
        return PatchReport(entryId, handler, edits > 0, sites, mapOf("bootHangEdits" to edits))
    }
}

object BacklightCurvePatch : SmaliPatch {
    override val entryId = "coloros.backlight_curve"
    override val handler = "BacklightCurvePatch"
    override val ruleVersion = 1
    override val isSafety = false

    private const val TARGET = "Lcom/android/server/display/LocalDisplayDeviceExtImpl;"
    private const val HELPER = "Lcom/android/server/display/OneOSPanelBacklight;"

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(ClassSelector.ExactClass(TARGET))
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val src = tx.getSource(TARGET) ?: return PatchReport(
            entryId = entryId,
            handler = handler,
            applied = false,
            reason = "$TARGET not present in JAR",
        )
        val helperSmali = ctx.helperSmaliSources["OneOSPanelBacklight.smali"]
            ?: return PatchReport(
                entryId = entryId,
                handler = handler,
                applied = false,
                reason = "OneOSPanelBacklight.smali not provided in context",
            )
        val lines = src.split("\n").toMutableList()
        val methods = SmaliEditUtils.findMethods(lines, TARGET)
        val span = methods["getBacklightFromBrightness(F)F"]?.singleOrNull()
            ?: return PatchReport(entryId, handler, false, reason = "getBacklightFromBrightness(F)F not found")

        SmaliEditUtils.ensureLocalsAtLeast(lines, span, 1)
        lines.addAll(
            span.bodyStartLine,
            listOf(
                "    # OneOS: map ColorOS backlight level to Samsung panel nits curve",
                "    invoke-static {p1}, $HELPER->mapBrightness(F)F",
                "    move-result v0",
                "    return v0",
            )
        )
        tx.putSource(TARGET, lines.joinToString("\n"))
        tx.addNewClass(HELPER, helperSmali, preferredDexEntryName = tx.getDexEntryName(TARGET) ?: "classes.dex")
        return PatchReport(
            entryId = entryId,
            handler = handler,
            applied = true,
            sites = listOf(PatchSite(TARGET, span.signature, "delegates to OneOSPanelBacklight")),
            counts = mapOf("backlightCurve" to 1),
        )
    }
}

object WebviewZygoteSafePatch : SmaliPatch {
    override val entryId = "core.webview_zygote_safe"
    override val handler = "WebviewZygoteSafePatch"
    override val ruleVersion = 1
    override val isSafety = false

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(ClassSelector.ConstString("Failed to start WebViewUpdateService", substringMatch = true))
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        return PatchReport(entryId, handler, true, emptyList(), emptyMap())
    }
}
