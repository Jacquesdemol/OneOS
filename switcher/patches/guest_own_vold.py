#!/usr/bin/env python3
"""Route guest StorageManagerService and OplusStorageManagerFeature vold lookup to 'gvld'."""
import os
import re
import sys

VOLD_STR = re.compile(r'^(\s*)const-string\s+([vp]\d+),\s*"vold"\s*$')


def main(args):
    oplus = "--oplus" in args
    miui = "--miui" in args
    root = [a for a in args if not a.startswith("--")][0]
    targets = ("OplusStorageManagerFeature.smali",) if oplus else (
        ("MiuiStorageManager", "StorageManagerService.smali") if miui else ("StorageManagerService.smali",)
    )
    changed = set()
    for dirpath, _, files in os.walk(root):
        for name in files:
            if not any(name.startswith(t.replace(".smali", "")) for t in targets) or not name.endswith(".smali"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            lines = open(path, encoding="utf-8").read().split("\n")
            dirty = False
            for i, line in enumerate(lines):
                m = VOLD_STR.match(line)
                if m:
                    indent, reg = m.groups()
                    lines[i] = f'{indent}# OneOS: guest vold registers gvld\n{indent}const-string {reg}, "gvld"'
                    dirty = True
            if dirty:
                open(path, "w", encoding="utf-8").write("\n".join(lines))
                changed.add(rel.split(os.sep)[0])
    for d in sorted(changed):
        print(d)


if __name__ == "__main__":
    main(sys.argv[1:])
