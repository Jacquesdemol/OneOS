# Estudo Técnico — Controle Térmico Nativo da One UI (`sdhms` / SSRM / SIOP) vs. Sistema Guest (`oneui95-s948b`)

**Referência:** Fase 5 de [estudo.md](file:///data/data/com.termux/files/home/agy/estudo.md#L64-L75)  
**Dispositivo / Sessão analisada:** Galaxy S22 Ultra (`SM-S908E`, perfil `s-ultra-8450`, Snapdragon 8 Gen 1 `SM8450`) · Host One UI 8 (SDK 36) · Guest `oneui95-s948b` (One UI 9.5, SDK 37, dump do S26 Ultra) · Sessão [ed6709971aafa046](file:///data/data/com.termux/files/home/agy/oneos-logs-ed6709971aafa046/summary.txt).  
**Escopo:** Somente pesquisa e documentação arquitetural (nenhuma alteração de código SSRM/SDHMS nativo aplicada nesta fase).

---

## 1. Como o sistema térmico da One UI host gerencia temperatura, throttling de CPU/GPU e brilho (`SM-S908E`)

No host `SM-S908E`, a proteção térmica e de energia funciona em **três camadas coordenadas** observadas diretamente no aparelho e nos logs da sessão (`last_kmsg.txt`, `diag_*_thermal.txt`, `takeover_app.log` e sysfs):

### 1.1 Camada Kernel & Drivers Samsung/Qualcomm
- **Zonas térmicas e Cooling Devices (`/sys/class/thermal/`):** o kernel `SM8450` expõe zonas térmicas específicas (`cpuss-0`..`3`, `gpuss-0`/`1`, `ddr`, `xo-therm`, `battery`, `pm8350b-ibat-lvl0`/`1`, além de zonas de modem/PA) ligadas a 44 dispositivos de resfriamento (`cooling_device0`..`43`) e mecanismo kernel `thermal_sys` / BCL (`bcl-warn`). Em `last_kmsg.txt:905`, o kernel atua diretamente sobre resfriamento de RF/modem (`thermal_sys: thermal: cdev[nr_scg_sub1_dsc:10]...`).
- **Driver de teto de CPU multi-votante (`/sys/devices/system/cpu/cpufreq_limit/`):**
  - Tabela observada em `/sys/devices/system/cpu/cpufreq_limit/info`:
    - `silver` (`policy0`): `307200 ~ 1785600` kHz
    - `gold` (`policy4`): `633600 ~ 2496000` kHz
    - `prime` (`policy7`): `806400 ~ 2995200` kHz
    - Slots internos `requested: [0]..[3]` com permissão `radio:system` (`0664`) em `cpufreq_max_limit`, permitindo que o framework Samsung vote um teto global sem sobrescrever diretamente os limites de hardware `cpuinfo_max_freq` (`requested[0] max(1843200)` observado antes do switch em `takeover_app.log:61,291`).
- **Driver de bateria e carregamento (`sec-battery` / `max77705`):**
  - `sec_bat_monitor_work` lê múltiplos termistores em décimos de °C (`last_kmsg.txt:466`: `Tbat(419)` = `41.9 °C`, `Tusb(386)`, `Tchg(540)` = `54.0 °C`, `Twpc(419)`, `Tlrp(446)`, `Tdchg(517)` = `51.7 °C`).
  - A corrente de carga é modulada dinamicamente por **SIOP** via `/sys/class/power_supply/battery/siop_level` (propriedade `system:radio`, observado em `80` tanto no host sysfs quanto em `last_kmsg.txt:488`), além de `siop_activated` e `siop_event`.
- **GPU Adreno (`KGSL`):** controlada via `/sys/class/kgsl/kgsl-3d0/max_gpuclk` e `devfreq/max_freq`.

### 1.2 Camada Vendor (`thermal-engine-v2` e Thermal HAL)
- **`/vendor/bin/thermal-engine-v2`:** daemon usuário-espaço Qualcomm (no `SM8450`, `/vendor/etc/thermal-engine.conf` possui apenas comentário padrão; as tabelas vêm compiladas/em perfis vendor). Atua em mitigação térmica de baixo nível e fechamento de zonas críticas.
- **`/vendor/bin/hw/vendor.samsung.hardware.thermal@1.0-service`:** registrado no manifesto VINTF `/vendor/etc/vintf/manifest/vendor.samsung.hardware.thermal@1.0-manifest.xml` provendo:
  - `android.hardware.thermal@1.0::IThermal/default` e `@2.0::IThermal/default` (**ThermalHAL 2.0**)
  - `vendor.samsung.hardware.thermal@1.0::ISehThermal/default`
  - Publica leituras como `AP`, `BAT`, `USB`, `SKIN`, `PATHM` e os respectivos níveis `mStatus` (`0..6`).

### 1.3 Camada Framework & Políticas (`SSRM`, `CFMS` e `SDHMS`)
- **`/system/framework/ssrm.jar` (`208.802` bytes, no `SYSTEMSERVERCLASSPATH`):** coleta contexto de cenário (tela ligada/desligada, aplicativo em primeiro plano, modo câmera/jogo, carregamento) e coordena políticas de energia/cenário com o `system_server` (`ActivityManagerService`, `DssController` com `mGameSiopLevel`).
- **`CustomFrequencyManagerService` (`CFMS`) + `/system/lib64/libnativecfms.so`:** registrados no `system_server` do host; processos nativos (como `cameraserver`/`libgui`) e apps do sistema solicitam boosts/restrições de frequência de CPU/GPU/barramento.
- **`SamsungDeviceHealthManagerService` (`com.sec.android.sdhms`, `/system/priv-app/SamsungDeviceHealthManagerService/SamsungDeviceHealthManagerService.apk`):** executa o motor **SIOP** (*Samsung Intelligent Overheat Protection*) específico do `SM-S908E`:
  1. **Throttling de CPU/GPU:** vota tetos em `/sys/devices/system/cpu/cpufreq_limit/cpufreq_max_limit` (ex.: `1843200` kHz observado em `takeover_app.log:61` mesmo aos `37.5 °C`–`38.3 °C` de pele) e ajusta níveis de GPU conforme temperatura + cenário.
  2. **Controle de Carregamento:** escreve em `/sys/class/power_supply/battery/siop_level` (observado `siop_level=80` com `lcd(1)` e cabo `PDIC_APDO` em `last_kmsg.txt:487-488`).
  3. **Limitação de Brilho e Painel:** reduz dinamicamente o teto superior de brilho (`PowerManager` `mAutoBrightnessUpperLimit`, `DisplaySolution`, `/sys/class/backlight/panel0-backlight`, máx. hardware `612` em `diag_40_backlight.txt` / `diag_90_backlight.txt`) e taxa de atualização (VRR/HFR) sob carga térmica prolongada.

---

## 2. O que está faltando ou conflitando no ambiente guest (`oneui95-s948b` no `SM-S908E`)

1. **Encerramento completo dos serviços térmicos do host na troca de OS:**
   - Ao parar o framework da One UI para iniciar o container, `last_kmsg.txt` comprova a morte simultânea dos serviços binder `'sdhms'` (`line 533`), `'thermalservice'` (`line 598`) e `'CustomFrequencyManagerService'` (`line 691`), além do envio de `SIGTERM`/`SIGKILL` ao `thermal-engine` (`lines 138-139`) durante o teardown de serviços init.
   - Sem o `sdhms` do host em execução, nenhum componente Samsung lê continuamente `SKIN`/`Tdchg`/`BAT` para modular `cpufreq_max_limit`, `siop_level` ou `mAutoBrightnessUpperLimit`.
   - Adicionalmente, o voto residual deixado em `/sys/devices/system/cpu/cpufreq_limit/cpufreq_max_limit` (`1843200` kHz, `requested[0]`) persistiria no kernel mesmo após o `system_server` da One UI morrer — exigindo que `heat_cap` (`oneos_switch.sh:4674`) libere `-1` manualmente na inicialização quando `HOST_LOW_POWER=0`.

2. **Incompatibilidade de tabelas SIOP entre S26 Ultra (`oneui95-s948b`) e S22 Ultra (`SM-S908E`):**
   - A imagem guest provém de um firmware `S948B` (S26 Ultra, geração mais nova com plataforma/SoC/tabelas distintas).
   - Conforme documentado em [guest_prepare.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/guest_prepare.sh#L8220-L8222), o `SamsungDeviceHealthManagerService` do guest carrega tabelas SIOP compiladas para o hardware do S26 Ultra; ao tentar avaliá-las em outro aparelho, `SiopExpression` lê arrays nulos e gera dezenas de crashes em loop (`95 crashes`), forçando o `guest_prepare.sh` a ocultar `/system/priv-app/SamsungDeviceHealthManagerService` com `tmpfs` somente leitura.

3. **Stub no-op de `CFMS` e isolamento de `ssrm.jar`:**
   - No guest, `CustomFrequencyManagerService` é atendido por [OneOSCfmsStub.java](file:///data/data/com.termux/files/home/agy/OneOS/switcher/patches/OneOSCfmsStub.java) (responde `int32 0, 0` a todas as transações para evitar bloqueios de 5 s em `libgui`/`cameraserver`). Nenhuma requisição `CFMS` ou `SemDvfsManager` altera clocks reais ou dispara políticas de cenário térmicas.
   - O `/system/framework/ssrm.jar` presente no `SYSTEMSERVERCLASSPATH` do guest roda sem `com.sec.android.sdhms` funcional e sem os nós/permissões SELinux compatíveis com a plataforma alvo.

4. **Divergência entre os limiares do `ThermalHAL 2.0` do host e os consumidores AOSP/One UI 9.5:**
   - O `ThermalHAL 2.0` compartilhado do `SM-S908E` já reporta `SKIN = 44.3 °C -> mStatus = 3 (SEVERE)` (`diag_40_thermal.txt:17,26` e `diag_90_thermal.txt:17,26`), muito abaixo dos ~`47–50 °C` típicos de throttling severo em outros builds.
   - Sem ajuste, o guest reage precocemente ao `mStatus=3/4` desativando blur (`BlurController.mDisabledByThermal`), travando `JobScheduler`, reduzindo FPS/VRR para `60 Hz` (`1–2 s` de engasgo ao alternar exibição) e disparando avisos térmicos. Por isso `oneos_switch.sh` aplica `GUEST_THERMAL_MAP` (`cmd thermalservice override-status`, deixando `IsStatusOverride: true` visível em `diag_40_thermal.txt:1`). *(Nota: durante `diag_20_thermal.txt`, o `thermalservice` ainda não havia sido publicado aos ~20 s de boot: `"Can't find service: thermalservice"`).*

5. **Escopo limitado da mitigação atual (`oneos_heat_cap` em `oneos_switch.sh`):**
   - O loop `oneos_heat_cap` em [oneos_switch.sh](file:///data/data/com.termux/files/home/agy/OneOS/switcher/assets/oneos_switch.sh#L4638-L4810) controla apenas `scaling_max_freq` dos clusters CPU (`policy0/4/7`), `kgsl-3d0/devfreq/max_freq` (GPU), limiares de carregamento direto (`direct_charging_chg_temp`) e `thermalservice override-status`.
   - Ele **não** atua dinamicamente sobre `/sys/class/power_supply/battery/siop_level` durante carregamento USB/PD normal quando `charging_type` não está em direct-charging nem aplica teto dinâmico de brilho (`mAutoBrightnessUpperLimit` / `panel0-backlight`) em função da temperatura de pele (`SKIN`) ou bateria (`BAT`).

---

## 3. Passos técnicos viáveis para portar ou adaptar o controle térmico nativo para o guest (Prós, Contras e Riscos)

### Opção A — Controlador Térmico Nativo do OneOS em Espaço de Usuário (Extensão estruturada / Daemon em C++ ou shell compilado por perfil)
*(Recomendada no médio prazo pela portabilidade e baixo risco de ABI)*

1. **Leitura direta de sensores e HAL:** ler `SKIN`, `AP`, `BAT` e `Tdchg` do `ThermalHAL 2.0` (ou diretamente dos sysfs mapeados por `Profiles`: `battery/temp`, `sec-battery`, zonas térmicas validadas no dispositivo).
2. **Atuação multi-atuador calibrada por perfil (`s-ultra-8450`):**
   - **CPU por cluster:** manter curvas assimétricas (`LITTLE` com mais folga para UI fluida; `Gold`/`Prime` com cortes progressivos, já iniciados na Fase 3 via `43/46/49 °C`).
   - **GPU (`KGSL`):** escalonamento via `devfreq/max_freq` respeitando os OPPs reais em `devfreq/available_frequencies`.
   - **Carga (`sec-battery`):** modular `/sys/class/power_supply/battery/siop_level` em degraus seguros (ex.: `100 -> 80 -> 70 -> 50`) quando `BAT >= 40 °C` ou `SKIN >= 46 °C` durante carregamento, restaurando o valor original no `return_to_oneui.sh`.
   - **Brilho máximo térmico:** aplicar teto superior via `DeviceConfig` / `PowerManager` ou clamp suave em `panel0-backlight` apenas acima de `SKIN >= 48–50 °C` (ou delegar quando `ThermalHAL >= 4`).
   - **Feedback para apps (`ThermalService`):** manter mapeamento consistente (`GUEST_THERMAL_MAP`) para não matar `JobScheduler` em `44 °C` nem esconder `CRITICAL`/`SHUTDOWN` reais (`>= 55 °C`).

| Aspecto | Avaliação |
| :--- | :--- |
| **Prós** | 100% independente da versão do Android guest (One UI, Pixel, ColorOS, HyperOS); zero dependência de `sdhms.apk` do S26 Ultra; fácil reversão e restauração determinística no `return_to_oneui.sh`. |
| **Contras** | Não possui inteligência por pacote/cenário do `SSRM` (ex.: distinguir câmera em gravação 4K60, jogo ou compilação `dex2oat` sem regras adicionais no daemon). |
| **Riscos** | Deixar `siop_level` ou `scaling_max_freq` travados após queda abrupta caso falte cleanup em `return_to_oneui.sh` (mitigado por arquivos `.active` + restore no boot/retorno). |

---

### Opção B — Transplante / Adaptação do `sdhms` + `ssrm.jar` do Host (`SM-S908E`) para o Guest One UI 9.5
*(Alta fidelidade teórica na One UI guest, porém alta fragilidade de ABI)*

1. **Preservar e substituir `SamsungDeviceHealthManagerService`:** montar `SamsungDeviceHealthManagerService.apk` do host (ou extrair suas tabelas/XML SIOP do `SM-S908E`) no lugar do APK do S26 Ultra (`guest_prepare.sh`), em vez de ocultá-lo com `tmpfs`.
2. **Conceder contexto de execução e permissões:** replicar UID/SELinux (`system` / `priv_app` + `seinfo`/assinatura de plataforma compatível com a One UI 9.5 guest) e permissões de escrita nos nós `/sys/devices/system/cpu/cpufreq_limit/cpufreq_max_limit` e `/sys/class/power_supply/battery/siop_level`.
3. **Implementar ponte parcial no `CFMS` / `ISehThermal`:** substituir o no-op completo de `OneOSCfmsStub` por encaminhamento seguro de solicitações SIOP ou desabilitar apenas chamadas incompatíveis entre SDK 36 (`One UI 8`) e SDK 37 (`One UI 9.5`).

| Aspecto | Avaliação |
| :--- | :--- |
| **Prós** | Reutiliza curvas térmicas exatas de CPU, GPU, carga (`siop_level`), refresh rate e limite de brilho calibradas pela Samsung para o `SM-S908E`. |
| **Contras** | Quebra fácil entre versões: `ssrm.jar` (`SDK 37`) e `SamsungDeviceHealthManagerService.apk` (`SDK 36`) usam interfaces privadas (`ISehThermal`, `SemDvfsManager`, `CustomFrequencyManager`, assinaturas `platform`) que divergem entre One UI 8 e One UI 9.5; inútil para guests não-Samsung. |
| **Riscos** | Crash-loop silencioso de `sdhms` ou travamento de `system_server` por transações binder incompatíveis; conflito direto com `oneos_heat_cap` escrevendo nos mesmos nós; risco de voto permanente no `cpufreq_max_limit` caso o serviço host falhe no retorno. |

---

### Opção C — Modificação de políticas no `/vendor` (`thermal-engine-v2` / `ThermalHAL`)
- **Desaconselhada:** exigir alterações na partição `/vendor` violaria `dm-verity` / integridade do host e persistiria fora do escopo efêmero do container OneOS, além de não resolver a ausência do `sdhms` para controle de brilho e `siop_level` por cenário.
