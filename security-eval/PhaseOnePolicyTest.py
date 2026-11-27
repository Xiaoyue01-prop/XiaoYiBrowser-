"""Static integration contracts for Android callbacks, binder visibility and native HTTP clients."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

MAIN = Path(__file__).resolve().parents[1] / "app/src/main"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def kotlin(name):
    return (MAIN / "java/com/example/webedge" / (name + ".kt")).read_text()


class PhaseOnePolicyTest(unittest.TestCase):
    def test_external_dispatch(self):
        method = kotlin("WebUtils").split("fun Activity.openExternalUrl(", 1)[1].split("class SummitWebView", 1)[0]
        guard = re.search(r"if \(!hasGesture\)\s*\{\s*toast\(.+?\)\s*return true\s*}", method)
        self.assertIsNotNone(guard)
        self.assertLess(method.index("if (scheme in INTERNAL_URL_SCHEMES) return false"), guard.start())
        self.assertLess(guard.end(), method.index("Intent.parseUri"))
        self.assertLess(guard.end(), method.index("startActivity("))

    def test_main_navigation(self):
        client = kotlin("BrowserWebViewClient").split("override fun shouldOverrideUrlLoading", 1)[1]
        self.assertRegex(client, r"handleSpecialUrl\([^\n]*request\.isForMainFrame && request\.hasGesture\(\)")
        self.assertLess(client.index("host.handleSpecialUrl"), client.index("HttpsFirst.upgrade"))
        self.assertIn("host.handleSpecialUrl(url, view, false)", client)
        self.assertIn("openExternalUrl(url, view, hasGesture, ::toast)", kotlin("MainActivity"))

    def test_popup_navigation(self):
        client = kotlin("PopupWindowController").split("override fun shouldOverrideUrlLoading", 1)[1]
        self.assertIn("openExternal(request.url.toString(), view, request.isForMainFrame && request.hasGesture())", client)
        self.assertIn("openExternal(url, view, false)", client)

    def test_overlay_navigation(self):
        client = kotlin("OverlayBrowserActivity").split("override fun shouldOverrideUrlLoading", 1)[1]
        self.assertRegex(client, r"openExternalUrl\([^\n]*request\.isForMainFrame && request\.hasGesture\(\)")
        self.assertIn("openExternalUrl(url, v, false", client)

    def test_popup_creation(self):
        body = kotlin("PopupWindowController").split("fun create(resultMsg:", 1)[1]
        self.assertRegex(body.split("if (webView != null)", 1)[0], r"if \(!isUserGesture\) return false")
        self.assertIn("sourceView !== webView", body)
        self.assertIn("configureWebViewForSource(popup, source)", body)
        self.assertIn("host.popup.create(resultMsg, view, isUserGesture)", kotlin("BrowserChromeClient"))

    def test_media_service(self):
        manifest = ET.parse(MAIN / "AndroidManifest.xml")
        service = next(s for s in manifest.findall(".//service") if s.get(ANDROID + "name") == ".WebMediaService")
        self.assertEqual(service.get(ANDROID + "exported"), "false")
        method = kotlin("WebMedia").split("override fun onGetSession", 1)[1].split("override fun onTaskRemoved", 1)[0]
        self.assertIn("controllerInfo.uid == applicationInfo.uid || controllerInfo.isTrusted", method)
        self.assertIn("else null", method)

    def test_authenticated_network_clients(self):
        for name in ("DriveSyncClient", "FootballApi", "TranslateApi"):
            with self.subTest(client=name):
                source = kotlin(name)
                self.assertNotIn('"http://', source)
                self.assertIn('"https://', source)
                self.assertIn("openConnection() as HttpsURLConnection", source)
                self.assertLess(source.index("instanceFollowRedirects = false"), source.index("setRequestProperty("))

    def test_download_transport(self):
        source = kotlin("Downloads")
        enqueue = source.split("fun enqueue(request:", 1)[1]
        guard = re.search(r'if \(uri\.scheme != "https" \|\| uri\.host\.isNullOrBlank\(\)\)\s*\{\s*toast\(.+?\)\s*return\s*}', enqueue)
        self.assertIsNotNone(guard)
        self.assertLess(guard.end(), enqueue.index("DownloadManager.Request(uri)"))
        self.assertNotIn('scheme != "http" && scheme != "https"', source.split("val pageUrl =", 1)[0])
        self.assertEqual(ET.parse(MAIN / "AndroidManifest.xml").find("application").get(ANDROID + "usesCleartextTraffic"), "true")
        self.assertIn("WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE", kotlin("WebUtils"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
