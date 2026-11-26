"""One UI 9.5's framework.jar against a One UI 8 cameraserver (framework_patched_oneui8.jar, 0.5.39).

Run by scripts/patch-built-jar.sh on the oneui95-a16 set's framework_patched.jar (every other patch of
that set kept): the camera interface classes are first put back as the image
has them (from ONEOS_CAMERA_ORIG_DECODED, the image's framework.jar decoded by apktool; the set's copies
carry One UI 9.0's numbering), then pixel_camera_abi.py renumbers them to switcher/patches/
host_camera_oneui8.txt with ONEOS_CAMERA_COMPAT_AS_INT=1, and a16_camera_batch_off.py keeps stream
configuration on the calls One UI 8 has.

ONEOS_A16_CAMERA_HOST=oneui85 (framework_patched_oneui85.jar, 0.5.45): a One UI 8.5 cameraserver instead
(switcher/patches/host_camera_oneui85.txt, read on the S23 FE): One UI 8's numbering but One UI 9.0's
CameraCompatibilityInfo parcelable, so the parcelable is written as it is (no ONEOS_CAMERA_COMPAT_AS_INT;
ONEOS_CAMERA_COMPAT_FROM_JAR: the set's jar already writes it without the sixth field, as for One UI 9.0). The One UI 8 jar on such a phone sent an int there and
cameraserver answered every getCameraCharacteristics with a NullPointerException (S23 FE 8a093c12, A35
5434447d: no camera; Samsung's video call engine com.sec.sve restarted about 2,000 times a session).

Usage: [ONEOS_A16_CAMERA_HOST=oneui85] ONEOS_CAMERA_ORIG_DECODED=<dir> a16_camera.py <decoded jar dir>
"""
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CLASSES = [
    "android/hardware/ICameraService$Stub$Proxy.smali", "android/hardware/ICameraService$Stub.smali",
    "android/hardware/ICameraServiceListener$Stub$Proxy.smali", "android/hardware/ICameraServiceListener$Stub.smali",
    "android/hardware/camera2/ICameraDeviceUser$Stub$Proxy.smali", "android/hardware/camera2/ICameraDeviceUser$Stub.smali",
    "android/hardware/camera2/ICameraDeviceCallbacks$Stub$Proxy.smali", "android/hardware/camera2/ICameraDeviceCallbacks$Stub.smali",
]
CAPTURE_RESULT_EXTRAS = "android/hardware/camera2/impl/CaptureResultExtras.smali"
MULTI_RES_READ_RE = re.compile(
    r'([ \t]*)sget-object ([vp]\d+), Landroid/hardware/camera2/impl/MultiResConcurrentReadersStartInfo;->CREATOR:Landroid/os/Parcelable\$Creator;\s+'
    r'(?:\.line \d+\s+)?'
    r'invoke-virtual \{[vp]\d+, \2\}, Landroid/os/Parcel;->createTypedArray\(Landroid/os/Parcelable\$Creator;\)\[Ljava/lang/Object;\s+'
    r'(?:\.line \d+\s+)?'
    r'move-result-object ([vp]\d+)\s+'
    r'(?:\.line \d+\s+)?'
    r'check-cast \3, \[Landroid/hardware/camera2/impl/MultiResConcurrentReadersStartInfo;\s+'
    r'(?:\.line \d+\s+)?'
    r'iput-object \3, p0, Landroid/hardware/camera2/impl/CaptureResultExtras;->multiResConcurrentReadersStartInfo:\[Landroid/hardware/camera2/impl/MultiResConcurrentReadersStartInfo;'
)


def where(root, cls):
    hits = [d for d in sorted(os.listdir(root)) if d.startswith("smali") and os.path.isfile(os.path.join(root, d, cls))]
    if len(hits) != 1:
        sys.exit(f"a16_camera: {cls} found {len(hits)} times under {root}")
    return hits[0]


