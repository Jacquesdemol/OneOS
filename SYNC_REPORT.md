# OneOS Upstream Sync Report

- **Sync Date:** 2026-11-25
- **Local Base Commit:** `4d13f58` (`Update OneOS`, branched from `393f9bd` / `v0.5.44`)
- **Upstream Target:** `7d65def` (`upstream/main` / `v0.5.46`)
- **Merge Commit:** `2d4d0d1` (`Merge upstream/main (v0.5.45..v0.5.46) with --theirs on conflict`)

## 1. Synced Upstream Commits

| Commit | Tag / Summary | Key Highlights |
|---|---|---|
| `fb49dbd` | `v0.5.45` (`OneOS 0.5.45`) | Added `map_netd_tcp_metrics_ringbuf` support (`guest_prepare.sh`, `return_to_oneui.sh`), One UI 8.5 camera compat (`framework_patched_oneui85.jar`, `host_camera_oneui85.txt`, `a16_camera.py`), Exynos `Hey Google` hotword mitigation, S Pen tablet fix, better shutdown/reboot attribution. |
| `5501087` | `Credits: pull requests #355, #358, #361, #362` | Updated `README.md` credits. |
| `7d65def` | `v0.5.46` (`OneOS 0.5.46`) | Restored "Send to the OneOS team" UI, Galaxy Tab ColorOS power-off alarm fix & stage-owner fix, Galaxy Tab S9 FE (`SM-X510`) support (brightness RRO, camera compat shim, Himax TDDI `aot_enable` fix, `OneOSCutoutFill`), Z Flip5 OriginOS 7 support. |

## 2. Conflict Resolution (`--theirs` / Upstream Priority)

Per `Sync.md` Phase 1.3, any merge conflict was resolved strictly in favor of upstream (`--theirs`).

### Conflicted & Overwritten File

- **`switcher/assets/guest_prepare.sh`**
  - **Conflict Cause:** Upstream `v0.5.46` modified the `system_ext` overlay staging block around `$OV/up/framework` to add `TAB_CAM_COMPAT` (`OneOSTabS9FeCamCompat.jar`) and change `rm -rf "$OV/up/framework"` to `rm -f "$OV/up/framework/oneos-telephony.jar"`, colliding with local commit `4d13f58` (`WANT_INJ=$INJ` and `rm -rf "$OV/up/framework"`).
  - **Resolution:** Executed `git checkout --theirs switcher/assets/guest_prepare.sh`.
  - **Local `4d13f58` Hunks Overwritten by `--theirs`:**
    1. Non-overlayfs `farm_dir` fallbacks for `oneos-telephony.jar`, `oneos-my-product-features.xml`, and `product/etc/displayconfig/`.
    2. `/data/oneos_bin/su` KernelSU bridge fallback when `$ROOTFS/system/xbin` does not exist.
    3. `ls -lZ /sys/fs/bpf/netd_shared` context inspection output.
    4. `ld.config` `:sphal` namespace alias resolution and `libcom_android_compos_ffi_c.so` missing-namespace gate.
    5. `bind_first_host_app` path probing for `NetworkStackGoogle` / `CaptivePortalLoginGoogle`.
    6. `PermissionController_patched.apk` missing warning suppression for images with `com.android.permission` or One UI platform.

*(Note: Any of the above `guest_prepare.sh` items still evidenced as failing in `oneos-logs` are evaluated and re-applied cleanly in Phases 2–4.)*

## 3. Cleanly Auto-Merged & Preserved Local Files

### Auto-Merged Files
- `switcher/assets/guest_props.sh`
- `switcher/assets/guest_start.sh`
- `switcher/assets/oneos_switch.sh`
- `switcher/assets/return_to_oneui.sh`

### Preserved Local-Only Files (`4d13f58`)
- `app-android/app/src/full/assets/logs/collect.list`
- `scripts/make-overlays.sh`
- `scripts/patch-framework-camera.sh`
- `scripts/pull-run-logs.sh`
- `scripts/test-boot-fixes.py`
- `switcher/assets/oneos_bpf_ringbuf`
- `switcher/native/build.sh`
- `switcher/native/oneos_bpf_ringbuf.c`
- `switcher/patches/oneui_rtts_undeclared.py`
