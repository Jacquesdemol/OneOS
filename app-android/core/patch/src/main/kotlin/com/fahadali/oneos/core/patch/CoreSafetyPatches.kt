package com.fahadali.oneos.core.patch

import com.fahadali.oneos.core.dex.ClassSelector
import com.fahadali.oneos.core.dex.SmaliTree

/**
 * Locked safety patches protecting host One UI storage, encryption keys, weaver slots,
 * biometric templates, gatekeeper SIDs, APEX state, power/sysrq, and app UID namespaces.
 */
object VoldKeysPatch : SmaliPatch {
    override val entryId = "safety.vold_key_guard"
    override val handler = "VoldKeysPatch"
    override val ruleVersion = 1
    override val isSafety = true

    val FORBIDDEN_KEY_METHODS = setOf(
        "addUserKeyAuth",
        "clearUserKeyAuth",
        "fixateNewestUserKeyAuth",
        "createUserKey",
        "destroyUserKey",
        "unlockUserKey",
        "lockUserKey",
        "setCeStorageProtection",
    )

    private val INVOKE_VOLD_KEY = Regex(
        """^\s*invoke-interface(?:/range)? \{[^}]*\}, Landroid/os/IVold;->(""" +
            FORBIDDEN_KEY_METHODS.joinToString("|") +
            """)\("""
    )

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return FORBIDDEN_KEY_METHODS.map { ClassSelector.Invokes(it, "Landroid/os/IVold;") }
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val sites = mutableListOf<PatchSite>()
        val counts = LinkedHashMap<String, Int>()

        for (desc in tx.descriptors()) {
            val src = tx.getSource(desc) ?: continue
            val lines = src.split("\n").toMutableList()
            var dirty = false
            for (i in lines.indices) {
                val m = INVOKE_VOLD_KEY.find(lines[i]) ?: continue
                val methodName = m.groupValues[1]
                SmaliEditUtils.dropInvokeAndResult(
                    lines,
                    i,
                    "OneOS: IVold.$methodName dropped (host encryption keys protected)",
                )
                dirty = true
                val key = "IVold.$methodName"
                counts[key] = (counts[key] ?: 0) + 1
                sites.add(PatchSite(desc, methodName, "dropped IVold.$methodName"))
            }
            if (dirty) {
                tx.putSource(desc, lines.joinToString("\n"))
            }
        }

        val total = counts.values.sum()
        counts["IVold.key_calls"] = total
        return PatchReport(
            entryId = entryId,
            handler = handler,
            applied = true,
            sites = sites,
            counts = counts,
        )
    }
}

object StorageGuardsPatch : SmaliPatch {
    override val entryId = "safety.storage_guards"
    override val handler = "StorageGuardsPatch"
    override val ruleVersion = 3
    override val isSafety = true

    val VOLD_DESTRUCTIVE = setOf(
        "destroyUserStorage",
        "prepareUserStorage",
        "format",
        "partition",
        "forgetPartition",
        "moveStorage",
        "onUserRemoved",
        "abortChanges",
        "startCheckpoint",
        "shutdown",
        "sdlockErase",
    )

    val INSTALLD_DESTRUCTIVE = setOf(
        "destroyUserData",
        "destroyAppData",
        "clearAppData",
        "clearAppDataWithPreservedCacheEntries",
        "destroyAppDataSnapshot",
        "destroyCeSnapshotsNotSpecified",
        "destroyPccData",
        "rmPackageDir",
        "cleanupInvalidPackageDirs",
        "moveCompleteApp",
        "moveAppDataPath",
        "copyAppDataPath",
        "migrateAppData",
        "restoreAppDataSnapshot",
        "onPrivateVolumeRemoved",
        "freeCache",
        "reconcileSdkData",
    )

    val KEYSTORE_DESTRUCTIVE = setOf(
        "clearNamespace",
        "onUserRemoved",
        "onUserLskfRemoved",
        "onUserAdded",
        "migrateKeyNamespace",
        "initUserSuperKeys",
        "deleteAllKeys",
    )

    private val VOLD_REGEX = Regex(
        """^\s*invoke-interface(?:/range)? \{[^}]*\}, Landroid/os/IVold;->(""" +
            VOLD_DESTRUCTIVE.joinToString("|") +
            """)\("""
    )
    private val INSTALLD_REGEX = Regex(
        """^\s*invoke-interface(?:/range)? \{[^}]*\}, Landroid/os/IInstalld;->(""" +
            INSTALLD_DESTRUCTIVE.joinToString("|") +
            """)\("""
    )
    private val KEYSTORE_REGEX = Regex(
        """^\s*invoke-(?:static|virtual|interface)(?:/range)? \{[^}]*\}, Landroid/security/AndroidKeyStoreMaintenance;->(""" +
            KEYSTORE_DESTRUCTIVE.joinToString("|") +
            """)\("""
    )
    private val RECOVERY_WIPE_REGEX = Regex(
        """^\s*invoke-static(?:/range)? \{([^}]*)\}, Landroid/os/RecoverySystem;->rebootPromptAndWipeUserData\(Landroid/content/Context;Ljava/lang/String;\)V"""
    )

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        val list = mutableListOf<ClassSelector>(
            ClassSelector.ClassSuffix("com/android/server/pm/UserDataPreparer;"),
            ClassSelector.ClassSuffix("com/android/server/storage/StorageManagerService;"),
            ClassSelector.ClassSuffix("com/android/server/usage/StorageStatsService;"),
            ClassSelector.Invokes("rebootPromptAndWipeUserData", "Landroid/os/RecoverySystem;"),
        )
        VOLD_DESTRUCTIVE.forEach { list.add(ClassSelector.Invokes(it, "Landroid/os/IVold;")) }
        INSTALLD_DESTRUCTIVE.forEach { list.add(ClassSelector.Invokes(it, "Landroid/os/IInstalld;")) }
        KEYSTORE_DESTRUCTIVE.forEach { list.add(ClassSelector.Invokes(it, "Landroid/security/AndroidKeyStoreMaintenance;")) }
        return list
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val sites = mutableListOf<PatchSite>()
        val counts = LinkedHashMap<String, Int>()
        val isPrimaryServicesJar = args["jar"] != "oplus-services.jar"

