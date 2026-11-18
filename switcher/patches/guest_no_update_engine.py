#!/usr/bin/env python3
"""Prevent guest StorageManagerService from binding to the host's A/B UpdateEngine."""
import os
import re
import sys

UE_CALL = re.compile(
    r"^(\s*)invoke-virtual(?:/range)? \{[^}]*\}, "
    r"Landroid/os/UpdateEngine;->(?:bind|applyPayload|applyPayloadFd|cancel|resetStatus|cleanupAppliedPayload|allocateSpaceForPayload)\([^)]*\)([VZ])\s*$"
)


def main(root):
    changed = set()
    for dirpath, _, files in os.walk(root):
        for name in files:
            if not name.endswith(".smali"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            lines = open(path, encoding="utf-8").read().split("\n")
            dirty = False
            for i, line in enumerate(lines):
                m = UE_CALL.match(line)
                if m:
                    indent, ret = m.groups()
                    lines[i] = f"{indent}# OneOS: UpdateEngine call dropped\n{indent}nop"
                    dirty = True
            if dirty:
                open(path, "w", encoding="utf-8").write("\n".join(lines))
                changed.add(rel.split(os.sep)[0])
    for d in sorted(changed):
        print(d)


if __name__ == "__main__":
    main(sys.argv[1])
