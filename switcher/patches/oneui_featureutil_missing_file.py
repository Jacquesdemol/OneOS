#!/usr/bin/env python3
"""
oneui_featureutil_missing_file: silence logcat spam in Samsung FeatureUtil when
/optics or /omr carrier feature JSON paths are absent inside the guest container.

On a host One UI 8 device, One UI 9.5 guest framework.jar retains mdc.system.nw_path /
persist.sys.nw_path pointing at /optics/configs/.../customer_carrier_feature.json and
/omr/carrier/.../customer_carrier_feature.json, which do not exist in the guest rootfs.
FeatureUtil.getSecCarrierFeature logs W FeatureUtil: files does not exist from <path>
on every carrier lookup (~15,000+ lines per session). Returning null quietly when !exists()
for /optics/ or /omr/ stops the logcat/CPU overhead while preserving warnings elsewhere.

Usage: oneui_featureutil_missing_file.py <apktool -r dir>
"""
from pathlib import Path
import re
import sys

MARK = "    # OneOS: quiet null for missing /optics or /omr carrier JSONs (oneui_featureutil_missing_file.py)"

PATTERN = re.compile(
    r"(if-nez v4, :cond_1\s*\n\s*\n)"
    r"(\s*\.line \d+\s*\n)?"
    r"(\s*sget-object p1, Lcom/samsung/android/feature/FeatureUtil;->LOG_TAG:Ljava/lang/String;\s*\n\s*\n"
    r"\s*new-instance p2, Ljava/lang/StringBuilder;\s*\n\s*\n"
    r"\s*invoke-direct \{p2, v1\}, Ljava/lang/StringBuilder;-><init>\(Ljava/lang/String;\)V\s*\n\s*\n"
    r"\s*invoke-virtual \{p2, p0\}, Ljava/lang/StringBuilder;->append\(Ljava/lang/String;\)Ljava/lang/StringBuilder;\s*\n\s*\n"
    r"\s*invoke-virtual \{p2\}, Ljava/lang/StringBuilder;->toString\(\)Ljava/lang/String;\s*\n\s*\n"
    r"\s*move-result-object p2\s*\n\s*\n"
    r"\s*invoke-static \{p1, p2\}, Landroid/util/Log;->w\(Ljava/lang/String;Ljava/lang/String;\)I\s*\n\s*\n"
    r"\s*return-object v2)",
)


def patch_feature_util(path: Path) -> bool:
    text = path.read_text(encoding="utf-8", errors="surrogateescape")
    if MARK in text:
        return False
    if 'const-string/jumbo v1, "files does not exist from "' not in text:
        return False

    m = PATTERN.search(text)
    if not m:
        return False

    line_dir = m.group(2) or ""
    body = m.group(3)
    replacement = (
        f"{m.group(1)}"
        f"{MARK}\n"
        '    const-string v4, "/optics/"\n\n'
        "    invoke-virtual {p0, v4}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z\n\n"
        "    move-result v4\n\n"
        "    if-nez v4, :cond_oneos_quiet_missing\n\n"
        '    const-string v4, "/omr/"\n\n'
        "    invoke-virtual {p0, v4}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z\n\n"
        "    move-result v4\n\n"
        "    if-eqz v4, :cond_oneos_log_missing\n\n"
        "    :cond_oneos_quiet_missing\n"
        "    return-object v2\n\n"
        "    :cond_oneos_log_missing\n"
        f"{line_dir}{body}"
    )
    new_text = text[: m.start()] + replacement + text[m.end() :]
    path.write_text(new_text, encoding="utf-8", errors="surrogateescape")
    return True


def main() -> int:
    if len(sys.argv) != 2:
        sys.exit("usage: oneui_featureutil_missing_file.py <apktool -r dir>")
    root = Path(sys.argv[1])
    if not root.is_dir():
        sys.exit(f"oneui_featureutil_missing_file: {root} is not a directory")

    changed_dirs = []
    for smali in sorted(root.rglob("FeatureUtil.smali")):
        rel = smali.relative_to(root)
        if rel.as_posix().endswith("/com/samsung/android/feature/FeatureUtil.smali"):
            if patch_feature_util(smali):
                top = rel.parts[0]
                if top not in changed_dirs:
                    changed_dirs.append(top)

    if changed_dirs:
        print(" ".join(changed_dirs))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