        for (desc in tx.descriptors()) {
            if (desc == "Landroid/os/IInstalld\$Stub\$Proxy;" || desc == "Lcom/android/server/pm/OneOSInstalldProxy;") {
                continue
            }
            val src = tx.getSource(desc) ?: continue
            val lines = src.split("\n").toMutableList()
            var dirty = false

            for (i in lines.indices) {
                val line = lines[i]
                val vm = VOLD_REGEX.find(line)
                if (vm != null) {
                    val mName = vm.groupValues[1]
                    SmaliEditUtils.dropInvokeAndResult(lines, i, "OneOS: IVold.$mName dropped")
                    dirty = true
                    counts["IVold.$mName"] = (counts["IVold.$mName"] ?: 0) + 1
                    sites.add(PatchSite(desc, mName, "dropped IVold.$mName"))
                    continue
                }
                val im = INSTALLD_REGEX.find(line)
                if (im != null) {
                    val mName = im.groupValues[1]
                    SmaliEditUtils.dropInvokeAndResult(lines, i, "OneOS: IInstalld.$mName dropped")
                    dirty = true
                    counts["IInstalld.$mName"] = (counts["IInstalld.$mName"] ?: 0) + 1
                    sites.add(PatchSite(desc, mName, "dropped IInstalld.$mName"))
                    continue
                }
                val km = KEYSTORE_REGEX.find(line)
                if (km != null) {
                    val mName = km.groupValues[1]
                    SmaliEditUtils.dropInvokeAndResult(lines, i, "OneOS: AndroidKeyStoreMaintenance.$mName dropped", "0x0")
                    dirty = true
                    counts["AndroidKeyStoreMaintenance.$mName"] = (counts["AndroidKeyStoreMaintenance.$mName"] ?: 0) + 1
                    sites.add(PatchSite(desc, mName, "dropped AndroidKeyStoreMaintenance.$mName"))
                    continue
                }
                val rm = RECOVERY_WIPE_REGEX.find(line)
                if (rm != null) {
                    SmaliEditUtils.dropInvokeAndResult(lines, i, "OneOS: RecoverySystem.rebootPromptAndWipeUserData dropped")
                    dirty = true
                    counts["RecoverySystem.rebootPromptAndWipeUserData"] =
                        (counts["RecoverySystem.rebootPromptAndWipeUserData"] ?: 0) + 1
                    sites.add(PatchSite(desc, "rebootPromptAndWipeUserData", "dropped RecoverySystem wipe"))
                }
            }

            if (desc.endsWith("com/android/server/pm/UserDataPreparer;")) {
                val methods = SmaliEditUtils.findMethods(lines, desc)
                val destroySpans = methods.entries.filter { it.key.startsWith("destroyUserDataLI(") }.flatMap { it.value }
                for (span in destroySpans.sortedByDescending { it.startLine }) {
                    lines.add(span.bodyStartLine, "    # OneOS: never destroy user data\n    return-void")
                    dirty = true
                    counts["UserDataPreparer.destroyUserDataLI"] = 1
                    sites.add(PatchSite(desc, span.signature, "early return-void"))
                }
            }

            if (desc.endsWith("StorageManagerService;")) {
                val methods = SmaliEditUtils.findMethods(lines, desc)
                val spans = methods.entries.filter { it.key.startsWith("isCeStorageUnlocked(") && it.key.endsWith(")Z") }.flatMap { it.value }
                for (span in spans.sortedByDescending { it.startLine }) {
                    SmaliEditUtils.ensureLocalsAtLeast(lines, span, 1)
                    lines.add(span.bodyStartLine, "    # OneOS: CE storage treated as unlocked\n    const/4 v0, 0x1\n    return v0")
                    dirty = true
                    counts["StorageManagerService.isCeStorageUnlocked"] = 1
                    sites.add(PatchSite(desc, span.signature, "returns true"))
                }
            }

            if (desc.endsWith("com/android/server/usage/StorageStatsService;")) {
                val methods = SmaliEditUtils.findMethods(lines, desc)
                val spans = methods.entries.filter { it.key.startsWith("isQuotaSupported(") && it.key.endsWith(")Z") }.flatMap { it.value }
                for (span in spans.sortedByDescending { it.startLine }) {
                    SmaliEditUtils.ensureLocalsAtLeast(lines, span, 1)
                    lines.add(span.bodyStartLine, "    # OneOS: quota queries disabled on shared installd\n    const/4 v0, 0x0\n    return v0")
                    dirty = true
                    counts["StorageStatsService.isQuotaSupported"] = 1
                    sites.add(PatchSite(desc, span.signature, "returns false"))
                }
            }

            if (dirty) {
                tx.putSource(desc, lines.joinToString("\n"))
            }
        }

        if (isPrimaryServicesJar && counts.isEmpty()) {
            throw PatchRefusedException(handler, "No storage guard anchors found in services.jar")
        }

        return PatchReport(
            entryId = entryId,
            handler = handler,
            applied = true,
            sites = sites,
            counts = counts,
        )
    }
}

object PowerGuardsPatch : SmaliPatch {
    override val entryId = "safety.power_guards"
    override val handler = "PowerGuardsPatch"
    override val ruleVersion = 2
    override val isSafety = true

