import importlib.util
import io
import sys
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from bs4 import BeautifulSoup

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import upstream


def html(value):
    return BeautifulSoup(value, "html.parser")


class DiscoveryTests(unittest.TestCase):
    listing = html('<h5 class="appRowTitle"><a href="/apk/waze/waze-5-25-0-0-release/">Waze 5.25.0.0</a></h5>')

    def test_prefers_arm64_and_captures_identity(self):
        variants = html('''<div class="table-row">5.25.0.0 BUNDLE 33 S 1030800 arm64-v8a + armeabi-v7a
          <a href="/universal-android-apk-download/">5.25.0.0</a></div>
          <div class="table-row">5.25.0.0 BUNDLE 27 S 1030800 arm64-v8a
          <a href="/arm64-android-apk-download/">5.25.0.0</a></div>''')
        with patch.object(upstream, "page", side_effect=[self.listing, variants]):
            result = upstream.discover(None)
        self.assertEqual(result["tag"], "waze-5.25.0.0-1030800-patches-1.11")
        self.assertTrue(result["variant_url"].endswith('/arm64-android-apk-download/'))
        self.assertEqual(result["extension"], ".apkm")

    def test_never_falls_back_when_latest_has_no_arm64(self):
        with patch.object(upstream, "page", side_effect=[self.listing, html('<div>armeabi-v7a</div>')]):
            with self.assertRaisesRegex(ValueError, "no ARM64"):
                upstream.discover(None)

    def test_missing_release_is_not_an_api_error(self):
        with patch.dict(upstream.os.environ, GH_TOKEN="test"), patch.object(upstream, "urlopen", side_effect=HTTPError('',404,'',{},None)):
            self.assertFalse(upstream.release_exists("owner/repo", "tag"))

    def test_auth_and_rate_limit_errors_do_not_trigger_builds(self):
        for status in [401, 403, 429, 500]:
            with patch.dict(upstream.os.environ, GH_TOKEN="test"), patch.object(upstream, "urlopen", side_effect=HTTPError('',status,'',{},None)):
                with self.assertRaises(HTTPError):
                    upstream.release_exists("owner/repo", "tag")

    def test_published_release_skips_and_draft_retries(self):
        for payload, expected in [(b'{"draft": false}', True), (b'{"draft": true}', False)]:
            with patch.dict(upstream.os.environ, GH_TOKEN="test"), patch.object(upstream, "urlopen", return_value=io.BytesIO(payload)):
                self.assertEqual(upstream.release_exists("owner/repo", "tag"), expected)


if __name__ == "__main__":
    unittest.main()
