"""Static contracts for silent subframe external URL blocking and toast throttling."""
from pathlib import Path
import re
import unittest

MAIN = Path(__file__).resolve().parents[1] / "app/src/main/java/com/example/webedge"


def kotlin(name):
    return (MAIN / (name + ".kt")).read_text()


class PhaseTwoAPolicyTest(unittest.TestCase):
    def assert_subframe_guard(self, name, dispatch):
        body = kotlin(name).split("override fun shouldOverrideUrlLoading", 1)[1]
        body = body.split("@Deprecated", 1)[0]
        guard = re.search(
            r"if \(!request\.isForMainFrame && isExternalUrlScheme\(request\.url\.scheme\)\) return true",
            body,
        )
        self.assertIsNotNone(guard, "External subframes must return before dispatch or toast")
        self.assertLess(guard.end(), body.index(dispatch))
        self.assertIn("request.isForMainFrame && request.hasGesture()", body)

    def test_main_subframe(self):
        self.assert_subframe_guard("BrowserWebViewClient", "host.handleSpecialUrl(")

    def test_popup_subframe(self):
        self.assert_subframe_guard("PopupWindowController", "openExternal(")

    def test_overlay_subframe(self):
        self.assert_subframe_guard("OverlayBrowserActivity", "openExternalUrl(")

    def test_scheme_classification(self):
        source = kotlin("WebUtils")
        self.assertRegex(source, r"fun isExternalUrlScheme\(scheme: String\?\): Boolean\s*=\s*\n?\s*scheme != null && scheme.lowercase\(Locale.ROOT\) !in INTERNAL_URL_SCHEMES")
        self.assertIn('setOf("http", "https", "about", "javascript", "data", "blob", "file")', source)

    def test_external_toast_throttle(self):
        source = kotlin("WebUtils")
        self.assertRegex(source, r"private const val EXTERNAL_URL_TOAST_INTERVAL_MS = 2_000L")
        self.assertIn("private var lastExternalUrlToastAt: Long? = null", source)
        limiter = source.split("private fun showExternalUrlToast(", 1)[1].split("/**", 1)[0]
        self.assertIn("SystemClock.elapsedRealtime()", limiter)
        self.assertRegex(limiter, r"if \(last != null && now - last < EXTERNAL_URL_TOAST_INTERVAL_MS\) return")
        self.assertLess(limiter.index("lastExternalUrlToastAt = now"), limiter.index("showToast(message)"))
        dispatch = source.split("fun Activity.openExternalUrl(", 1)[1].split("class SummitWebView", 1)[0]
        self.assertIn("val toast: (String) -> Unit = { message -> showExternalUrlToast(message, showToast) }", dispatch)
        self.assertNotRegex(dispatch, r"showToast\s*\(")
        self.assertIn('toast("Hãy chạm vào liên kết', dispatch)


if __name__ == "__main__":
    unittest.main(verbosity=2)
