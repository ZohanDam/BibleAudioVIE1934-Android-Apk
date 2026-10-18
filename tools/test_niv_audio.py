"""Focused tests for the recording metadata parser (no network)."""
import json
import unittest

from import_niv_audio import extract_audio


class AudioMetadataTests(unittest.TestCase):
    @staticmethod
    def page(url):
        return ('<html><script type="application/ld+json">' +
                json.dumps({"@type": "AudioObject", "contentUrl": url}) +
                '</script></html>')

    def test_valid(self):
        url = "https://audio-bible-cdn.youversionapi.com/3/32k/GEN/1-test.mp3?version_id=111"
        self.assertEqual(url, extract_audio(self.page(url)))

    def test_reject_wrong_recording(self):
        with self.assertRaises(ValueError):
            extract_audio(self.page("https://audio-bible-cdn.youversionapi.com/79/32k/GEN/1.mp3?version_id=193"))

    def test_reject_unexpected_host(self):
        with self.assertRaises(ValueError):
            extract_audio(self.page("https://example.com/3/32k/GEN/1.mp3?version_id=111"))

    def test_reject_missing_metadata(self):
        with self.assertRaises(ValueError):
            extract_audio("<html/>")


if __name__ == "__main__":
    unittest.main()