    private val COMMIT_CHANGES = Regex(
        """^\s*invoke-interface(?:/range)? \{[^}]*\}, Landroid/os/storage/IStorageManager;->commitChanges\(\)V"""
    )

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(
            ClassSelector.ClassSuffix("com/android/server/am/ActivityManagerService;"),
            ClassSelector.ClassSuffix("com/android/server/power/ShutdownThread;"),
            ClassSelector.ClassSuffix("com/android/server/power/PowerManagerService;"),
            ClassSelector.ClassSuffix("com/android/server/recoverysystem/RecoverySystemService;"),
            ClassSelector.ConstString("sys.powerctl"),
            ClassSelector.Invokes("commitChanges", "Landroid/os/storage/IStorageManager;"),
        )
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val sites = mutableListOf<PatchSite>()
        val counts = LinkedHashMap<String, Int>()

        for (desc in tx.descriptors()) {
            val src = tx.getSource(desc) ?: continue
            val lines = src.split("\n").toMutableList()
            var dirty = false

            // 1. Drop IStorageManager.commitChanges() in AMS
            for (i in lines.indices) {
                if (COMMIT_CHANGES.containsMatchIn(lines[i])) {
                    SmaliEditUtils.dropInvokeAndResult(lines, i, "OneOS: IStorageManager.commitChanges dropped")
                    dirty = true
                    counts["IStorageManager.commitChanges"] = (counts["IStorageManager.commitChanges"] ?: 0) + 1
                    sites.add(PatchSite(desc, "commitChanges", "dropped commitChanges"))
                }
                if (lines[i].contains("\"sys.powerctl\"")) {
                    lines[i] = lines[i].replace("\"sys.powerctl\"", "\"oneos.guest.powerctl\"")
                    dirty = true
                    counts["sys.powerctl"] = (counts["sys.powerctl"] ?: 0) + 1
                    sites.add(PatchSite(desc, "sys.powerctl", "rewritten to oneos.guest.powerctl"))
                }
            }

            // 2. ShutdownThread entry points -> record oneos.guest.powerctl and return-void
            if (desc.endsWith("com/android/server/power/ShutdownThread;")) {
                val methods = SmaliEditUtils.findMethods(lines, desc)
                val targetNames = setOf("reboot", "rebootSafeMode", "shutdown", "rebootOrShutdown")
                val spans = methods.entries
                    .filter { (sig, _) -> targetNames.any { sig.startsWith("$it(") } && sig.endsWith(")V") }
                    .flatMap { it.value }
                for (span in spans.sortedByDescending { it.startLine }) {
                    SmaliEditUtils.ensureLocalsAtLeast(lines, span, 2)
                    val hook = listOf(
                        "    # OneOS: guest ShutdownThread entry blocked; notify host watcher via oneos.guest.powerctl",
                        "    const-string v0, \"OneOS\"",
                        "    const-string v1, \"guest ShutdownThread.${span.signature.substringBefore('(')} blocked\"",
                        "    invoke-static {v0, v1}, Landroid/util/Slog;->w(Ljava/lang/String;Ljava/lang/String;)I",
                        "    const-string v0, \"oneos.guest.powerctl\"",
                        "    const-string v1, \"reboot,guest\"",
                        "    invoke-static {v0, v1}, Landroid/os/SystemProperties;->set(Ljava/lang/String;Ljava/lang/String;)V",
                        "    return-void",
                    )
                    lines.addAll(span.bodyStartLine, hook)
                    dirty = true
                    counts["ShutdownThread.entrypoints"] = (counts["ShutdownThread.entrypoints"] ?: 0) + 1
                    sites.add(PatchSite(desc, span.signature, "blocked ShutdownThread entry"))
                }
            }

            // 3. RecoverySystemService BCB / recovery reboot methods
            if (desc.endsWith("com/android/server/recoverysystem/RecoverySystemService;")) {
                val methods = SmaliEditUtils.findMethods(lines, desc)
                val spans = methods.entries
                    .filter { (sig, _) ->
                        sig.startsWith("setupBcb(") ||
                            sig.startsWith("clearBcb(") ||
                            sig.startsWith("rebootRecoveryWithCommand(")
                    }
                    .flatMap { it.value }
                for (span in spans.sortedByDescending { it.startLine }) {
                    if (span.signature.endsWith(")Z")) {
                        SmaliEditUtils.ensureLocalsAtLeast(lines, span, 1)
                        lines.add(span.bodyStartLine, "    const/4 v0, 0x0\n    return v0")
                    } else if (span.signature.endsWith(")V")) {
                        lines.add(span.bodyStartLine, "    return-void")
                    }
                    dirty = true
                    counts["RecoverySystemService.methods"] = (counts["RecoverySystemService.methods"] ?: 0) + 1
                    sites.add(PatchSite(desc, span.signature, "blocked RecoverySystemService BCB/reboot"))
                }
            }

            if (dirty) {
                tx.putSource(desc, lines.joinToString("\n"))
            }
        }

        if ((counts["sys.powerctl"] ?: 0) == 0 && (counts["ShutdownThread.entrypoints"] ?: 0) == 0) {
            throw PatchRefusedException(handler, "Neither sys.powerctl nor ShutdownThread found in JAR")
        }

        return PatchReport(
            entryId = entryId,
            handler = handler,
            applied = true,
            sites = sites,
            counts = counts,
        )
    }
}

object SysRqPatch : SmaliPatch {
    override val entryId = "safety.no_sysrq_crash"
    override val handler = "SysRqPatch"
    override val ruleVersion = 1
    override val isSafety = true

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(ClassSelector.DefinesMethod("doSysRq"))
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val sites = mutableListOf<PatchSite>()
        var patchedCount = 0

