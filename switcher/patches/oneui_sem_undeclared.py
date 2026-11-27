#!/usr/bin/env python3
"""
oneui_sem_undeclared: keep guest system_server from blocking forever in libsec_sem.so
waitForService("vendor.samsung.hardware.security.sem.ISehSem/default") when running on a
host phone (e.g., Galaxy S22/S23 One UI 8) that does not expose ISehSem/default.

SemService lives in One UI's framework.jar (com/android/server/SemService.smali).
Its native methods inside libsec_sem.so call C++ waitForService("...ISehSem/default"),
which blocks binder threads inside system_server indefinitely and spams init every second.

This patcher:
1. Adds a small static helper SemService.oneosHasSemHal()Z checking
   ServiceManager.checkService("vendor.samsung.hardware.security.sem.ISehSem/default") != null
   (if not already present).
2. Auto-discovers every `.method ... native` declaration in SemService.smali and guards
   all non-native methods that call any of them (plus requestSpiUsage()Z) so no
   unregistered HAL call can reach libsec_sem.so, upgrading partially patched files safely.

Exits 0 cleanly (printing modified smali top dirs) even on non-OneUI frameworks.

Usage: oneui_sem_undeclared.py <apktool -r dir>
"""
from pathlib import Path
import re
import sys

HAL_NAME = "vendor.samsung.hardware.security.sem.ISehSem/default"
HELPER_SIG = "SemService;->oneosHasSemHal()Z"
MARK = "    # OneOS: guard libsec_sem calls when ISehSem/default is absent (oneui_sem_undeclared.py)"

HELPER_METHOD = (
    f"\n{MARK}\n"
    ".method private static blacklist oneosHasSemHal()Z\n"
    "    .locals 1\n\n"
    f'    const-string/jumbo v0, "{HAL_NAME}"\n\n'
    "    invoke-static {v0}, Landroid/os/ServiceManager;->checkService(Ljava/lang/String;)Landroid/os/IBinder;\n\n"
    "    move-result-object v0\n\n"
    "    if-nez v0, :cond_oneos_sem_present\n\n"
    "    const/4 v0, 0x0\n\n"
    "    return v0\n\n"
    "    :cond_oneos_sem_present\n"
    "    const/4 v0, 0x1\n\n"
    "    return v0\n"
    ".end method\n"
)

GUARD_RETURN_BY_TYPE = {
    "Z": ("const/4 v0, 0x0", "return v0", 1),
    "I": ("const/16 v0, -0xa", "return v0", 1),
    "B": ("const/4 v0, -0x1", "return v0", 1),
    "J": ("const-wide/16 v0, 0x0", "return-wide v0", 2),
    "V": (None, "return-void", 1),
}

NATIVE_CALL_RE = re.compile(r"Lcom/android/server/SemService;->([A-Za-z0-9_$]+\([^)]*\)\S+)")

KNOWN_NATIVE_WRAPPERS = {
    "synchronizedCloseSpi(I)I",
}


def _method_sig(header: str) -> str:
    m = re.search(r"([A-Za-z0-9_$<>]+\([^)]*\)\S+)\s*$", header)
    return m.group(1) if m else ""


def _guard_return(ret_type: str):
    if ret_type in GUARD_RETURN_BY_TYPE:
        return GUARD_RETURN_BY_TYPE[ret_type]
    if ret_type.startswith("L") or ret_type.startswith("["):
        return ("const/4 v0, 0x0", "return-object v0", 1)
    return None


def patch_sem_service(path: Path) -> bool:
    text = path.read_text(encoding="utf-8", errors="surrogateescape")
    if "requestSpiUsage()Z" not in text:
        return False

    blocks = text.split("\n.method ")
    if len(blocks) < 2:
        return False

    native_sigs = set()
    for block in blocks[1:]:
        hdr = block.splitlines()[0].strip()
        if " native " in f" {hdr} ":
            sig = _method_sig(hdr)
            if sig:
                native_sigs.add(sig)

    new_blocks = [blocks[0]]
    guarded_count = 0
    existing_labels = set(re.findall(r":(cond_oneos_sem_\d+)\b", text))
    next_id = 0

    for block in blocks[1:]:
        lines = block.splitlines(keepends=True)
        hdr = lines[0].strip()
        sig = _method_sig(hdr)
        calls_native = any(c in native_sigs for c in NATIVE_CALL_RE.findall(block))
        wants_guard = (sig == "requestSpiUsage()Z" or sig in KNOWN_NATIVE_WRAPPERS or calls_native) and " native " not in f" {hdr} "
        if wants_guard and HELPER_SIG not in block:
            ret_type = sig.rsplit(")", 1)[1]
            ret_spec = _guard_return(ret_type)
            if ret_spec is None:
                sys.exit(f"oneui_sem_undeclared: unsupported return type in {sig}")
            const_insn, ret_insn, min_locals = ret_spec
            inserted = False
            for idx, line in enumerate(lines):
                if line.strip().startswith(".locals "):
                    cnt = int(line.strip().split()[1])
                    if cnt < min_locals:
                        lines[idx] = f"    .locals {min_locals}\n"
                    while f"cond_oneos_sem_{next_id}" in existing_labels:
                        next_id += 1
                    lbl = f"cond_oneos_sem_{next_id}"
                    existing_labels.add(lbl)
                    next_id += 1
                    snippet = (
                        f"{MARK}\n"
                        f"    invoke-static {{}}, Lcom/android/server/{HELPER_SIG}\n\n"
                        "    move-result v0\n\n"
                        f"    if-nez v0, :{lbl}\n\n"
                        + (f"    {const_insn}\n\n" if const_insn else "")
                        + f"    {ret_insn}\n\n"
                        + f"    :{lbl}\n"
                    )
                    lines.insert(idx + 1, snippet)
                    inserted = True
                    guarded_count += 1
                    break
            if not inserted:
                sys.exit(f"oneui_sem_undeclared: missing .locals in {sig}")
        new_blocks.append("".join(lines))

    if guarded_count == 0:
        return False

    joined = "\n.method ".join(new_blocks)
    if ".method private static blacklist oneosHasSemHal()Z" not in joined:
        anchor = "# direct methods\n"
        if anchor not in joined:
            sys.exit("oneui_sem_undeclared: # direct methods section not found")
        joined = joined.replace(anchor, anchor + HELPER_METHOD, 1)
    path.write_text(joined, encoding="utf-8", errors="surrogateescape")
    return True


def main() -> int:
    if len(sys.argv) != 2:
        sys.exit("usage: oneui_sem_undeclared.py <apktool -r dir>")
    root = Path(sys.argv[1])
    if not root.is_dir():
        sys.exit(f"oneui_sem_undeclared: {root} is not a directory")

    changed_dirs = []
    for smali in sorted(root.rglob("SemService.smali")):
        rel = smali.relative_to(root)
        if rel.as_posix().endswith("/com/android/server/SemService.smali"):
            if patch_sem_service(smali):
                top = rel.parts[0]
                if top not in changed_dirs:
                    changed_dirs.append(top)

    if changed_dirs:
        print(" ".join(changed_dirs))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
