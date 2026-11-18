package com.fahadali.oneos.core.patch

import com.fahadali.oneos.core.dex.ClassSelector
import com.fahadali.oneos.core.dex.SmaliTree

/**
 * Port of `switcher/patches/guest_installd_abi.py`.
 *
 * In `proxy` mode (`services.jar`):
 * - Creates `Lcom/android/server/pm/OneOSInstalldProxy;` from `Landroid/os/IInstalld$Stub$Proxy;`.
 * - Remaps `ALLOWED` methods whose signature matches the host's `IInstalld` table to the host's
 *   transaction code.
 * - Throws `RemoteException("OneOS: installd.<name> blocked in the container")` at entry of all
 *   other methods (fail-closed: destructive methods, signature mismatches, unknown OEM methods).
 * - Redirects all `Landroid/os/IInstalld$Stub;->asInterface` call sites outside `Landroid/os/IInstalld*`
 *   to `Lcom/android/server/pm/OneOSInstalldProxy;->asInterface`.
 *
 * In `callers` mode (`oplus-services.jar`, `miui-services.jar`, `vivo-services.jar`):
 * - Redirects all `Landroid/os/IInstalld$Stub;->asInterface` call sites to `OneOSInstalldProxy`.
 */
object InstalldAbiPatch : SmaliPatch {
    override val entryId = "safety.installd_abi_proxy"
    override val handler = "InstalldAbiPatch"
    override val ruleVersion = 1
    override val isSafety = true

    val ALLOWED = setOf(
        "createUserData", "setFirstBoot", "createAppData", "createAppDataBatched",
        "restoreconAppData", "fixupAppData", "getAppSize", "getUserSize",
        "getExternalSize", "getAppCrates", "getUserCrates",
        "controlDexOptBlocking", "mergeProfiles", "dumpProfiles", "copySystemProfile",
        "createProfileSnapshot", "linkNativeLibraryDirectory", "createOatDirs",
        "linkFile", "hashSecondaryDexFile", "invalidateMounts", "isQuotaSupported",
        "prepareAppProfile", "tryMountDataMirror", "getOdexVisibility",
        "createFsveritySetupAuthToken", "enableFsverity",
    )

    const val STUB_DESC = "Landroid/os/IInstalld\$Stub;"
    const val PROXY_DESC = "Landroid/os/IInstalld\$Stub\$Proxy;"
    const val OWN_DESC = "Lcom/android/server/pm/OneOSInstalldProxy;"
    const val INSTALLER_DESC = "Lcom/android/server/pm/Installer;"
    private const val AS_INTERFACE = "->asInterface(Landroid/os/IBinder;)Landroid/os/IInstalld;"

    private val TRANSACTION_FIELD = Regex("""^\.field static final TRANSACTION_(\w+):I = (-?0x[0-9a-f]+|\d+)""")
    private val PROXY_METHOD = Regex("""^\.method public (?:final )?(\w+)(\([^)]*\)\S+)$""")
    private val TRANSACT_CALL = Regex(
        """^\s*invoke-interface \{([vp]\d+), ([vp]\d+), ([vp]\d+), ([vp]\d+), ([vp]\d+)\}, Landroid/os/IBinder;->transact\(ILandroid/os/Parcel;Landroid/os/Parcel;I\)Z\s*$"""
    )
    private val STUB_AS_INTERFACE_CALL = Regex(
        """^(\s*invoke-static(?:/range)? \{[^}]*\}, )Landroid/os/IInstalld\${'$'}Stub;->asInterface\(Landroid/os/IBinder;\)Landroid/os/IInstalld;\s*$"""
    )
    private val ENCLOSING_ANNOTATION = Regex(
        """(?ms)^\.annotation system Ldalvik/annotation/(?:EnclosingClass|InnerClass);\n.*?^\.end annotation\n"""
    )

    private val OWN_AS_INTERFACE_METHOD = """
        .method public static asInterface(Landroid/os/IBinder;)Landroid/os/IInstalld;
            .locals 1

            # OneOS: the host installd is only reached through this class.
            if-nez p0, :oneos_binder

            const/4 v0, 0x0

            return-object v0

            :oneos_binder
            new-instance v0, $OWN_DESC

            invoke-direct {v0, p0}, $OWN_DESC-><init>(Landroid/os/IBinder;)V

            return-object v0
        .end method
    """.trimIndent()