        for (desc in tx.descriptors()) {
            val src = tx.getSource(desc) ?: continue
            val lines = src.split("\n").toMutableList()
            val methods = SmaliEditUtils.findMethods(lines, desc)
            val spans = methods["doSysRq(C)V"].orEmpty()
            if (spans.isEmpty()) continue

            for (span in spans.sortedByDescending { it.startLine }) {
                SmaliEditUtils.ensureLocalsAtLeast(lines, span, 1)
                val hook = listOf(
                    "    # OneOS: never let the guest watchdog trigger a kernel sysrq crash",
                    "    return-void",
                )
                lines.addAll(span.bodyStartLine, hook)
                patchedCount++
                sites.add(PatchSite(desc, span.signature, "doSysRq returns void at entry"))
            }
            tx.putSource(desc, lines.joinToString("\n"))
        }

        if (args["jar"] != "oplus-services.jar" && patchedCount == 0) {
            throw PatchRefusedException(handler, "doSysRq(C)V not found in services.jar")
        }

        return PatchReport(
            entryId = entryId,
            handler = handler,
            applied = patchedCount > 0,
            sites = sites,
            counts = mapOf("doSysRq" to patchedCount),
        )
    }
}

object NoWeaverPatch : SmaliPatch {
    override val entryId = "safety.weaver_guard"
    override val handler = "NoWeaverPatch"
    override val ruleVersion = 1
    override val isSafety = true

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(
            ClassSelector.ClassSuffix("com/android/server/locksettings/SyntheticPasswordManager;"),
            ClassSelector.DefinesMethod("getWeaverServiceLocked"),
        )
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val sites = mutableListOf<PatchSite>()
        var count = 0

        for (desc in tx.descriptors()) {
            val src = tx.getSource(desc) ?: continue
            val lines = src.split("\n").toMutableList()
            val methods = SmaliEditUtils.findMethods(lines, desc)
            val spans = methods.entries
                .filter { it.key.startsWith("getWeaverServiceLocked(") }
                .flatMap { it.value }
            if (spans.isEmpty()) continue

            for (span in spans.sortedByDescending { it.startLine }) {
                SmaliEditUtils.ensureLocalsAtLeast(lines, span, 1)
                lines.add(
                    span.bodyStartLine,
                    "    # OneOS: never expose host weaver slots to guest SyntheticPasswordManager\n    const/4 v0, 0x0\n    return-object v0",
                )
                count++
                sites.add(PatchSite(desc, span.signature, "returns null at entry"))
            }
            tx.putSource(desc, lines.joinToString("\n"))
        }

        if (count == 0) {
            throw PatchRefusedException(handler, "getWeaverServiceLocked not found in SyntheticPasswordManager")
        }

        return PatchReport(entryId, handler, true, sites, mapOf("getWeaverServiceLocked" to count))
    }
}

object NoBiometricCleanupPatch : SmaliPatch {
    override val entryId = "safety.biometric_cleanup_guard"
    override val handler = "NoBiometricCleanupPatch"
    override val ruleVersion = 1
    override val isSafety = true

    private const val CLS = "Lcom/android/server/biometrics/sensors/InternalCleanupClient;"
    private const val BASE = "Lcom/android/server/biometrics/sensors/BaseClientMonitor;"
    private const val CB = "Lcom/android/server/biometrics/sensors/ClientMonitorCallback;"

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(ClassSelector.ClassSuffix("com/android/server/biometrics/sensors/InternalCleanupClient;"))
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val desc = tx.descriptors().firstOrNull { it.endsWith("InternalCleanupClient;") }
            ?: throw PatchRefusedException(handler, "InternalCleanupClient not found")
        val src = tx.getSource(desc)!!
        val lines = src.split("\n").toMutableList()
        val methods = SmaliEditUtils.findMethods(lines, desc)
        val spans = methods["startCleanupUnknownHalTemplates()V"].orEmpty()
        if (spans.size != 1) {
            throw PatchRefusedException(handler, "Expected startCleanupUnknownHalTemplates()V once, found ${spans.size}")
        }
        val span = spans.single()
        SmaliEditUtils.ensureLocalsAtLeast(lines, span, 2)
        val callbackOwner = if (src.contains("$CLS->mCallback:$CB")) CLS else BASE
        val hook = listOf(
            "    # OneOS: unknown HAL biometric templates belong to One UI; clear list without removing from HAL",
            "    iget-object v0, p0, $CLS->mUnknownHALTemplates:Ljava/util/ArrayList;",
            "    invoke-virtual {v0}, Ljava/util/ArrayList;->clear()V",
            "    const-string v0, \"InternalCleanupClient\"",
            "    const-string v1, \"OneOS: unknown HAL templates are the phone's (One UI's); none removed\"",
            "    invoke-static {v0, v1}, Landroid/util/Slog;->w(Ljava/lang/String;Ljava/lang/String;)I",
            "    iget-object v0, p0, $callbackOwner->mCallback:$CB",
            "    const/4 v1, 0x1",
            "    invoke-interface {v0, p0, v1}, $CB->onClientFinished(${BASE}Z)V",
            "    return-void",
        )
        lines.addAll(span.bodyStartLine, hook)
        tx.putSource(desc, lines.joinToString("\n"))
        return PatchReport(
            entryId = entryId,
            handler = handler,
            applied = true,
            sites = listOf(PatchSite(desc, span.signature, "clears mUnknownHALTemplates and finishes client")),
            counts = mapOf("startCleanupUnknownHalTemplates" to 1),
        )
    }
}

object NoGatekeeperPatch : SmaliPatch {
    override val entryId = "safety.gatekeeper_guard"
    override val handler = "NoGatekeeperPatch"
    override val ruleVersion = 1
    override val isSafety = true

    private const val GK = "Landroid/service/gatekeeper/IGateKeeperService;"
    private const val SLOG_MSG = "OneOS: the screen lock is the phone's gatekeeper; not set in the guest"

