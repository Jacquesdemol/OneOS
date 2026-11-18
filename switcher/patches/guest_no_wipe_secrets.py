#!/usr/bin/env python3
"""Prevent guest wipe paths from deleting Secretkeeper, Weaver, or vold storage/metadata keys."""
import os
import re
import sys

SECRET_CALL = re.compile(
    r"^(\s*)invoke-(?:interface|virtual)(?:/range)? \{[^}]*\}, "
    r"(?:[^\s;]*Secretkeeper[^\s;]*|Landroid/os/IVold[^;]*;)"
    r"->(?:deleteAll|deleteIds|processSecretManagementRequest|destroyDsuMetadataKey|destroyUserStorage)\([^)]*\)V\s*$"
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
                m = SECRET_CALL.match(line)
                if m:
                    indent = m.group(1)
                    lines[i] = f"{indent}# OneOS: wipe-secrets call dropped\n{indent}nop"
                    dirty = True
            if dirty:
                open(path, "w", encoding="utf-8").write("\n".join(lines))
                changed.add(rel.split(os.sep)[0])
    for d in sorted(changed):
        print(d)


if __name__ == "__main__":
    main(sys.argv[1])
