"""Static integration checks for policies with Android-only entry points."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/example/webedge"

class PolicyTest(unittest.TestCase):
    def test_ssl_fails_closed(self):
        client = (SRC / "BrowserWebViewClient.kt").read_text()
        body = client.split("override fun onReceivedSslError", 1)[1].split("override fun", 1)[0]
        self.assertIn("handler.cancel()", body)
        self.assertNotIn("proceed(", body)
        self.assertNotIn("confirmSsl", body)

    def test_local_resource_access(self):
        settings = (SRC / "WebUtils.kt").read_text()
        for setting in ["allowContentAccess", "allowFileAccess"]:
            self.assertIn(setting + " = false", settings)

    def test_profile_lifecycle(self):
        profile = (SRC / "PrivateProfileManager.kt").read_text()
        for guard in ["it !in liveProfiles", "liveProfiles.add(it)", "liveProfiles.remove(name)"]:
            self.assertIn(guard, profile)

    def test_array_parse_failure_blocks_writes(self):
        store = (SRC / "Store.kt").read_text()
        self.assertEqual(store.count("ProtectedRecords.readArray(sp,"), 3)
        records = (SRC / "ProtectedRecords.kt").read_text()
        body = records.split("fun readArray(", 1)[1].split("fun readable", 1)[0]
        self.assertIn("markFailure(e)", body)

if __name__ == "__main__":
    unittest.main()