    private val ENROLL_RE = Regex(
        """^\s*invoke-interface(?:/range)? \{[^}]*\}, Landroid/service/gatekeeper/IGateKeeperService;->enroll\(I\[B\[B\[B\)Landroid/service/gatekeeper/GateKeeperResponse;\s*$"""
    )
    private val CLEAR_RE = Regex(
        """^\s*invoke-interface(?:/range)? \{[^}]*\}, Landroid/service/gatekeeper/IGateKeeperService;->clearSecureUserId\(I\)V\s*$"""
    )
    private val REPORT_RE = Regex(
        """^\s*invoke-interface(?:/range)? \{[^}]*\}, Landroid/service/gatekeeper/IGateKeeperService;->reportDeviceSetupComplete\(\)V\s*$"""
    )

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(
            ClassSelector.ClassSuffix("com/android/server/locksettings/LockSettingsService;"),
            ClassSelector.Invokes("enroll", GK),
            ClassSelector.Invokes("clearSecureUserId", GK),
            ClassSelector.Invokes("reportDeviceSetupComplete", GK),
        )
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val sites = mutableListOf<PatchSite>()
        var enrollCount = 0
        var clearCount = 0
        var reportCount = 0
        var lockMethodsGuarded = 0

        for (desc in tx.descriptors()) {
            if (!desc.startsWith("Lcom/android/server/locksettings/")) continue
            val src = tx.getSource(desc) ?: continue
            val lines = src.split("\n").toMutableList()
            var dirty = false

            for (i in lines.indices) {
                val line = lines[i]
                when {
                    ENROLL_RE.matches(line) -> {
                        val replacedResult = SmaliEditUtils.dropInvokeAndResult(
                            lines,
                            i,
                            "OneOS: IGateKeeperService->enroll dropped",
                            "0x0",
                        )
                        if (!replacedResult) {
                            throw PatchRefusedException(handler, "$desc: enroll at line ${i + 1} not followed by move-result-object")
                        }
                        enrollCount++
                        dirty = true
                        sites.add(PatchSite(desc, "enroll", "dropped IGateKeeperService.enroll"))
                    }
                    CLEAR_RE.matches(line) -> {
                        SmaliEditUtils.dropInvokeAndResult(lines, i, "OneOS: IGateKeeperService->clearSecureUserId dropped")
                        clearCount++
                        dirty = true
                        sites.add(PatchSite(desc, "clearSecureUserId", "dropped IGateKeeperService.clearSecureUserId"))
                    }
                    REPORT_RE.matches(line) -> {
                        SmaliEditUtils.dropInvokeAndResult(lines, i, "OneOS: IGateKeeperService->reportDeviceSetupComplete dropped")
                        reportCount++
                        dirty = true
                        sites.add(PatchSite(desc, "reportDeviceSetupComplete", "dropped IGateKeeperService.reportDeviceSetupComplete"))
                    }
                }
            }

            if (desc == "Lcom/android/server/locksettings/LockSettingsService;") {
                val methods = SmaliEditUtils.findMethods(lines, desc)
                val targetSigs = listOf(
                    "setLockCredentialInternal(Lcom/android/internal/widget/LockscreenCredential;Lcom/android/internal/widget/LockscreenCredential;IZ)Z",
                    "setLockCredentialWithTokenInternalLocked(Lcom/android/internal/widget/LockscreenCredential;J[BI)Z",
                )
                val spans = targetSigs.flatMap { sig ->
                    val found = methods[sig].orEmpty()
                    if (found.size != 1) {
                        throw PatchRefusedException(handler, "$desc: expected $sig once, found ${found.size}")
                    }
                    found
                }
                for (span in spans.sortedByDescending { it.startLine }) {
                    SmaliEditUtils.ensureLocalsAtLeast(lines, span, 2)
                    val hook = listOf(
                        "    # OneOS: the guest never sets or resets the phone's shared screen lock.",
                        "    const-string v0, \"LockSettingsService\"",
                        "",
                        "    const-string v1, \"$SLOG_MSG\"",
                        "",
                        "    invoke-static {v0, v1}, Landroid/util/Slog;->w(Ljava/lang/String;Ljava/lang/String;)I",
                        "",
                        "    const/4 v0, 0x0",
                        "",
                        "    return v0",
                        "",
                    )
                    lines.addAll(span.bodyStartLine, hook)
                    lockMethodsGuarded++
                    dirty = true
                    sites.add(PatchSite(desc, span.signature, "returns false at entry"))
                }
            }

            if (dirty) {
                tx.putSource(desc, lines.joinToString("\n"))
            }
        }

        if (lockMethodsGuarded != 2 || enrollCount == 0 || clearCount == 0 || reportCount == 0) {
            throw PatchRefusedException(
                handler,
                "Missing gatekeeper anchors: lockMethods=$lockMethodsGuarded, enroll=$enrollCount, clear=$clearCount, report=$reportCount",
            )
        }

        return PatchReport(
            entryId = entryId,
            handler = handler,
            applied = true,
            sites = sites,
            counts = mapOf(
                "setLockMethods" to lockMethodsGuarded,
                "enroll" to enrollCount,
                "clearSecureUserId" to clearCount,
                "reportDeviceSetupComplete" to reportCount,
            ),
        )
    }
}

object NoApexInstallsPatch : SmaliPatch {
    override val entryId = "safety.no_apex_installs"
    override val handler = "NoApexInstallsPatch"
    override val ruleVersion = 1
    override val isSafety = true

    private const val IMPL = "Lcom/android/server/pm/ApexManager\$ApexManagerImpl;"
    private const val PME = "Lcom/android/server/pm/PackageManagerException;"
    private const val WHY = "APEX changes are the phone's; not from the guest"