    override fun selectors(args: Map<String, String>): List<ClassSelector> {
        val mode = args["mode"] ?: "proxy"
        return if (mode == "callers") {
            listOf(
                ClassSelector.Invokes("asInterface", STUB_DESC),
                ClassSelector.Invokes("<init>", PROXY_DESC),
            )
        } else {
            listOf(
                ClassSelector.ExactClass(STUB_DESC),
                ClassSelector.ExactClass(PROXY_DESC),
                ClassSelector.ExactClass(INSTALLER_DESC),
                ClassSelector.Invokes("asInterface", STUB_DESC),
                ClassSelector.Invokes("<init>", PROXY_DESC),
            )
        }
    }

    override fun apply(tx: SmaliTree.Tx, args: Map<String, String>, ctx: PatchContext): PatchReport {
        val mode = args["mode"] ?: "proxy"
        if (mode == "callers") {
            val (sites, count) = redirectCallers(tx)
            return PatchReport(
                entryId = entryId,
                handler = handler,
                applied = true,
                sites = sites,
                counts = mapOf("redirectedCallers" to count),
            )
        }

        if (ctx.hostInstalldMethods.isEmpty()) {
            throw PatchRefusedException(handler, "Host installd table is empty; cannot build OneOSInstalldProxy")
        }
        if (tx.getSource(OWN_DESC) != null) {
            throw PatchRefusedException(handler, "$OWN_DESC already exists in SmaliTree")
        }

        val stubSrc = tx.getSource(STUB_DESC)
            ?: throw PatchRefusedException(handler, "$STUB_DESC not found in JAR")
        val proxySrc = tx.getSource(PROXY_DESC)
            ?: throw PatchRefusedException(handler, "$PROXY_DESC not found in JAR")
        val installerDexEntry = tx.getDexEntryName(INSTALLER_DESC)
            ?: throw PatchRefusedException(handler, "$INSTALLER_DESC not found in JAR")

        val guestCodes = LinkedHashMap<String, Int>()
        for (line in stubSrc.lineSequence()) {
            val m = TRANSACTION_FIELD.find(line.trim()) ?: continue
            val name = m.groupValues[1]
            val raw = m.groupValues[2]
            val code = if (raw.startsWith("0x") || raw.startsWith("-0x")) {
                Integer.decode(raw)
            } else {
                raw.toInt()
            }
            guestCodes[name] = code
        }
        if (guestCodes.isEmpty()) {
            throw PatchRefusedException(handler, "$STUB_DESC has no TRANSACTION_* fields")
        }

        val lines = proxySrc.split("\n").toMutableList()
        val remapped = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        val kept = mutableListOf<String>()

        var i = 0
        while (i < lines.size) {
            val m = PROXY_METHOD.matchEntire(lines[i])
            if (m == null || m.groupValues[1] !in guestCodes) {
                i++
                continue
            }
            val name = m.groupValues[1]
            val sig = m.groupValues[2]
            var end = i + 1
            while (end < lines.size && lines[end].trim() != ".end method") end++
            if (end >= lines.size) {
                throw PatchRefusedException(handler, "Method $name in $PROXY_DESC has no .end method")
            }

            val hostMethod = ctx.hostInstalldMethods[name]
            if (name in ALLOWED && hostMethod != null && hostMethod.signature == sig) {
                val transactIdx = (i..end).firstOrNull { TRANSACT_CALL.matches(lines[it]) }
                    ?: throw PatchRefusedException(handler, "$name: IBinder->transact not found")
                val codeReg = TRANSACT_CALL.matchEntire(lines[transactIdx])!!.groupValues[2]
                val constRe = Regex("""^\s*const(?:/4|/16)? ${Regex.escape(codeReg)}, """)
                val constIdx = (transactIdx - 1 downTo i).firstOrNull { constRe.containsMatchIn(lines[it]) }
                    ?: throw PatchRefusedException(handler, "$name: const loading $codeReg not found before transact")
                val guestCode = guestCodes.getValue(name)
                if (hostMethod.code != guestCode) {
                    lines[constIdx] = "    const/16 $codeReg, 0x${hostMethod.code.toString(16)}"
                    remapped.add(name)
                } else {
                    kept.add(name)
                }
                i = end + 1
            } else {
                var k = i + 1
                var localsIdx = -1
                var localsCount = 0
                var inAnnotation = false
                while (k < end) {
                    val s = lines[k].trim()
                    if (s.startsWith(".locals ")) {
                        localsIdx = k
                        localsCount = s.removePrefix(".locals ").trim().toInt()
                    }
                    if (s.startsWith(".annotation")) {
                        inAnnotation = true
                    } else if (s.startsWith(".end annotation")) {
                        inAnnotation = false
                    } else if (s.isNotEmpty() && !inAnnotation &&
                        !s.startsWith(".locals") && !s.startsWith(".param") &&
                        !s.startsWith(".end param") && !s.startsWith("#")
                    ) {
                        break
                    }
                    k++
                }
                if (localsIdx < 0) {
                    throw PatchRefusedException(handler, "$name: missing .locals directive")
                }
                if (localsCount < 2) {
                    lines[localsIdx] = "    .locals 2"
                }
                val throwBlock = listOf(
                    "    # OneOS: not sent to the host installd (different interface or not allowed).",
                    "    new-instance v0, Landroid/os/RemoteException;",
                    "",
                    "    const-string v1, \"OneOS: installd.$name blocked in the container\"",
                    "",
                    "    invoke-direct {v0, v1}, Landroid/os/RemoteException;-><init>(Ljava/lang/String;)V",
                    "",
                    "    throw v0",
                    "",
                )
                lines.addAll(k, throwBlock)
                blocked.add(name)
                i = end + throwBlock.size + 1
            }
        }

        val classified = (remapped + blocked + kept).toSet()
        val missing = guestCodes.keys - classified
        if (missing.isNotEmpty()) {
            throw PatchRefusedException(handler, "IInstalld methods without a proxy method: ${missing.sorted()}")
        }
        if ("destroyUserData" !in blocked || ("createAppData" !in kept && "createAppData" !in remapped)) {
            throw PatchRefusedException(
                handler,
                "Unexpected IInstalld classification: destroyUserData blocked=${"destroyUserData" in blocked}, createAppData allowed=${"createAppData" in kept + remapped}",
            )
        }

        val newProxySource = buildOwnClassSource(lines)
        tx.addNewClass(OWN_DESC, newProxySource, preferredDexEntryName = installerDexEntry)

        val (callerSites, callerCount) = redirectCallers(tx)
        if (callerSites.none { it.classDescriptor == INSTALLER_DESC }) {
            throw PatchRefusedException(handler, "Installer: IInstalld.Stub.asInterface call not found")
        }

        return PatchReport(
            entryId = entryId,
            handler = handler,
            applied = true,
            sites = callerSites + PatchSite(OWN_DESC, "<class>", "kept=${kept.size}, remapped=${remapped.size}, blocked=${blocked.size}"),
            counts = mapOf(
                "kept" to kept.size,
                "remapped" to remapped.size,
                "blocked" to blocked.size,
                "redirectedCallers" to callerCount,
            ),
        )
    }

