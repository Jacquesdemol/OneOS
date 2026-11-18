package com.fahadali.oneos.core.safety

/**
 * Validates the complete UID and App ID isolation model between the Samsung One UI 8 host
 * and the OneOS Android 16 guest (`FIRST_APPLICATION_UID = 15000`).
 *
 * Range partition (per UserHandle `PER_USER_RANGE = 100000`):
 * - `0..9999`: System & OEM fixed UIDs (`AID_ROOT`, `AID_SYSTEM`, `AID_SHELL`, etc.)
 * - `10000..14999`: Host regular application & shared UIDs (never allocated by guest)
 * - `15000..19999`: Guest regular application & shared UIDs (`FIRST_APPLICATION_UID = 15000`)
 * - `20000..24999`: Host SDK sandbox UIDs (`hostAppId + 10000`)
 * - `25000..29999`: Guest SDK sandbox UIDs (`guestAppId + 10000`, where `guestAppId in 15000..19999`)
 * - `90000..98999`: Isolated process UIDs (ephemeral)
 * - `99000..99999`: App-zygote isolated UIDs (ephemeral)
 */
object UidIsolationValidator {
    const val PER_USER_RANGE = 100_000
    const val HOST_FIRST_APP_UID = 10_000
    const val HOST_LAST_APP_UID = 14_999
    const val GUEST_FIRST_APP_UID = 15_000
    const val GUEST_LAST_APP_UID = 19_999
    const val HOST_FIRST_SDK_SANDBOX_UID = 20_000
    const val HOST_LAST_SDK_SANDBOX_UID = 24_999
    const val GUEST_FIRST_SDK_SANDBOX_UID = 25_000
    const val GUEST_LAST_SDK_SANDBOX_UID = 29_999
    const val FIRST_ISOLATED_UID = 90_000
    const val LAST_ISOLATED_UID = 98_999
    const val FIRST_APP_ZYGOTE_ISOLATED_UID = 99_000
    const val LAST_APP_ZYGOTE_ISOLATED_UID = 99_999

    enum class UidDomain {
        SYSTEM_OR_OEM,
        HOST_APP,
        GUEST_APP,
        HOST_SDK_SANDBOX,
        GUEST_SDK_SANDBOX,
        ISOLATED,
        APP_ZYGOTE_ISOLATED,
        UNASSIGNED,
    }

    data class PackageUidEntry(
        val name: String,
        val appId: Int,
        val isSharedUser: Boolean = false,
    )

    data class UidValidationReport(
        val valid: Boolean,
        val problems: List<String>,
        val migratedEntries: List<PackageUidEntry>,
    )

    fun getUserId(uid: Int): Int = uid / PER_USER_RANGE

    fun getAppId(uid: Int): Int = uid % PER_USER_RANGE

    fun getUid(userId: Int, appId: Int): Int = userId * PER_USER_RANGE + (appId % PER_USER_RANGE)

    fun toSdkSandboxUid(uid: Int): Int {
        val userId = getUserId(uid)
        val appId = getAppId(uid)
        require(appId in HOST_FIRST_APP_UID..GUEST_LAST_APP_UID) {
            "Cannot compute SDK sandbox UID for non-application appId=$appId"
        }
        return getUid(userId, appId + 10_000)
    }

    fun getAppUidFromSdkSandboxUid(sandboxUid: Int): Int {
        val userId = getUserId(sandboxUid)
        val sandboxAppId = getAppId(sandboxUid)
        require(sandboxAppId in HOST_FIRST_SDK_SANDBOX_UID..GUEST_LAST_SDK_SANDBOX_UID) {
            "Not an SDK sandbox UID: $sandboxUid (appId=$sandboxAppId)"
        }
        return getUid(userId, sandboxAppId - 10_000)
    }

    fun classifyUid(uid: Int): UidDomain {
        if (uid < 0) return UidDomain.UNASSIGNED
        val appId = getAppId(uid)
        return when (appId) {
            in 0..9999 -> UidDomain.SYSTEM_OR_OEM
            in HOST_FIRST_APP_UID..HOST_LAST_APP_UID -> UidDomain.HOST_APP
            in GUEST_FIRST_APP_UID..GUEST_LAST_APP_UID -> UidDomain.GUEST_APP
            in HOST_FIRST_SDK_SANDBOX_UID..HOST_LAST_SDK_SANDBOX_UID -> UidDomain.HOST_SDK_SANDBOX
            in GUEST_FIRST_SDK_SANDBOX_UID..GUEST_LAST_SDK_SANDBOX_UID -> UidDomain.GUEST_SDK_SANDBOX
            in FIRST_ISOLATED_UID..LAST_ISOLATED_UID -> UidDomain.ISOLATED
            in FIRST_APP_ZYGOTE_ISOLATED_UID..LAST_APP_ZYGOTE_ISOLATED_UID -> UidDomain.APP_ZYGOTE_ISOLATED
            else -> UidDomain.UNASSIGNED
        }
    }

