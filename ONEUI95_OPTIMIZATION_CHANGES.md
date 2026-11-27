# One UI 9.5 Guest Optimization & Log Audit Summary

This document summarizes all code, script, and patched-JAR changes implemented for the **One UI 9.5 (`oneui95-s948b` / `SM-S948B`)** guest running over a **One UI 8 (`SM-S908E` / `b0q`)** host, based on telemetry and log captures in `oneos-logs-7cae41757be6a70f` and requirements in [optimize.md](file:///data/data/com.termux/files/home/agy/optimize.md).

---

## 1. Root Cause Analysis of Idle CPU Drain, Heat, and Deep Sleep Blockers (P0)

Log forensics across `capture/data_local_tmp_oneos_logs_boot.log*` and `diag/data_local_tmp_oneos_diag_*_power.txt` established that the 45 °C idle heat and failure to enter Deep Sleep were caused by continuous HAL wait loops in `system_server`, a 24/7 software hotword microphone capture loop, and excessive missing-file warning logging—**not** hardware Play Integrity attestation failures:

| Offender / Symptom | Observed Evidence in Session Logs | Implemented Fix |
| :--- | :--- | :--- |
| **`word_detector_0` (`u0ai9000` / UID `99000`) & `AudioIn` Wakelock** | Because OneOS enables `debug.soundtrigger_middleware.use_mock_hal=true` (preventing QTI `audio_pd` / SoundTrigger VTS crashes), Google App fell back to software hotword detection (`VoiceInteractionManagerService.startListeningFromMic()`), keeping mic input active continuously. Logs recorded `am_cpu: [17182,99000,word_detector_0,35885,1350,740]` and `[17182,99000,word_detector_0,54577,3200,1530]`, plus `PARTIAL_WAKE_LOCK 'AudioIn' ACQ=-3m42s637ms LONG` (`WorkChain{(99000), (0)}`). | Added [guest_no_sw_hotword.py](file:///data/data/com.termux/files/home/agy/OneOS/switcher/patches/guest_no_sw_hotword.py) (patched into `services_patched.jar`). |
| **`SemService` / `ISehSem/default` 1s `waitForService` loop** | **4,353** failure entries across `boot.log*` querying `vendor.samsung.hardware.security.sem.ISehSem/default` (absent on the One UI 8 host), blocking `system_server` binder threads inside `libsec_sem.so` C++ `waitForService`. | Created [oneui_sem_undeclared.py](file:///data/data/com.termux/files/home/agy/OneOS/switcher/patches/oneui_sem_undeclared.py) (patched into `framework_patched*.jar`). |
| **`RealTimeTokenService` / `ISehRealTimeToken/default` 1s `waitForService` loop** | **2,163** failure entries across `boot.log*` querying `vendor.samsung.hardware.security.rtts.ISehRealTimeToken/default`. In One UI 9.5, `librtts_jni.so` performs `waitForService` natively, bypassing smali `ServiceManager.waitForService` rewrites alone. | Updated [oneui_rtts_undeclared.py](file:///data/data/com.termux/files/home/agy/OneOS/switcher/patches/oneui_rtts_undeclared.py) (patched into `framework_patched*.jar`). |
| **`FeatureUtil` missing `/optics/` & `/omr/` JSON log spam** | **15,307** `W FeatureUtil: files does not exist` warnings across `boot.log*` because carrier JSON paths under `/optics/` and `/omr/` do not exist in the guest rootfs, generating continuous logd/CPU churn. | Created [oneui_featureutil_missing_file.py](file:///data/data/com.termux/files/home/agy/OneOS/switcher/patches/oneui_featureutil_missing_file.py) (patched into `framework_patched*.jar`). |

---

## 2. Detailed Code & Script Changes

### 2.1. Block Background Software Hotword Capture (`guest_no_sw_hotword.py`)
- **File:** [guest_no_sw_hotword.py](file:///data/data/com.termux/files/home/agy/OneOS/switcher/patches/guest_no_sw_hotword.py)
- **Target:** `services.jar` -> `com/android/server/voiceinteraction/VoiceInteractionManagerService$VoiceInteractionManagerServiceStub.smali`
- **What & Why:** Guards `startListeningFromMic(AudioFormat, IMicrophoneHotwordDetectionVoiceInteractionCallback)` with `SystemProperties.getBoolean("debug.soundtrigger_middleware.use_mock_hal", false)`. When mock SoundTrigger HAL is active in a OneOS session, it logs `OneOS: software startListeningFromMic skipped (use_mock_hal active)` and returns early. This eliminates the 24/7 `word_detector_0` CPU drain and permanent `AudioIn` partial wakelock that prevented Deep Sleep, while leaving explicit user-initiated microphone input (dictation, voice messages, assistant button) untouched.

### 2.2. Guard `SemService` Against Missing `ISehSem/default` (`oneui_sem_undeclared.py`)
- **File:** [oneui_sem_undeclared.py](file:///data/data/com.termux/files/home/agy/OneOS/switcher/patches/oneui_sem_undeclared.py)
- **Target:** `framework.jar` -> `com/android/server/SemService.smali`
- **What & Why:** Injects a private static helper `SemService.oneosHasSemHal()Z` that runs non-blocking `ServiceManager.checkService("vendor.samsung.hardware.security.sem.ISehSem/default") != null`, then prepends a fast-fail guard to `requestSpiUsage()Z` and the 18 Java caller methods (`UNGUARDED_NATIVE_CALLERS`, e.g., `ICD`, `encData`, `grdm_*`, `send_Data`, `close_Spi`, `synchronizedCloseSpi`, etc.) that otherwise invoke native `libsec_sem.so` calls and trigger C++ `waitForService` stalls.

### 2.3. Guard Native `librtts_jni.so` Paths in `RealTimeTokenService` & `TokenJniBridge` (`oneui_rtts_undeclared.py`)
- **File:** [oneui_rtts_undeclared.py](file:///data/data/com.termux/files/home/agy/OneOS/switcher/patches/oneui_rtts_undeclared.py)
- **Target:** `framework.jar` (`services.jar` also checked for Java `waitFor*`) -> `RealTimeTokenService` and `TokenJniBridge`
- **What & Why:** Kept the existing Java `ServiceManager.waitForDeclaredService` / `waitForService` -> `checkService` rewrite, and added smali entry guards to `RealTimeTokenService.initialize()Z` (returns `false`) and `TokenJniBridge.*Wrapper(...)I` (returns `-0x65` / `-101`) whenever `ServiceManager.checkService("vendor.samsung.hardware.security.rtts.ISehRealTimeToken/default")` returns `null`. This prevents C++ `librtts_jni.so` from ever blocking in `waitForService`.

### 2.4. Silence Missing `/optics/` & `/omr/` Warnings in `FeatureUtil` (`oneui_featureutil_missing_file.py`)
- **File:** [oneui_featureutil_missing_file.py](file:///data/data/com.termux/files/home/agy/OneOS/switcher/patches/oneui_featureutil_missing_file.py)
- **Target:** `framework.jar` -> `com/samsung/android/feature/FeatureUtil.smali`
- **What & Why:** When `FeatureUtil.getSecCarrierFeature` encounters `!File.exists()` for a path starting with `/optics/` or `/omr/`, it returns `null` quietly without calling `Log.w(...)`, eliminating ~15,307 repeated log lines and associated `logd` CPU overhead while preserving missing-file warnings for all other paths.

### 2.5. Create Placeholder `Kumiho.policy` on One UI 8 Hosts (`guest_prepare.sh`)
- **File:** [guest_prepare.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_prepare.sh#L8273-L8288)
- **What & Why:** `com.samsung.android.messageguardsync` crashed at boot with `java.io.FileNotFoundException: /data/system/Kumiho/Kumiho.policy: open failed: ENOENT` (`stage/data_local_tmp_oneos_guest_crash.log:30-48`) because the One UI 8 host lacks `/data/system/Kumiho/Kumiho.policy` (`guest_prepare.sh` previously only copied the policy when present on the host). Updated the script to create an empty `/data/system/Kumiho/Kumiho.policy` (`1000:1000`, dir `0755`, file `0644`, SELinux context `u:object_r:system_data_file:s0`) when absent on the host.

### 2.6. Overlay Build Wiring, Rebuilt Overlay JARs, and Automated Tests
- **Build scripts:**
  - [scripts/make-overlays.sh](file:///data/data/com.termux/files/home/agy/OneOS/scripts/make-overlays.sh) now applies `guest_no_sw_hotword.py` when building `services_patched.jar`.
  - [scripts/patch-framework-camera.sh](file:///data/data/com.termux/files/home/agy/OneOS/scripts/patch-framework-camera.sh) now applies `oneui_sem_undeclared.py` and `oneui_featureutil_missing_file.py` alongside `oneui_rtts_undeclared.py` when building `framework_patched*.jar`.
- **Rebuilt & hash-verified overlay packages:**
  - [packages/oneui95/](file:///data/data/com.termux/files/home/agy/OneOS/packages/oneui95): `services_patched.jar`, `framework_patched.jar`, and updated [guards.txt](file:///data/data/com.termux/files/home/agy/OneOS/packages/oneui95/guards.txt).
  - [packages/oneui95-a16/](file:///data/data/com.termux/files/home/agy/OneOS/packages/oneui95-a16): `services_patched.jar`, `framework_patched.jar`, `framework_patched_oneui8.jar`, `framework_patched_oneui85.jar`, and updated [guards.txt](file:///data/data/com.termux/files/home/agy/OneOS/packages/oneui95-a16/guards.txt).
- **Unit tests:**
  - Updated [scripts/test-boot-fixes.py](file:///data/data/com.termux/files/home/agy/OneOS/scripts/test-boot-fixes.py) with synthetic smali coverage and idempotency checks for `oneui_rtts_undeclared.py`, `oneui_sem_undeclared.py`, `oneui_featureutil_missing_file.py`, `guest_no_sw_hotword.py`, `guest_prepare.sh` Kumiho placeholder, and script wiring.

---

## 3. Additional Log Audits & Findings Not Requiring Code Edits (P1 & P2)

### 3.1. Secondary Log Errors Audited & Intentionally Left Unpatched
- **`QtiSurfaceFlingerExtension: Failed to send SET_CORNER_RADIUS command to HWC` (`17,550` warnings):** Warning log emitted by `surfaceflinger` when HWC rejects rounded-corner ioctl commands; non-blocking and not a spin-loop bottleneck.
- **`SemWifiBpf-JNI` (`2,162` lines):** Fails to open host BPF maps (`nativeGetUidTrafficStats`, `nativeGetTrafficTimeStats`); isolated to non-fatal traffic stats queries.
- **`NfcAntennaGuideDialog` (`58` lines):** UI resource/helper warnings when NFC overlay guides aren't applicable.
- **`com.samsung.aasaservice` crash (`guest_crash.log:8-29`):** Crashes with `java.lang.SecurityException: Root detected` inside `StorageManagerService.validateKeepCaller` while calling `SemPassStorageManager.create`. Expected behavior while the session runs with KernelSU / SELinux Permissive.

### 3.2. FOD (Fingerprint on Display) Verification (`optimize.md` §2.3)
- `boot.log` confirms the ultrasonic FOD HAL and framework communication initialized properly (`bauth_service: BAuthService, 429`, `bauth_FPBAuthService: ncmi : S90X, ncpf : 0`), along with touch/UDFPS coordination (`FingerprintServiceInternal: handleFodEvent`, `FingerprintService.Ext: showUdfpsIcon`, `setTspMode`). FOD did not exhibit a daemon crash or spin loop causing `system_server` overheating.

### 3.3. Samsung Gallery AI Diagnostics (`optimize.md` §2.5)
- `boot.log.01` shows `PhotoEditor_AIFull` (`com.sec.android.mimage.photoretouching`) crashing with fatal `DEBUG` traces in `libPetDetector_v1.camera.samsung.so` right after logging:
  `I VIZ_PET_DETECTOR_MULTIF: Models path :/vendor/etc/petdetector/`
- Root cause: One UI 9.5 Photo Editor native pet-detector library expects model assets under `/vendor/etc/petdetector/`, which are absent on the One UI 8 host `/vendor` partition.

---

## 4. Play Integrity Technical Feasibility Reconsideration (`optimize.md` §2.4 — Study Only)

> **Policy Reminder:** Zero Play Integrity spoofing/bypass code changes were made. See the full technical study in [ESTUDO_PLAY_INTEGRITY.md](file:///data/data/com.termux/files/home/agy/OneOS/ESTUDO_PLAY_INTEGRITY.md).

### Key Reconsideration: Post-One UI 8 Locked Bootloader & Host `MEETS_STRONG_INTEGRITY`
On Galaxy `SM-S908E` post-One UI 8, the bootloader cannot be unlocked, and the session logs directly confirm genuine hardware integrity at boot (`boot/last_kmsg.txt`):
- `androidboot.vbmeta.device_state=locked`
- `VB2: Authenticate complete! boot state is: green`
- `VB2: The device unlocked 0 times` (`boot state: green(0)`)
- Furthermore, your **main One UI 8 host system already passes `MEETS_STRONG_INTEGRITY`**, confirming an intact OEM hardware TEE KeyMint keybox and valid verified boot state.

Consequently, failure inside the OneOS guest is **not** caused by an unlocked bootloader or missing OEM keybox (and third-party leaked keyboxes are unnecessary). Instead, the observed blockers are container/runtime-level:

1. **Keystore2 RKPD / Attestation Record Error in Guest Session:**
   - Although standard `keystore2` operations succeed (`boot.log:2047+`) and `guest_props.sh` protects `*_for_attestation` props and `ro.build.version.sdk`, requests needing attestation/RKPD inside the session fail in `keystore2` (`boot.log:4856-4870`: `Failed to get rpc for sec level r#TRUSTED_ENVIRONMENT` -> `Error::Km(r#HARDWARE_TYPE_UNAVAILABLE)`) and in Play Store (`store_watch.log:37,42`: `E Finsky : [210] Integrity key attestation record generation failed.`).
2. **Runtime Tamper Visibility (`SELinux Permissive` + KernelSU + Mounts):**
   - [guest_start.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_start.sh#L1156) notes `oneos_switch.sh` applies `setenforce 0`, and `guest/data_local_tmp_oneos_data_oneos_sm_diag.log` confirms `enforce=0 getenforce=Permissive` and `ctx=u:r:ksu:s0`.
   - Note that OneOS's `ONEOS_GUEST_HIDE_GMS` setting (`hide-gms 0` in `takeover_app.log:2849`) only hides Google APKs via empty `tmpfs` mounts to disable GMS—it does **not** hide root or SELinux Permissive from DroidGuard.
3. **Model Split (`SM-S948B` vs `SM-S908E` TEE Identity):**
   - `guest_props.sh:2089-2102` overrides `ro.product.*.model` / `ro.boot.em.model` to `SM-S948B` (One UI 9.5) for Galaxy Store compatibility while the host TEE/AVB attests `SM-S908E` (`b0q`, `S908EXXSEGZH4`), creating a potential metadata mismatch for GMS server-side validation.

**Revised Verdict:** `MEETS_DEVICE_INTEGRITY` and `MEETS_STRONG_INTEGRITY` are **architecturally possible in principle using your phone's genuine hardware TEE (without external leaked keyboxes)**, **if** future work resolves: (1) the `keystore2` `HARDWARE_TYPE_UNAVAILABLE` / Finsky attestation failure inside the guest container, (2) running without exposing `SELinux Permissive`, `KernelSU`, or container mounts to `com.google.android.gms.unstable`, and (3) aligning GMS-visible device model/build props cleanly with the `SM-S908E` hardware certificate.
