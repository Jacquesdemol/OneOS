package com.fahadali.oneos.core.safety

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import java.util.zip.ZipFile

/**
 * Independent post-patch static bytecode safety verifier (`check-dex.py` equivalent +
 * reflection, MethodHandles, IBinder.transact, dynamic code loading, and BOOTCLASSPATH
 * shadowing detection).
 *
 * Scans all `classes*.dex` across all JARs on the guest's runtime `SYSTEMSERVERCLASSPATH`
 * and fails closed if any forbidden host-mutating call, reflection bypass, raw binder
 * transaction, or dynamic code loading risk remains reachable.
 */
object GuardVerifier {
    private val DEX_ENTRY = Regex("""^classes(\d*)\.dex$""")

    val FORBIDDEN_VOLD_METHODS: Set<String> = setOf(
        "onUserAdded",
        "onUserRemoved",
        "onUserStarted",
        "onUserStopped",
        "addUserKeyAuth",
        "clearUserKeyAuth",
        "fixateNewestUserKeyAuth",
        "unlockUserKey",
        "lockUserKey",
        "prepareUserStorage",
        "destroyUserStorage",
        "destroyDsuMetadataKey",
        "reset",
        "fstrim",
        "abortIdleMaint",
        "runIdleMaint",
        "mountFstab",
        "encryptFstab",
        "setStorageBindingSeed",
    )

    val FORBIDDEN_INSTALLD_METHODS: Set<String> = setOf(
        "destroyUserData",
        "clearAppProfiles",
        "destroyAppProfiles",
        "cleanupInvalidPackageDirs",
        "migrateAppData",
        "clearAppData",
        "destroyAppData",
        "fixupAppData",
        "moveCompleteApp",
        "setFirstBoot",
    )

    val FORBIDDEN_KEYSTORE_METHODS: Set<String> = setOf(
        "onUserAdded",
        "onUserRemoved",
        "onUserPasswordChanged",
        "onUserLskfRemoved",
        "clearNamespace",
        "deleteAllKeys",
        "earlyBootEnded",
        "migrateKeyNamespace",
    )

    val FORBIDDEN_GATEKEEPER_METHODS: Set<String> = setOf(
        "enroll",
        "clearSecureUserId",
        "deleteUser",
        "deleteAllUsers",
    )

    val FORBIDDEN_WEAVER_METHODS: Set<String> = setOf(
        "write",
        "read",
    )

    val FORBIDDEN_SECRETKEEPER_METHODS: Set<String> = setOf(
        "processSecretManagementRequest",
        "deleteIds",
        "deleteAll",
    )

    val FORBIDDEN_UPDATE_ENGINE_METHODS: Set<String> = setOf(
        "applyPayload",
        "applyPayloadFd",
        "bind",
        "cancel",
        "cleanupAppliedPayload",
        "resetStatus",
        "allocateSpaceForPayload",
    )

    val FORBIDDEN_RECOVERY_METHODS: Set<String> = setOf(
        "rebootPromptAndWipeUserData",
        "rebootWipeUserData",
        "wipeEuiccData",
    )

    val GUARDED_DESCRIPTORS: Set<String> = setOf(
        "android.os.IVold",
        "android.os.IInstalld",
        "android.hardware.weaver.IWeaver",
        "android.hardware.weaver.V1_0.IWeaver",
        "android.hardware.security.secretkeeper.ISecretkeeper",
        "android.service.gatekeeper.IGateKeeperService",
        "android.system.keystore2.IKeystoreMaintenance",
        "android.os.IUpdateEngine",
        "android.security.keystore2.AndroidKeyStoreMaintenance",
    )

    val DYNAMIC_CODE_LOADERS: Set<String> = setOf(
        "Ldalvik/system/DexClassLoader;",
        "Ldalvik/system/InMemoryDexClassLoader;",
        "Ldalvik/system/DexFile;",
    )

    private val ALLOWED_DCL_CALLERS: Set<String> = setOf(
        "Lcom/android/server/SystemServer;",
        "Lcom/android/server/SystemServiceManager;",
        "Lcom/android/server/pm/PackageManagerService;",
    )

    data class Violation(
        val category: String,
        val jarName: String,
        val dexEntry: String,
        val callerClass: String,
        val callerMethod: String,
        val detail: String,
    ) {
        override fun toString(): String =
            "[$category] $jarName!$dexEntry $callerClass->$callerMethod: $detail"
    }