    private fun buildOwnClassSource(lines: List<String>): String {
        var text = lines.joinToString("\n")
        text = ENCLOSING_ANNOTATION.replace(text, "")
        val firstLine = text.lineSequence().first()
        if (!firstLine.endsWith(PROXY_DESC)) {
            throw PatchRefusedException(handler, "Unexpected first line in $PROXY_DESC: $firstLine")
        }
        text = text.replaceFirst(firstLine, ".class public final $OWN_DESC")
        text = text.replace(PROXY_DESC, OWN_DESC)
        if (text.contains("IInstalld\$Stub\$Proxy")) {
            throw PatchRefusedException(handler, "IInstalld\$Stub\$Proxy reference left in OneOSInstalldProxy")
        }
        return text.trimEnd() + "\n\n" + OWN_AS_INTERFACE_METHOD + "\n"
    }

    private fun redirectCallers(tx: SmaliTree.Tx): Pair<List<PatchSite>, Int> {
        val sites = mutableListOf<PatchSite>()
        var totalHits = 0
        for (desc in tx.descriptors()) {
            if (desc.startsWith("Landroid/os/IInstalld")) continue
            if (desc == OWN_DESC) continue
            val src = tx.getSource(desc) ?: continue
            val lines = src.split("\n").toMutableList()
            var hits = 0
            for (i in lines.indices) {
                val m = STUB_AS_INTERFACE_CALL.matchEntire(lines[i]) ?: continue
                lines[i] = m.groupValues[1] + OWN_DESC + AS_INTERFACE
                hits++
            }
            val updated = lines.joinToString("\n")
            if (updated.contains("Landroid/os/IInstalld\$Stub;->asInterface") ||
                updated.contains("$PROXY_DESC-><init>")
            ) {
                throw PatchRefusedException(handler, "$desc: IInstalld proxy reference not redirected")
            }
            if (hits > 0) {
                tx.putSource(desc, updated)
                totalHits += hits
                sites.add(PatchSite(desc, "asInterface", "redirected $hits calls to OneOSInstalldProxy"))
            }
        }
        return sites to totalHits
    }
}
