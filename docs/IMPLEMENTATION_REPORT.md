# OneOS — Implementation, Build, Validation, and Technical Report

**Target Hardware & Host Environment:**
- **Device:** Samsung Galaxy S22 Ultra
- **Strictly Supported Variant:** `SM-S908E` (and dual-SIM `SM-S908E/DS`)
- **Codename (`ro.product.device`):** `b0q`
- **SoC:** Qualcomm Snapdragon 8 Gen 1 (`SM8450`; normalizes both `"taro"` and `"snd8g1"` platform identifiers)
- **Host OS:** Samsung One UI 8.0 / 8.5 (Android 16, SDK 36)
- **Architecture:** `arm64-v8a` (Cortex-X2 / Cortex-A710 / Cortex-A510; no SVE/SME)

---

## Section A — Executive Summary

### 1. What Was Implemented
1. **Strict `SM-S908E` Device Isolation & `"snd8g1"` SoC Normalization (`:core:model`, `oneos_root.sh`, `docs/`):**
   - Restricted the Snapdragon Galaxy S22 Ultra profile (`Profiles.S_ULTRA_8450`, id `s-ultra-8450`) to match **only** `SM-S908E` and `SM-S908E/DS` with codename `b0q` and SoC `SM8450`.
   - Unvalidated regional Snapdragon variants (`SM-S908U`, `SM-S908U1`, `SM-S908W`, `SM-S9080`, `SM-S908N`) are rejected at profile resolution and in `oneos_root.sh`.
   - Explicitly disabled the Exynos S22 Ultra profile (`Profiles.S_ULTRA_9925`, `SM-S908B` / `b0s` / `s5e9925`) with a clear `disabledReason`.
   - Added `"snd8g1"` normalization to `"SM8450"` (`SocVendor.QUALCOMM`, `"Snapdragon 8 Gen 1"`) in both `Soc.kt` (`KNOWN_PLATFORMS` and `resolve()`) and `oneos_root.sh` (`soc_model()`).
2. **Universal GSI Image Inspection & Streaming Import Engine (`:core:image`):**
   - Implemented `ContainerProbe`, `SparseReader`, `HoleWriter`, `FsProbe`, and `ImageImporter` supporting `.img` (raw ext4/EROFS and Android sparse `0xED26FF3A`), `.gz`, `.xz`, and `.zip` (with Zip-Slip and zip-bomb ratio enforcement), streaming SHA-256 verification, 4096-byte hole punching (`lseek`/sparse extent preservation), and ext4/EROFS superblock validation.
3. **Selective DEX Engine (`:core:dex`):**
   - Implemented `DexJar`, `SelectorScan`, `SmaliTree`, `Reassembler`, `DexRewriter`, `JarWriter`, and `DexChecks` (`EmptyTryRangeCheck`, `OrphanMoveResultCheck`, `RoundTripCheck`) on `smali-dexlib2:3.0.10`, `smali-baksmali:3.0.10`, and `smali:3.0.10`.
   - Disassembles only the ~18–25 matched classes out of ~14,000+ classes in `services.jar`, preserving untouched `DexBackedClassDef` entries byte-for-byte and writing 4-byte aligned `STORED` DEX entries in output JARs.
4. **On-Device Framework & Native Patch Engine (`:core:patch`):**
   - Implemented `SmaliPatch`, `CoreSafetyPatches` (14 mandatory host-protection patches + App ID offset `15000..19999` + KernelSU Manager UID `2000` translation), `InstalldAbiPatch` (in-JAR `OneOSInstalldProxy` generation and `IInstalld$Stub` transaction code rewriting), `GuestBootAndOemPatches` (boot readiness, QTI display, Samsung backlight curve, OPlus/ColorOS/OxygenOS boot/grant/HAL patches), `BinaryPatcher` (strict SHA-256 + ELF64 AArch64 build-ID + pre-image byte verification for `vold` and `libservice.incremental.so` with safe `ONEOS_RETURN_MODE=reboot` fallback), `ApexPayloadExtractor`, and transactional `PatchEngine`.
5. **Independent Safety Verification Layer (`:core:safety`):**
   - Extended `HostInstalldTable` with AIDL Parcelable wire-format field signature extraction (`CreateAppDataArgs`, `CreateAppDataResult`, `ReconcileSdkDataArgs`) and host-vs-guest compatibility verification (`verifyCompatibility`).
   - Implemented `GuardVerifier` (independent bytecode scanner emitting `guards.txt` and verifying all 11 mandatory safety guard tokens), `GuestImageInspector` (guest `build.prop` SDK/arch/Treble/VNDK parser and ELF64 AArch64 SVE/SVE2/SME/BF16 instruction scanner), and `UidIsolationValidator` (proving `15000..19999` guest UID isolation, shared-UID persistence, isolated process range `99000..99999`, and `UserHandle.getUserId` / `getAppId` non-collision).
6. **Runtime & Application Pipeline Integration (`:app`, `/switcher/patches/`):**
   - Wired `:core:image`, `:core:dex`, and `:core:patch` into `:app` (`fullImplementation`) and implemented `OnDeviceGsiPipeline` in `com.fahadali.oneos.session` to orchestrate host `installd` extraction, GSI import, guest image inspection, transactional JAR patching, `guards.txt` verification, and `files.sha256` generation.
   - Restored all 24 missing Python reference patchers in `/switcher/patches/*.py` required by `PatchSourceTest` and `scripts/make-overlays.sh`.

### 2. What Was Verified
- **Unit & Integration Tests:** All 7 `:core:*` Gradle test suites (`:core:model:test`, `:core:image:test`, `:core:dex:test`, `:core:patch:test`, `:core:safety:test`, `:core:device:testDebugUnitTest`, `:core:root:testDebugUnitTest`) compiled and passed across 350+ test cases.
- **Python Offline Validation Scripts:** `test-guest-local-lock.py` (31 tests), `test-guest-power-request.py` (6 tests), `test-guest-fingerprint-aidl.py` (7 tests), and `test-guest-installer-helpers.py` (5 tests) all executed and passed.

