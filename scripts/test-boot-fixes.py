#!/usr/bin/env python3
"""
Regression tests for the OneOS guest boot failure fixes (oneui95-s948b on s-ultra-8450):
1. oneui_rtts_undeclared.py: waitForDeclaredService/waitForService -> checkService, idempotent, no-op when absent
2. ld.config.guest.txt awk logic from guest_prepare.sh: :sphal namespace links and optional CompOS skip
3. return_to_oneui.sh ip rule netId normalization + OneOS leftover rule detection
4. Script wiring assertions across oneos_switch.sh, guest_prepare.sh, guest_start.sh, guest_props.sh,
   return_to_oneui.sh, make-overlays.sh, patch-framework-camera.sh, and oneos_bpf_ringbuf.c.
"""
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent


def find_busybox() -> str:
    for candidate in (shutil.which("busybox"), "/data/adb/ksu/bin/busybox"):
        if candidate and Path(candidate).is_file():
            return candidate
    raise RuntimeError("busybox not found")


BB = find_busybox()


def test_rtts_patcher() -> None:
    patcher = ROOT / "switcher/patches/oneui_rtts_undeclared.py"
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        target = root / "smali_classes2/com/android/server/RttsService.smali"
        untouched = root / "smali/com/android/server/OtherService.smali"
        target.parent.mkdir(parents=True)
        untouched.parent.mkdir(parents=True)

        target.write_text(
            ".class public Lcom/android/server/RttsService;\n"
            ".super Ljava/lang/Object;\n"
            ".method public init()V\n"
            '    const-string v0, "vendor.samsung.hardware.security.rtts.ISehRealTimeToken/default"\n'
            "    invoke-static {v0}, Landroid/os/ServiceManager;->waitForDeclaredService(Ljava/lang/String;)Landroid/os/IBinder;\n"
            "    invoke-static {v0}, Landroid/os/ServiceManager;->waitForService(Ljava/lang/String;)Landroid/os/IBinder;\n"
            "    return-void\n"
            ".end method\n"
        )
        orig_other = (
            ".class public Lcom/android/server/OtherService;\n"
            ".super Ljava/lang/Object;\n"
            ".method public init()V\n"
            '    const-string v0, "other.service"\n'
            "    invoke-static {v0}, Landroid/os/ServiceManager;->waitForService(Ljava/lang/String;)Landroid/os/IBinder;\n"
            "    return-void\n"
            ".end method\n"
        )
        untouched.write_text(orig_other)

        r1 = subprocess.run([sys.executable, str(patcher), str(root)], capture_output=True, text=True, check=True)
        assert r1.stdout.strip() == "smali_classes2", f"unexpected output: {r1.stdout!r}"
        patched = target.read_text()
        assert "waitForDeclaredService" not in patched
        assert "waitForService" not in patched
        assert patched.count("ServiceManager;->checkService(") == 2
        assert untouched.read_text() == orig_other

        # Idempotent second run
        r2 = subprocess.run([sys.executable, str(patcher), str(root)], capture_output=True, text=True, check=True)
        assert r2.stdout.strip() == "", f"expected empty on second run: {r2.stdout!r}"


def test_ld_config_awk() -> None:
    gp = (ROOT / "switcher/assets/guest_prepare.sh").read_text()
    m = re.search(
        r"    if \$BB awk '\n(.*?)\n' \"\$STAGE/ld\.config\.guest\.req\"",
        gp,
        re.DOTALL,
    )
    assert m, "could not extract ld.config awk program from guest_prepare.sh"
    awk_src = m.group(1)

    with tempfile.TemporaryDirectory() as td:
        p = Path(td)
        req = p / "req.txt"
        gld = p / "ld.config.txt"
        req.write_text(
            "com_android_media :sphal libfoo.so\n"
            "com_android_art libcom_android_compos_ffi_c.so\n"
        )
        gld.write_text(
            "[vendor]\n"
            "namespace.com_android_media.links = default,system\n"
            "namespace.com_android_media.link.system.shared_libs = libc.so\n"
            "namespace.sphal.isolated = false\n"
            "namespace.system.link.other.shared_libs = libfoo.so\n"
            "[system]\n"
            "namespace.com_android_media.links = default\n"
            "namespace.com_android_art.links = default\n"
        )
        res = subprocess.run(
            [BB, "awk", awk_src, str(req), str(gld), str(gld)],
            capture_output=True,
            text=True,
            check=True,
        )
        assert "namespace.com_android_media.links = default,system,sphal" in res.stdout
        assert "namespace.com_android_media.link.sphal.allow_all_shared_libs = true" in res.stdout
        assert "no namespace provides :sphal, left out" not in res.stderr
        assert "no namespace provides libcom_android_compos_ffi_c.so, left out" not in res.stderr
        assert "optional namespace :sphal absent on host, skipped" in res.stderr
        assert "optional libcom_android_compos_ffi_c.so absent" in res.stderr


