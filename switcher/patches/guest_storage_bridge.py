#!/usr/bin/env python3
"""
guest_storage_bridge: route guest app/user/external size queries and Settings
Clear Data / Clear Cache operations to oneos_storaged inside the guest namespace.

Why:
- Host installd resolves /data_mirror/data_{ce,de}/null/0 on One UI's own dm-58
  userdata (unbridged), never the guest's /data/user/0.
- One UI 9.5 IInstalld also diverges from One UI 8 on getAppSize, getUserSize,
  and clearAppData, causing FetchPackageStorage RemoteException ("method 8 is not
  on this phone's installd") and stuck "Calculating..." / 0 B in Settings.
- guest_storage_guards.py still drops IInstalld.clearAppData globally so
  fingerprint-mismatch/OTA paths never harm host data; here we hook ONLY:
  1. Installer.getAppSize / getAppSizeAndFileCount / getUserSize / getExternalSize
  2. PackageManagerService.clearApplicationUserDataLIF (Clear Data)
  3. PackageManagerService$IPackageManagerImpl$$ExternalSyntheticLambda20 (Clear Cache)

Usage: guest_storage_bridge.py <apktool -r dir> [OneOSStorageHelper.smali]
Prints the smali_* directories it changed, one per line.
"""
from pathlib import Path
import os
import re
import sys

HELPER_DESC = "Lcom/android/server/pm/OneOSStorageHelper;"
MARK = "    # OneOS: guest storage bridge (guest_storage_bridge.py)"


def find_one(root: Path, suffix: str) -> Path:
    hits = sorted(p for p in root.rglob(Path(suffix).name) if p.as_posix().endswith("/" + suffix))
    if len(hits) != 1:
        sys.exit(f"guest_storage_bridge: expected 1 {suffix}, found {len(hits)}")
    return hits[0]


def top_dir(root: Path, path: Path) -> str:
    return path.relative_to(root).parts[0]


def patch_installer_method(lines: list[str], method_sig: str, hook_lines: list[str], label: str) -> bool:
    start = next((i for i, l in enumerate(lines) if l.startswith(".method ") and method_sig in l), None)
    if start is None:
        sys.exit(f"guest_storage_bridge: missing {method_sig} in Installer.smali")
    end = next((i for i in range(start + 1, len(lines)) if lines[i] == ".end method"), None)
    if end is None:
        sys.exit(f"guest_storage_bridge: unterminated {method_sig}")

    body = "\n".join(lines[start:end])
    if HELPER_DESC in body:
        return False

    anchor = None
    for i in range(start, end):
        if "Lcom/android/server/pm/Installer;->checkBeforeRemote()V" in lines[i]:
            anchor = i
            break
    if anchor is None:
        sys.exit(f"guest_storage_bridge: missing checkBeforeRemote() in {method_sig}")

    lines[anchor + 1 : anchor + 1] = ["", MARK, *hook_lines, f"    :{label}"]
    return True


def patch_installer(path: Path) -> bool:
    lines = path.read_text(encoding="utf-8", errors="surrogateescape").split("\n")
    changed = False

    changed |= patch_installer_method(
        lines,
        "getAppSize(Ljava/lang/String;[Ljava/lang/String;IIII[J[Ljava/lang/String;Landroid/content/pm/PackageStats;)V",
        [
            f"    invoke-static/range {{v1 .. v9}}, {HELPER_DESC}->getAppSize(Ljava/lang/String;[Ljava/lang/String;IIII[J[Ljava/lang/String;Landroid/content/pm/PackageStats;)Z",
            "",
            "    move-result v13",
            "",
            "    if-eqz v13, :cond_oneos_getappsize_fallback",
            "",
            "    return-void",
        ],
        "cond_oneos_getappsize_fallback",
    )

    changed |= patch_installer_method(
        lines,
        "getAppSizeAndFileCount(Ljava/lang/String;[Ljava/lang/String;IIII[J[Ljava/lang/String;)[J",
        [
            f"    invoke-static/range {{v1 .. v8}}, {HELPER_DESC}->getAppSizeAndFileCount(Ljava/lang/String;[Ljava/lang/String;IIII[J[Ljava/lang/String;)[J",
            "",
            "    move-result-object v12",
            "",
            "    if-eqz v12, :cond_oneos_getappsizecnt_fallback",
            "",
            "    return-object v12",
        ],
        "cond_oneos_getappsizecnt_fallback",
    )

    changed |= patch_installer_method(
        lines,
        "getUserSize(Ljava/lang/String;II[I[ILandroid/content/pm/PackageStats;)V",
        [
            f"    invoke-static/range {{p1 .. p6}}, {HELPER_DESC}->getUserSize(Ljava/lang/String;II[I[ILandroid/content/pm/PackageStats;)Z",
            "",
            "    move-result v1",
            "",
            "    if-eqz v1, :cond_oneos_getusersize_fallback",
            "",
            "    return-void",
        ],
        "cond_oneos_getusersize_fallback",
    )

    changed |= patch_installer_method(
        lines,
        "getExternalSize(Ljava/lang/String;II[I)[J",
        [
            f"    invoke-static {{p1, p2, p3, p4}}, {HELPER_DESC}->getExternalSize(Ljava/lang/String;II[I)[J",
            "",
            "    move-result-object v1",
            "",
            "    if-eqz v1, :cond_oneos_getextsize_fallback",
            "",
            "    return-object v1",
        ],
        "cond_oneos_getextsize_fallback",
    )

    if changed:
        path.write_text("\n".join(lines), encoding="utf-8", errors="surrogateescape")
    return changed