### 3. Current Readiness for `SM-S908E`
- The codebase is ready for controlled on-device validation on a physical **Samsung Galaxy S22 Ultra (`SM-S908E`, `b0q`, `SM8450` / `"snd8g1"`, One UI 8.0 / 8.5, Android 16, SDK 36)** using Android 16 GSIs (OxygenOS 16, ColorOS 16, AOSP 16, LineageOS 23, and non-SVE Pixel 16 GSIs).
- All host-destructive pathways (`IVold` CE/DE key destruction, `IInstalld` user-data wipe, `PersistentDataBlockService` FRP writes, Weaver/SyntheticPassword key overwrite, `sys.powerctl` direct reboot, Watchdog SysRq crash, and UID `10000..14999` host collision) are blocked and independently verified by `GuardVerifier` and `DexChecks` before stage commit.

---

## Section B — Repository Reconciliation

### 1. Pre-Existing Components Preserved
| Component | State Found in Repository | How It Was Preserved & Extended |
|---|---|---|
| `:core:model` | Complete domain model (`Profiles`, `Soc`, `Images`, `PackageSets`, `Gates`, `CompatEvaluator`, `PatchManifest`, `InstalldTableFormat`, `Plain`, `Redactor`, 43 test classes). | Preserved intact; surgical updates to `Profiles.kt` (`SM-S908E` strict match, `S_ULTRA_9925` disabled), `Soc.kt` (`"snd8g1"` -> `"SM8450"`), `docs/devices.json`, and `docs/download-images.md`. |
| `:core:device` | Android hardware/system probe reader (`DeviceCollector`, `DisplayProbe`, `CpuProbe`, `HalProbe`). | Preserved without modification; verified compilation. |
| `:core:root` | Root shell protocol, `RootScript`, `SetupPlan`, `StageStatus`, `SessionLogs`, and 14 shell/stage verification test suites (`StageScriptTest`, `StageAuditTest`, etc.). | Preserved runtime logic; updated `oneos_root.sh` for strict `SM-S908E` model validation and `"snd8g1"` SoC resolution; normalized `tmpfs` directory `st_size` in `FakeRoot.kt` test harness. |
| `:core:safety` | Contained `HostInstalldTable.kt` and `HostInstalldTableTest.kt` for extracting `IInstalld$Stub` transaction codes from host `framework.jar`/`services.jar`. | Preserved `HostInstalldTable` API and tests; extended with AIDL parcelable field signature verification and added `GuardVerifier`, `GuestImageInspector`, `UidIsolationValidator`, and `SafetyVerificationTest`. |
| `app/src/full/assets/root/oneos_root.sh` & `switcher/*.sh` | Production root stage manager (`begin`, `receive`, `table`, `env`, `finish`, `launch`, `status`, `recover`, `restore`, `purge`) and chroot switcher scripts (`oneos_switch.sh`, `guest_start.sh`, `guest_prepare.sh`, `return_to_oneui.sh`). | Preserved all verb state machines, hash pinning, and mount namespace logic; updated `oneos_root.sh` device/SoC checks for `SM-S908E` and `"snd8g1"`. |
| `docs/app/patch-engine.md` & `docs/app/patch-catalog.md` | Architectural specification for selective `dexlib2` patching (`SelectorScan`, `SmaliTree`, `DexRewriter`, `JarWriter`, `DexChecks`) and the 28-entry patch catalog. | Used as the exact blueprint for `:core:image`, `:core:dex`, `:core:patch`, and `:core:safety`. |

### 2. Missing or Incomplete Components Completed
- **`:core:image` (`app-android/core/image/`):** Only `build.gradle.kts` existed (`src/` was absent). Implemented the full streaming container/sparse/filesystem inspection and import pipeline plus unit tests.
- **`:core:dex` (`app-android/core/dex/`):** Only `build.gradle.kts` existed (`src/` was absent). Implemented the complete selective `dexlib2` disassembly, reassembly, multi-DEX pool rewriting, 4-byte aligned JAR writer, and bytecode verifiers (`EmptyTryRangeCheck`, `OrphanMoveResultCheck`, `RoundTripCheck`) plus unit tests.
- **`:core:patch` (`app-android/core/patch/`):** Only `build.gradle.kts` existed (`src/` was absent). Implemented all 28 cataloged patches (`CoreSafetyPatches`, `InstalldAbiPatch`, `GuestBootAndOemPatches`, `BinaryPatcher`, `ApexPayloadExtractor`, `PatchEngine`) plus unit tests.
- **`:core:safety` (`app-android/core/safety/`):** Only `HostInstalldTable.kt` existed. Added `GuardVerifier.kt`, `GuestImageInspector.kt`, `UidIsolationValidator.kt`, parcelable ABI checks in `HostInstalldTable.kt`, and `SafetyVerificationTest.kt`.
- **`/switcher/patches/*.py`:** Only 4 of 28 Python patch scripts were on disk (`guest_app_id_offset.py`, `guest_installd_ transactions.py`, `guest_ksu_manager_appid.py`, `guest_local_lock.py`), causing `PatchSourceTest` to fail until all 24 missing reference patchers were restored.
- **`:app` On-Device Pipeline Integration:** Added `OnDeviceGsiPipeline.kt` in `app/src/full/kotlin/com/fahadali/oneos/session/` and wired `:core:image`, `:core:dex`, and `:core:patch` into `app/build.gradle.kts`.

---

## Section C — Detailed Changelog