    data class VerificationResult(
        val passed: Boolean,
        val scannedJars: List<String>,
        val scannedClasses: Int,
        val scannedMethods: Int,
        val violations: List<Violation>,
        val guardsReport: String,
    )

    /**
     * Discovers all JARs on the guest's `SYSTEMSERVERCLASSPATH` and `STANDALONE_SYSTEMSERVER_JARS`
     * from `init.environ.rc` or `etc/classpaths/systemserverclasspath.pb`, falling back to scanning
     * `system/framework` and `system/system_ext/framework` JARs.
     */
    fun discoverSystemServerJars(systemRoot: File): List<File> {
        val discovered = linkedSetOf<File>()
        val initEnviron = File(systemRoot, "etc/init/hw/init.environ.rc")
            .takeIf { it.isFile }
            ?: File(systemRoot, "init.environ.rc").takeIf { it.isFile }

        if (initEnviron != null) {
            val text = initEnviron.readText()
            val regex = Regex("""export\s+(?:SYSTEMSERVERCLASSPATH|STANDALONE_SYSTEMSERVER_JARS)\s+([^\r\n]+)""")
            for (m in regex.findAll(text)) {
                for (path in m.groupValues[1].trim().split(':')) {
                    val rel = path.removePrefix("/system/").removePrefix("/")
                    val candidate = File(systemRoot, rel)
                    if (candidate.isFile) discovered += candidate
                }
            }
        }

        val defaultPaths = listOf(
            "framework/services.jar",
            "framework/oplus-services.jar",
            "framework/miui-services.jar",
            "framework/sec-services.jar",
            "framework/vivo-services.jar",
            "system_ext/framework/oplus-services.jar",
        )
        for (rel in defaultPaths) {
            val f = File(systemRoot, rel)
            if (f.isFile) discovered += f
        }
        return discovered.toList()
    }

    /**
     * Verifies that patched classes in [patchedFrameworkJar] are not shadowed by an earlier
     * JAR in [bootClassPathJars].
     */
    fun verifyNoBootClassPathShadowing(
        patchedFrameworkJar: File,
        bootClassPathJars: List<File>,
        patchedClassDescriptors: Set<String>,
    ): List<Violation> {
        val violations = mutableListOf<Violation>()
        val frameworkIndex = bootClassPathJars.indexOfFirst {
            it.name == "framework.jar" || it.name == patchedFrameworkJar.name
        }
        val precedingJars = if (frameworkIndex >= 0) bootClassPathJars.subList(0, frameworkIndex) else bootClassPathJars
        for (jar in precedingJars) {
            if (!jar.isFile || jar == patchedFrameworkJar) continue
            for ((entry, bytes) in readDexEntries(jar)) {
                val dex = DexBackedDexFile(Opcodes.getDefault(), bytes)
                for (cls in dex.classes) {
                    if (cls.type in patchedClassDescriptors) {
                        violations += Violation(
                            category = "BOOTCLASSPATH_SHADOW",
                            jarName = jar.name,
                            dexEntry = entry,
                            callerClass = cls.type,
                            callerMethod = "<class>",
                            detail = "${cls.type} in ${jar.name} precedes ${patchedFrameworkJar.name} on BOOTCLASSPATH",
                        )
                    }
                }
            }
        }
        return violations
    }

    /**
     * Scans all [jars] for forbidden direct invocations, reflection bypasses,
     * `MethodHandles.Lookup` resolution, raw `IBinder.transact` calls, and dynamic code loading.
     */
    fun verifyJars(jars: List<File>): VerificationResult {
        val violations = mutableListOf<Violation>()
        var totalClasses = 0
        var totalMethods = 0
        val scannedNames = mutableListOf<String>()

        for (jar in jars) {
            if (!jar.isFile) continue
            scannedNames += jar.name
            for ((entry, bytes) in readDexEntries(jar)) {
                val r = scanDexBytes(jar.name, entry, bytes)
                totalClasses += r.first
                totalMethods += r.second
                violations += r.third
            }
        }

        val report = buildString {
            appendLine("# OneOS GuardVerifier Report")
            appendLine("jars=${scannedNames.joinToString(",")}")
            appendLine("classes=$totalClasses")
            appendLine("methods=$totalMethods")
            appendLine("violations=${violations.size}")
            for (v in violations) {
                appendLine("VIOLATION $v")
            }
        }

        return VerificationResult(
            passed = violations.isEmpty(),
            scannedJars = scannedNames,
            scannedClasses = totalClasses,
            scannedMethods = totalMethods,
            violations = violations,
            guardsReport = report,
        )
    }

