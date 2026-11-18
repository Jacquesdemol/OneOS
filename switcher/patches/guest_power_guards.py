#!/usr/bin/env python3
"""Prevent the guest framework from rebooting or shutting down the host phone.

Redirects ShutdownThread.reboot / shutdown and PowerManagerService low-level
sys.powerctl writes to `/data/adb/oneos_power_request` and prevents Watchdog
sysrq-trigger ('c') kernel crashes.
"""
import os
import re
import sys

POWERCTL_RE = re.compile(r'^(\s*)const-string\s+([vp]\d+),\s*"sys\.powerctl"\s*$')


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
                m = POWERCTL_RE.match(line)
                if m:
                    indent, reg = m.groups()
                    lines[i] = f'{indent}# OneOS: redirect sys.powerctl to guest property\n{indent}const-string {reg}, "oneos.guest.powerctl"'
                    dirty = True
            if dirty:
                open(path, "w", encoding="utf-8").write("\n".join(lines))
                changed.add(rel.split(os.sep)[0])
    for d in sorted(changed):
        print(d)


if __name__ == "__main__":
    main(sys.argv[1])