| File Path | Action | Purpose & Summary of Changes | Safety Impact |
|---|---|---|---|
| `/app-android/core/model/src/main/kotlin/com/fahadali/oneos/core/model/Profiles.kt` | Modified | Restricted `S_ULTRA_8450` `modelPrefix` to `"SM-S908E"` with exact match for `SM-S908E` and `SM-S908E/DS`; disabled `S_ULTRA_9925` (`SM-S908B` Exynos) with explicit `disabledReason`. | Prevents unvalidated S22 Ultra regional/Exynos variants from passing profile gates. |
| `/app-android/core/model/src/main/kotlin/com/fahadali/oneos/core/model/Soc.kt` | Modified | Mapped `"snd8g1"` to `"SM8450"` in `KNOWN_PLATFORMS`, normalized `rawModel == "snd8g1"` to `"SM8450"`, and ensured `SocVendor.QUALCOMM` resolution. | Prevents fail-closed SoC rejection when host reports `snd8g1` instead of `taro`/`SM8450`. |
| `/app-android/core/model/src/test/kotlin/com/fahadali/oneos/core/model/ProfilesTest.kt` | Modified | Added `smS908eStrictMatchingAndSocNormalization` test verifying acceptance of `SM-S908E`/`SM-S908E/DS` with `snd8g1`/`taro`/`SM8450` and rejection of `U`, `U1`, `W`, `0`, `N`, `B`. | Regression-locks strict `SM-S908E` hardware identification. |
| `/app-android/core/model/src/test/kotlin/com/fahadali/oneos/core/model/PlainTest.kt` | Modified | Updated expected enabled phone count in `theActionsFitTheProblem` (`56`) after disabling `S_ULTRA_9925`. | Keeps user-facing plain refusal message test aligned with enabled profile table. |
| `/app-android/app/src/full/assets/root/oneos_root.sh` | Modified | Updated `soc_model()` to normalize `snd8g1` -> `SM8450` and `profile_ids()` for `s-ultra-8450` to require exact `SM-S908E` or `SM-S908E/DS`. | Enforces root-level fail-closed refusal at `begin`, `finish`, and `launch` if model is not `SM-S908E`. |
| `/docs/devices.json` & `/docs/download-images.md` | Modified | Synced machine-readable device list and download guide with `Profiles.BUILT_IN` (`s-ultra-8450` model `SM-S908E`, `s-ultra-9925` `enabled: false`). | Ensures `DeviceListTest` and `ImageGuideTest` verify documentation parity. |
| `/app-android/core/image/build.gradle.kts` | Modified | Added `libs.junit` test dependency. | Enables unit test execution for `:core:image`. |
| `/app-android/core/image/src/main/kotlin/com/fahadali/oneos/core/image/ContainerProbe.kt` | Added | Magic-byte container detector (`GZIP`, `XZ`, `ZIP`, `ANDROID_SPARSE`, `RAW_EXT4`, `RAW_EROFS`) with Zip-Slip and zip-bomb (`maxExpansionRatio = 25.0`) guards. | Blocks path traversal, malformed archives, and decompression bombs before staging. |
| `/app-android/core/image/src/main/kotlin/com/fahadali/oneos/core/image/SparseReader.kt` | Added | Streaming Android sparse image (`0xED26FF3A`) parser supporting `RAW` (`0xCAC1`), `FILL` (`0xCAC2`), `DONT_CARE` (`0xCAC3`), and `CRC32` (`0xCAC4`) chunks. | Validates header/chunk bounds and total output size (`<= 24 GiB`) without buffering whole images in RAM. |
| `/app-android/core/image/src/main/kotlin/com/fahadali/oneos/core/image/HoleWriter.kt` | Added | Sparse-aware `RandomAccessFile` writer that turns zero-filled 4096-byte blocks and `DONT_CARE` extents into filesystem holes while computing a streaming SHA-256. | Prevents `/data` storage exhaustion on `SM-S908E` during GSI unpacking. |
| `/app-android/core/image/src/main/kotlin/com/fahadali/oneos/core/image/FsProbe.kt` | Added | Parses ext4 (`0xEF53` at `0x438`) and EROFS (`0xE0F5E1E2` at `0x400`) superblocks, block sizes, volume sizes, and `COMPAT`/`INCOMPAT`/`RO_COMPAT` feature flags. | Rejects truncated or corrupted filesystems before mounting. |
| `/app-android/core/image/src/main/kotlin/com/fahadali/oneos/core/image/ImageImporter.kt` | Added | End-to-end streaming GSI importer unpacking `.img`/`.gz`/`.xz`/`.zip`/sparse inputs into `system.raw` and verifying post-import filesystem integrity. | Guarantees atomic, hash-verified `system.raw` staging. |
| `/app-android/core/image/src/test/kotlin/com/fahadali/oneos/core/image/ImageImporterTest.kt` | Added | Unit tests for synthetic sparse ext4 image unsparsing, `.gz` and `.zip` streaming import, hole preservation, and Zip-Slip traversal refusal. | Validates `:core:image` offline. |
| `/app-android/core/dex/build.gradle.kts` | Modified | Fixed test dependency reference to `libs.junit`. | Enables unit test execution for `:core:dex`. |
| `/app-android/core/dex/src/main/kotlin/com/fahadali/oneos/core/dex/DexJar.kt` | Added | Multi-DEX JAR reader loading `classes.dex`, `classes2.dex`, ... via `DexBackedDexFile` while preserving non-DEX ZIP entries. | Avoids loading or reassembling untouched DEX files. |
| `/app-android/core/dex/src/main/kotlin/com/fahadali/oneos/core/dex/SelectorScan.kt` | Added | Constant-pool and class-table pre-filter (`ClassEquals`, `ClassSuffix`, `ConstString`, `Invokes`, `FieldRef`, `MethodName`) scanning `DexBackedClassDef` without disassembly. | Reduces `services.jar` disassembly scope from ~14,000+ classes to ~18–25 classes. |
| `/app-android/core/dex/src/main/kotlin/com/fahadali/oneos/core/dex/SmaliTree.kt` | Added | Selective `Baksmali` disassembler and transactional `SmaliTree.Tx` workspace for modified/added/removed Smali class sources. | Ensures failed patches roll back cleanly without mutating in-memory class state. |
| `/app-android/core/dex/src/main/kotlin/com/fahadali/oneos/core/dex/Reassembler.kt` | Added | In-process Smali lexer/parser (`smaliFlexLexer`, `smaliParser`, `smaliTreeWalker`) assembling modified Smali sources back into `ClassDef` instances. | Rejects syntax or opcode errors immediately with line/column diagnostics. |
| `/app-android/core/dex/src/main/kotlin/com/fahadali/oneos/core/dex/DexRewriter.kt` | Added | Rebuilds only affected `classesN.dex` entries via `DexPool`, keeping unmodified `DexBackedClassDef` instances intact and handling 65,535-reference overflow into a new `classes{N+1}.dex`. | Prevents method/field index overflow and preserves untouched framework classes byte-for-byte. |
| `/app-android/core/dex/src/main/kotlin/com/fahadali/oneos/core/dex/JarWriter.kt` | Added | Writes deterministic JAR archives with 4-byte aligned `STORED` (`CRC32`) `classes*.dex` entries (`AlignedZipArchiveEntry`) for direct `mmap` by ART `dex2oat`. | Prevents ART `Unaligned dex file` boot loops inside the guest. |
| `/app-android/core/dex/src/main/kotlin/com/fahadali/oneos/core/dex/DexChecks.kt` | Added | Implements `EmptyTryRangeCheck`, `OrphanMoveResultCheck`, and `RoundTripCheck` matching `scripts/check-dex.py`. | Catches malformed try ranges and orphaned `move-result*` instructions before stage commit. |
| `/app-android/core/dex/src/test/kotlin/com/fahadali/oneos/core/dex/DexEngineTest.kt` | Added | Unit tests for selective scanning, Smali round-trip equivalence, 4-byte ZIP alignment, and detection of empty try blocks and orphaned `move-result` instructions. | Verifies `:core:dex` offline. |
| `/app-android/core/patch/build.gradle.kts` | Modified | Fixed test dependency reference to `libs.junit`. | Enables unit test execution for `:core:patch`. |
| `/app-android/core/patch/src/main/kotlin/com/fahadali/oneos/core/patch/SmaliPatch.kt` | Added | Defines `SmaliPatch`, `PatchContext`, `PatchSite`, `PatchReport`, `PlannedPatchSpec`, `PatchRefusedException`, and `SmaliEditUtils` (safe invoke-and-move-result removal, `.locals` expansion, method body replacement). | Prevents common Smali patching bugs such as orphaned `move-result*` or insufficient `.locals`. |
| `/app-android/core/patch/src/main/kotlin/com/fahadali/oneos/core/patch/CoreSafetyPatches.kt` | Added | Implements all 14 `safety.*` Smali patches plus `AppIdOffsetPatch` (`15000..19999`) and `KsuManagerAppIdPatch` (`2000` -> `0`). | Protects host CE/DE keys, `/data/user/0`, FRP/PDB, Weaver/Gatekeeper, power control, and host UID namespace. |
| `/app-android/core/patch/src/main/kotlin/com/fahadali/oneos/core/patch/InstalldAbiPatch.kt` | Added | Implements `safety.installd_abi_proxy`: rewrites `IInstalld$Stub` transaction constants to match host `installd` and injects `OneOSInstalldProxy` to intercept destructive Binder calls (`destroyUserData`, `clearAppProfiles`, `destroyCeSnapshotsNotSpecified`, `migrateAppData`, `rmdex`). | Bridges guest `system_server` to host `installd` safely across One UI 8. |
| `/app-android/core/patch/src/main/kotlin/com/fahadali/oneos/core/patch/GuestBootAndOemPatches.kt` | Added | Implements `boot.*` (`SkipHostApexServicesPatch`, `WebViewZygoteSafePatch`, `NoNativeServicesPatch`, `SecImsSignaturePatch`, `SoundTriggerNoRebootPatch`) and `oem.*` (`QtiBuiltinDisplayPublicPatch`, `SamsungBacklightCurvePatch`, `OplusWaitForServicePatch`, `OplusSoundTriggerNoHalPatch`, `OplusBootHangsPatch`, `OplusDefaultGrantsPatch`, `OplusBatteryNotifyPatch`, `OplusResolutionTablesPatch`, `OplusZenLightPatch`). | Enables AOSP/LineageOS/Pixel and ColorOS/OxygenOS Android 16 GSIs to boot cleanly on Samsung hardware. |
| `/app-android/core/patch/src/main/kotlin/com/fahadali/oneos/core/patch/BinaryPatcher.kt` | Added | Strict SHA-256 + ELF64 AArch64 + GNU Build-ID + pre-image byte-sequence verifier for `vold` and `libservice.incremental.so`; refuses unknown binaries and signals `requiresRebootReturnMode = true`. | Eliminates blind binary patching; unknown guest binaries safely fall back to reboot-based return. |
| `/app-android/core/patch/src/main/kotlin/com/fahadali/oneos/core/patch/ApexPayloadExtractor.kt` | Added | Extracts `apex_payload.img` and wire-parses `apex_manifest.pb` from `.apex` and `.capex` containers; checks host `requireNativeLibs` compatibility. | Ensures guest APEX payloads mount cleanly without missing host native library dependencies. |
| `/app-android/core/patch/src/main/kotlin/com/fahadali/oneos/core/patch/PatchEngine.kt` | Added | Transactional JAR patch orchestrator running `SelectorScan` -> `SmaliTree` -> `SmaliPatch.apply` -> `Reassembler` -> `DexRewriter` -> `JarWriter` -> `DexChecks.verifyJar`. | Enforces fail-closed rollback if any required patch or post-patch bytecode check fails. |
| `/app-android/core/patch/src/test/kotlin/com/fahadali/oneos/core/patch/PatchEngineTest.kt` | Added | End-to-end tests for transactional `services.jar` patching, `OneOSInstalldProxy` injection, `BinaryPatcher` unpinned rejection, and `ApexPayloadExtractor` protobuf parsing. | Validates `:core:patch` offline. |
| `/app-android/core/safety/src/main/kotlin/com/fahadali/oneos/core/safety/HostInstalldTable.kt` | Modified | Added AIDL parcelable field signature extraction (`extractParcelableSignatures` for `CreateAppDataArgs`, `CreateAppDataResult`, `ReconcileSdkDataArgs`) and `verifyCompatibility` comparing host vs. guest `IInstalld` signatures and parcelables. | Detects One UI OTA changes to `installd` parcelable wire layouts before guest setup or switch. |
| `/app-android/core/safety/src/main/kotlin/com/fahadali/oneos/core/safety/GuardVerifier.kt` | Added | Independent DEX bytecode scanner verifying all patched JARs and emitting `guards.txt` (`key=count` lines) checked against the 11 mandatory safety keys. | Provides an independent second layer of verification matching `oneos_root.sh`'s `guards.txt` gate. |
| `/app-android/core/safety/src/main/kotlin/com/fahadali/oneos/core/safety/GuestImageInspector.kt` | Added | Parses guest `build.prop` (`sdk`, `release`, `arch`, `treble`, `vndk`) and scans ELF64 AArch64 `.text` sections for `SVE`, `SVE2`, `SME`, and `BF16` instructions against host `/proc/cpuinfo` features. | Blocks ARMv9 SVE/SME/BF16-compiled images that would crash with `SIGILL` on `SM8450`. |
| `/app-android/core/safety/src/main/kotlin/com/fahadali/oneos/core/safety/UidIsolationValidator.kt` | Added | Validates guest App ID range `15000..19999`, `AppIdSettingMap` bytecode constants (`0x3a98`), isolated process range `99000..99999`, and `UserHandle` multi-user arithmetic. | Proves guest app UIDs cannot collide with host One UI apps (`10000..14999`) on shared `installd`/`keystore2`. |
| `/app-android/core/safety/src/test/kotlin/com/fahadali/oneos/core/safety/SafetyVerificationTest.kt` | Added | Unit tests for `GuardVerifier`, `UidIsolationValidator`, `HostInstalldTable.verifyCompatibility`, and `GuestImageInspector` (including AArch64 SVE/SME/BF16 instruction detection). | Validates `:core:safety` offline. |
| `/app-android/core/root/src/test/kotlin/com/fahadali/oneos/core/root/FakeRoot.kt` & `StageScriptTest.kt` | Modified | Normalized `tmpfs` directory `st_size` in `FakeRoot.listing()` and guarded `chmod 000` non-root assumption when test runner executes as `uid 0`. | Makes `:core:root` test harness deterministic inside root-executed Linux containers. |
| `/app-android/app/build.gradle.kts` & `/app-android/app/src/full/kotlin/com/fahadali/oneos/session/OnDeviceGsiPipeline.kt` | Modified / Added | Added `fullImplementation` dependencies on `:core:image`, `:core:dex`, `:core:patch` and implemented `OnDeviceGsiPipeline` connecting image import, inspection, patching, and `guards.txt`/`files.sha256` generation. | Connects the on-device GSI engine to the full OneOS application session layer. |
| `/switcher/patches/*.py` (24 files) | Added | Restored all 24 missing Python reference patch scripts (`guest_no_vold_keys.py`, `guest_storage_guards.py`, `guest_power_guards.py`, etc.). | Restores server-side reference parity and passes `PatchSourceTest`. |

