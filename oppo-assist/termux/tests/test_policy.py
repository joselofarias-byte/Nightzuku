import os
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from jolufa_client import assert_safe, build, load_config  # noqa: E402
from jolufa_policy import (  # noqa: E402
    PolicyError,
    check_host,
    confidently_unlocked,
    is_tailscale_literal,
    uninstall_blocked,
)


def cfg():
    os.environ["JOLUFA_HOST"] = "100.64.8.8"
    os.environ["JOLUFA_ADB_PORT"] = "4242"
    os.environ["JOLUFA_AGENT_PORT"] = "8765"
    os.environ["JOLUFA_TOKEN"] = "d" * 64
    os.environ["JOLUFA_CONFIG"] = "/no/such/jolufa-config"
    return load_config()


class PolicyTests(unittest.TestCase):
    def test_host_cases(self):
        cases = (ROOT.parent / "shared" / "host-cases.list").read_text(encoding="utf-8")
        for line in cases.splitlines():
            text = line.split("#", 1)[0].strip()
            if not text:
                continue
            host, expect = text.split()
            self.assertEqual(is_tailscale_literal(host), expect == "allow", host)

    def test_public_host_rejected(self):
        with self.assertRaises(PolicyError):
            check_host("8.8.8.8")
        with self.assertRaises(PolicyError):
            check_host("192.168.1.20")

    def test_name_must_resolve_only_to_tailscale(self):
        self.assertEqual(check_host("oppo.tailnet.ts.net", lambda _name: ["100.64.1.9"]), "100.64.1.9")
        with self.assertRaises(PolicyError):
            check_host("oppo.tailnet.ts.net", lambda _name: ["100.64.1.9", "8.8.8.8"])

    def test_keyguard_fail_closed(self):
        fixtures = Path(__file__).resolve().parent / "fixtures"
        self.assertTrue(confidently_unlocked((fixtures / "unlocked.dump").read_text()))
        self.assertFalse(confidently_unlocked((fixtures / "locked.dump").read_text()))
        self.assertFalse(confidently_unlocked((fixtures / "unknown.dump").read_text()))

    def test_builders_stay_on_tailscale_and_skip_bypass(self):
        config = cfg()
        samples = [
            build("connect", [], config),
            build("tap", ["10", "20"], config),
            build("scrcpy", [], config),
            build("reboot", [], config, confirmed=True),
            build("uninstall", ["com.example.juego"], config, confirmed=True),
            build("wifi-adb-off", [], config),
            build("set-date", ["2026-11-02", "18:30"], config),
        ]
        for cmd in samples:
            assert_safe(cmd)
            blob = " ".join(cmd)
            self.assertNotIn("8.8.8.8", blob)
            self.assertIn("100.64.8.8", blob)

    def test_refuses_confirmation_and_protected_packages(self):
        config = cfg()
        with self.assertRaises(PolicyError):
            build("reboot", [], config, confirmed=False)
        with self.assertRaises(PolicyError):
            build("uninstall", ["com.android.settings"], config, confirmed=True)
        with self.assertRaises(PolicyError):
            build("uninstall", ["com.joselofarias.oppoassist"], config, confirmed=True)
        with self.assertRaises(PolicyError):
            build("text", ["hola;rm"], config)

    def test_shell_script_does_not_name_bypass_tools(self):
        text = (ROOT / "bin" / "jolufa").read_text(encoding="utf-8").lower()
        for word in ("locksettings", "tcpip", "0.0.0.0"):
            self.assertNotIn(word, text)


if __name__ == "__main__":
    unittest.main()
