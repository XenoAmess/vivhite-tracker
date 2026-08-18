import hashlib
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock


WORKFLOWS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(WORKFLOWS_DIR))

import build_beta_chains as beta
import build_delta_chains as delta


class SharedFileLogicTest(unittest.TestCase):
    def test_sha256_reads_complete_file(self):
        payload = b"vivhite" * 200_000
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "payload.bin"
            path.write_bytes(payload)
            expected = hashlib.sha256(payload).hexdigest()
            self.assertEqual(expected, beta.sha256(path))
            self.assertEqual(expected, delta.sha256(path))

    def test_apk_native_library_detection(self):
        with tempfile.TemporaryDirectory() as directory:
            with_lib = Path(directory) / "with-lib.apk"
            without_lib = Path(directory) / "without-lib.apk"
            with zipfile.ZipFile(with_lib, "w") as archive:
                archive.writestr("lib/arm64-v8a/libapkpatch.so", b"native")
            with zipfile.ZipFile(without_lib, "w") as archive:
                archive.writestr("classes.dex", b"dex")

            for module in (beta, delta):
                self.assertTrue(module.apk_has_native_lib(with_lib))
                self.assertFalse(module.apk_has_native_lib(without_lib))
                self.assertFalse(module.apk_has_native_lib(Path(directory) / "missing.apk"))


class BetaChainLogicTest(unittest.TestCase):
    def test_select_recent_sources_filters_and_sorts(self):
        sources = [(14, "v14"), (0, "root"), (9, "v9"), (12, "v12"), (20, "v20")]
        self.assertEqual(
            [(12, "v12"), (14, "v14")],
            beta.select_recent_sources(sources, new_vc=20, limit=2),
        )

    def test_build_direct_chains_skips_source_without_hash(self):
        patches = {
            10: {"file": "p10.patch", "size": 123, "patchSha256": "patch-10"},
            11: {"file": "p11.patch", "size": 456, "patchSha256": "patch-11"},
        }
        chains = beta.build_direct_chains(patches, {10: "apk-10"}, 12, "apk-12")

        self.assertEqual(["10"], list(chains))
        self.assertEqual("apk-10", chains["10"]["fromApkSha256"])
        self.assertEqual(12, chains["10"]["hops"][0]["toVersionCode"])
        self.assertTrue(chains["10"]["hops"][0]["url"].endswith("/p10.patch"))

    def test_channel_metadata_failure_restores_previous_pair(self):
        previous = Path(__file__).resolve().parents[3]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "new.apk"
            apk.write_bytes(b"new-apk")
            (root / "version.json").write_bytes(b"new-version")
            uploads = []

            def download(name, dest):
                Path(dest, name).write_bytes(b"old-" + name.encode())
                return True

            def upload(path):
                payload = Path(path).read_bytes()
                uploads.append((Path(path).name, payload))
                if Path(path).name == "version.json" and payload == b"new-version":
                    raise RuntimeError("metadata upload failed")

            with mock.patch.object(beta, "release_asset_names", return_value={"beta-latest.apk", "version.json"}), \
                    mock.patch.object(beta, "download_asset", side_effect=download), \
                    mock.patch.object(beta, "upload_asset", side_effect=upload), \
                    mock.patch.object(beta, "delete_asset") as delete, \
                    mock.patch.object(beta, "run", side_effect=lambda cmd: __import__("shutil").copy(cmd[1], cmd[2])):
                try:
                    __import__("os").chdir(directory)
                    with self.assertRaises(RuntimeError):
                        beta.upload_channel_files(apk)
                finally:
                    __import__("os").chdir(previous)

            self.assertIn(("beta-latest.apk", b"old-beta-latest.apk"), uploads)
            self.assertIn(("version.json", b"old-version.json"), uploads)
            delete.assert_not_called()

    def test_channel_aborts_before_publish_when_existing_backup_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "new.apk"
            apk.write_bytes(b"new")
            with mock.patch.object(beta, "release_asset_names", return_value={"beta-latest.apk"}), \
                    mock.patch.object(beta, "download_asset", return_value=False), \
                    mock.patch.object(beta, "upload_asset") as upload:
                with self.assertRaises(RuntimeError):
                    beta.upload_channel_files(apk)
                upload.assert_not_called()

    def test_history_upload_failure_does_not_prune_assets(self):
        entry = {"apk": "beta-1.apk", "patches": {"0": {"file": "p.patch"}}}
        previous = Path(__file__).resolve().parents[3]
        with tempfile.TemporaryDirectory() as directory:
            try:
                __import__("os").chdir(directory)
                with mock.patch.object(beta, "upload_asset", side_effect=RuntimeError("upload")), \
                        mock.patch.object(beta, "delete_asset") as delete:
                    with self.assertRaises(RuntimeError):
                        beta.publish_history_and_prune({2: {"apk": "beta-2.apk"}}, [entry])
                    delete.assert_not_called()
            finally:
                __import__("os").chdir(previous)

    def test_history_is_committed_before_pruning(self):
        calls = []
        entry = {"apk": "beta-1.apk", "patches": {"0": {"file": "p.patch"}}}
        previous = Path(__file__).resolve().parents[3]
        with tempfile.TemporaryDirectory() as directory:
            try:
                __import__("os").chdir(directory)
                with mock.patch.object(beta, "upload_asset", side_effect=lambda path: calls.append(("upload", Path(path).name))), \
                        mock.patch.object(beta, "delete_asset", side_effect=lambda name: calls.append(("delete", name))):
                    beta.publish_history_and_prune({2: {"apk": "beta-2.apk"}}, [entry])
            finally:
                __import__("os").chdir(previous)

        self.assertEqual(
            [("upload", "beta-history.json"), ("delete", "beta-1.apk"), ("delete", "p.patch")],
            calls,
        )


class StableChainLogicTest(unittest.TestCase):
    def test_select_beta_sources_ignores_malformed_and_future_entries(self):
        history = {
            "8": {"apk": "beta-8.apk", "sha256": "sha8"},
            "10": {"apk": "beta-10.apk", "sha256": "sha10"},
            "11": {"apk": "beta-11.apk", "sha256": "sha11"},
            "bad": {"apk": "bad.apk", "sha256": "bad"},
            "7": {"apk": "", "sha256": "sha7"},
            "6": "not-an-entry",
        }
        self.assertEqual(
            [(8, "beta-8.apk", "sha8"), (10, "beta-10.apk", "sha10")],
            delta.select_beta_sources(history, new_vc=11, limit=2),
        )
        self.assertEqual([], delta.select_beta_sources([], new_vc=11, limit=2))

    def test_build_direct_chains_uses_stable_release_url(self):
        patches = {
            21: {"file": "patch-21-to-22.patch", "size": 99, "patchSha256": "patch"}
        }
        chains = delta.build_direct_chains(patches, {21: "apk-21"}, 22, "apk-22", "v2.2.0")

        hop = chains["21"]["hops"][0]
        self.assertEqual("apk-21", chains["21"]["fromApkSha256"])
        self.assertEqual("apk-22", hop["resultSha256"])
        self.assertIn("/releases/download/v2.2.0/", hop["url"])


if __name__ == "__main__":
    unittest.main()