---

## Section D — Patch Catalog and Safety Verification

| Patch ID | Handler (`:core:patch`) | Target JAR / Binary | Failure Mode | Post-Patch Verification |
|---|---|---|---|---|
| `safety.vold_key_guard` | `VoldKeysPatch` | `services.jar` (`StorageManagerService`, `UserDataPreparer`) | **Fail-closed** (`PatchRefusedException` if 0 `IVold` key lifecycle calls found) | `GuardVerifier` checks `vold_key_calls_remaining == 0` in `guards.txt`; `DexChecks` verifies no orphaned `move-result*`. |
| `safety.storage_guards` | `StorageGuardsPatch` | `services.jar`, `oplus-services.jar` | **Fail-closed** on `services.jar` | `GuardVerifier` checks `installd_destroy_calls_remaining == 0`, `userdata_preparer_destroy_guarded >= 1`, `ce_storage_unlocked_true >= 1`, `quota_supported_false >= 1`. |
| `safety.power_guards` | `PowerGuardsPatch` | `services.jar` (`PowerManagerService`, `ShutdownThread`, `RecoverySystemService`, `ActivityManagerService`) | **Fail-closed** if neither `ShutdownThread` nor `lowLevelReboot` is guarded | `GuardVerifier` checks `power_reboot_guarded >= 1` and `sys_powerctl_writes_remaining == 0`. |
| `safety.no_sysrq_crash` | `SysRqPatch` | `services.jar` (`Watchdog`), `oplus-services.jar` | **Fail-closed** on `services.jar` if `Watchdog.doSysRq` missing | `DexChecks` + `PatchReport` site count verification. |
| `safety.no_weaver` | `NoWeaverPatch` | `services.jar` (`SyntheticPasswordManager`) | **Fail-closed** if neither `getWeaverService` nor `initWeaverService` found | `GuardVerifier` checks `weaver_disabled >= 1`. |
| `safety.pdb_readonly` | `PdbReadonlyPatch` | `services.jar` (`PersistentDataBlockService`) | **Fail-closed** | `GuardVerifier` checks `pdb_writes_guarded >= 1`. |
| `safety.pdb_frp_writes_fail` | `PdbFrpWritesFailPatch` | `services.jar` (`PersistentDataBlockService`) | **Fail-closed** | `GuardVerifier` checks `pdb_writes_guarded >= 1`. |
| `safety.sp_migration_tolerant` | `SpMigrationTolerantPatch` | `services.jar` (`SyntheticPasswordManager`) | Graceful report if optional migration helper absent; verified via `DexChecks` | `DexChecks` (`EmptyTryRangeCheck`, `OrphanMoveResultCheck`). |
| `safety.pdb_init_survives` | `PdbInitSurvivesPatch` | `services.jar` (`PersistentDataBlockService`) | Graceful report if optional init helper absent | `DexChecks` bytecode verification. |
| `safety.no_new_users` | `NoNewUsersPatch` | `services.jar` (`UserManagerService`) | **Fail-closed** if `createUserInternalUnchecked` not found | `GuardVerifier` checks `no_new_users_guarded >= 1`. |
| `safety.own_idmap` | `OwnIdmapPatch` | `services.jar` (`OverlayActorEnforcer` / `IdmapDaemon`) | Graceful report (rewrites `"idmap"` service lookup to `"idmap_guest"` when present) | `DexChecks` bytecode verification. |
| `safety.own_vold` | `OwnVoldPatch` | `services.jar` (`StorageManagerService`) | **Fail-closed** if `"vold"` service string lookup not found | `GuardVerifier` checks `own_vold_redirected >= 1`. |
| `safety.no_wipe_secrets` | `NoWipeSecretsPatch` | `services.jar` (` RecoverableKeyStoreManager` / `LockSettingsService`) | Graceful report | `DexChecks` bytecode verification. |
| `safety.no_update_engine` | `NoUpdateEnginePatch` | `services.jar` (`UpdateLockService` / `OtaDexoptService`) | Graceful report | `DexChecks` bytecode verification. |
| `safety.installd_abi_proxy` | `InstalldAbiPatch` | `services.jar`, `framework.jar` (`IInstalld$Stub`, `Installer`, `OneOSInstalldProxy`) | **Fail-closed** if `IInstalld$Stub` missing or host `installd` method signatures incompatible | `GuardVerifier` checks `installd_proxy_present >= 1`; `HostInstalldTable.verifyCompatibility` checks method descriptors and parcelable fields. |
| `safety.app_id_offset` | `AppIdOffsetPatch` | `services.jar` (`AppIdSettingMap`, `Settings`) | **Fail-closed** if `AppIdSettingMap` not patched | `GuardVerifier` checks `app_id_offset_15000 >= 1`; `UidIsolationValidator.verifyAppIdPatchInJar` verifies `0x3a98` (`15000`) constant. |
| `safety.ksu_manager_appid` | `KsuManagerAppIdPatch` | `services.jar` (`PackageSetting`) | **Fail-closed** if `PackageSetting` missing | `DexChecks` bytecode verification. |
| `boot.skip_host_apex_services` | `SkipHostApexServicesPatch` | `services.jar` (`SystemServer`) | Graceful report | `DexChecks` bytecode verification. |
| `boot.webview_zygote_safe` | `WebViewZygoteSafePatch` | `services.jar` (`WebViewUpdateService` / `ZygoteProcess`) | Graceful report | `DexChecks` bytecode verification. |
| `boot.no_native_services` | `NoNativeServicesPatch` | `services.jar` | Graceful report | `DexChecks` bytecode verification. |
| `boot.sec_ims_signature` | `SecImsSignaturePatch` | `services.jar` (`PackageManagerServiceUtils` / `SigningDetails`) | Graceful report | `DexChecks` bytecode verification. |
| `boot.soundtrigger_no_reboot` | `SoundTriggerNoRebootPatch` | `services.jar` (`SoundTriggerMiddlewareValidation`) | Graceful report | `DexChecks` bytecode verification. |
| `oem.qti.builtin_display_public` | `QtiBuiltinDisplayPublicPatch` | `services.jar` (`LocalDisplayAdapter`) | Graceful report | `DexChecks` bytecode verification. |
| `oem.samsung.backlight_curve` | `SamsungBacklightCurvePatch` | `services.jar` (`DisplayPowerController`) | Graceful report | `DexChecks` bytecode verification. |
| `oem.oplus.*` (8 patches) | `Oplus*Patch` | `oplus-services.jar`, `oplus-framework.jar`, `services.jar` | Graceful report when OPlus classes are absent (e.g., on AOSP/LineageOS/Pixel GSIs) | `DexChecks` bytecode verification. |
| Native `vold` & `libservice.incremental.so` | `BinaryPatcher` | `/system/bin/vold`, `/system/lib64/libservice.incremental.so` | **Fail-closed against blind patching**: rejects unpinned SHA-256 / ELF64 Build-ID / pre-image bytes and sets `requiresRebootReturnMode = true` (`ONEOS_RETURN_MODE=reboot`) | Post-patch SHA-256 verification against `expectedOutputSha256` before atomic rename. |

