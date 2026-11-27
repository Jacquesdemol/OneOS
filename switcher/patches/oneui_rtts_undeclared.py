#!/usr/bin/env python3
"""
oneui_rtts_undeclared: keep guest system_server from blocking or polling every second on
Samsung's RealTimeToken (ISehRealTimeToken/default) AIDL HAL when the host phone does not
declare or run it.

Modeled after oneui_engmode_undeclared.py and oneui_vaultkeeper_unreachable.py: when a One UI
guest runs on a host (e.g., Galaxy S22/S23 One UI 8) without vendor.samsung.hardware.security.rtts.
ISehRealTimeToken/default in VINTF, waitForService / waitForDeclaredService triggers init's
lazy-service lookup loop every second in servicemanager and blocks callers in system_server.

In One UI 9.5 (S948B), C++ librtts_jni.so calls waitForService("...ISehRealTimeToken/default")
from native code invoked by TokenJniBridge / RealTimeTokenService, so Java-side smali lacks
literal ServiceManager.waitForService references. This patcher:
1. Rewrites any Java-side ServiceManager.waitFor* referencing ISehRealTimeToken to checkService.
2. Guards RealTimeTokenService.initialize()Z to return false immediately via
   ServiceManager.checkService("vendor.samsung.hardware.security.rtts.ISehRealTimeToken/default")
   when the HAL is absent, avoiding librtts_jni.so load & native waitForService hangs.
3. Guards TokenJniBridge.*Wrapper() native bridge methods to return -0x65 (-101, JNI unavailable)
   when ISehRealTimeToken/default is not registered.

Exits 0 cleanly (printing modified smali top dirs) even if no matching class exists.

Usage: oneui_rtts_undeclared.py <apktool -r dir>
"""
from pathlib import Path
import re
import sys

IFACE = "vendor.samsung.hardware.security.rtts.ISehRealTimeToken"
HAL_NAME = "vendor.samsung.hardware.security.rtts.ISehRealTimeToken/default"
MARK = "    # OneOS: non-blocking checkService when ISehRealTimeToken is undeclared (oneui_rtts_undeclared.py)\n"
JNI_MARK = "    # OneOS: skip librtts_jni when ISehRealTimeToken/default is absent (oneui_rtts_undeclared.py)"

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

RTTS_INIT_SIG = ".method public final initialize()Z"
BRIDGE_WRAPPER_RE = re.compile(r"^\.method public final \w+Wrapper\([^)]*\)I\s*$")


def _insert_class_mark(lines: list[str]) -> str:
    out = []
    inserted = False
    for line in lines:
        out.append(line)
        if not inserted and line.startswith(".class "):
            out.append(MARK)
            inserted = True
    if not inserted:
        out.insert(0, MARK)
    return "".join(out)


def patch_wait_calls(path: Path) -> bool:
    text = path.read_text(encoding="utf-8", errors="surrogateescape")
    if IFACE not in text or MARK in text:
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

    path.write_text(_insert_class_mark(new_text.splitlines(keepends=True)), encoding="utf-8", errors="surrogateescape")
    return True


def _inject_hal_guard_in_method(lines: list[str], start_idx: int, ret_const: str) -> tuple[list[str], int]:
    """Insert ServiceManager.checkService(HAL_NAME) == null fast-return after .locals/.registers."""
    i = start_idx + 1
    while i < len(lines) and not lines[i].startswith(".end method"):
        stripped = lines[i].strip()
        if stripped.startswith(".locals "):
            cnt = int(stripped.split()[1])
            if cnt < 1:
                lines[i] = "    .locals 1\n"
            guard = (
                f"{JNI_MARK}\n"
                f'    const-string/jumbo v0, "{HAL_NAME}"\n\n'
                "    invoke-static {v0}, Landroid/os/ServiceManager;->checkService(Ljava/lang/String;)Landroid/os/IBinder;\n\n"
                "    move-result-object v0\n\n"
                "    if-nez v0, :cond_oneos_rtts_ok\n\n"
                f"    {ret_const}\n\n"
                "    return v0\n\n"
                "    :cond_oneos_rtts_ok\n"
            )
            lines.insert(i + 1, guard)
            return lines, 1
        i += 1
    return lines, 0


def patch_rtts_service(path: Path) -> bool:
    text = path.read_text(encoding="utf-8", errors="surrogateescape")
    if JNI_MARK in text or RTTS_INIT_SIG not in text:
        return False

    lines = text.splitlines(keepends=True)
    changed = False
    i = 0
    while i < len(lines):
        if lines[i].strip() == RTTS_INIT_SIG:
            lines, delta = _inject_hal_guard_in_method(lines, i, "const/4 v0, 0x0")
            if delta:
                changed = True
                break
        i += 1

    if not changed:
        return False
    path.write_text("".join(lines), encoding="utf-8", errors="surrogateescape")
    return True


def patch_token_bridge(path: Path) -> bool:
    text = path.read_text(encoding="utf-8", errors="surrogateescape")
    if JNI_MARK in text:
        return False

    lines = text.splitlines(keepends=True)
    changed = False
    i = 0
    while i < len(lines):
        if BRIDGE_WRAPPER_RE.match(lines[i].strip()):
            lines, delta = _inject_hal_guard_in_method(lines, i, "const/16 v0, -0x65")
            if delta:
                changed = True
                i += delta
        i += 1

    if not changed:
        return False
    path.write_text("".join(lines), encoding="utf-8", errors="surrogateescape")
    return True


def main() -> int:
    if len(sys.argv) != 2:
        sys.exit("usage: oneui_rtts_undeclared.py <apktool -r dir>")
    root = Path(sys.argv[1])
    if not root.is_dir():
        sys.exit(f"oneui_rtts_undeclared: {root} is not a directory")

    changed_dirs = []
    for smali in sorted(root.rglob("*.smali")):
        rel = smali.relative_to(root)
        rel_posix = rel.as_posix()
        did = patch_wait_calls(smali)
        if rel_posix.endswith("/com/android/server/RealTimeTokenService.smali"):
            did = patch_rtts_service(smali) or did
        elif rel_posix.endswith("/com/android/server/TokenJniBridge.smali"):
            did = patch_token_bridge(smali) or did
        if did:
            top = rel.parts[0]
            if top not in changed_dirs:
                changed_dirs.append(top)

    if changed_dirs:
        print(" ".join(changed_dirs))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