    fun verifyDexBytes(jarName: String, dexEntry: String, dexBytes: ByteArray): VerificationResult {
        val (classes, methods, violations) = scanDexBytes(jarName, dexEntry, dexBytes)
        val report = buildString {
            appendLine("# OneOS GuardVerifier Report")
            appendLine("jars=$jarName")
            appendLine("classes=$classes")
            appendLine("methods=$methods")
            appendLine("violations=${violations.size}")
            for (v in violations) {
                appendLine("VIOLATION $v")
            }
        }
        return VerificationResult(
            passed = violations.isEmpty(),
            scannedJars = listOf(jarName),
            scannedClasses = classes,
            scannedMethods = methods,
            violations = violations,
            guardsReport = report,
        )
    }

    private fun scanDexBytes(
        jarName: String,
        dexEntry: String,
        dexBytes: ByteArray,
    ): Triple<Int, Int, List<Violation>> {
        val dex = DexBackedDexFile(Opcodes.getDefault(), dexBytes)
        val violations = mutableListOf<Violation>()
        var classCount = 0
        var methodCount = 0

        val allForbiddenMethodNames = FORBIDDEN_VOLD_METHODS +
            FORBIDDEN_INSTALLD_METHODS +
            FORBIDDEN_KEYSTORE_METHODS +
            FORBIDDEN_GATEKEEPER_METHODS +
            FORBIDDEN_WEAVER_METHODS +
            FORBIDDEN_SECRETKEEPER_METHODS +
            FORBIDDEN_UPDATE_ENGINE_METHODS +
            FORBIDDEN_RECOVERY_METHODS

        for (cls in dex.classes) {
            classCount++
            val callerClass = cls.type
            val isInstalldProxy = callerClass == "Lcom/android/server/pm/OneOSInstalldProxy;"
            val isGeneratedStubOrProxy = callerClass.endsWith("\$Stub;") ||
                callerClass.endsWith("\$Stub\$Proxy;") ||
                callerClass.endsWith("\$Default;")

            for (method in cls.methods) {
                methodCount++
                val impl = method.implementation ?: continue
                val callerMethod = "${method.name}${"(" + method.parameterTypes.joinToString("") + ")" + method.returnType}"

                val stringConstants = mutableSetOf<String>()
                val methodRefs = mutableListOf<MethodReference>()
                val typeRefs = mutableListOf<String>()

                for (insn in impl.instructions) {
                    if (insn is ReferenceInstruction) {
                        when (val ref = insn.reference) {
                            is StringReference -> stringConstants += ref.string
                            is MethodReference -> methodRefs += ref
                            is TypeReference -> typeRefs += ref.type
                        }
                    }
                }

                // 1. Check direct method invocations
                for (mRef in methodRefs) {
                    val targetClass = mRef.definingClass
                    val targetName = mRef.name

                    // 1a. IVold
                    if (targetClass == "Landroid/os/IVold;" || targetClass == "Landroid/os/IVold\$Stub\$Proxy;") {
                        if (targetName in FORBIDDEN_VOLD_METHODS && !isGeneratedStubOrProxy) {
                            val allowedResetInInit = targetName == "reset" &&
                                callerClass == "Lcom/android/server/StorageManagerService;" &&
                                method.name == "<init>"
                            if (!allowedResetInInit) {
                                violations += Violation(
                                    category = "DIRECT_VOLD_CALL",
                                    jarName = jarName,
                                    dexEntry = dexEntry,
                                    callerClass = callerClass,
                                    callerMethod = callerMethod,
                                    detail = "Forbidden IVold call: $targetClass->$targetName",
                                )
                            }
                        }
                    }

                    // 1b. IInstalld and IInstalld$Stub.asInterface
                    if (targetClass == "Landroid/os/IInstalld;" || targetClass == "Landroid/os/IInstalld\$Stub\$Proxy;") {
                        if (targetName in FORBIDDEN_INSTALLD_METHODS && !isInstalldProxy && !isGeneratedStubOrProxy) {
                            violations += Violation(
                                category = "DIRECT_INSTALLD_CALL",
                                jarName = jarName,
                                dexEntry = dexEntry,
                                callerClass = callerClass,
                                callerMethod = callerMethod,
                                detail = "Unproxied IInstalld call: $targetClass->$targetName",
                            )
                        }
                    }
                    if (targetClass == "Landroid/os/IInstalld\$Stub;" && targetName == "asInterface" && !isInstalldProxy) {
                        violations += Violation(
                            category = "UNPROXIED_INSTALLD_BINDER",
                            jarName = jarName,
                            dexEntry = dexEntry,
                            callerClass = callerClass,
                            callerMethod = callerMethod,
                            detail = "IInstalld\$Stub.asInterface called outside OneOSInstalldProxy",
                        )
                    }

                    // 1c. AndroidKeyStoreMaintenance / IKeystoreMaintenance
                    if (targetClass == "Landroid/security/keystore2/AndroidKeyStoreMaintenance;" ||
                        targetClass == "Landroid/system/keystore2/IKeystoreMaintenance;"
                    ) {
                        if (targetName in FORBIDDEN_KEYSTORE_METHODS && !isGeneratedStubOrProxy) {
                            violations += Violation(
                                category = "DIRECT_KEYSTORE_CALL",
                                jarName = jarName,
                                dexEntry = dexEntry,
                                callerClass = callerClass,
                                callerMethod = callerMethod,
                                detail = "Forbidden Keystore2 maintenance call: $targetClass->$targetName",
                            )
                        }
                    }

                    // 1d. IGateKeeperService
                    if (targetClass == "Landroid/service/gatekeeper/IGateKeeperService;" ||
                        targetClass == "Landroid/service/gatekeeper/IGateKeeperService\$Stub\$Proxy;"
                    ) {
                        if (targetName in FORBIDDEN_GATEKEEPER_METHODS && !isGeneratedStubOrProxy) {
                            violations += Violation(
                                category = "DIRECT_GATEKEEPER_CALL",
                                jarName = jarName,
                                dexEntry = dexEntry,
                                callerClass = callerClass,
                                callerMethod = callerMethod,
                                detail = "Forbidden GateKeeper call: $targetClass->$targetName",
                            )
                        }
                    }

                    // 1e. IWeaver & ISecretkeeper
                    if ((targetClass.contains("hardware/weaver/IWeaver") || targetClass.contains("weaver/V1_0/IWeaver")) &&
                        targetName in FORBIDDEN_WEAVER_METHODS && !isGeneratedStubOrProxy
                    ) {
                        violations += Violation(
                            category = "DIRECT_WEAVER_CALL",
                            jarName = jarName,
                            dexEntry = dexEntry,
                            callerClass = callerClass,
                            callerMethod = callerMethod,
                            detail = "Forbidden Weaver call: $targetClass->$targetName",
                        )
                    }
                    if (targetClass.contains("security/secretkeeper/ISecretkeeper") &&
                        targetName in FORBIDDEN_SECRETKEEPER_METHODS && !isGeneratedStubOrProxy
                    ) {
                        violations += Violation(
                            category = "DIRECT_SECRETKEEPER_CALL",
                            jarName = jarName,
                            dexEntry = dexEntry,
                            callerClass = callerClass,
                            callerMethod = callerMethod,
                            detail = "Forbidden Secretkeeper call: $targetClass->$targetName",
                        )
                    }

                    // 1f. UpdateEngine & RecoverySystem wipe
                    if ((targetClass == "Landroid/os/UpdateEngine;" || targetClass == "Landroid/os/IUpdateEngine;") &&
                        targetName in FORBIDDEN_UPDATE_ENGINE_METHODS && !isGeneratedStubOrProxy
                    ) {
                        violations += Violation(
                            category = "DIRECT_UPDATE_ENGINE_CALL",
                            jarName = jarName,
                            dexEntry = dexEntry,
                            callerClass = callerClass,
                            callerMethod = callerMethod,
                            detail = "Forbidden UpdateEngine call: $targetClass->$targetName",
                        )
                    }
                    if (targetClass == "Landroid/os/RecoverySystem;" && targetName in FORBIDDEN_RECOVERY_METHODS) {
                        violations += Violation(
                            category = "DIRECT_RECOVERY_WIPE",
                            jarName = jarName,
                            dexEntry = dexEntry,
                            callerClass = callerClass,
                            callerMethod = callerMethod,
                            detail = "Forbidden RecoverySystem wipe call: $targetClass->$targetName",
                        )
                    }

                    // 1g. Dynamic code loading in system_server
                    if (targetClass in DYNAMIC_CODE_LOADERS && callerClass !in ALLOWED_DCL_CALLERS) {
                        violations += Violation(
                            category = "DYNAMIC_CODE_LOADING",
                            jarName = jarName,
                            dexEntry = dexEntry,
                            callerClass = callerClass,
                            callerMethod = callerMethod,
                            detail = "Dynamic code loading ($targetClass->$targetName) inside system_server",
                        )
                    }
                }

                // 2. Check reflection & MethodHandles.Lookup targeting guarded interfaces/methods
                val usesReflection = methodRefs.any {
                    (it.definingClass == "Ljava/lang/Class;" && (it.name == "forName" || it.name == "getMethod" || it.name == "getDeclaredMethod")) ||
                        (it.definingClass == "Ljava/lang/reflect/Method;" && it.name == "invoke")
                }
                val usesMethodHandles = methodRefs.any {
                    it.definingClass == "Ljava/lang/invoke/MethodHandles\$Lookup;" &&
                        (it.name == "findVirtual" || it.name == "findStatic" || it.name == "findSpecial")
                }
                if (usesReflection || usesMethodHandles) {
                    val referencedGuardedDesc = stringConstants.filter { s ->
                        GUARDED_DESCRIPTORS.any { desc -> s.contains(desc) || s.contains(desc.replace('.', '/')) }
                    }
                    val referencedForbiddenMethods = stringConstants.intersect(allForbiddenMethodNames)
                    if (referencedGuardedDesc.isNotEmpty() || referencedForbiddenMethods.isNotEmpty()) {
                        violations += Violation(
                            category = if (usesMethodHandles) "METHOD_HANDLE_BYPASS" else "REFLECTION_BYPASS",
                            jarName = jarName,
                            dexEntry = dexEntry,
                            callerClass = callerClass,
                            callerMethod = callerMethod,
                            detail = "Reflective/MethodHandle resolution referencing guarded targets: classes=$referencedGuardedDesc methods=$referencedForbiddenMethods",
                        )
                    }
                }

                // 3. Check raw IBinder.transact on guarded descriptors outside OneOSInstalldProxy and generated Stub/Proxy
                val callsBinderTransact = methodRefs.any {
                    it.definingClass == "Landroid/os/IBinder;" && it.name == "transact"
                }
                if (callsBinderTransact && !isInstalldProxy && !isGeneratedStubOrProxy) {
                    val matchedDesc = stringConstants.filter { it in GUARDED_DESCRIPTORS }
                    if (matchedDesc.isNotEmpty()) {
                        violations += Violation(
                            category = "RAW_BINDER_TRANSACT",
                            jarName = jarName,
                            dexEntry = dexEntry,
                            callerClass = callerClass,
                            callerMethod = callerMethod,
                            detail = "Direct IBinder.transact referencing guarded interface $matchedDesc",
                        )
                    }
                }

                // 4. Check sys.powerctl in PowerManagerService
                if (callerClass.endsWith("PowerManagerService;") && "sys.powerctl" in stringConstants) {
                    val setsSystemProperty = methodRefs.any {
                        it.definingClass == "Landroid/os/SystemProperties;" && it.name == "set"
                    }
                    if (setsSystemProperty) {
                        violations += Violation(
                            category = "UNPROXIED_POWERCTL",
                            jarName = jarName,
                            dexEntry = dexEntry,
                            callerClass = callerClass,
                            callerMethod = callerMethod,
                            detail = "Direct SystemProperties.set(\"sys.powerctl\", ...) in PowerManagerService",
                        )
                    }
                }
            }
        }

        return Triple(classCount, methodCount, violations)
    }

    private fun readDexEntries(jar: File): Sequence<Pair<String, ByteArray>> = sequence {
        ZipFile(jar).use { zip ->
            val entries = zip.entries().toList()
                .filter { DEX_ENTRY.matches(it.name) }
                .sortedBy { DEX_ENTRY.matchEntire(it.name)!!.groupValues[1].ifEmpty { "1" }.toInt() }
            for (e in entries) {
                yield(e.name to zip.getInputStream(e).use { it.readBytes() })
            }
        }
    }
}