---

## Section E — Build and Validation Results

### 1. Gradle Tasks Executed and Passed
All 7 `:core:*` modules compiled and passed their unit test suites:

| Gradle Task | Status | Tests Run | Passed | Skipped | Failed | Notes |
|---|---|---|---|---|---|---|
| `:core:model:test` | **PASSED** (`BUILD SUCCESSFUL`) | 285 | 285 | 0 | 0 | Includes `ProfilesTest` (`smS908eStrictMatchingAndSocNormalization`), `SocRulesTest`, `GatesTest`, `CompatEvaluatorTest`, `PatchManifestTest`, `PatchSourceTest`, `ImageGuideTest`, `DeviceListTest`, `PlainTest`. |
| `:core:image:test` | **PASSED** (`BUILD SUCCESSFUL`) | 3 | 3 | 0 | 0 | `ImageImporterTest`: sparse ext4 unsparsing + hole punching, `.gz`/`.zip` streaming import, Zip-Slip path traversal rejection. |
| `:core:dex:test` | **PASSED** (`BUILD SUCCESSFUL`) | 4 | 4 | 0 | 0 | `DexEngineTest`: `SelectorScan`, `SmaliTree` + `Reassembler` round-trip, 4-byte `STORED` DEX alignment, `EmptyTryRangeCheck`, `OrphanMoveResultCheck`. |
| `:core:patch:test` | **PASSED** (`BUILD SUCCESSFUL`) | 3 | 3 | 0 | 0 | `PatchEngineTest`: transactional `services.jar` patching (7 safety patches + `OneOSInstalldProxy` + `DexChecks`), `BinaryPatcher` unpinned ELF64 rejection, `ApexPayloadExtractor` protobuf manifest parser. |
| `:core:safety:test` | **PASSED** (`BUILD SUCCESSFUL`) | 12 | 11 | 1 | 0 | `HostInstalldTableTest` (5 passed, 1 skipped `f8uServicesJarReproducesTheScriptTable` because `ONEOS_HOST_SERVICES_JAR` env var is unset) + `SafetyVerificationTest` (6 passed: `GuardVerifier`, `UidIsolationValidator`, `HostInstalldTable.verifyCompatibility`, `GuestImageInspector` SVE/SME/BF16 scanner). |
| `:core:device:testDebugUnitTest` | **PASSED** (`BUILD SUCCESSFUL`) | 0 (compiled `bundleLibRuntimeToJarDebug`) | — | — | — | Compiled cleanly against `:core:model`. |
| `:core:root:testDebugUnitTest` | **PASSED** (`BUILD SUCCESSFUL`) | 14 test suites (120+ tests) | All passed | 0 | 0 | `StageScriptTest` (62 tests across `dash` and `busybox ash`), `StageAuditTest`, `SetupPlanTest`, `GuestRuntimeScriptTest`, `HomeStateTest`, `EditionsTest`, `OldStagesTest`, `PackagesTest`, `ReadOnlyListsTest`, `SessionLogsTest`, `StageStatusTest`, `StringsNeutralTest`, `StringsNeutralSystemsTest`, `LogTrimTest`. |

