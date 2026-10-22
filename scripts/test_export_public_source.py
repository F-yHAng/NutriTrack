"""Publication boundary regression checks using artificial fixtures only."""
import tempfile
import unittest
from pathlib import Path
import export_public_source as exporter


class PublicExportTest(unittest.TestCase):
    def test_local_data_and_private_history_are_excluded(self):
        for name in [".git/config", "local.properties", "app/google-services.json",
                     "app/src/debug/google-services.json", "docs/context-handoff/report.md",
                     "app/build/output.txt", "docs/trace.log", "docs/account.db",
                     ".env", "app/release.jks"]:
            with self.subTest(path=name):
                self.assertFalse(exporter.allowed(Path(name)))

    def test_ci_docs_and_source_remain_in_the_export(self):
        for name in [".github/workflows/android.yml", "CONTRIBUTING.md",
                     "docs/screenshots/home.png", "app/src/main/AndroidManifest.xml"]:
            self.assertTrue(exporter.allowed(Path(name)))

    def test_scanner_reports_location_without_echoing_the_secret(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            seed = root / "app/src/main/assets/data.csv"
            seed.parent.mkdir(parents=True)
            seed.write_text("User_ID,PhoneNumber\n" + "".join(f"{i},{2025550100+i}\n" for i in range(1, 10)))
            synthetic_key = "AIza" + "x" * 35
            target = root / "README.md"
            target.write_text("example\n" + synthetic_key)
            findings = exporter.check(root, [Path("README.md")])
            self.assertEqual(["README.md:2: google-api-key"], findings)
            self.assertNotIn(synthetic_key, " ".join(findings))

    def test_symlinked_source_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "target").write_text("fixture")
            (root / "README.md").symlink_to(root / "target")
            with self.assertRaises(ValueError):
                exporter.source_files(root)


if __name__ == "__main__":
    unittest.main()
