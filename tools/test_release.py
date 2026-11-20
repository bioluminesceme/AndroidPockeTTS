"""Check public voice assets and build-helper failure handling. No network or inference."""

import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import build_android
import package_runtime

ROOT = Path(__file__).resolve().parents[1]


def check_voice_set(directory, catalog):
    expected = {entry["file"] for entry in catalog}
    actual = {path.name for path in directory.glob("*.pttsvoice")}
    if actual != expected:
        raise ValueError("Voice files differ from the reviewed Kyutai catalog")
    contract = json.loads((ROOT / "android/app/src/main/assets/runtime-contract.json").read_text(encoding="utf-8"))
    for entry in catalog:
        data = (directory / entry["file"]).read_bytes()
        if hashlib.sha256(data).hexdigest() != entry["profile_sha256"]:
            raise ValueError("Voice profile checksum differs")
        with zipfile.ZipFile(directory / entry["file"]) as archive:
            if set(archive.namelist()) != {"manifest.json", "speaker.safetensors"}:
                raise ValueError("Unexpected profile entry")
            manifest = json.loads(archive.read("manifest.json"))
            speaker = archive.read("speaker.safetensors")
        if hashlib.sha256(speaker).hexdigest() != entry["speaker_sha256"]:
            raise ValueError("Speaker checksum differs")
        if any(manifest[key] != value for key, value in contract["identity"].items()):
            raise ValueError("Incompatible voice identity")
        if manifest["name"] != entry["name"] or not entry["source_url"].startswith("https://huggingface.co/kyutai/tts-voices/"):
            raise ValueError("Voice attribution differs")


class ReleaseTests(unittest.TestCase):
    def test_only_reviewed_profiles_are_present(self):
        catalog = json.loads((ROOT / "voices/catalog.json").read_text(encoding="utf-8"))
        self.assertEqual(len(catalog), 15)
        check_voice_set(ROOT / "voices", catalog)

    def test_unlisted_voice_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "private.pttsvoice").write_bytes(b"not public")
            with self.assertRaisesRegex(ValueError, "reviewed Kyutai"):
                check_voice_set(directory, [])

    def test_changed_voice_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "example.pttsvoice").write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "checksum"):
                check_voice_set(directory, [{"file": "example.pttsvoice", "profile_sha256": "0" * 64}])

    def test_warning_is_a_build_failure(self):
        result = subprocess.CompletedProcess([], 0, "WARNING: compiler diagnostic", "")
        with patch.object(build_android.subprocess, "run", return_value=result), patch("builtins.print"):
            with self.assertRaises(RuntimeError):
                build_android.run(["not executed"], {})

    def test_nonzero_exit_is_a_build_failure(self):
        result = subprocess.CompletedProcess([], 1, "", "")
        with patch.object(build_android.subprocess, "run", return_value=result), patch("builtins.print"):
            with self.assertRaises(RuntimeError):
                build_android.run(["not executed"], {})

    def test_runtime_mismatch_is_rejected_before_packaging(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "model"
            path.write_bytes(b"wrong model")
            with self.assertRaisesRegex(ValueError, "Runtime identity mismatch"):
                package_runtime.checked_files(path, path)


if __name__ == "__main__":
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ReleaseTests))
    if not result.wasSuccessful():
        raise SystemExit(1)
    print("PASS: Release asset and build-helper checks")