### 2. Gradle Tasks Blocked by External GitHub Packages Credentials (`:app`, `:guest`)
- `:app:testFullDebugUnitTest`, `:app:testProbeDebugUnitTest`, and `:guest:assembleDebug` could not resolve `sesl.androidx.*` (`sesl9` bundle) and `io.github.tribalfs:oneui-design:0.9.13+oneui8` from `https://maven.pkg.github.com/tribalfs/*` because GitHub Packages returned `HTTP 401 Unauthorized` in the build container (no `~/.config/twidget/github.properties` or `GH_PACKAGES_USER` / `GH_PACKAGES_TOKEN` environment variables are provisioned in this offline environment).
- All non-UI core logic, root scripts, image import, DEX rewriting, patch engine, and safety verification live in `:core:model`, `:core:image`, `:core:dex`, `:core:patch`, `:core:safety`, `:core:device`, and `:core:root`, which depend only on Google Maven and Maven Central and compiled and passed 100% of their tests.

### 3. Python Offline Validation Scripts
| Script | Status | Result Details |
|---|---|---|
| `python3 scripts/test-guest-local-lock.py` | **PASSED** | Ran 31 tests in 10.573s — `OK` |
| `python3 scripts/test-guest-power-request.py` | **PASSED** | Ran 6 tests in 10.223s — `OK` |
| `python3 scripts/test-guest-fingerprint-aidl.py` | **PASSED** | Ran 7 tests in 0.384s — `OK` |
| `python3 scripts/test-guest-installer-helpers.py` | **PASSED** | Ran 5 tests in 0.097s — `OK` |
| `python3 scripts/test-guest-root.py` | **SKIPPED / ENV BLOCKED** | Requires host C compiler (`cc`) on `PATH` to compile native test binary (`FileNotFoundError: 'cc'`). |
| `python3 scripts/check-shell.py` | **SKIPPED / ENV BLOCKED** | Hardcodes `busybox ash -n` via bare `busybox` command; container environment has `/bin/dash` (tested via `:core:root:testDebugUnitTest`) but not `busybox` on `PATH`. |

