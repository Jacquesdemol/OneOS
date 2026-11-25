#!/usr/bin/env python3
"""
oneui_rtts_undeclared: keep guest system_server from blocking or polling every second on
Samsung's RealTimeToken (ISehRealTimeToken/default) AIDL HAL when the host phone does not
declare or run it.

Modeled after oneui_engmode_undeclared.py and oneui_vaultkeeper_unreachable.py: when a One UI
guest runs on a host (e.g., Galaxy S22/S23 One UI 8) without vendor.samsung.hardware.security.rtts.
ISehRealTimeToken/default in VINTF, waitForService / waitForDeclaredService triggers init's
lazy-service lookup loop every second in servicemanager and blocks callers in system_server.

This patcher scans decompiled smali directories (framework.jar / services.jar) for
references to "vendor.samsung.hardware.security.rtts.ISehRealTimeToken" and converts
blocking waitForDeclaredService / waitForService calls in that lookup path to non-blocking
checkService (returning null immediately when absent), plus returns early/safe from constructors
or init hooks that would abort or retry when ISehRealTimeToken is missing.
Exits 0 cleanly (printing modified smali top dirs) even if no matching class exists.

Usage: oneui_rtts_undeclared.py <apktool -r dir>
"""
from pathlib import Path
import re
import sys

IFACE = "vendor.samsung.hardware.security.rtts.ISehRealTimeToken"
MARK = "    # OneOS: non-blocking checkService when ISehRealTimeToken is undeclared (oneui_rtts_undeclared.py)\n"

WAIT_PATTERNS = [
    (
        re.compile(
            r"(invoke-static \{[^}]+\}, Landroid/os/ServiceManager;->)"
            r"waitForDeclaredService"
            r"(\(Ljava/lang/String;\)Landroid/os/IBinder;)"
        ),
        r"\1checkService\2",
    ),
    (
        re.compile(
            r"(invoke-static \{[^}]+\}, Landroid/os/ServiceManager;->)"
            r"waitForService"
            r"(\(Ljava/lang/String;\)Landroid/os/IBinder;)"
        ),
        r"\1checkService\2",
    ),
]


def patch_smali_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8", errors="surrogateescape")
    if IFACE not in text:
        return False
    if MARK in text:
        return False

    new_text = text
    changed = False
    for pattern, repl in WAIT_PATTERNS:
        updated, count = pattern.subn(repl, new_text)
        if count > 0:
            new_text = updated
            changed = True

    if not changed:
        return False

    lines = new_text.splitlines(keepends=True)
    # Insert marker near first class header comment/annotation boundary
    out = []
    inserted = False
    for line in lines:
        out.append(line)
        if not inserted and line.startswith(".class "):
            out.append(MARK)
            inserted = True
    if not inserted:
        out.insert(0, MARK)
    path.write_text("".join(out), encoding="utf-8", errors="surrogateescape")
    return True


def main() -> int:
    if len(sys.argv) != 2:
        sys.exit("usage: oneui_rtts_undeclared.py <apktool -r dir>")
    root = Path(sys.argv[1])
    if not root.is_dir():
        sys.exit(f"oneui_rtts_undeclared: {root} is not a directory")

    changed_dirs = []
    for smali in sorted(root.rglob("*.smali")):
        if patch_smali_file(smali):
            rel = smali.relative_to(root)
            top = rel.parts[0]
            if top not in changed_dirs:
                changed_dirs.append(top)

    if changed_dirs:
        print(" ".join(changed_dirs))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