    private val RETURN_VOID = setOf(
        "markStagedSessionReady(I)V",
        "markStagedSessionSuccessful(I)V",
        "markBootCompleted()V",
        "reserveSpaceForCompressedApex(Landroid/apex/CompressedApexInfoList;)V",
    )
    private val RETURN_FALSE = setOf(
        "abortStagedSession(I)Z",
        "revertActiveSessions()Z",
        "snapshotCeData(IILjava/lang/String;)Z",
        "restoreCeData(IILjava/lang/String;)Z",
        "destroyDeSnapshots(I)Z",
        "destroyCeSnapshots(II)Z",
        "destroyCeSnapshotsNotSpecified(I[I)Z",
        "uninstallApex(Ljava/lang/String;)Z",
    )
    private val THROW_MAP = mapOf(
        "submitStagedSession(Landroid/apex/ApexSessionParams;)Landroid/apex/ApexInfoList;" to "-0x16",
        "installPackage(Ljava/io/File;Z)Landroid/apex/ApexInfo;" to "-0x6e",
    )

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(
            ClassSelector.ExactClass(IMPL),
            ClassSelector.ExactClass("Lcom/android/server/pm/ApexManager;"),
        )
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val src = tx.getSource(IMPL)
            ?: throw PatchRefusedException(handler, "$IMPL not found")
        val lines = src.split("\n").toMutableList()
        val methods = SmaliEditUtils.findMethods(lines, IMPL)
        val allTargets = listOf("isApexSupported()Z") + RETURN_VOID + RETURN_FALSE + THROW_MAP.keys
        val spans = allTargets.map { sig ->
            val found = methods[sig].orEmpty()
            if (found.size != 1) {
                throw PatchRefusedException(handler, "$IMPL: expected $sig once, found ${found.size}")
            }
            found.single()
        }

        val sites = mutableListOf<PatchSite>()
        for (span in spans.sortedByDescending { it.startLine }) {
            val sig = span.signature
            val name = sig.substringBefore('(')
            when {
                sig == "isApexSupported()Z" -> {
                    SmaliEditUtils.ensureLocalsAtLeast(lines, span, 1)
                    lines.addAll(
                        span.bodyStartLine,
                        listOf(
                            "    # OneOS: no APEX sessions in the guest; apexd is the phone's (One UI's).",
                            "    const/4 v0, 0x0",
                            "    return v0",
                        )
                    )
                }
                sig in RETURN_VOID -> {
                    SmaliEditUtils.ensureLocalsAtLeast(lines, span, 2)
                    lines.addAll(
                        span.bodyStartLine,
                        listOf(
                            "    const-string v0, \"ApexManager\"",
                            "    const-string v1, \"OneOS: $name() skipped: $WHY\"",
                            "    invoke-static {v0, v1}, Landroid/util/Slog;->w(Ljava/lang/String;Ljava/lang/String;)I",
                            "    return-void",
                        )
                    )
                }
                sig in RETURN_FALSE -> {
                    SmaliEditUtils.ensureLocalsAtLeast(lines, span, 2)
                    lines.addAll(
                        span.bodyStartLine,
                        listOf(
                            "    const-string v0, \"ApexManager\"",
                            "    const-string v1, \"OneOS: $name() skipped: $WHY\"",
                            "    invoke-static {v0, v1}, Landroid/util/Slog;->w(Ljava/lang/String;Ljava/lang/String;)I",
                            "    const/4 v0, 0x0",
                            "    return v0",
                        )
                    )
                }
                sig in THROW_MAP -> {
                    val codeHex = THROW_MAP.getValue(sig)
                    SmaliEditUtils.ensureLocalsAtLeast(lines, span, 3)
                    lines.addAll(
                        span.bodyStartLine,
                        listOf(
                            "    const-string v0, \"ApexManager\"",
                            "    const-string v1, \"OneOS: $name() refused: $WHY\"",
                            "    invoke-static {v0, v1}, Landroid/util/Slog;->w(Ljava/lang/String;Ljava/lang/String;)I",
                            "    new-instance v0, $PME",
                            "    const/16 v1, $codeHex",
                            "    const-string v2, \"OneOS: $WHY\"",
                            "    invoke-direct {v0, v1, v2}, $PME-><init>(ILjava/lang/String;)V",
                            "    throw v0",
                        )
                    )
                }
            }
            sites.add(PatchSite(IMPL, sig, "guarded at entry"))
        }

        tx.putSource(IMPL, lines.joinToString("\n"))
        return PatchReport(
            entryId = entryId,
            handler = handler,
            applied = true,
            sites = sites,
            counts = mapOf("ApexManagerImpl.guarded" to sites.size),
        )
    }
}

object AppIdOffsetPatch : SmaliPatch {
    override val entryId = "safety.app_id_offset"
    override val handler = "AppIdOffsetPatch"
    override val ruleVersion = 1
    override val isSafety = true

    private const val CLS = "Lcom/android/server/pm/AppIdSettingMap;"
    private const val WAL = "Lcom/android/server/utils/WatchedArrayList;"
    private const val SIG = "acquireAndRegisterNewAppId(Lcom/android/server/pm/SettingBase;)I"

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(ClassSelector.ExactClass(CLS))
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val src = tx.getSource(CLS)
            ?: throw PatchRefusedException(handler, "$CLS not found")
        if (src.contains("oneos_appid")) {
            throw PatchRefusedException(handler, "$CLS already patched with oneos_appid")
        }
        val lines = src.split("\n").toMutableList()
        val methods = SmaliEditUtils.findMethods(lines, CLS)
        val spans = methods[SIG].orEmpty()
        if (spans.size != 1) {
            throw PatchRefusedException(handler, "$CLS: expected $SIG once, found ${spans.size}")
        }
        val span = spans.single()
        if (span.localsCount < 2) {
            throw PatchRefusedException(handler, "$CLS->$SIG: expected .locals >= 2, got ${span.localsCount}")
        }

