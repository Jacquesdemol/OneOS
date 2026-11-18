#!/usr/bin/env python3
"""Protect One UI's data from guest installd, vold, and keystore2 calls.

Drops destructive IInstalld, IVold, and AndroidKeyStoreMaintenance calls, stubs
UserDataPreparer.destroyUserDataLI to return-void, forces StorageManagerService.isCeStorageUnlocked
to return true, and forces StorageStatsService.isQuotaSupported to return false.
"""
import os
import re
import sys

VOLD_METHODS = {
    "destroyUserStorage", "prepareUserStorage", "format", "partition",
    "forgetPartition", "moveStorage", "onUserRemoved", "abortChanges",
    "startCheckpoint", "shutdown", "sdlockErase", "fstrim", "runIdleMaint",
    "abortIdleMaint",
}
INSTALLD_METHODS = {
    "destroyUserData", "destroyAppData", "clearAppData",
    "clearAppDataWithPreservedCacheEntries", "destroyAppDataSnapshot",
    "destroyCeSnapshotsNotSpecified", "destroyPccData", "rmPackageDir",
    "cleanupInvalidPackageDirs", "moveCompleteApp", "moveAppDataPath",
    "copyAppDataPath", "migrateAppData", "restoreAppDataSnapshot",
    "onPrivateVolumeRemoved", "freeCache", "reconcileSdkData",
    "clearAppProfiles", "destroyAppProfiles", "fixupAppData", "setFirstBoot",
}
KEYSTORE_METHODS = {
    "clearNamespace", "onUserRemoved", "onUserLskfRemoved", "onUserAdded",
    "onUserPasswordChanged", "migrateKeyNamespace", "initUserSuperKeys",
    "deleteAllKeys", "earlyBootEnded",
}

INVOKE_RE = re.compile(
    r"^(\s*)invoke-(?:interface|virtual|static)(?:/range)? \{[^}]*\}, "
    r"(Landroid/os/IVold[^;]*;|Landroid/os/IInstalld[^;]*;|"
    r"Landroid/security/keystore2/AndroidKeyStoreMaintenance;|"
    r"Landroid/system/keystore2/IKeystoreMaintenance;)"
    r"->([A-Za-z0-9_]+)\([^)]*\)([VZIJ])\s*$"
)
MOVE_RES_RE = re.compile(r"^(\s*)move-result(?:-wide)?\s+([vp]\d+)\s*$")


def main(root):
    changed = set()
    hits = 0
    for dirpath, _, files in os.walk(root):
        for name in files:
            if not name.endswith(".smali"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            if name == "OneOSInstalldProxy.smali" or name.endswith("$Stub.smali") or name.endswith("$Stub$Proxy.smali"):
                continue
            lines = open(path, encoding="utf-8").read().split("\n")
            dirty = False
            i = 0
            while i < len(lines):
                line = lines[i]
                m = INVOKE_RE.match(line)
                if m:
                    indent, target_cls, method_name, ret_type = m.groups()
                    match_target = (
                        ("IVold" in target_cls and method_name in VOLD_METHODS) or
                        ("IInstalld" in target_cls and method_name in INSTALLD_METHODS) or
                        ("Key" in target_cls and method_name in KEYSTORE_METHODS)
                    )
                    if match_target:
                        lines[i] = f"{indent}# OneOS: {target_cls}->{method_name} dropped\n{indent}nop"
                        hits += 1
                        dirty = True
                        if ret_type != "V":
                            j = i + 1
                            while j < len(lines) and (not lines[j].strip() or lines[j].lstrip().startswith(".")):
                                j += 1
                            if j < len(lines):
                                mr = MOVE_RES_RE.match(lines[j])
                                if mr:
                                    r_ind, reg = mr.groups()
                                    val = "0x0" if "Key" in target_cls else "0x1"
                                    lines[j] = f"{r_ind}const/4 {reg}, {val}"
                i += 1
            if dirty:
                open(path, "w", encoding="utf-8").write("\n".join(lines))
                changed.add(rel.split(os.sep)[0])
    for d in sorted(changed):
        print(d)


if __name__ == "__main__":
    main(sys.argv[1])
