#!/usr/bin/env python3
"""Rewrite ServiceManager.waitForService -> checkService for missing OPPO HALs/daemons."""
import os
import re
import sys

WAIT_RE = re.compile(
    r"^(\s*invoke-static(?:/range)? \{[^}]*\}, Landroid/os/ServiceManager;)"
    r"->waitForService\(Ljava/lang/String;\)Landroid/os/IBinder;\s*$"
)


def main(args):
    root = [a for a in args if not a.startswith("--")][0]
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
                m = WAIT_RE.match(line)
                if m:
                    lines[i] = f"{m.group(1)}->checkService(Ljava/lang/String;)Landroid/os/IBinder;"
                    dirty = True
            if dirty:
                open(path, "w", encoding="utf-8").write("\n".join(lines))
                changed.add(rel.split(os.sep)[0])
    for d in sorted(changed):
        print(d)


if __name__ == "__main__":
    main(sys.argv[1:])