    /**
     * Validates that the host's KernelSU/Magisk manager appId (`oneos.ksu.mgr_appid`)
     * resides in the host application UID band (`10000..14999`) so it cannot collide
     * with any guest application UID (`15000..19999`).
     */
    fun validateKernelSuManagerAppId(mgrAppId: Int): List<String> {
        if (mgrAppId !in HOST_FIRST_APP_UID..HOST_LAST_APP_UID) {
            return listOf("oneos.ksu.mgr_appid=$mgrAppId is outside host app UID band ($HOST_FIRST_APP_UID..$HOST_LAST_APP_UID)")
        }
        return emptyList()
    }

    /**
     * Validates guest `packages.xml` and `packages.list` entries:
     * 1. No guest regular package or `<shared-user>` may remain in `10000..14999`.
     * 2. Every guest regular package and `<shared-user>` must have `appId in 15000..19999`
     *    or a fixed system UID (`0..9999`).
     * 3. `packages.list` UIDs must match `packages.xml` UIDs without duplicates or collisions.
     * 4. Pre-existing `10000..14999` entries can be deterministically migrated (`+5000`) into
     *    `15000..19999` only if no collision occurs.
     */
    fun validateAndMigratePackages(
        xmlEntries: List<PackageUidEntry>,
        packagesListText: String,
        allowMigrationFromAospBand: Boolean = false,
    ): UidValidationReport {
        val problems = mutableListOf<String>()
        val migrated = mutableListOf<PackageUidEntry>()
        val usedAppIdsBySharedOrPkg = mutableMapOf<Int, MutableSet<String>>()

        for (entry in xmlEntries) {
            val resolvedAppId = when {
                entry.appId in 0..9999 -> entry.appId
                entry.appId in GUEST_FIRST_APP_UID..GUEST_LAST_APP_UID -> entry.appId
                entry.appId in HOST_FIRST_APP_UID..HOST_LAST_APP_UID -> {
                    if (allowMigrationFromAospBand) {
                        entry.appId + 5000
                    } else {
                        problems += "Guest ${if (entry.isSharedUser) "shared-user" else "package"} '${entry.name}' has unmigrated host-band appId=${entry.appId} (must be in $GUEST_FIRST_APP_UID..$GUEST_LAST_APP_UID)"
                        entry.appId
                    }
                }
                else -> {
                    problems += "Guest ${if (entry.isSharedUser) "shared-user" else "package"} '${entry.name}' has out-of-range appId=${entry.appId}"
                    entry.appId
                }
            }
            val updated = entry.copy(appId = resolvedAppId)
            migrated += updated
            usedAppIdsBySharedOrPkg.getOrPut(resolvedAppId) { mutableSetOf() } +=
                if (entry.isSharedUser) "shared:${entry.name}" else "pkg:${entry.name}"
        }

        // Parse packages.list lines: "<pkgName> <appId> <debuggable> <dataDir> <seinfo> <gids...>"
        val xmlPkgMap = migrated.filter { !it.isSharedUser }.associateBy { it.name }
        for ((lineNo, rawLine) in packagesListText.lineSequence().withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 4) {
                problems += "packages.list:${lineNo + 1}: malformed line '$line'"
                continue
            }
            val pkgName = parts[0]
            val listUid = parts[1].toIntOrNull()
            if (listUid == null) {
                problems += "packages.list:${lineNo + 1}: invalid UID '${parts[1]}' for $pkgName"
                continue
            }
            val listAppId = getAppId(listUid)
            if (listAppId in HOST_FIRST_APP_UID..HOST_LAST_APP_UID && !allowMigrationFromAospBand) {
                problems += "packages.list:${lineNo + 1}: package '$pkgName' has host-band appId=$listAppId"
            }
            val xmlEntry = xmlPkgMap[pkgName]
            if (xmlEntry != null) {
                val expectedAppId = if (allowMigrationFromAospBand && listAppId in HOST_FIRST_APP_UID..HOST_LAST_APP_UID) {
                    listAppId + 5000
                } else {
                    listAppId
                }
                if (xmlEntry.appId != expectedAppId) {
                    problems += "packages.list:${lineNo + 1}: UID mismatch for '$pkgName' (packages.xml=${xmlEntry.appId} vs packages.list=$listAppId)"
                }
            }
        }

        return UidValidationReport(
            valid = problems.isEmpty(),
            problems = problems,
            migratedEntries = migrated,
        )
    }
}