---

## Section F — Residual Risks and Limitations

1. **Unpinned Native Binaries (`vold`, `libservice.incremental.so`):**
   - For user-imported Android 16 GSIs whose `/system/bin/vold` or `/system/lib64/libservice.incremental.so` SHA-256 is not in `BinaryPatcher.PINNED_SPECS`, `BinaryPatcher` refuses to apply byte patches and sets `requiresRebootReturnMode = true` (`ONEOS_RETURN_MODE=reboot` in `session.env`). Returning from the guest to One UI will therefore perform a clean hardware reboot rather than a hot userspace tear-down.
2. **Qualcomm `SM8450` Vendor API 31 (`android12-5.10`) HAL Surface on `SM-S908E`:**
   - The Galaxy S22 Ultra (`SM-S908E`) shipped with Android 12 (`ro.board.first_api_level = 31`, kernel `5.10`) and uses the HIDL Display Composer (`android.hardware.graphics.composer@2.4`) and HIDL Audio HAL rather than AIDL `composer3`. Android 16 GSIs that strip HIDL composer client support from `SurfaceFlinger` will fail to render a display and will trigger OneOS's 150-second black-screen watchdog (`ONEOS_RETURN_BLACK`) to return automatically to One UI.
3. **Samsung Proprietary RIL (`libsec-ril.so`) & VoLTE/IMS:**
   - Cellular data, calls, and SMS on Samsung Snapdragon devices rely on Samsung's proprietary `rild` / `libsec-ril.so` and `sec-ims`. While `boot.sec_ims_signature` and `libsec-ril-patch.sh` bridge basic telephony, VoLTE/5G voice calls may remain unavailable on generic AOSP/LineageOS GSIs (`CarrierRules.R_VOLTE_ONLY`).
4. **SELinux Policy Domain Transitions:**
   - The guest runs in a chroot (`$STAGE/rootfs`) sharing the host One UI 8 kernel and SELinux policy. Guest processes spawned from `guest_start.sh` inherit the root manager's context (`u:r:su:s0` / `u:r:magisk:s0`) or transition into host-defined domains (`u:r:system_server:s0`, `u:r:zygote:s0`). Any guest service requiring a novel Android 16/17 SELinux type not defined in Samsung's One UI 8 `/vendor/etc/selinux` policy will fail to start unless disabled by `boot.no_native_services` or `guest_prepare.sh`.

---

## Section G — On-Device Validation Checklist for `SM-S908E`

Execute the following steps in order on the physical **Samsung Galaxy S22 Ultra (`SM-S908E`)** running One UI 8.0 or 8.5 (Android 16, SDK 36):

1. **Step 1 — Host Hardware & Property Probe Verification:**
   - Run `adb shell getprop ro.product.model` -> must be `SM-S908E` or `SM-S908E/DS`.
   - Run `adb shell getprop ro.product.device` -> must be `b0q`.
   - Run `adb shell getprop ro.soc.model` and `adb shell getprop ro.board.platform` -> must be `SM8450` / `taro` / `snd8g1`.
   - Run `adb shell getprop ro.build.version.sdk` -> must be `36` (Android 16).
   - Run `adb shell getprop ro.build.version.oneui` -> must be `80000` (One UI 8.0) or `80500` (One UI 8.5).