        val hook = listOf(
            "    # OneOS: keystore2 is One UI's and keys live under the app's uid; One UI's apps",
            "    # hold 10000 and up, so the guest's new app ids start at 15000 (index 5000).",
            "    iget v0, p0, $CLS->mFirstAvailableAppId:I",
            "",
            "    const/16 v1, 0x3a98",
            "",
            "    if-ge v0, v1, :oneos_appid_first_ok",
            "",
            "    iput v1, p0, $CLS->mFirstAvailableAppId:I",
            "",
            "    :oneos_appid_first_ok",
            "    iget-object v0, p0, $CLS->mNonSystemSettings:$WAL",
            "",
            "    invoke-virtual {v0}, $WAL->size()I",
            "",
            "    move-result v1",
            "",
            "    add-int/lit16 v1, v1, -0x1388",
            "",
            "    if-gez v1, :oneos_appid_padded",
            "",
            "    const-string v0, \"PackageManager\"",
            "",
            "    const-string v1, \"OneOS: new app ids start at 15000, above One UI's apps (shared keystore2)\"",
            "",
            "    invoke-static {v0, v1}, Landroid/util/Slog;->i(Ljava/lang/String;Ljava/lang/String;)I",
            "",
            "    :oneos_appid_pad",
            "    iget-object v0, p0, $CLS->mNonSystemSettings:$WAL",
            "",
            "    invoke-virtual {v0}, $WAL->size()I",
            "",
            "    move-result v1",
            "",
            "    add-int/lit16 v1, v1, -0x1388",
            "",
            "    if-gez v1, :oneos_appid_padded",
            "",
            "    const/4 v1, 0x0",
            "",
            "    invoke-virtual {v0, v1}, $WAL->add(Ljava/lang/Object;)Z",
            "",
            "    goto :oneos_appid_pad",
            "",
            "    :oneos_appid_padded",
        )
        lines.addAll(span.bodyStartLine, hook)
        tx.putSource(CLS, lines.joinToString("\n"))

        return PatchReport(
            entryId = entryId,
            handler = handler,
            applied = true,
            sites = listOf(PatchSite(CLS, SIG, "pads mNonSystemSettings to index 5000 and raises mFirstAvailableAppId to 15000")),
            counts = mapOf("firstAppId" to 15000),
        )
    }
}

object KsuManagerAppIdPatch : SmaliPatch {
    override val entryId = "safety.ksu_manager_appid"
    override val handler = "KsuManagerAppIdPatch"
    override val ruleVersion = 1
    override val isSafety = true

    private const val CLS = "Lcom/android/server/pm/AppIdSettingMap;"
    private const val PS = "Lcom/android/server/pm/PackageSetting;"
    private const val SB = "Lcom/android/server/pm/SettingBase;"
    private const val SP = "Landroid/os/SystemProperties;"
    private const val SIG = "acquireAndRegisterNewAppId(Lcom/android/server/pm/SettingBase;)I"

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(ClassSelector.ExactClass(CLS), ClassSelector.ExactClass(PS))
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val src = tx.getSource(CLS)
            ?: throw PatchRefusedException(handler, "$CLS not found")
        if (src.contains("oneos_ksu_mgr")) {
            throw PatchRefusedException(handler, "$CLS already patched with oneos_ksu_mgr")
        }
        val lines = src.split("\n").toMutableList()
        val methods = SmaliEditUtils.findMethods(lines, CLS)
        if (!methods.containsKey("registerExistingAppId(ILcom/android/server/pm/SettingBase;Ljava/lang/Object;)Z")) {
            throw PatchRefusedException(handler, "$CLS missing registerExistingAppId(int, SettingBase, Object)")
        }
        val span = methods[SIG]?.singleOrNull()
            ?: throw PatchRefusedException(handler, "$CLS missing single $SIG")
        SmaliEditUtils.ensureLocalsAtLeast(lines, span, 3)

        val hook = listOf(
            "    # OneOS: the KernelSU manager keeps One UI's app id (oneos.ksu.mgr_pkg / oneos.ksu.mgr_appid).",
            "    const-string v0, \"oneos.ksu.mgr_pkg\"",
            "    invoke-static {v0}, $SP->get(Ljava/lang/String;)Ljava/lang/String;",
            "    move-result-object v0",
            "    invoke-virtual {v0}, Ljava/lang/String;->isEmpty()Z",
            "    move-result v1",
            "    if-nez v1, :oneos_ksu_mgr_done",
            "    instance-of v1, p1, $PS",
            "    if-eqz v1, :oneos_ksu_mgr_done",
            "    move-object v1, p1",
            "    check-cast v1, $PS",
            "    invoke-virtual {v1}, $PS->getPackageName()Ljava/lang/String;",
            "    move-result-object v1",
            "    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
            "    move-result v0",
            "    if-eqz v0, :oneos_ksu_mgr_done",
            "    const-string v0, \"oneos.ksu.mgr_appid\"",
            "    const/4 v2, 0x0",
            "    invoke-static {v0, v2}, $SP->getInt(Ljava/lang/String;I)I",
            "    move-result v0",
            "    const/16 v2, 0x2710",
            "    if-lt v0, v2, :oneos_ksu_mgr_done",
            "    const/16 v2, 0x3a98",
            "    if-ge v0, v2, :oneos_ksu_mgr_done",
            "    invoke-virtual {p0, v0, p1, v1}, $CLS->registerExistingAppId(I${SB}Ljava/lang/Object;)Z",
            "    move-result v2",
            "    if-eqz v2, :oneos_ksu_mgr_done",
            "    const-string v1, \"PackageManager\"",
            "    const-string v2, \"OneOS: KernelSU manager keeps One UI's app id\"",
            "    invoke-static {v1, v2}, Landroid/util/Slog;->i(Ljava/lang/String;Ljava/lang/String;)I",
            "    return v0",
            "    :oneos_ksu_mgr_done",
        )
        lines.addAll(span.bodyStartLine, hook)
        tx.putSource(CLS, lines.joinToString("\n"))

