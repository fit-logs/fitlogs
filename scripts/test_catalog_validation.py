"""Negative fixtures exercise the CI gate without changing public files."""
import hashlib
import json
import shutil
import tempfile
import unittest
from jsonschema.exceptions import ValidationError
from pathlib import Path

from validate_catalog import CATALOG, validate


class CatalogValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / "catalog"
        shutil.copytree(CATALOG, self.directory)

    def manifest(self, change):
        file = self.directory / "manifest.json"
        value = json.loads(file.read_text())
        change(value)
        file.write_text(json.dumps(value))

    def program(self, change):
        manifest = json.loads((self.directory / "manifest.json").read_text())
        entry = manifest["programs"][0]
        file = self.directory / entry["path"]
        value = json.loads(file.read_text())
        change(value)
        file.write_text(json.dumps(value))
        # Update the digest so semantic checks, rather than only integrity, run.
        self.manifest(lambda m: m["programs"][0].update(sha256=hashlib.sha256(file.read_bytes()).hexdigest()))

    def rejected(self):
        with self.assertRaises((AssertionError, ValueError, KeyError, FileNotFoundError, ValidationError)):
            validate(self.directory)

    def test_original_release_and_prior_import_fixture_pass(self):
        self.assertEqual(8, validate(self.directory))

    def test_malformed_json_is_rejected(self):
        (self.directory / "manifest.json").write_text("{")
        self.rejected()

    def test_duplicate_ids_are_rejected(self):
        self.manifest(lambda m: m["programs"][1].update(id=m["programs"][0]["id"]))
        self.rejected()

    def test_unsupported_schema_is_rejected(self):
        self.program(lambda p: p.update(schemaVersion=2))
        self.rejected()

    def test_missing_exercise_reference_is_rejected(self):
        self.program(lambda p: p["days"][0]["exercises"][0].update(canonicalId="missing"))
        self.rejected()

    def test_personal_weight_is_rejected(self):
        self.program(lambda p: p["days"][0]["exercises"][0]["sets"][0].update(weight="80", unit="kg"))
        self.rejected()

    def test_reversed_rep_range_is_rejected(self):
        self.program(lambda p: p["days"][0]["exercises"][0]["sets"][0].update(repsMin=10, repsMax=6))
        self.rejected()

    def test_missing_file_is_rejected(self):
        next((self.directory / "programs").glob("*.json")).unlink()
        self.rejected()

    def test_license_drift_is_rejected(self):
        self.program(lambda p: p.update(license={"id": "unknown"}))
        self.rejected()

    def test_old_version_paths_remain_valid_beside_a_new_manifest(self):
        manifest = json.loads((self.directory / "manifest.json").read_text())
        entry = manifest["programs"][0]
        previous = json.loads((self.directory / entry["path"]).read_text())
        previous["programVersion"] = "0"
        (self.directory / "programs" / f"{entry['id']}-v0.json").write_text(json.dumps(previous))
        self.assertEqual(8, validate(self.directory))
        previous["days"][0]["exercises"][0]["sets"][0].update(weight="80", unit="kg")
        (self.directory / "programs" / f"{entry['id']}-v0.json").write_text(json.dumps(previous))
        self.rejected()


if __name__ == "__main__":
    unittest.main()