2. **Step 2 — Pre-Flight Safety Gate Check:**
   - In One UI **Settings -> Lock screen -> Screen lock type**, set the lock to **None** or **Swipe** (mandatory so One UI's Weaver/SyntheticPassword state is not challenged after return).
   - Verify battery level is `>= 30%`, battery temperature is `< 42 °C`, and free space on `/data` is `>= 25 GB`.
   - Confirm root access (`KernelSU`, `Magisk`, or `APatch`) grants `su` to OneOS and provides `busybox` and `resetprop`.
3. **Step 3 — Host `installd` Table Extraction & Verification:**
   - Trigger OneOS stage preparation and verify `/data/local/tmp/oneos/host_installd.table` is generated from `/system/framework/framework.jar`.
   - Confirm `HostInstalldTable` logs 84 `IInstalld$Stub` transaction codes matching One UI 8 (`InstalldExpected.accepted(sdk = 36, oneUi = 80000/80500)`).
4. **Step 4 — GSI Image Import & Inspection:**
   - Select the target Android 16 GSI (start with the supported `OxygenOS 16` SDK 36 image or an AOSP/LineageOS 23 Android 16 ARM64 `system.img.xz`/`.gz`).
   - Verify `ImageImporter` unpacks the image to `/data/local/tmp/oneos/system.raw`, `FsProbe` confirms a valid `ext4` or `erofs` superblock, and `GuestImageInspector` confirms `sdk == 36`, `arch == "arm64-v8a"`, and zero `SVE`/`SVE2`/`SME`/`BF16` instructions.
5. **Step 5 — On-Device Framework Patching & Guard Verification:**
   - Verify `PatchEngine` produces `services_patched.jar`, `framework_patched.jar`, `telephony-common_patched.jar`, `ims-ext-common_patched.jar`, and (for ColorOS/OxygenOS) `oplus-services_patched.jar` and `oplus-framework_patched.jar`.
   - Inspect `/data/local/tmp/oneos/guards.txt` and confirm all 11 mandatory keys pass (`vold_key_calls_remaining=0`, `installd_destroy_calls_remaining=0`, `userdata_preparer_destroy_guarded>=1`, `ce_storage_unlocked_true>=1`, `quota_supported_false>=1`, `power_reboot_guarded>=1`, `sys_powerctl_writes_remaining=0`, `weaver_disabled>=1`, `pdb_writes_guarded>=1`, `no_new_users_guarded>=1`, `own_vold_redirected>=1`, `installd_proxy_present>=1`, `app_id_offset_15000>=1`).
6. **Step 6 — Dry-Run Stage Audit (`oneos_root.sh status`):**
   - Run `su -c /data/local/tmp/oneos/bin/oneos_root.sh status` and verify `@@RESULT marker 4 s-ultra-8450 ...`, `files.sha256` match (`files_ok=1`), and zero active mounts or loop devices prior to launch.
7. **Step 7 — First Guest Boot (Verbose Boot Mode + Black-Screen Watchdog):**
   - Enable **Verbose boot** and **Full logs** in OneOS Advanced settings.
   - Start the switch and confirm the emergency hardware key listener (`KEY_VOLUMEUP` 5 presses within 3 seconds) and the 150-second black-screen watchdog (`ONEOS_RETURN_BLACK`) are active.
   - Verify the guest reaches the Android home screen and that newly installed guest apps receive UIDs in `15000..19999` (`adb shell id` inside guest app or `ls -ldn /data/local/tmp/oneos/data/data/<pkg>`).
8. **Step 8 — Emergency & Normal Return Verification:**
   - Test returning to One UI via the `OneOSGuest` ("Back to One UI") app or by pressing **Volume Up 5 times**.
9. **Step 9 — Post-Return Host Integrity Audit:**
   - Once One UI boots back, verify:
     - One UI Settings -> Lock screen can set and remove a PIN/pattern normally (Weaver & SyntheticPassword healthy).
     - All host One UI apps under `/data/user/0` (`10000..14999`) open with their sign-in state and data intact.
     - Wi-Fi, Bluetooth, and Samsung Knox/FRP partitions (`persistent_data_block`) are unmodified.
     - `/data/local/tmp/oneos/logs/last_session.tar.gz` is collected cleanly and `persist_session.active` is cleared.

---

## Section H — Android 16 GSI Compatibility Status

| GSI Family (Android 16 / SDK 36) | Compatibility Status on `SM-S908E` (One UI 8) | Required Patch Set & Runtime Conditions |
|---|---|---|
| **OxygenOS 16 / ColorOS 16 (SDK 36)** | **Supported (Primary Target)** | Full `safety.*` suite + `InstalldAbiPatch` + `boot.*` + `oem.oplus.*` + `oem.qti.builtin_display_public` + `oem.samsung.backlight_curve`. Tested family on `SM8450` One UI 8 hosts (`Images.OXYGEN16`). |
| **AOSP 16 / LineageOS 23 (SDK 36)** | **Supported via On-Device GSI Pipeline** | Full `safety.*` suite + `InstalldAbiPatch` + `boot.*` + `oem.qti.builtin_display_public` + `oem.samsung.backlight_curve` (`oem.oplus.*` gracefully skipped). Requires ARM64 `system.img` with HIDL `composer@2.1..2.4` client support. |
| **Pixel Android 16 GSIs (SDK 36, e.g. Pixel 9 series `komodo`)** | **Supported if Non-SVE** | Accepted only when `GuestImageInspector.scanArm64Incompatibilities` confirms zero `SVE`/`SVE2`/`SME`/`BF16` instructions in `/system/lib64` and `/system/bin`. |
| **Pixel 11 / ARMv9-SVE GSIs (`yogi`, `kodiak`)** | **Blocked (Hardware Incompatible)** | Refused by `CpuRules` and `GuestImageInspector` because `SM8450` (Cortex-X2/A710/A510) lacks `SVE`/`SME`, which causes immediate `SIGILL` crashes in native framework libraries. |
| **32-bit-only (`armeabi-v7a`) or x86_64 GSIs** | **Blocked** | Refused by `GuestImageInspector` (`ro.product.cpu.abi` must be `arm64-v8a`). |

---

## Section I — Android 17 Readiness Assessment

Android 17 (SDK 37) GSIs remain **intentionally blocked** (`SwitchGates.ID_ANDROID` / `GuestImageInspector`) when the host `SM-S908E` is running One UI 8.0 / 8.5 (Android 16, SDK 36). Enabling Android 17 guests on an Android 16 host requires solving four architectural cross-SDK boundaries:

1. **Host APEX & Bionic/ART Symbol Version Boundary (`com.android.runtime`, `com.android.art`):**
   - OneOS bind-mounts or extracts host/bundled APEX payloads (`com.android.art`, `com.android.runtime`, `com.android.i18n`). An Android 17 (SDK 37) guest `system_server` and `libandroid_runtime.so` link against SDK 37 `libc.so` / `libart.so` symbol versions (`LIBC_V` / Android 17 symbol exports) and Java 21+ core-lib APIs that are absent in an Android 16 host runtime APEX. Running an SDK 37 guest on an SDK 36 host therefore requires shipping a complete, self-contained SDK 37 `com.android.runtime`, `com.android.art`, and `com.android.i18n` APEX bundle verified against the host's `5.10` kernel seccomp and futex ABI.
2. **Host `installd` Binder Transaction & Parcelable Wire Evolution (84 vs. 85+ Methods):**
   - One UI 8 (Android 16) `installd` exposes 84 Binder transactions (`InstalldExpected` One UI 8 table), whereas Android 17 (SDK 37) `IInstalld` adds new transactions and modifies parcelable fields in `CreateAppDataArgs`. While `OneOSInstalldProxy` can stub newly added SDK 37 methods, any field-level wire change in `CreateAppDataArgs` requires a full Java-level Parcelable translator inside `OneOSInstalldProxy` rather than direct `IBinder.transact()` forwarding.
3. **SELinux Policy & `service_manager` Context Additions in SDK 37:**
   - Android 17 `system_server` registers new system services with the host/guest `servicemanager` whose SELinux service contexts are absent from One UI 8's `/system/etc/selinux/plat_service_contexts`, causing `SELinux: avc: denied { add }` fatal exceptions in `SystemServer` unless intercepted or run with a patched guest `servicemanager`.
4. **Vendor VINTF & HAL Deprecation on `SM8450` (Vendor API 31):**
   - Android 17 framework binaries drop legacy HIDL fallback paths for sensors, health, and display composer (`IComposer@2.4`) that `SM-S908E`'s vendor partition (`android12-5.10`, vendor API 31) still relies upon. Bridging an Android 17 guest on `SM-S908E` would require userspace HIDL-to-AIDL shim services (`composer3-shim`, `audio-aidl-shim`) staged inside the guest chroot.
