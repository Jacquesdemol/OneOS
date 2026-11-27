# Estudo de Viabilidade Técnica: Play Integrity API no OneOS (One UI 9.5 Guest / Dual-OS)

> **Escopo:** Documento **exclusivamente técnico e de viabilidade** (item 2.4 de [optimize.md](file:///data/data/com.termux/files/home/agy/optimize.md)). **Nenhuma modificação de código, script ou ROM foi implementada para spoofing/bypass de Play Integrity.**

---

## 1. Premissa Crítica Reconsiderada (Bootloader Travado + TEE Hardware Íntegro no Host)

Nos aparelhos Samsung pós-One UI 8 (incluindo este `SM-S908E` em `S908EXXSEGZH4`), **o bootloader não pode ser desbloqueado**. Os logs de boot e o estado real do host confirmam uma premissa fundamentalmente diferente daquela assumida em ROMs customizadas clássicas:

1. **Bootloader genuinamente travado e Verified Boot GREEN no hardware (`boot/last_kmsg.txt`):**
   - `AVB CMD LINE`: `androidboot.vbmeta.device_state=locked`
   - `VB2: Authenticate complete! boot state is: green`
   - `VB2: The device unlocked 0 times`
   - `VB2: boot state: green(0)`
   - `AUTHENTICATE Succeed Vbmeta binary: vbmeta` (`Model Name [SM-S908E_SEA_DX_QKEY2]`, `Version Name [S908EXXSEGZH4]`)
2. **Sistema principal (host One UI 8) já passa em `MEETS_STRONG_INTEGRITY`:**
   - Isso comprova que a **chave de atestação de fábrica no TEE (KeyMint hardware) e o estado de boot verificado são 100% válidos e não estão revogados nem corrompidos**.
   - Portanto, **NÃO falta keybox OEM nem bootloader travado** — e soluções baseadas em *leaked keyboxes* de terceiros (ex.: TrickyStore com keybox vazado) são **desnecessárias e contraproducentes**, pois o próprio aparelho já possui atestação de hardware genuína.

---

## 2. Por que a Play Integrity Ainda Falha / Degrada Dentro do Guest OneOS?

Análise direta de `capture/data_local_tmp_oneos_logs_boot.log*`, `stage/data_local_tmp_oneos_store_watch.log`, `stage/data_local_tmp_oneos_takeover_app.log`, `stage/data_local_tmp_oneos_guest_crash.log`, `guest/data_local_tmp_oneos_data_oneos_sm_diag.log` e do código do `OneOS` (`switcher/assets/guest_props.sh`, `switcher/assets/guest_start.sh`, `switcher/patches/guest_storage_guards.py`):

### 2.1. Falha de RKPD / Geração de Registro de Atestação no `keystore2` / Play Store
- No guest, o `keystore2` (`pid 1202`) é **compartilhado com o host One UI 8** via Binder (inclusive [guest_storage_guards.py](file:///data/data/com.termux/files/home/agy/OneOS/switcher/patches/guest_storage_guards.py) impede que o guest limpe namespaces do `keystore2` compartilhado), e operações normais de chave (`IKeystoreOperation`) funcionam (`boot.log:2047+`).
- Porém, chamadas de atestação/RKPD falham durante a sessão:
  - `boot.log:4856-4870` (`keystore2`):
    - `remote_provisioning.rs:131: Trying to get IRPC name.`
    - `globals.rs:542: Failed to get rpc for sec level r#TRUSTED_ENVIRONMENT`
    - `Error::Km(r#HARDWARE_TYPE_UNAVAILABLE)`
  - `stage/data_local_tmp_oneos_store_watch.log:37-43` (Play Store UID `15301`):
    - `PlayCore: StandardIntegrity : requestExpressIntegrityToken(...)`
    - `PlayCore: IntegrityService : requestIntegrityToken(...)`
    - `E Finsky  : [210] Integrity key attestation record generation failed.`
    - `I Finsky  : [60] requestIntegrityToken() finished for com.android.vending.`
- **Interpretação técnica (hipótese baseada nos logs):** Mesmo com o KeyMint do host funcionando no sistema principal, dentro da sessão OneOS a geração de registro de atestação falha ao tentar obter RPC/RKPD ou ao criar a chave de atestação a partir do contexto do guest (UIDs com offset `+15000` como `15301`, container One UI 9.5 sobre `keystore2` do host One UI 8, `SELinux Permissive` e estado isolado de `/data`).

### 2.2. Sinais de Ambiente Rootado / SELinux Permissive Expostos em Tempo de Execução
- Embora o bootloader esteja `locked` (`green`), durante a sessão OneOS o ambiente de runtime expõe sinais claros de adulteração:
  1. **SELinux em modo Permissive:** [guest_start.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_start.sh#L1156) registra que `oneos_switch.sh` executa `setenforce 0`, confirmado no log `guest/data_local_tmp_oneos_data_oneos_sm_diag.log:2,52`: `enforce=0 getenforce=Permissive deny_unknown=1 reject_unknown=0`.
  2. **KernelSU ativo e contexto privilegiado:** `this shell ctx=u:r:ksu:s0 uid=0` (`sm_diag.log:4`), além de montagens do container (`overlay`/`tmpfs`/bind mounts no chroot).
  3. **Detecção explícita de root pelos serviços Samsung:** `stage/data_local_tmp_oneos_guest_crash.log:8-29` mostra `com.samsung.aasaservice` caindo com `java.lang.SecurityException: Root detected` em `StorageManagerService.validateKeepCaller`, e `boot.log.02:37182` registra checagens em `com.samsung.android.kmxservice` (`SksAttestation.getDeviceIntegrity()`).
  4. **Esclarecimento sobre `hide-gms` nos logs (`takeover_app.log:2849: hide-gms 0`):** no OneOS, a flag `ONEOS_GUEST_HIDE_GMS` ([guest_prepare.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_prepare.sh#L9226-L9254)) **não oculta root/container do GMS** — ela apenas oculta os APKs dos serviços Google via `tmpfs` vazio para rodar o guest *sem* Google.

### 2.3. Dualidade de Identidade (`SM-S948B` One UI 9.5 vs TEE `SM-S908E` / `b0q` Android 16)
- [guest_props.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_props.sh#L219-L222) já evita corretamente sobrescrever propriedades `*_for_attestation` para não conflitar com a verificação do KeyMint no host, e mantém `ro.build.version.sdk` alinhado ao host (`36`, [guest_props.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_props.sh#L1942-L1955)).
- Entretanto, [guest_props.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_props.sh#L2089-L2102) altera `ro.product.*.model` e `ro.boot.em.model` para **`SM-S948B`** (S26 Ultra / One UI 9.5) para compatibilidade com a Galaxy Store, enquanto o bootloader/TEE硬件 atesta **`SM-S908E`** (`b0q`, `S908EXXSEGZH4`).
- Verificações do Play Integrity / DroidGuard que cruzam identidade de sistema/fingerprint/modelo/patch com o certificado X.509 emitido pelo hardware TEE podem rejeitar ou rebaixar o veredito se houver incoerência entre as propriedades visíveis ao GMS e o certificado hardware do `SM-S908E`.

### 2.4. Impacto Térmico / CPU de `gms.unstable`
- No `cpu_top.log`, `com.google.android.gms.unstable` aparece pontualmente durante janelas de verificação (ex.: `45.2%` de 1 núcleo em `23:20:42`), **não** sendo o causador do aquecimento contínuo em idle (que vinha principalmente de `word_detector_0` + `AudioIn` wakelock, `ISehSem/default`, `ISehRealTimeToken/default` e spam de `FeatureUtil`).

---

## 3. Viabilidade Atualizada por Nível da Play Integrity API

Comparativo atualizado considerando que **o dispositivo possui bootloader travado (`locked` / `green(0)`) e o host já atinge `MEETS_STRONG_INTEGRITY`**:

| Nível / Veredito | Exigência do Google | Situação Atual no Guest OneOS | Viabilidade Reavaliada (Sem Keybox de Terceiros) |
| :--- | :--- | :--- | :--- |
| **`MEETS_BASIC_INTEGRITY`** | Pacote GMS intacto, ausência de adulteração/root detectável em runtime (`SELinux`, mounts, `su`/`ksu`). | Bloqueado ou instável quando `DroidGuard` lê `SELinux Permissive` (`enforce=0`), artefatos KernelSU/mounts do container ou falha no registro de atestação. | **Alta** (requer isolamento/ocultação de root, mounts do container e SELinux `Permissive` do processo GMS/DroidGuard, sem precisar de keybox externo). |
| **`MEETS_DEVICE_INTEGRITY`** (Android 13+ Hardware-Backed) | Atestação por hardware válida + estado `GREEN` (ou verificação equivalente confiável) + ausência de sinais de adulteração em runtime. | **Pré-requisito de hardware já existe no host (`locked` + `green`),** mas falha no guest por:<br>1. `E Finsky: Integrity key attestation record generation failed` / `keystore2` `HARDWARE_TYPE_UNAVAILABLE` (RKPD).<br>2. `SELinux Permissive` (`enforce=0`) + KernelSU visíveis na sessão.<br>3. Divergência potencial de props (`SM-S948B` vs TEE `SM-S908E`). | **Média / Condicionada (Diferente de bootloaders desbloqueados):** tecnicamente possível **sem keybox vazado** usando o TEE nativo do host, desde que seja resolvido o erro de RKPD/atestação no `keystore2` para a sessão guest + ocultação de Permissive/KSU/mounts + coerência de modelo para o GMS. |
| **`MEETS_STRONG_INTEGRITY`** | Atestação de hardware OEM intacta (`locked` / `GREEN`, não revogada) + patch de segurança recente + ambiente íntegro (sem Permissive/root visível). | Como o **host já passa `STRONG`**, o hardware TEE do aparelho já satisfaz a cadeia criptográfica exigida. O impedimento é exclusivamente a **camada de runtime e ponte keystore2/RKPD do container guest**. | **Possível em tese via passthrough nativo (sem spoofing de keybox), porém de alta complexidade na prática:** exige manter SELinux **Enforcing** durante o guest (hoje `guest_start.sh` depende de `setenforce 0`), isolar montagens KernelSU/OneOS do GMS, corrigir a chamada de atestação (`Finsky` / `keystore2` `HARDWARE_TYPE_UNAVAILABLE`) e alinhar identidade de dispositivo ao `SM-S908E` para o GMS. |

---

## 4. Conclusões e Recomendações Técnicas (Somente Estudo)

1. **Vantagem estrutural do cenário travado (Pós-One UI 8):** Como o bootloader não foi desbloqueado (`unlock count = 0`) e o host já passa `MEETS_STRONG_INTEGRITY`, o OneOS **não precisa arriscar o sistema usando keyboxes vazados de terceiros**. O caminho legítimo e tecnicamente mais sólido seria investigar o uso direto do TEE/KeyMint do próprio host.
2. **Os 3 pré-requisitos reais para viabilizar `DEVICE` / `STRONG` no guest no futuro:**
   - **(A) Depurar o erro de geração de atestação (`Finsky` -> `keystore2`):** descobrir exatamente qual chamada da Play Store (`StandardIntegrity` / `IntegrityService`) dispara `Failed to get rpc for sec level r#TRUSTED_ENVIRONMENT` (`Error::Km(r#HARDWARE_TYPE_UNAVAILABLE)`) dentro do guest — verificando diferença entre StrongBox/RKPD vs hardware TEE padrão do `SM-S908E`, impacto do offset UID `+15000` (`15301`) e diretórios de keystore/attestation no chroot.
   - **(B) Eliminar a necessidade de `SELinux Permissive` global (`setenforce 0`) ou impedir exposição:** enquanto `/sys/fs/selinux/enforce` retornar `0` (`Permissive`) e `KernelSU`/montagens de container estiverem visíveis no namespace do `com.google.android.gms.unstable`, verificações de integridade reprovarão o runtime independentemente do bootloader estar travado.
   - **(C) Escopo da identidade `SM-S948B` vs `SM-S908E`:** avaliar se alterar `ro.product.*.model` para `SM-S948B` afeta validação cruzada com o certificado TEE `SM-S908E` no GMS (caso positivo, manter `SM-S948B` restrito à Galaxy Store / apps Samsung ou reconciliar propriedades expostas ao `com.google.android.gms`).
3. **Prioridade atual mantida (zero alterações de Play Integrity no código):** o foco desta entrega continua sendo eliminar gargalos térmicos/CPU e erros de boot do One UI 9.5 guest.
