#!/usr/bin/env python3
"""Keep the phone's FRP PersistentDataBlockService partition read-only from the guest."""
import os
import re
import sys

PDB_WRITE_METHODS = {
    "doSetFlashLockState", "enforceChecksumValidity", "formatIfOemUnlockEnabled",
    "formatPartitionLocked", "setFactoryResetProtectionSecret", "deactivateFrp",
}
HEADER_RE = re.compile(r"^\.method (?:private |public |protected |final |synchronized )*([A-Za-z0-9_]+)\([^)]*\)([VZ])\s*$")


def main(root):
    changed = set()
    for dirpath, _, files in os.walk(root):
        for name in files:
            if not name.startswith("PersistentDataBlockService") or not name.endswith(".smali"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            lines = open(path, encoding="utf-8").read().split("\n")
            dirty = False
            for i, line in enumerate(lines):
                m = HEADER_RE.match(line.strip())
                if m and m.group(1) in PDB_WRITE_METHODS:
                    ret = m.group(2)
                    j = i + 1
                    while j < len(lines) and (not lines[j].strip() or lines[j].lstrip().startswith((".locals", ".param", ".end param"))):
                        j += 1
                    stub = "    return-void" if ret == "V" else "    const/4 v0, 0x1\n    return v0"
                    lines.insert(j, f"    # OneOS: PDB partition stays read-only\n{stub}")
                    dirty = True
            if dirty:
                open(path, "w", encoding="utf-8").write("\n".join(lines))
                changed.add(rel.split(os.sep)[0])
    for d in sorted(changed):
        print(d)


if __name__ == "__main__":
    main(sys.argv[1])
