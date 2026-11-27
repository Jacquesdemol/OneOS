#!/usr/bin/env python3
"""
guest_no_sw_hotword: stop background software hotword microphone capture
(SoftwareTrustedHotwordDetectorSession / GsaHotwordDetectionService:hotword_detector_0)
when OneOS has enabled debug.soundtrigger_middleware.use_mock_hal.

When OneOS disables ADSP SoundTrigger (mock HAL = true) to prevent QTI audio_pd / VTS
crashes, Google App falls back to SoftwareHotwordDetector -> VoiceInteractionManagerService
startListeningFromMic(), spawning isolated hotword_detector_0 (u0ai9000) and opening
AUDIO_SOURCE_HOTWORD (1999) 24/7 (~18% CPU + continuous AudioIn wakelock blocking Deep Sleep),
even when RECORD_AUDIO_HOTWORD appop is denied.

Guarding startListeningFromMic() when debug.soundtrigger_middleware.use_mock_hal is true
prevents software always-on hotword capture without affecting user-initiated mic input
(SpeechRecognizer, keyboard dictation, Assistant button, Gemini voice).

Usage: guest_no_sw_hotword.py <apktool -r dir>
"""
from pathlib import Path
import re
import sys

TARGET_SUFFIX = "/com/android/server/voiceinteraction/VoiceInteractionManagerService$VoiceInteractionManagerServiceStub.smali"
MARK = "    # OneOS: block software hotword mic listening when mock STHAL is active (guest_no_sw_hotword.py)"
METHOD_RE = re.compile(
    r"^\.method public final startListeningFromMic\(Landroid/media/AudioFormat;"
    r"Landroid/service/voice/IMicrophoneHotwordDetectionVoiceInteractionCallback;\)V\s*$"
)


def patch_vims_stub(path: Path) -> bool:
    text = path.read_text(encoding="utf-8", errors="surrogateescape")
    if MARK in text:
        return False

    lines = text.splitlines(keepends=True)
    changed = False
    i = 0
    while i < len(lines):
        if METHOD_RE.match(lines[i].strip()):
            j = i + 1
            while j < len(lines) and not lines[j].startswith(".end method"):
                if lines[j].strip().startswith(".locals "):
                    cnt = int(lines[j].strip().split()[1])
                    if cnt < 2:
                        lines[j] = "    .locals 2\n"
                    guard = (
                        f"{MARK}\n"
                        '    const-string/jumbo v0, "debug.soundtrigger_middleware.use_mock_hal"\n\n'
                        "    const/4 v1, 0x0\n\n"
                        "    invoke-static {v0, v1}, Landroid/os/SystemProperties;->getBoolean(Ljava/lang/String;Z)Z\n\n"
                        "    move-result v0\n\n"
                        "    if-eqz v0, :cond_oneos_allow_sw_hw\n\n"
                        '    const-string/jumbo v0, "VoiceInteractionManager"\n\n'
                        '    const-string v1, "OneOS: software startListeningFromMic skipped (use_mock_hal active)"\n\n'
                        "    invoke-static {v0, v1}, Landroid/util/Slog;->i(Ljava/lang/String;Ljava/lang/String;)I\n\n"
                        "    return-void\n\n"
                        "    :cond_oneos_allow_sw_hw\n"
                    )
                    lines.insert(j + 1, guard)
                    changed = True
                    break
                j += 1
            break
        i += 1

    if not changed:
        return False
    path.write_text("".join(lines), encoding="utf-8", errors="surrogateescape")
    return True


def main() -> int:
    if len(sys.argv) != 2:
        sys.exit("usage: guest_no_sw_hotword.py <apktool -r dir>")
    root = Path(sys.argv[1])
    if not root.is_dir():
        sys.exit(f"guest_no_sw_hotword: {root} is not a directory")

    changed_dirs = []
    for smali in sorted(root.rglob("VoiceInteractionManagerService$VoiceInteractionManagerServiceStub.smali")):
        rel = smali.relative_to(root)
        if rel.as_posix().endswith(TARGET_SUFFIX):
            if patch_vims_stub(smali):
                top = rel.parts[0]
                if top not in changed_dirs:
                    changed_dirs.append(top)

    if changed_dirs:
        print(" ".join(changed_dirs))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