        return PatchReport(
            entryId = entryId,
            handler = handler,
            applied = true,
            sites = listOf(PatchSite(CLS, SIG, "preserves KernelSU manager appId in 10000..14999")),
            counts = mapOf("ksuManagerHook" to 1),
        )
    }
}

object NoNewUsersPatch : SmaliPatch {
    override val entryId = "safety.no_new_users"
    override val handler = "NoNewUsersPatch"
    override val ruleVersion = 1
    override val isSafety = true

    private const val UMS = "Lcom/android/server/pm/UserManagerService;"

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(ClassSelector.ExactClass(UMS))
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val src = tx.getSource(UMS)
            ?: throw PatchRefusedException(handler, "$UMS not found")
        val lines = src.split("\n").toMutableList()
        val methods = SmaliEditUtils.findMethods(lines, UMS)
        val spans = methods.entries
            .filter { (sig, _) ->
                (sig.startsWith("createUserInternalUnchecked(") || sig.startsWith("createUserEvenWhenDisallowed(") || sig.startsWith("preCreateUser(")) &&
                    sig.endsWith(")Landroid/content/pm/UserInfo;")
            }
            .flatMap { it.value }
        if (spans.isEmpty()) {
            throw PatchRefusedException(handler, "$UMS: no createUserInternalUnchecked/preCreateUser found")
        }

        val sites = mutableListOf<PatchSite>()
        for (span in spans.sortedByDescending { it.startLine }) {
            SmaliEditUtils.ensureLocalsAtLeast(lines, span, 2)
            lines.addAll(
                span.bodyStartLine,
                listOf(
                    "    # OneOS: guest cannot create secondary users or profiles (shared keystore per-user keys)",
                    "    const-string v0, \"UserManagerService\"",
                    "    const-string v1, \"OneOS: user/profile creation blocked in guest\"",
                    "    invoke-static {v0, v1}, Landroid/util/Slog;->w(Ljava/lang/String;Ljava/lang/String;)I",
                    "    const/4 v0, 0x0",
                    "    return-object v0",
                )
            )
            sites.add(PatchSite(UMS, span.signature, "returns null at entry"))
        }
        tx.putSource(UMS, lines.joinToString("\n"))
        return PatchReport(entryId, handler, true, sites, mapOf("userCreateMethods" to sites.size))
    }
}

object NoWipeSecretsPatch : SmaliPatch {
    override val entryId = "safety.no_wipe_secrets"
    override val handler = "NoWipeSecretsPatch"
    override val ruleVersion = 1
    override val isSafety = true

    private val SECRET_CALLS = Regex(
        """^\s*invoke-interface(?:/range)? \{[^}]*\}, (?:Landroid/hardware/security/secretkeeper/ISecretkeeper;|Landroid/os/IVold;)->(deleteIds|deleteAll|destroyDsuMetadataKey)\("""
    )

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(
            ClassSelector.Invokes("deleteIds", "ISecretkeeper;"),
            ClassSelector.Invokes("deleteAll", "ISecretkeeper;"),
            ClassSelector.Invokes("destroyDsuMetadataKey", "Landroid/os/IVold;"),
        )
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val sites = mutableListOf<PatchSite>()
        var count = 0
        for (desc in tx.descriptors()) {
            val src = tx.getSource(desc) ?: continue
            val lines = src.split("\n").toMutableList()
            var dirty = false
            for (i in lines.indices) {
                val m = SECRET_CALLS.find(lines[i]) ?: continue
                val mName = m.groupValues[1]
                SmaliEditUtils.dropInvokeAndResult(lines, i, "OneOS: secret wipe $mName dropped")
                dirty = true
                count++
                sites.add(PatchSite(desc, mName, "dropped secret wipe call"))
            }
            if (dirty) tx.putSource(desc, lines.joinToString("\n"))
        }
        return PatchReport(entryId, handler, true, sites, mapOf("secretWipeCalls" to count))
    }
}

object NoUpdateEnginePatch : SmaliPatch {
    override val entryId = "safety.no_update_engine"
    override val handler = "NoUpdateEnginePatch"
    override val ruleVersion = 1
    override val isSafety = true

    private val UE_CALLS = Regex(
        """^\s*invoke-virtual(?:/range)? \{[^}]*\}, Landroid/os/UpdateEngine;->(bind|applyPayload|cancel|resetStatus)\("""
    )

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        return listOf(
            ClassSelector.Invokes("bind", "Landroid/os/UpdateEngine;"),
            ClassSelector.Invokes("applyPayload", "Landroid/os/UpdateEngine;"),
        )
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val sites = mutableListOf<PatchSite>()
        var count = 0
        for (desc in tx.descriptors()) {
            val src = tx.getSource(desc) ?: continue
            val lines = src.split("\n").toMutableList()
            var dirty = false
            for (i in lines.indices) {
                val m = UE_CALLS.find(lines[i]) ?: continue
                val mName = m.groupValues[1]
                SmaliEditUtils.dropInvokeAndResult(lines, i, "OneOS: UpdateEngine.$mName dropped", "0x0")
                dirty = true
                count++
                sites.add(PatchSite(desc, mName, "dropped UpdateEngine.$mName"))
            }
            if (dirty) tx.putSource(desc, lines.joinToString("\n"))
        }
        return PatchReport(entryId, handler, true, sites, mapOf("updateEngineCalls" to count))
    }
}
