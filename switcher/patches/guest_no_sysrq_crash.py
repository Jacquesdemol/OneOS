#!/usr/bin/env python3
"""Prevent guest Watchdog.doSysRq(char) from crashing the host kernel via 'c'."""
import os
import re
import sys

HEADER_RE = re.compile(r"^\.method (?:private |public |protected |static |final )*doSysRq\(C\)V$")


def main(root):
    changed = set()
    for dirpath, _, files in os.walk(root):
        for name in files:
            if name != "Watchdog.smali":
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            lines = open(path, encoding="utf-8").read().split("\n")
            dirty = False
            for i, line in enumerate(lines):
                if HEADER_RE.match(line.strip()):
                    j = i + 1
                    while j < len(lines) and (not lines[j].strip() or lines[j].lstrip().startswith((".locals", ".param", ".end param"))):
                        j += 1
                    lines.insert(j, "    # OneOS: never write to /proc/sysrq-trigger from guest Watchdog\n    return-void")
                    dirty = True
                    break
            if dirty:
                open(path, "w", encoding="utf-8").write("\n".join(lines))
                changed.add(rel.split(os.sep)[0])
    for d in sorted(changed):
        print(d)


if __name__ == "__main__":
    main(sys.argv[1])
