#!/usr/bin/env python3
"""Route guest IdmapDaemon binder lookup to 'idmap_guest' instead of host 'idmap'."""
import os
import re
import sys

IDMAP_STR = re.compile(r'^(\s*)const-string\s+([vp]\d+),\s*"idmap"\s*$')


def main(root):
    changed = set()
    for dirpath, _, files in os.walk(root):
        for name in files:
            if not name.startswith("IdmapDaemon") or not name.endswith(".smali"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            lines = open(path, encoding="utf-8").read().split("\n")
            dirty = False
            for i, line in enumerate(lines):
                m = IDMAP_STR.match(line)
                if m:
                    indent, reg = m.groups()
                    lines[i] = f'{indent}# OneOS: guest idmap2d service\n{indent}const-string {reg}, "idmap_guest"'
                    dirty = True
            if dirty:
                open(path, "w", encoding="utf-8").write("\n".join(lines))
                changed.add(rel.split(os.sep)[0])
    for d in sorted(changed):
        print(d)


if __name__ == "__main__":
    main(sys.argv[1])
