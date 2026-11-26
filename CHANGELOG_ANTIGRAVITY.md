# CHANGELOG_ANTIGRAVITY

Sessão de análise e correções baseada no protocolo [Sync.md](file:///data/data/com.termux/files/home/agy/Sync.md) e nos logs da sessão `c6469cf3ab495fd8` (`App 0.5.45 (188)`, perfil `s-ultra-8450` / `SM-S908E`, imagem `oneui95-s948b`).

---

## Fase 1 — Sincronização com Upstream

- **Remote upstream:** `https://github.com/fahadalijaved/OneOS.git`
- **Commits sincronizados de `upstream/main` (`393f9bd..7d65def`):**
  - `fb49dbd` (`v0.5.45`) — `OneOS 0.5.45`
  - `5501087` — `Credits: pull requests #355, #358, #361, #362`
  - `7d65def` (`v0.5.46`) — `OneOS 0.5.46`
- **Commit de merge:** `2d4d0d1` (`Merge upstream/main (v0.5.45..v0.5.46) with --theirs on conflict`)
- Conflitos em `switcher/assets/guest_prepare.sh` foram resolvidos com `--theirs` e documentados em [SYNC_REPORT.md](file:///data/data/com.termux/files/home/agy/OneOS/SYNC_REPORT.md). Os arquivos específicos de `4d13f58` aplicáveis ao dispositivo foram reintegrados na Fase 4 mantendo as adições do `v0.5.46` (`TAB_CAM_COMPAT`).

---

## Fase 2 — Diagnóstico e Correção de Áudio (`SM-S908E` / Qualcomm PAL + ADSP)

### Causa Raiz Observada nos Logs
- Em `boot.log.06` (`08:40:20`) e `boot.log.01` (`08:45:41`), `com.google.android.googlequicksearchbox` carrega o modelo de hotword de ~110 KB (`7038ddc8-30f2-11e6-b0ac-40a8f03d3f15`, `HOTWORD`) via `PAL: StreamSoundTrigger` -> `SoundTriggerEngineGsl: ProcessStartRecognition`.
- ~23–40 ms depois, o domínio de áudio no ADSP cai (`COSR: ar_osal_pd_notifier_cb: state(0) domain(msm/adsp/audio_pd)`, `PAL: SndMonitor: card status 0`, `SoundTriggerEngineGsl: Failed to start session, status = -102`).
- Diferente do Exynos (onde `vts_load_sound_model` derruba o HAL e o `audioserver`), no Qualcomm PAL o `audioserver` permanece vivo (`ST_STATE_SSR`), fazendo o PAL recarregar o modelo a cada ~0,5 s em loop infinito de SSR (`rproc_recovery_wq` até 57.1% CPU, `audioadsprpcd` 24.1%, `adsprpcd` 17.8%), impedindo reprodução em `AudioOut_15` / `AudioOut_D`.

### Arquivos Modificados
- [guest_start.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_start.sh):
  - Extraídos os helpers `restart_hal()` e `hotword_off()` dentro de `audio_keeper`.
  - Além do crash Exynos `(vts_load_sound_model+`, `hotword_off()` detecta falhas QTI PAL/ADSP (`SoundTriggerEngineGsl:.*Failed to start` ou `StreamSoundTrigger:.*LoadSoundModel` acompanhado de `ar_osal_pd_notifier_cb` `state(0)` em `msm/adsp/audio_pd` ou `SndMonitor:.*card status 0`) mesmo enquanto o `audioserver` ainda está vivo.
  - Aplica `cmd appops set <pkg> RECORD_AUDIO_HOTWORD ignore` e `am force-stop` em `com.google.android.googlequicksearchbox` (e `com.android.hotwordenrollment.okgoogle` / `xgoogle`), grava `/data/oneos_hotword_off.log`, reinicia `vendor.audio-hal*` e `audioserver` para limpar o estado `ST_STATE_SSR` e normalizar o áudio.

---

## Fase 3 — Diagnóstico e Correção de FOD (`QBT2608` Ultrassônico)

### Causa Raiz Observada nos Logs
1. `oneui95-a16.guards.txt` não possui `# biometric-remove guard`, pois [make-overlays.sh](file:///data/data/com.termux/files/home/agy/OneOS/scripts/make-overlays.sh) aplicava `guest_no_biometric_cleanup.py` sem aplicar `guest_no_biometric_remove.py` nem emitir `# biometric-remove guard` em `guards.txt`. Consequentemente, [guest_prepare.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_prepare.sh) removia `settings_fingerprint*.xml` do guest (`hasEnrollments: false`).
2. Ao tentar cadastrar uma nova digital nas Configurações do guest, `SemFingerprintEnrollClient` envia o token placeholder de `OneOSGuestLock.hat()` (sem HMAC válido de Gatekeeper) para o HAL físico `vendor.samsung.hardware.biometrics.fingerprint@3.0::ISehBiometricsFingerprint/default` (PID 11839), que rejeita o cadastro com `handleError: error=8, vendor=1003` (`BMFP: fp_error: 8, 1003, enroll_error`), acompanhado de `FingerprintProvider: skip req (35) due to Hal not running` e `BiometricSysUiManager: show: No exist ID`.
3. No guest One UI 9.5 (`oneui95-s948b`), a mesclagem de `/system/etc/floating_feature.xml` preservava apenas chaves `SEC_FLOATING_FEATURE_CAMERA_*` do host, mantendo `SEC_FLOATING_FEATURE_BIOAUTH_*` do aparelho doador (S26 Ultra).

### Arquivos Modificados
- [make-overlays.sh](file:///data/data/com.termux/files/home/agy/OneOS/scripts/make-overlays.sh):
  - Integra `switcher/patches/guest_no_biometric_remove.py` na geração de `services_patched.jar` e emite `# biometric-remove guard` em `$OUT/guards.txt` quando aplicado (e aplica condicionalmente `guest_force_aidl_fingerprint.py`, `guest_udfps_location.py`, `guest_seh_touch.py`, `guest_fp_touchwatch.py` em imagens não-One UI compatíveis).
- [guest_prepare.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_prepare.sh):
  - Preserva as chaves `SEC_FLOATING_FEATURE_BIOAUTH_*` do host junto às chaves `SEC_FLOATING_FEATURE_CAMERA_*` em `$STAGE/data/oneos_system_floating_feature.xml` para que o sensor ultrassônico/óptico real do aparelho host seja exposto ao framework guest.

> **Observação importante:** Os pacotes binários pré-compilados em `packages/oneui95-a16` precisam ser reconstruídos com `scripts/make-overlays.sh` para incluir `guest_no_biometric_remove.py` e `# biometric-remove guard` no `guards.txt` e assim habilitar com segurança o reaproveitamento das digitais já cadastradas na One UI.

---

## Fase 4 — Estabilidade, Fluidez e Bateria

### Problemas Identificados nos Logs
- **`AdbDebuggingManager` em loop de 200x/s (`29.065` linhas E/W em `boot.log.06`):** Em rede Wi-Fi confiável, `AdbDebuggingManager` enviava `W1` para o `adbd` do host (`Broken pipe`) e reconectava continuamente sem pausa, consumindo CPU e bateria.
- **Perdas do `--theirs` em `guest_prepare.sh` confirmadas em `takeover_app.log` e `ld.config.guest.log`:** Kernel `android12-5.10` (`SM-S908E`) recusando `overlayfs` em `system_ext` (perdendo `oneos-telephony.jar`) e em `product/etc` (`displayconfig` recusado), ausência de `/system/xbin` ao montar tmpfs para `su`, e 8 avisos de `:sphal` / `libcom_android_compos_ffi_c.so` no `ld.config.guest`.
- **`dex2oat64` (233% CPU) / `kswapd0` (89% CPU):** O limite de threads/heap de `dex2oat` adicionado em `guest_props.sh` (já preservado no merge em `4d13f58..HEAD`) não estava presente na versão `0.5.45` executada nos logs.

### Arquivos Modificados
- [guest_prepare.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_prepare.sh):
  - Reaplica `WANT_INJ` e fallbacks `farm_dir` em `system_ext/framework` (`oneos-telephony.jar`, `OneOSTabS9FeCamCompat.jar` do `0.5.46`) e `system_ext/etc/permissions` quando `overlayfs` é recusado.
  - Reaplica fallback `bind-farm` em `product/etc/displayconfig` (`display_port_*.xml`).
  - Reaplica ponte `/data/oneos_bin/su` quando `$ROOTFS/system/xbin` não existe.
  - Reaplica suporte a links de namespace (`:sphal`) e skip opcional de `libcom_android_compos_ffi_c.so` no awk do `ld.config.guest.txt`.
  - Reaplica `bind_first_host_app` (`NetworkStack*`, `CaptivePortalLogin*`), verificação de `PermissionController` em payloads/One UI e diagnóstico `ls -lZ /sys/fs/bpf/netd_shared`.
  - Define `adb_wifi_enabled=0` no `seed_settings` de `settings_global.xml` para impedir o loop `Send packet W1 -> Broken pipe` contra o `adbd` do host.

---

## Verificação e Instruções de Build / Teste

### Checks Executados após as Últimas Edições
- Sintaxe shell (`sh -n` e `bash -n`): **PASSOU** em `guest_start.sh`, `guest_prepare.sh` e `make-overlays.sh`.
- `test-audio-hal-stale-restart.py`: **PASSOU** (`Ran 4 tests ... OK`).
- `test-guest-fingerprint-aidl.py`: **PASSOU** (`Ran 7 tests ... OK`).
- `test-guest-root.py`: **PASSOU** (`Ran 6 tests ... OK`).
- `test-guest-controls.py`: **PASSOU** (`Ran 10 tests ... OK`).
- `test-guest-installer-helpers.py`: **PASSOU** (`Ran 5 tests ... OK`).
- `test-verified-overlays.py`: **PASSOU** (`Ran 4 tests ... OK`).
- `test-launch-switch-verdict.py`: **PASSOU** (`Ran 18 tests ... OK`).
- `test-boot-fixes`: **PASSOU** (`test-boot-fixes OK`).
- Smoke checks (Python/sh): validado `guest_no_biometric_remove.py` em smali `SemFingerprintRemovalClient`, presença de `SEC_FLOATING_FEATURE_BIOAUTH_` e `adb_wifi_enabled=0`, ausência de apóstrofos no bloco `audio_keeper`, e match da assinatura PAL/ADSP nos logs reais.
- Notas de ambiente no Termux:
  - `check-shell.py` executado com `sh -n` (no lugar de `busybox`, ausente no PATH do Termux) verificou 16 scripts embutidos sem erros nos blocos alterados (apenas apontando uma linha pré-existente com `grep -q '^ro.build.version.oneui='` em `guest_start.sh:2466`).
  - `test-guest-audio-policy.py` (2/31 falhas) e `test-guest-boot-service-gate.py` (2/2 falhas) apresentaram falhas ligadas ao ambiente Termux/host Android ou suíte pré-existente fora dos trechos editados; `check-oneos-refs.py` requer `<dexdump>` sobre overlays compilados.

### Build / Implantação
1. **Atualizar overlays (`services_patched.jar` + `guards.txt`)** em ambiente Linux/CI com Android SDK + `apktool`:
   ```sh
   scripts/make-overlays.sh <caminho/system.img> out/overlays
   ```
   Confirme que `out/overlays/guards.txt` contém `# biometric-remove guard: ...` e rode `scripts/check-oneos-refs.py <dexdump> out/overlays/services_patched.jar`.
2. **Empacotar os scripts atualizados** (`switcher/assets/guest_start.sh`, `switcher/assets/guest_prepare.sh`) no aplicativo `app-android` (`./gradlew :app:assembleDebug` ou equivalente) para aplicar em sessão no `SM-S908E`.

---

## Workflows CI/CD (GitHub Actions) e Otimização de CPU/Memória no Switch (`work.md`)

### Frente 1 — Workflow de Overlays ([build-overlays.yml](file:///data/data/com.termux/files/home/agy/OneOS/.github/workflows/build-overlays.yml))
- Acionado manualmente via `workflow_dispatch`:
  - `image_url` (`string`, obrigatório): URL direta de imagem/arquivo base (`.zip`, `.tar.gz`/`.tgz`, `.tar.xz`/`.txz`, `.xz`, `.gz`, `.7z`, `.img`, sparse `simg2img`, `system.new.dat.br`, `super.img` via `lpunpack` e EROFS via `fsck.erofs`/`mount` + `mke2fs`). Download com `aria2c` e fallback `curl -fSL`.
  - `package_name` (`string`, padrão `oneui95-a16`): subpasta destino em `packages/<package_name>` e nome do artefato.
  - `commit_to_repo` (`boolean`, padrão `false`): quando `true`, copia a saída para `packages/<package_name>/` (sincronizando `app-android/packages/<package_name>.guards.txt` se existir) e realiza commit/push no branch atual.
- Ambiente `ubuntu-latest`: instala JDK 17 (`temurin`), Android SDK (`build-tools;35.0.0`, `platforms;android-36`), `apktool` (`2.11.1` se ausente em `mysticgsi/tools/apktool`), `~/.android/debug.keystore`, `e2fsprogs` (`debugfs`), `erofs-utils`, `fuse2fs`, `p7zip-full`, `android-sdk-libsparse-utils`, `brotli`.
- Executa `scripts/make-overlays.sh`, valida `guards.txt` (`# biometric-remove guard`) e `scripts/check-oneos-refs.py $(command -v dexdump) out/overlays/services_patched.jar`, empacota `.tar.gz`/`.zip` e publica via `actions/upload-artifact@v4`.

### Frente 2 — Workflow de Build do APK ([build-apk.yml](file:///data/data/com.termux/files/home/agy/OneOS/.github/workflows/build-apk.yml))
- Acionado em `workflow_dispatch` e `push` em `main`.
- Executa validação pré-build (`sh -n` em `switcher/assets/*.sh` e `scripts/*.sh`, `test-boot-fixes.py` e suítes unitárias em `scripts/test-*.py`).
- Configura JDK 21 (`temurin`), Android SDK (`platform-tools`, `build-tools;35.0.0`, `platforms;android-36`, `platforms;android-37 || true`) e cache Gradle (`gradle/actions/setup-gradle@v4`).
- Verifica se os secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD` estão preenchidos:
  - Presentes: cria `~/.oneos-release/release.jks` e `keystore.properties`, executa `scripts/build-guest-manager.sh release` e `./gradlew :app:assembleRelease`.
  - Ausentes: executa `scripts/build-guest-manager.sh debug` e `./gradlew :app:assembleDebug`.
- Publica os APKs gerados com nome descritivo `oneos-apk-v<versão>-<short_sha>-<debug|release>` via `actions/upload-artifact@v4`.

### Frente 3 — Otimizações de CPU, `dex2oat`, Memória e Watchdogs no Switch
- **Bloqueio preventivo de Hotword (Zero Crash no ADSP)** ([guest_start.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_start.sh#L4013-L4042)):
  - Subshell leve aguarda o serviço `package` (no namespace de montagem do guest) e `appops`, aplicando preventivamente `cmd appops set <pkg> RECORD_AUDIO_HOTWORD ignore` em `com.google.android.googlequicksearchbox` e `com.android.hotwordenrollment.{okgoogle,xgoogle}` em hosts Qualcomm (`QTI`, configurável via `ONEOS_GUEST_PREVENT_HOTWORD=0`) antes do `SoundTrigger` inicializar, gravando `/data/oneos_hotword_preblocked` e `/data/oneos_hotword_preblock.log`.
- **Controle rigoroso de `dex2oat` e `bg-dexopt-job`** ([guest_props.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_props.sh#L2118-L2147), [guest_start.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_start.sh#L4032-L4035)):
  - Além de `boot-dex2oat-threads=2`, `dex2oat-threads=2`, `dex2oat-Xmx=256m` e `dex2oat-minidebuginfo=false`, adiciona `dalvik.vm.background-dex2oat-threads=1`, `dalvik.vm.dex2oat-cpu-set`, `dalvik.vm.background-dex2oat-cpu-set` (lidos de `policy0/related_cpus`, padrão `0,1,2,3` para confinar aos núcleos LITTLE no Snapdragon 8 Gen 1) e `pm.dexopt.bg-dexopt=speed-profile` (restaurados/limpos no retorno por `guest_props.sh remove`).
  - Cancela execuções imediatas de `cmd package bg-dexopt-job --cancel` logo após a subida do `package` service e +20 s depois (`ONEOS_DEFER_BG_DEXOPT=0` desativa).
- **Alívio de pressão de memória e `kswapd0`** ([oneos_switch.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/oneos_switch.sh#L1752-L1780), [return_to_oneui.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/return_to_oneui.sh#L95-L97), [return_to_oneui.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/return_to_oneui.sh#L1149-L1154)):
  - `return_to_oneui.sh baseline` salva `/proc/sys/vm/swappiness` e `vfs_cache_pressure` em `inplace_base/vm_sysctl`.
  - Após parar a One UI e antes de subir o container guest (`ONEOS_GUEST_MEM_TUNE=0` desativa), `oneos_switch.sh` executa `sync` + `echo 1 > /proc/sys/vm/drop_caches`, limita `vm.swappiness` a `60` (quando > 60, ex.: `130` no `SM-S908E`) e ajusta `vm.vfs_cache_pressure` para `80`, restaurando os valores exatos do host no `return_to_oneui.sh`.
- **Suavização de polling e `logcat` filtrado em `audio_keeper`** ([guest_start.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_start.sh#L1604-L1643)):
  - Filtro de tags no `logcat` (`-t 250 -s "PAL: SoundTriggerEngineGsl:E" "PAL: StreamSoundTrigger:I" "PAL: SndMonitor:I" "COSR:I" "*:S"` e `-b crash -t 100`), espaçamento do check reativo para cada 15 s (`hwt % 5`), e skip imediato quando `/data/oneos_hotword_preblocked` ou `/data/oneos_hotword_off.log` estiver presente.

### Verificação Final após as Últimas Edições (`task-453`)
- `sh -n` e `bash -n` **PASSARAM** em `guest_start.sh`, `guest_prepare.sh`, `guest_props.sh`, `oneos_switch.sh`, `return_to_oneui.sh` e `make-overlays.sh`.
- `YAML_BASIC_STRUCTURE_OK` em `build-overlays.yml` e `build-apk.yml` (PyYAML não instalado no Termux).
- `test-boot-fixes.py` (4/4), `test-audio-hal-stale-restart.py` (4/4), `test-guest-fingerprint-aidl.py` (7/7), `test-guest-root.py` (6/6), `test-guest-controls.py` (10/10), `test-guest-installer-helpers.py` (5/5), `test-verified-overlays.py` (4/4) e `test-launch-switch-verdict.py` (18/18) **PASSARAM**.
- `check-shell.py` (`sh -n` no lugar de `busybox`): 41 blocos verificados sem falhas nos trechos modificados (apenas 2 achados pré-existentes do upstream em `guest_start.sh:2466` e `oneos_switch.sh:2725`).

---

## Correção de Resolução Maven/Gradle no GitHub Actions (`maven.md`)

### Causa Raiz Identificada
1. **Dependências SESL9 e `oneui-design` hospedadas exclusivamente no GitHub Packages (`tribalfs`):**
   - Validação direta via `curl` nos endpoints confirmou que `io.github.tribalfs:oneui-design:0.9.13+oneui8` e os 17 artefatos `sesl.androidx.*` / `sesl.com.google.android.material:material` (`sesl9`) não estão no Maven Central (`404`) nem no JitPack (`400`/`404`), existindo apenas nos repositórios GitHub Packages de `tribalfs` (`tribalfs/oneui-design`, `tribalfs/sesl-androidx`, `tribalfs/sesl-material-components-android`).
   - Em contrapartida, `com.android.application:9.4.1`, `com.android.tools.smali:smali-dexlib2:3.0.10` (`200` no Google Maven), `io.github.oneuiproject:icons:1.1.0` (`200` no Maven Central) e `org.jetbrains.kotlin.jvm:2.4.21` (`200` no Gradle Plugin Portal) estão íntegros.
2. **Autenticação cross-owner no GitHub Actions (`build-apk.yml` e `android.yml`):**
   - [build-apk.yml](file:///data/data/com.termux/files/home/agy/OneOS/.github/workflows/build-apk.yml) não declarava `permissions: contents: read` / `packages: read` e fixava `GH_PACKAGES_TOKEN: ${{ secrets.GITHUB_TOKEN }}` sem consultar secrets de PAT. Como `GITHUB_TOKEN` é restrito ao repositório atual, o GitHub Packages recusa downloads cross-owner em `maven.pkg.github.com/tribalfs/*` com `401 Unauthorized` / `403 Forbidden`.
   - [android.yml](file:///data/data/com.termux/files/home/agy/OneOS/.github/workflows/android.yml) também usava JDK `24` (incompatível com `jvmToolchain = "21"` e `org.gradle.java.installations.auto-download=false`) e não passava credenciais nem cache Gradle.
3. **Configuração de repositórios e timeouts no Gradle (`settings.gradle.kts` e `gradle-wrapper.properties`):**
   - [settings.gradle.kts](file:///data/data/com.termux/files/home/agy/OneOS/app-android/settings.gradle.kts) usava a URL wildcard `https://maven.pkg.github.com/tribalfs/*` sem `content { includeGroupByRegex(...) }`, consultando o GitHub Packages inclusive para artefatos do Maven Central/Google Maven, enviando `credentials` vazias quando sem token e ignorando `GPR_USER`/`GPR_TOKEN`/`GITHUB_ACTOR`/`GITHUB_TOKEN` e propriedades Gradle.
   - [gradle-wrapper.properties](file:///data/data/com.termux/files/home/agy/OneOS/app-android/gradle/wrapper/gradle-wrapper.properties) definia `networkTimeout=10000` e `retries=0`, insuficiente para redes de CI.

### Arquivos Alterados
- [settings.gradle.kts](file:///data/data/com.termux/files/home/agy/OneOS/app-android/settings.gradle.kts):
  - Substituído `https://maven.pkg.github.com/tribalfs/*` pelos 3 repositórios explícitos (`tribalfs/oneui-design`, `tribalfs/sesl-androidx`, `tribalfs/sesl-material-components-android`) com filtro `content { includeGroupByRegex("^(io\\.github\\.tribalfs|sesl\\..*)$") }`.
  - Adicionada resolução limpa de credenciais (`~/.config/twidget/github.properties`, `local.properties`, propriedades Gradle `ghPackages*`/`gpr.*` e env vars `GH_PACKAGES_*`, `GPR_*`, `GITHUB_ACTOR`/`GITHUB_TOKEN`), aplicando `credentials` apenas quando não vazias e emitindo aviso claro se ausentes em modo online.
- [gradle-wrapper.properties](file:///data/data/com.termux/files/home/agy/OneOS/app-android/gradle/wrapper/gradle-wrapper.properties):
  - `networkTimeout` aumentado para `60000` e `retries` para `3`.
- [build-apk.yml](file:///data/data/com.termux/files/home/agy/OneOS/.github/workflows/build-apk.yml):
  - Adicionado `permissions: contents: read` e `packages: read`.
  - Configurado `GH_PACKAGES_USER`/`GPR_USER` (`secrets.GH_PACKAGES_USER || secrets.GPR_USER || github.actor`) e `GH_PACKAGES_TOKEN`/`GPR_TOKEN` (`secrets.GH_PACKAGES_TOKEN || secrets.GPR_TOKEN || secrets.GITHUB_TOKEN`), além de `GRADLE_OPTS` com `-Dorg.gradle.internal.http.connectionTimeout=60000 -Dorg.gradle.internal.http.socketTimeout=60000`.
  - Adicionada verificação diagnóstica não bloqueante de acesso ao GitHub Packages (`Check GitHub Packages Maven access`), instalação opcional de `build-tools;36.0.0`, geração limpa de `keystore.properties` via `printf` e `--stacktrace` no `./gradlew`.
- [android.yml](file:///data/data/com.termux/files/home/agy/OneOS/.github/workflows/android.yml):
  - Alinhado JDK de `24` para `21`, adicionado `permissions`, `setup-gradle@v4`, `GRADLE_OPTS` e repasse de credenciais `GH_PACKAGES_*` / `GPR_*`.

### Secret Necessário no GitHub Actions (para cache frio)
- Para resolver `io.github.tribalfs:oneui-design` e os pacotes `sesl.*` (`sesl9`) do proprietário `tribalfs` em cache frio no GitHub Actions, configure nos **Repository Secrets** (`Settings -> Secrets and variables -> Actions`):
  - **`GH_PACKAGES_TOKEN`** (ou **`GPR_TOKEN`**): um Personal Access Token (Classic) do GitHub com o escopo **`read:packages`**.
  - **`GH_PACKAGES_USER`** (ou **`GPR_USER`**, opcional): usuário GitHub dono do PAT (se omitido, usa `${{ github.actor }}`).

---

## Correções Fases 1–6 (`hwga.md` — `SM-S908E`, App `0.5.46 (189)`, `oneui95-s948b`)

Análise baseada nos logs completos em `/data/data/com.termux/files/home/agy/oneos-logs/` para `SM-S908E` (`s-ultra-8450`, host One UI 8.0 / SDK 36, guest `oneui95-s948b`).

### Fase 1 — Isolamento do Repositório (`Jacquesdemol/OneOS`) e Desativação de Auto-Update
- [AppUpdate.kt](file:///data/data/com.termux/files/home/agy/OneOS/app-android/core/model/src/main/kotlin/com/fahadali/oneos/core/model/AppUpdate.kt#L12-L37) e [AppUpdateTest.kt](file:///data/data/com.termux/files/home/agy/OneOS/app-android/core/model/src/test/kotlin/com/fahadali/oneos/core/model/AppUpdateTest.kt#L12-L56): URL de `update.json`, `APK_PREFIX` e fixtures apontados exclusivamente para `Jacquesdemol/OneOS` (rejeitando `fahadalijaved/OneOS` nos testes de validação).
- [Images.kt](file:///data/data/com.termux/files/home/agy/OneOS/app-android/core/model/src/main/kotlin/com/fahadali/oneos/core/model/Images.kt#L119): `GOFILE_FOLDER_URL` alterado para `https://github.com/Jacquesdemol/OneOS#where-the-images-come-from`. Mantidos os downloads de imagens/artefatos em `fahadalijaved/OneOS-files`, pois `Jacquesdemol/OneOS-files` não existe (`404`).
- [release-app.py](file:///data/data/com.termux/files/home/agy/OneOS/scripts/release-app.py#L40): `REPO` alterado para `"Jacquesdemol/OneOS"`.
- [AppUpdater.kt](file:///data/data/com.termux/files/home/agy/OneOS/app-android/app/src/full/kotlin/com/fahadali/oneos/session/AppUpdater.kt), [EditionHome.kt](file:///data/data/com.termux/files/home/agy/OneOS/app-android/app/src/full/kotlin/com/fahadali/oneos/ui/EditionHome.kt), [SystemsActivity.kt](file:///data/data/com.termux/files/home/agy/OneOS/app-android/app/src/full/kotlin/com/fahadali/oneos/ui/SystemsActivity.kt) e [EnvironmentActivity.kt](file:///data/data/com.termux/files/home/agy/OneOS/app-android/app/src/full/kotlin/com/fahadali/oneos/ui/EnvironmentActivity.kt):
  - Removida verificação automática de atualização em `onResume()` de `EditionHome` / `SystemsActivity` e bloqueado `AppUpdater.check(ctx, force = false)` exceto quando `force = true` ou `testUrl != null`.
  - Protegida a leitura `PatchChannels.stableRelease()` em `checkPatches()` com `runCatching { ... }.getOrNull()`.

### Fase 2 — Desempenho, Aquecimento, `dex2oat64`, Teto de CPU e Taxa de Atualização
- **Causa raiz nos logs (`oneos-logs`):**
  1. Os logs rodavam build `0.5.46 (189)` anterior à mitigação preventiva de `HOTWORD` ADSP SSR (`adsprpcd` / `rproc_recovery_wq` consumindo até 38,7% de CPU).
  2. `dex2oat64` (PID 6167) abortou com `SIGABRT` (`art::ClassLinker::InitWithoutImage -> FindSystemClass -> Thread::SetException`, saída `81` do `odrefresh --compile`) por falta de heap (`dalvik.vm.dex2oat-Xmx=256m`).
  3. O `performance_handover.log` registrou o SSRM do host mantendo `cpufreq_max_limit=1843200` kHz após a troca; `heat_cap` não liberava `-1` na inicialização e derrubava o teto imediatamente para `level 2 (65%)` com `dchg_temp` em `52 °C` (`>= 48,0 °C`), enquanto a temperatura de pele estava em apenas `41,4 °C`.
  4. `GUEST_THERMAL_MAP` excluía guests `oneui` (o guest viu `Thermal Status: 3` `SEVERE` aos `43,8 °C` de pele em `diag_40_thermal.txt`).
  5. `panel_keeper` esperava dois ciclos de 15 s (16 s a `24 Hz`) antes do primeiro ajuste de SurfaceFlinger para `120 Hz`.
- **Alterações aplicadas:**
  - [guest_props.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_props.sh#L2133): `dalvik.vm.dex2oat-Xmx` elevado de `256m` para `512m`.
  - [oneos_switch.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/oneos_switch.sh#L4640-L4835):
    - Habilitado `GUEST_THERMAL_MAP=1` também para imagens `oneui*`.
    - Liberação imediata de host `cpufreq_max_limit` (`echo -1 > ...` + registro em `$STAGE/cpufreq_max_limit.released`) no início do `heat_cap` quando `HEAT_CAP=1` e `HOST_LOW_POWER=0`.
    - Limiares `dchg_temp` do `heat_cap` ajustados de `440`/`480` (`44 °C`/`48 °C`) para `520`/`560` (`52 °C`/`56 °C` com histerese de `2 °C`).
    - `panel_keeper` passa a consultar a cada `4 s` nos 10 primeiros ticks enquanto `kicks = 0` e dispara o kick já no primeiro ciclo em baixa taxa de quadros.

### Fase 3 — Correção de Crash e Queda de Frames na `SamsungCamera`
- **Causa raiz nos logs (`oneos-logs`):**
  1. `boot.log.02:107638` registrou `W NodeFeatureLoader: loadNodeFeature - invalid node feature name [image_codec.samsung.v1]`, abortando a thread `PostProcessThread` da `SamsungCamera` (`IllegalStateException: ImageCodecDummyNode can't initialize`) porque a `NodeFeature$NodeFeatureVersion` da One UI 9.5 aceita apenas `image_codec.samsung.v2`.
  2. O parcelamento de `CaptureResultExtras` do Android 17 lê `MultiResConcurrentReadersStartInfo[]` (`createTypedArray`) após o fim do `CaptureResultExtras` escrito pela câmera do host One UI 8, consumindo os bytes seguintes de `onCaptureStarted` e causando `10.502` falhas de `BadParcelableException` (`unread size: 4` / alocação inválida).
- **Alterações aplicadas:**
  - [a16_camera.py](file:///data/data/com.termux/files/home/agy/OneOS/switcher/patches/a16_camera.py#L53-L95): Adicionado `patch_capture_result_extras(root, orig)`, que copia `CaptureResultExtras.smali` de `orig` e zera a leitura de `MultiResConcurrentReadersStartInfo.CREATOR` (`createTypedArray` substituído por `const/4 ..., 0x0` + `iput-object`) dentro de `readFromParcel(Parcel)`.
  - [guest_prepare.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_prepare.sh#L8644-L8672): Na mesclagem de `oneos_system_floating_feature.xml`, adicionados `tag_val()`, `img_codec_tok()` e `merge_vendor_lib()` para preservar o token `image_codec.*` do guest (`image_codec.samsung.v2`) dentro da chave `SEC_FLOATING_FEATURE_CAMERA_CONFIG_VENDOR_LIB_INFO` vinda do host.

### Fase 4 — Curva de Brilho Automático e Configuração de Painel (`SM-S908E`)
- **Causa raiz nos logs (`oneos-logs`):**
  1. No build `0.5.46`, `Display config: read-only overlay on product/etc refused, left out` deixou ativo o perfil de backlight do S26 Ultra (`DisplayDeviceConfig: backlight max=1275`, `mBrightnessMax=5.0` vs `2.4` do `SM-S908E`), limitando o brilho real de hardware a ~14–15% mesmo com o slider em >90%.
  2. A mesclagem de `oneos_system_floating_feature.xml` cobria apenas `CAMERA` e `BIOAUTH`, mantendo ativas chaves `SEC_FLOATING_FEATURE_LCD_*` do doador S26 Ultra (`PRIVACY_DISPLAY=1`, `NIGHT_DIM=1`, `HW_MDNIE=QC`, `HFR_MODE=4`).
- **Alterações aplicadas:**
  - [guest_prepare.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_prepare.sh#L2123): No caminho `bind-farm` de `$PETC/displayconfig`, adicionados `mount --make-rslave "$PETC/displayconfig"` e `remount,ro "$DOV"`.
  - [guest_prepare.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_prepare.sh#L8618-L8672): Estendida a mesclagem do `oneos_system_floating_feature.xml` para incluir as chaves `SEC_FLOATING_FEATURE_LCD_*` do host (`CAMERA|BIOAUTH|LCD`).

### Fase 5 — Diagnóstico e Validação de FOD (`QBT2608` Ultrassônico)
- **Achados nos logs (`oneos-logs`) e no `services_patched.jar` local:**
  - O host limpou `/data/system/users/0/settings_fingerprint_0.xml` (`boot.log.03:19034` -> `fp_remove_all: user=0`) ao remover o bloqueio do host antes da troca, e o `guards.txt` da build `0.5.46` não possuía `# biometric-remove guard`.
  - Dentro do guest, o cadastro nativo via Configurações falha no TEE/HIDL (`bauth_FPBAuthService: enroll hat is 0` -> `error=8, vendor=1003` -> `BiometricSysUiManager: show: No exist ID`) porque `QBT2608` valida o HMAC TEE do `HardwareAuthToken` do Gatekeeper, inexistente no bloqueio local `OneOSGuestLock`.
  - Validado sobre o smali de `services_patched.jar` que tanto `guest_no_biometric_cleanup.py` quanto `guest_no_biometric_remove.py` aplicam sem erros (`SemFingerprintRemovalClient` inexistente; remoção protegida em `FingerprintRemovalClient`/`FaceRemovalClient`), enquanto `FingerprintProvider/defaultHIDL`, `showUdfpsIcon` e `setTspMode` já operam normalmente nos logs.
  - **Pré-requisito de uso:** cadastrar as digitais na One UI host **sem remover o bloqueio de tela do host** e reconstruir `packages/oneui95-a16` para ativar `# biometric-remove guard`.

### Fase 6 — Análise do Módulo `MuhammedAliMizrakTR/OneOS` (`SwBridge IO v2.0` / PR #110) e Revisão do PR #107
- **Veredito técnico sobre `OneOS_Sw_Bridge_IO_v2.zip` / PR #110 — REJEITADO / NÃO APLICADO:**
  - O núcleo em `scripts/oneos_core.sh` executa `locksettings clear --old "$pin"` + `locksettings set-disabled true` + `rm -f /data/system/gatekeeper.password.key /data/system/gatekeeper.pattern.key` antes da troca (`SwOUT`) e restaura o PIN via `locksettings set-pin/password "$pin"` (`SwIN`), gravando o PIN em texto puro em `/data/adb/oneos_helper/config.json`.
  - **Obstáculos críticos no `SM-S908E`:**
    1. Chamar `locksettings clear` na One UI 8 dispara `FingerprintService.removeAll` (`fp_remove_all`), apagando todas as digitais `QBT2608` antes mesmo de o guest subir — anulando a preservação biométrica da Fase 5.
    2. Remover `gatekeeper.*.key` diretamente em `/data/system` cria risco de dessincronia de FBE / `SyntheticPassword`.
    3. Armazenamento de credencial do usuário em texto puro no `/data/adb/oneos_helper/config.json`.
    4. Inconsistência entre `README_EN.md` (que descreve backup/restore de permissões KernelSU `ManualSUBak`) e o `oneos_core.sh` v2.0 (onde essa lógica foi removida), além de ser funcionalmente redundante frente ao isolamento já feito por `guest_no_weaver.py`, `guest_no_gatekeeper.py`, `guest_local_lock.py` e `guest_no_biometric_remove.py`.
- **Revisão e endurecimento do PR #107 (`Faster OneOS starts and returns`, já integrado ao upstream):**
  - [check_host_apex.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/check_host_apex.sh#L32-L35): Adicionado `[ "$n" -eq 0 ] && continue` antes de `find ... | xargs -0 cat`, evitando que o `xargs -0` do BusyBox/toybox (sem `-r`) execute `cat` bloqueado em `stdin` quando um APEX não contiver arquivos regulares.
  - [oneos_switch.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/oneos_switch.sh#L620): Substituída a expansão direta `${R#*displayHeight=}` por `cut_after "$R" 'displayHeight=' ' '`, padronizando a guarda contra ausência do marcador na extração de `HDH`.
  - [guest_prepare.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_prepare.sh#L675-L678): Reforçada a checagem de cache em `sec_ims_stage()` para confirmar a presença de `$SECIMS/priv-app/OneOSSecIms/imsservice.apk` e `$SECIMS/framework/oneos-secims-compat.jar` além de `.stamp`.
  - [guest_props.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_props.sh#L2373-L2385): Adicionado `if (eq <= 1) continue` e `close(...)` na leitura de `nowf`/`blankf`/`snapf` do awk de `restore-persist`, impedindo chaves vazias caso haja linhas malformadas.

> **Ação obrigatória de build (CI):**
> 1. Reconstruir `packages/oneui95-a16` (`framework_patched_oneui8.jar` / `services_patched.jar` / `guards.txt`) via [build-overlays.yml](file:///data/data/com.termux/files/home/agy/OneOS/.github/workflows/build-overlays.yml) para incluir `patch_capture_result_extras` (`a16_camera.py`) e `# biometric-remove guard` (`guest_no_biometric_remove.py`).
> 2. Recompilar o APK do app Android (`app-android`) via [build-apk.yml](file:///data/data/com.termux/files/home/agy/OneOS/.github/workflows/build-apk.yml) (ou Gradle local) para incluir os ajustes das Fases 1–4 e 6.

### Checklist de Verificação no Aparelho (Termux / ADB após Nova Sessão no Guest)
1. **Isolamento de Repositório e Auto-Update (Fase 1):**
   - Abrir o app OneOS (`EditionHome`, `SystemsActivity`) no host sem tráfego de rede automático para `fahadalijaved/OneOS`.
   - Confirmar via botão manual de verificação na UI que erros de `PatchChannels` não fecham a tela (`EnvironmentActivity`).
2. **Desempenho, Térmica, `dex2oat64` e Refresh Rate (Fase 2):**
   - No host via ADB/Termux durante a sessão:
     ```sh
     cat /data/local/tmp/oneos/heat_cap.log | tail -n 30
     cat /data/local/tmp/oneos/panel_keeper.log | tail -n 20
     grep -E 'dex2oat64|odrefresh' /data/local/tmp/oneos/boot.log* | head -n 40
     ```
   - Confirmar liberação inicial de `cpufreq_max_limit` (`->-1`), mapa térmico ativo em skin `< 47 °C` (`NONE`), ausência de `SIGABRT` em `dex2oat64` e resposta imediata de `panel_keeper` para `120 Hz` (`/sys/class/lcd/panel/vrr`).
3. **Câmera (`SamsungCamera` — Fase 3):**
   - Confirmar após rebuild de `oneui95-a16`:
     ```sh
     grep -E 'ImageCodecDummyNode|loadNodeFeature|BadParcelableException|CaptureResultExtras' /data/local/tmp/oneos/boot.log*
     grep 'CAMERA_CONFIG_VENDOR_LIB_INFO' /data/local/tmp/oneos/data/oneos_system_floating_feature.xml
     ```
   - Testar abertura da câmera, preview contínuo, captura de foto e troca de lentes (UW/1x/3x/10x) sem fechamento nem quedas repetidas de `onCaptureStarted`.
4. **Brilho Automático e Configuração de Painel (Fase 4):**
   - Conferir nos logs da sessão:
     ```sh
     grep -E 'Display config|brightnessMaximum|DisplayDeviceConfig' /data/local/tmp/oneos/takeover_app.log /data/local/tmp/oneos/boot.log*
     grep 'FLOATING_FEATURE_LCD_' /data/local/tmp/oneos/data/oneos_system_floating_feature.xml
     ```
   - Confirmar montagem bem-sucedida de `displayconfig` (via bind-farm sem recusa read-only) e slider em ambiente externo atingindo ~400+ nits no `SM-S908E`.
5. **FOD Ultrassônico (`QBT2608` — Fase 5):**
   - Garantir digital cadastrada na One UI host (PIN/padrão ativo, sem usar módulos como `SwBridge`), verificar `# biometric-remove guard` em `guards.txt`, iniciar o guest e confirmar desbloqueio na tela de bloqueio sem re-cadastro dentro do guest.

---

## Correção Adicional de Áudio QTI PAL/ADSP (`oneos-logs` pós-sync)

### Causa Raiz Confirmada em `/data/data/com.termux/files/home/agy/oneos-logs/` e no Bytecode
1. Até `18:49:58.959` (`boot.log.03`), a reprodução e o `SoundAlive` operavam sem erros de HAL. No instante `18:49:58.959`, `com.google.android.googlequicksearchbox` (`SoundTriggerMiddlewareLogging: ATTACH [client= "com.google.android.googlequicksearchbox1trusted"]`) carregou `HOTWORD` (`va-mic-mono-lpi`) via `PAL: StreamSoundTrigger: LoadSoundModel`, derrubando `msm/adsp/audio_pd` (`ar_osal_pd_notifier_cb state(0)`) `109 ms` depois e quebrando todos os streams `StreamPCM` (`pcm_write failed`, `pcm_start failed -102`, `pal_stream_open failed with status -5`), enquanto o `PAL` (`vendor.audio-hal`, PID `30262`) entrava em loop automático `StSSR -> LoadSoundModel -> crash` a cada `500 ms` até depois do retorno à One UI (`18:53:35`).
2. **Falhas encontradas na mitigação anterior do `guest_start.sh` (`e494c70`):**
   - Em `hotword_off()` (`guest_start.sh`), `logcat -s "PAL: SoundTriggerEngineGsl:E" "PAL: StreamSoundTrigger:I" "PAL: SndMonitor:I"` usava nomes com espaços em vez da tag real **`PAL`**, fazendo o `logcat` descartar todas as linhas `PAL` e impedir a detecção do loop QTI.
   - Em `audio_keeper`, a condição `[ ! -f /data/oneos_hotword_off.log ] && [ ! -f /data/oneos_hotword_preblocked ]` desabilitava totalmente o monitoramento reativo assim que o pré-bloqueio criava o arquivo marcador.
   - A inspeção em bytecode de `SoundTriggerMiddlewarePermission.enforcePermissionForPreflight()` em `services_patched.jar` demonstrou que `PERMISSION_SOFT_DENIED (1)` (retornado por `MODE_IGNORED` / `appops set ... ignore`) ainda é **aceito** pelo middleware (`if-eq p0, v0, :cond_1 -> return-void`), exigindo `MODE_ERRORED` (**`deny`**) para gerar `PERMISSION_HARD_DENIED (2)`.

### Alterações Aplicadas
- [guest_start.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_start.sh):
  - Pré-bloqueio `apply_hw_preblock()` passa a aplicar `cmd appops set "$hw" RECORD_AUDIO_HOTWORD deny` (com fallback `ignore`), registra resultado detalhado em `/data/oneos_hotword_preblock.log` e reaplica após `20 s`.
  - Em `hotword_off()` / `audio_keeper`: corrigido filtro do `logcat` para `-s "PAL:I" "COSR:I" "SoundTriggerMiddlewareLogging:I" "*:S"`, incluída detecção de `SessionAlsaPcm:.*pcm_(start|write) failed` com `state(0)`/`card status 0`, removido o bloqueio pelo arquivo `/data/oneos_hotword_preblocked` (substituído por cooldown de `20 s` e limite de `5` intervenções por sessão), extraído dinamicamente o cliente anexado em `SoundTriggerMiddlewareLogging: ATTACH` e mantido reinício coordenado de `vendor.audio-hal*` + `audioserver`.
- [test-qti-hotword-guard.py](file:///data/data/com.termux/files/home/agy/OneOS/scripts/test-qti-hotword-guard.py): adicionada suíte unitária validando ausência de apóstrofos em `audio_keeper`, filtro `"PAL:I"`, `RECORD_AUDIO_HOTWORD deny`, parsing dinâmico do cliente em log real e cooldown.