def test_return_ip_rule_norm() -> None:
    ret = (ROOT / "switcher/assets/return_to_oneui.sh").read_text()
    assert "fwmark 0x(1|5)?[0-9a-f]+\\/0x1?ffff" in ret
    assert "LEFTOVER_ONEOS_RULE" in ret

    # Simulate awk check from return_to_oneui.sh
    m = re.search(r"ip rule show 2>/dev/null \| awk '\n(.*?)\n' >", ret, re.DOTALL)
    assert m, "could not extract ip rule awk block"
    awk_src = m.group(1)

    base = (
        "0:\tfrom all lookup local\n"
        "10000:\tfrom all fwmark 0xc0000/0xd0000 lookup legacy_system\n"
        "13000:\tfrom all fwmark 0x100d8/0x1ffff iif lo lookup local_network\n"
        "16000:\tfrom all fwmark 0xd8/0xffff lookup wlan0\n"
    )
    now_ok = base.replace("0x100d8", "0x10065").replace("0xd8/0xffff", "0x65/0xffff")
    now_dirty = now_ok + "14999:\tfrom all iif lo lookup 4242\n"

    sed_norm = lambda s: re.sub(r"fwmark 0x(1|5)?[0-9a-f]+/0x1?ffff", "fwmark 0xNETID/0xMASK", s)

    r_ok = subprocess.run([BB, "awk", awk_src], input=now_ok, capture_output=True, text=True)
    assert r_ok.returncode == 0
    assert r_ok.stdout == sed_norm(base), f"diff:\n{r_ok.stdout!r}\nvs\n{sed_norm(base)!r}"

    r_dirty = subprocess.run([BB, "awk", awk_src], input=now_dirty, capture_output=True, text=True)
    assert r_dirty.returncode == 1
    assert "LEFTOVER_ONEOS_RULE" in r_dirty.stdout


def test_wired_fixes() -> None:
    sw = (ROOT / "switcher/assets/oneos_switch.sh").read_text()
    assert '-T "$SW_SINCE"' in sw
    assert "ONEOS_VERBOSE_SOUND_LOG" in sw
    assert "capture_guest_crash.sh" in sw
    assert "P=$(pidof surfaceflinger | awk '{print $1}')" in sw or 'P=\\$(pidof surfaceflinger' in sw
    assert "ip -o link show dev wlan0" in sw

    gp = (ROOT / "switcher/assets/guest_prepare.sh").read_text()
    # diag_host/bpf_netd_shared.txt runs AFTER array-create and uses ls -lZ
    assert gp.index("array-create") < gp.index("bpf_netd_shared.txt")
    assert "ls -lZ /sys/fs/bpf/netd_shared" in gp
    assert "bind_first_host_app" in gp
    assert "/system/priv-app/NetworkStackGoogle" in gp
    assert "$ROOTFS/system/app/CaptivePortalLoginGoogle" in gp
    assert "sysext_framework_farm" in gp
    assert "product_displayconfig_farm" in gp
    assert "/data/oneos_bin/su" in gp
    assert "guest permission payload or One UI platform present" in gp

    gs = (ROOT / "switcher/assets/guest_start.sh").read_text()
    assert 'if [ ! -f "$ADC/dalvik-cache/arm64/boot.art" ]' in gs
    assert "/data/oneos_bin" in gs

    gprops = (ROOT / "switcher/assets/guest_props.sh").read_text()
    for prop in (
        "dalvik.vm.boot-dex2oat-threads=2",
        "dalvik.vm.dex2oat-threads=2",
        "dalvik.vm.dex2oat-Xmx=512m",
        "dalvik.vm.dex2oat-minidebuginfo=false",
    ):
        assert prop in gprops

    bpf_c = (ROOT / "switcher/native/oneos_bpf_ringbuf.c").read_text()
    assert "/proc/thread-self/attr/fscreate" in bpf_c
    assert bpf_c.index("open_fscreate") < bpf_c.index("sys_bpf(BPF_OBJ_PIN")
    assert "gid = is_prog ? 0 : 1006" in bpf_c
    assert "(size_t)label_len + 1" in bpf_c

    build_sh = (ROOT / "switcher/native/build.sh").read_text()
    assert "zig cc -target aarch64-linux-musl -static" in build_sh
    assert "clang -static" in build_sh

    assert "zygote|system_server" in sw
    assert "guest_crash.log" in (ROOT / "app-android/app/src/full/assets/logs/collect.list").read_text()
    assert "guest_crash.log" in (ROOT / "scripts/pull-run-logs.sh").read_text()

    assert "oneui_rtts_undeclared.py" in (ROOT / "scripts/make-overlays.sh").read_text()
    assert "oneui_rtts_undeclared.py" in (ROOT / "scripts/patch-framework-camera.sh").read_text()


def main() -> int:
    tests = [test_rtts_patcher, test_ld_config_awk, test_return_ip_rule_norm, test_wired_fixes]
    for fn in tests:
        fn()
        print(f"PASS {fn.__name__}")
    print(f"ALL {len(tests)} BOOT FIX TESTS PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
