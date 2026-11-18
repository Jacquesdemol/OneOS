#!/usr/bin/env python3
"""Never let the guest touch the phone's Weaver slots (SyntheticPasswordManager)."""
import os
import re
import sys

WEAVER_CALL = re.compile(
    r"^(\s*)invoke-(?:interface|virtual|direct)(?:/range)? \{[^}]*\}, "
    r"[^\s;]*Weaver[^\s;]*->(?:write|read|weaverEnroll|weaverVerify)\([^)]*\)([VZLIJ]|L[^;]+;)\s*$"
)
MOVE_RES = re.compile(r"^(\s*)move-result(?:-object|-wide)?\s+([vp]\d+)\s*$")


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
                m = WEAVER_CALL.match(line)
                if m:
                    indent, ret = m.groups()
                    lines[i] = f"{indent}# OneOS: Weaver call dropped\n{indent}nop"
                    dirty = True
                    if ret != "V":
                        j = i + 1
                        while j < len(lines) and (not lines[j].strip() or lines[j].lstrip().startswith(".")):
                            j += 1
                        if j < len(lines):
                            mr = MOVE_RES.match(lines[j])
                            if mr:
                                r_ind, reg = mr.groups()
                                lines[j] = f"{r_ind}const/4 {reg}, 0x0"
            if dirty:
                open(path, "w", encoding="utf-8").write("\n".join(lines))
                changed.add(rel.split(os.sep)[0])
    for d in sorted(changed):
        print(d)


if __name__ == "__main__":
    main(sys.argv[1])
