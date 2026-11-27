#!/usr/bin/env python3
"""Host lock & QBT2608 fingerprint isolation guard tests for switcher scripts."""

from pathlib import Path
import unittest

REPO = Path(__file__).resolve().parent.parent
SWITCH_SH = REPO / "switcher" / "assets" / "oneos_switch.sh"
RETURN_SH = REPO / "switcher" / "assets" / "return_to_oneui.sh"
PREPARE_SH = REPO / "switcher" / "assets" / "guest_prepare.sh"
GUARDS_TXT = REPO / "packages" / "oneui95-a16" / "guards.txt"


class HostLockGuardTests(unittest.TestCase):
    def test_oneos_switch_enforces_lock_and_biometric_isolation_guards(self) -> None:
        text = SWITCH_SH.read_text(encoding="utf-8")
        self.assertIn("HOST_LOCK_ACTIVE=0", text)
        self.assertIn("/data/system_de/0/spblob/*", text)
        self.assertIn("for t in gatekeeper weaver biometric; do", text)
        self.assertIn("grep -q '^# biometric-remove guard' \"$STAGE/guards.txt\"", text)
        self.assertNotIn("locksettings clear", text)
        self.assertNotIn("fp_remove_all", text)

    def test_host_state_snapshots_lock_and_biometrics_on_switch_and_return(self) -> None:
        for path in (SWITCH_SH, RETURN_SH):
            text = path.read_text(encoding="utf-8")
            for p in (
                "/data/system_de/0/spblob",
                "/data/vendor_de/0/fpdata",
                "/data/system/locksettings.db",
                "/data/system/gatekeeper.*.key",
                "/data/system/users/0/settings_fingerprint*.xml",
            ):
                self.assertIn(p, text, f"{path.name} missing host_state path {p}")

    def test_guest_prepare_never_clears_host_lock_or_leaks_secrets(self) -> None:
        text = PREPARE_SH.read_text(encoding="utf-8")
        self.assertIn("One UI host locksettings/spblob untouched", text)
        self.assertNotIn("locksettings clear", text)

    def test_oneui95_a16_guards_declare_all_lock_isolation_tokens(self) -> None:
        text = GUARDS_TXT.read_text(encoding="utf-8")
        for token in ("gatekeeper", "weaver", "biometric"):
            self.assertRegex(text, rf"(?m)^#.*\b{token}\b")
        self.assertIn("# biometric-remove guard", text)


if __name__ == "__main__":
    unittest.main()
