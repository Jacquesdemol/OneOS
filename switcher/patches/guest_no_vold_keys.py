#!/usr/bin/env python3
"""Drop guest user-key and storage-key calls to the shared host vold (IVold).

Replaces void IVold calls (onUserAdded, onUserRemoved, onUserStarted, onUserStopped,
addUserKeyAuth, clearUserKeyAuth, fixateNewestUserKeyAuth, unlockUserKey, lockUserKey,
prepareUserStorage, destroyUserStorage, destroyDsuMetadataKey, mountFstab, encryptFstab,
setStorageBindingSeed, reset outside StorageManagerService.<init>) with `nop`.
"""
import os
import re
import sys

VOLD_VOID_METHODS = {
    "onUserAdded", "onUserRemoved", "onUserStarted", "onUserStopped",
    "addUserKeyAuth", "clearUserKeyAuth", "fixateNewestUserKeyAuth",
    "unlockUserKey", "lockUserKey", "prepareUserStorage", "destroyUserStorage",
    "destroyDsuMetadataKey", "mountFstab", "encryptFstab", "setStorageBindingSeed",
    "reset",
}

CALL_RE = re.compile(
    r"^(\s*)invoke-(?:interface|virtual)(?:/range)? \{[^}]*\}, "
    r"Landroid/os/IVold(?:\$Stub\$Proxy)?;->([A-Za-z0-9_]+)\([^)]*\)V\s*$"
)


def main(root):
    changed = set()
    hits = 0
    for dirpath, _, files in os.walk(root):
        for name in files:
            if not name.endswith(".smali"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            lines = open(path, encoding="utf-8").read().split("\n")
            dirty = False
            in_sms_init = False
            is_sms = name == "StorageManagerService.smali"
            for i, line in enumerate(lines):
                s = line.strip()
                if s.startswith(".method "):
                    in_sms_init = is_sms and " constructor <init>(" in s
                elif s == ".end method":
                    in_sms_init = False
                m = CALL_RE.match(line)
                if m and m.group(2) in VOLD_VOID_METHODS:
                    if m.group(2) == "reset" and in_sms_init:
                        continue
                    indent = m.group(1)
                    lines[i] = f"{indent}# OneOS: IVold->{m.group(2)} dropped\n{indent}nop"
                    dirty = True
                    hits += 1
            if dirty:
                open(path, "w", encoding="utf-8").write("\n".join(lines))
                changed.add(rel.split(os.sep)[0])
    if hits == 0:
        sys.exit("guest_no_vold_keys: no IVold user-key call sites found")
    for d in sorted(changed):
        print(d)


if __name__ == "__main__":
    main(sys.argv[1])
