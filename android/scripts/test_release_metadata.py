import pathlib
import subprocess
import tempfile
import unittest

from release_metadata import BUILD_FILE, plan_release, read_version, select_release


def build(name="1.0.0-beta.2", code=3):
    return f'    versionCode = {code}\n    versionName = "{name}"\n'


class ReleaseMetadataTest(unittest.TestCase):
    def test_ordinary_gradle_edit_skips_release(self):
        self.assertEqual(
            plan_release(build() + "// dependency change\n", build(), "push"),
            (False, "v1.0.0-beta.2"),
        )

    def test_new_version_selects_matching_tag(self):
        self.assertEqual(
            plan_release(build("1.0.0-beta.3", 4), build(), "push"),
            (True, "v1.0.0-beta.3"),
        )

    def test_partial_or_nonincreasing_version_change_fails(self):
        for name, code in [("1.0.0-beta.3", 3), ("1.0.0-beta.3", 2), ("1.0.0-beta.2", 4)]:
            with self.subTest(name=name, code=code), self.assertRaises(ValueError):
                plan_release(build(name, code), build(), "push")

    def test_manual_retry_uses_current_version(self):
        for requested in ["", "v1.0.0-beta.2"]:
            self.assertEqual(
                plan_release(build(), None, "workflow_dispatch", requested),
                (True, "v1.0.0-beta.2"),
            )

    def test_manual_tag_must_match_source(self):
        with self.assertRaises(ValueError):
            plan_release(build(), None, "workflow_dispatch", "v1.0.0-beta.1")

    def test_unsupported_event_or_missing_baseline_fails(self):
        for event in ["pull_request", "push"]:
            with self.subTest(event=event), self.assertRaises(ValueError):
                plan_release(build(), None, event)

    def test_ambiguous_or_invalid_version_fails(self):
        for source in [build("../secret"), build(code=0), build() + build(), "versionName = provider()"]:
            with self.subTest(source=source), self.assertRaises(ValueError):
                read_version(source)

    def test_push_compares_entire_push_and_requires_notes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / BUILD_FILE
            source.parent.mkdir(parents=True)

            def git(*args):
                return subprocess.check_output(["git", *args], cwd=root, text=True).strip()

            git("init", "-q")
            git("config", "user.name", "Release test")
            git("config", "user.email", "release-test@example.invalid")
            source.write_text(build())
            git("add", ".")
            git("commit", "-qm", "Initial version")
            before = git("rev-parse", "HEAD")
            source.write_text(build("1.0.0-beta.3", 4))
            git("commit", "-qam", "Increase Android version")
            source.write_text(build("1.0.0-beta.3", 4) + "// later commit in same push\n")
            git("commit", "-qam", "Follow-up change")
            with self.assertRaisesRegex(ValueError, "Missing release notes"):
                select_release(root, "push", before)
            notes = root / "android/docs/releases/v1.0.0-beta.3.md"
            notes.parent.mkdir(parents=True)
            notes.write_text("Release notes\n")
            self.assertEqual(select_release(root, "push", before), (True, "v1.0.0-beta.3"))
            self.assertEqual(
                select_release(root, "push", git("rev-parse", "HEAD^")),
                (False, "v1.0.0-beta.3"),
            )
            for invalid in ["", "0" * 40, "--all"]:
                with self.subTest(before=invalid), self.assertRaises(ValueError):
                    select_release(root, "push", invalid)


if __name__ == "__main__":
    unittest.main()
