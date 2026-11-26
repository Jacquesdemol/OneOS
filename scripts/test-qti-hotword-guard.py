#!/usr/bin/env python3
"""Verify QTI SoundTrigger/ADSP hotword guard and audio_keeper logcat matching."""

from pathlib import Path
import os
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
START = (ROOT / "switcher/assets/guest_start.sh").read_text(encoding="utf-8")

AUDIO_KEEPER_ANCHOR = "    $BB sh -c 'as=$1"
AUDIO_KEEPER_END = "' sh \"$AS_PID\" </dev/null >> /data/audio_keeper.log 2>&1 &"
HOTWORD_ANCHOR = "        block_hw() {"
HOTWORD_END = "            return 0\n        }"


class QtiHotwordGuardTest(unittest.TestCase):
    def setUp(self):
        self.assertEqual(1, START.count(AUDIO_KEEPER_ANCHOR))
        ak_body = START.split(AUDIO_KEEPER_ANCHOR, 1)[1].split(AUDIO_KEEPER_END, 1)[0]
        self.assertNotIn("'", ak_body, "audio_keeper is inside single-quoted sh -c")
        self.assertIn('-s "PAL:I"', ak_body)
        self.assertNotIn('"PAL: SoundTriggerEngineGsl:E"', ak_body)
        self.assertNotIn('[ ! -f /data/oneos_hotword_preblocked ]', ak_body)
        self.assertIn("apply_hw_preblock()", START)
        self.assertIn("RECORD_AUDIO_HOTWORD deny", START)

        block = HOTWORD_ANCHOR + START.split(HOTWORD_ANCHOR, 1)[1].split(HOTWORD_END, 1)[0] + HOTWORD_END
        self.tmp = tempfile.TemporaryDirectory()
        self.d = Path(self.tmp.name)
        self.fix = self.d / "fix"
        self.fix.mkdir()
        self.genv = self.d / "oneos_genv.sh"
        self.genv.write_text('#!/bin/sh\nexec "$@"\n', encoding="utf-8")
        self.genv.chmod(0o755)
        self.off_log = self.d / "oneos_hotword_off.log"

        self.block = (
            block.replace("/data/oneos_genv.sh", str(self.genv))
            .replace("/data/oneos_hotword_off.log", str(self.off_log))
            .replace("/system/bin/cmd", str(self.fix / "cmd"))
            .replace("/system/bin/am", str(self.fix / "am"))
            .replace("/system/bin/sh", "sh")
        )

        for name, body in (
            ("cmd", '#!/bin/sh\nprintf "%s\\n" "$*" >> "$FIX/cmd.log"\n'),
            ("am", '#!/bin/sh\nprintf "%s\\n" "$*" >> "$FIX/am.log"\n'),
            ("logcat", '#!/bin/sh\ncat "$FIX/logcat.txt"\n'),
        ):
            p = self.fix / name
            p.write_text(body, encoding="utf-8")
            p.chmod(0o755)

    def tearDown(self):
        self.tmp.cleanup()

    def _run_hotword(self, log_text, twice=False):
        (self.fix / "logcat.txt").write_text(log_text, encoding="utf-8")
        env = dict(os.environ)
        env["PATH"] = f"{self.fix}{os.pathsep}{env.get('PATH', '')}"
        env["FIX"] = str(self.fix)
        extra_call = "\nhotword_off qti; echo second=$?" if twice else ""
        script = self.block + "\nhotword_off qti; echo first=$?" + extra_call
        return subprocess.run(["sh", "-c", script], env=env, capture_output=True, text=True, check=True)

    def test_qti_adsp_ssr_triggers_deny_and_parses_client(self):
        sample = "\n".join([
            '11-25 18:49:56.736 13723 21828 I SoundTriggerMiddlewareLogging: ATTACH [client= "com.google.android.googlequicksearchbox1trusted"] (0, cb, true)',
            '11-25 18:49:57.100 13723 21828 I SoundTriggerMiddlewareLogging: ATTACH [client= "com.example.bixby.wake0"] (0, cb, false)',
            '11-25 18:49:58.959 30262  4780 I PAL: StreamSoundTrigger: LoadSoundModel: 1335: Module type:101 name:HOTWORD',
            '11-25 18:49:59.094  1901  7825 I COSR    : ar_osal_pd_notifier_cb:101 ar_osal_pd_notifier_cb state(0) domain(msm/adsp/audio_pd)',
        ]) + "\n"
        out = self._run_hotword(sample, twice=True)
        self.assertIn("first=0", out.stdout)
        self.assertIn("second=1", out.stdout)
        cmds = (self.fix / "cmd.log").read_text(encoding="utf-8").splitlines()
        ams = (self.fix / "am.log").read_text(encoding="utf-8").splitlines()
        self.assertIn("appops set com.google.android.googlequicksearchbox RECORD_AUDIO_HOTWORD deny", cmds)
        self.assertIn("appops set com.example.bixby.wake RECORD_AUDIO_HOTWORD deny", cmds)
        self.assertIn("force-stop com.example.bixby.wake", ams)
        self.assertTrue(self.off_log.is_file())

    def test_benign_pal_logs_do_not_trigger(self):
        sample = "\n".join([
            '11-25 18:48:30.000 30262 4511 I PAL: StreamPCM: start: 100: Exit status 0',
            '11-25 18:48:31.000 1901 20334 I COSR    : ar_osal_pd_notifier_cb:101 ar_osal_pd_notifier_cb state(1) domain(msm/adsp/audio_pd)',
        ]) + "\n"
        out = self._run_hotword(sample)
        self.assertIn("first=1", out.stdout)
        self.assertFalse((self.fix / "cmd.log").exists())


if __name__ == "__main__":
    unittest.main()