def patch_clear_data(pms_path: Path) -> bool:
    text = pms_path.read_text(encoding="utf-8", errors="surrogateescape")
    if "OneOSStorageHelper;->clearAppData(" in text:
        return False
    pattern = re.compile(
        r"(\n\.method [^\n]*clearApplicationUserDataLIF\([^\n]*\n.*?"
        r"invoke-virtual \{[vp]\d+, ([vp]\d+), ([vp]\d+), [vp]\d+\}, Lcom/android/server/pm/AppDataHelper;->clearAppDataLIF\(Lcom/android/server/pm/pkg/AndroidPackage;II\)V)",
        re.S,
    )
    m = pattern.search(text)
    if not m:
        sys.exit("guest_storage_bridge: clearAppDataLIF call inside clearApplicationUserDataLIF not found")
    pkg_reg, user_reg = m.group(2), m.group(3)
    inject = (
        f"\n\n{MARK}\n"
        f"    invoke-static {{{pkg_reg}, {user_reg}}}, {HELPER_DESC}->clearAppData(Lcom/android/server/pm/pkg/AndroidPackage;I)V"
    )
    text = text[: m.end(1)] + inject + text[m.end(1) :]
    pms_path.write_text(text, encoding="utf-8", errors="surrogateescape")
    return True


def patch_clear_cache(root: Path) -> Path | None:
    clear_call = "Lcom/android/server/pm/AppDataHelper;->clearAppDataLIF(Lcom/android/server/pm/pkg/AndroidPackage;II)V"
    matches = []
    for p in sorted(root.rglob("PackageManagerService$IPackageManagerImpl$$ExternalSyntheticLambda*.smali")):
        txt = p.read_text(encoding="utf-8", errors="surrogateescape")
        if clear_call in txt and ("0x17" in txt or "0x27" in txt):
            matches.append((p, txt))
    if len(matches) != 1:
        sys.exit(f"guest_storage_bridge: expected 1 cache-clear lambda, found {len(matches)}")
    path, text = matches[0]
    if "OneOSStorageHelper;->clearAppCache(" in text:
        return None
    call_re = re.compile(
        r"invoke-virtual \{v\d+, (v\d+), (v\d+), v\d+\}, " + re.escape(clear_call)
    )
    hits = list(call_re.finditer(text))
    if not hits:
        sys.exit(f"guest_storage_bridge: clearAppDataLIF invocation pattern mismatch in {path.name}")
    last = hits[-1]
    pkg_reg, user_reg = last.group(1), last.group(2)
    inject = (
        f"\n\n{MARK}\n"
        f"    invoke-static {{{pkg_reg}, {user_reg}}}, {HELPER_DESC}->clearAppCache(Lcom/android/server/pm/pkg/AndroidPackage;I)V"
    )
    text = text[: last.end()] + inject + text[last.end() :]
    path.write_text(text, encoding="utf-8", errors="surrogateescape")
    return path


def main() -> int:
    if len(sys.argv) not in (2, 3):
        sys.exit("usage: guest_storage_bridge.py <apktool -r dir> [OneOSStorageHelper.smali]")
    root = Path(sys.argv[1])
    if not root.is_dir():
        sys.exit(f"guest_storage_bridge: {root} is not a directory")
    helper_src = (
        Path(sys.argv[2])
        if len(sys.argv) == 3
        else Path(__file__).resolve().with_name("OneOSStorageHelper.smali")
    )
    if not helper_src.is_file():
        sys.exit(f"guest_storage_bridge: helper smali missing at {helper_src}")

    installer = find_one(root, os.path.join("com", "android", "server", "pm", "Installer.smali"))
    pms = find_one(root, os.path.join("com", "android", "server", "pm", "PackageManagerService.smali"))

    changed_dirs = set()
    helper_dst = installer.with_name("OneOSStorageHelper.smali")
    helper_text = helper_src.read_text(encoding="utf-8", errors="surrogateescape")
    if not helper_dst.exists() or helper_dst.read_text(encoding="utf-8", errors="surrogateescape") != helper_text:
        helper_dst.write_text(helper_text, encoding="utf-8", errors="surrogateescape")
        changed_dirs.add(top_dir(root, helper_dst))

    if patch_installer(installer):
        changed_dirs.add(top_dir(root, installer))
    if patch_clear_data(pms):
        changed_dirs.add(top_dir(root, pms))
    cache_lambda = patch_clear_cache(root)
    if cache_lambda is not None:
        changed_dirs.add(top_dir(root, cache_lambda))

    for d in sorted(changed_dirs):
        print(d)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