def patch_capture_result_extras(root, orig):
    if not any(os.path.isfile(os.path.join(root, d, CAPTURE_RESULT_EXTRAS)) for d in os.listdir(root) if d.startswith("smali")):
        return None
    d, o = where(root, CAPTURE_RESULT_EXTRAS), where(orig, CAPTURE_RESULT_EXTRAS)
    if d != o:
        sys.exit(f"a16_camera: {CAPTURE_RESULT_EXTRAS} in {d} here, {o} in the image")
    dst = os.path.join(root, d, CAPTURE_RESULT_EXTRAS)
    shutil.copyfile(os.path.join(orig, o, CAPTURE_RESULT_EXTRAS), dst)
    with open(dst, "r", encoding="utf-8") as fh:
        text = fh.read()
    if "multiResConcurrentReadersStartInfo" not in text:
        return None
    m_method = re.search(r'(\.method [^\n]*\breadFromParcel\(Landroid/os/Parcel;\)V\n.*?\n\.end method)', text, re.S)
    if not m_method:
        sys.exit("a16_camera: CaptureResultExtras.readFromParcel(Parcel) not found")
    body = m_method.group(1)
    patched_body, n = MULTI_RES_READ_RE.subn(
        r'\1# OneOS: One UI 8/8.5 cameraserver (Android 16) does not write MultiResConcurrentReadersStartInfo[].\n'
        r'\1const/4 \2, 0x0\n\n'
        r'\1iput-object \2, p0, Landroid/hardware/camera2/impl/CaptureResultExtras;->multiResConcurrentReadersStartInfo:[Landroid/hardware/camera2/impl/MultiResConcurrentReadersStartInfo;',
        body,
    )
    if n != 1:
        sys.exit(f"a16_camera: unexpected MultiResConcurrentReadersStartInfo pattern count ({n}) in CaptureResultExtras.readFromParcel")
    text = text[:m_method.start(1)] + patched_body + text[m_method.end(1):]
    with open(dst, "w", encoding="utf-8") as fh:
        fh.write(text)
    print("a16_camera: skipped Android 17 MultiResConcurrentReadersStartInfo read in CaptureResultExtras.readFromParcel", file=sys.stderr)
    return d


def main(root):
    orig = os.environ.get("ONEOS_CAMERA_ORIG_DECODED")
    if not orig or not os.path.isdir(orig):
        sys.exit("a16_camera: ONEOS_CAMERA_ORIG_DECODED (the image's decoded framework.jar) is required")
    dirs = set()
    for cls in CLASSES:
        d, o = where(root, cls), where(orig, cls)
        if d != o:
            sys.exit(f"a16_camera: {cls} in {d} here, {o} in the image")
        shutil.copyfile(os.path.join(orig, o, cls), os.path.join(root, d, cls))
        dirs.add(d)
    cre_dir = patch_capture_result_extras(root, orig)
    if cre_dir:
        dirs.add(cre_dir)
    host = os.environ.get("ONEOS_A16_CAMERA_HOST", "oneui8")
    if host not in ("oneui8", "oneui85"):
        sys.exit(f"a16_camera: ONEOS_A16_CAMERA_HOST={host} (oneui8 or oneui85)")
    env = dict(os.environ)
    env.pop("ONEOS_CAMERA_KEEP_COMPAT", None)
    env.pop("ONEOS_CAMERA_COMPAT_AS_INT", None)
    if host == "oneui8":
        env["ONEOS_CAMERA_COMPAT_AS_INT"] = "1"
    else:
        env["ONEOS_CAMERA_COMPAT_FROM_JAR"] = "1"
    for cmd in (["python3", "-I", os.path.join(HERE, "pixel_camera_abi.py"), root, os.path.join(HERE, f"host_camera_{host}.txt")],
                ["python3", "-I", os.path.join(HERE, "a16_camera_batch_off.py"), root]):
        r = subprocess.run(cmd, capture_output=True, text=True, env=env)
        sys.stderr.write(r.stderr)
        if r.returncode != 0:
            sys.exit(f"a16_camera: {os.path.basename(cmd[2])} failed")
        dirs.update(l for l in r.stdout.split() if l)
    for d in sorted(dirs):
        print(d)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
