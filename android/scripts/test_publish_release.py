import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch

import publish_release as publisher
from prepare_update_assets import ASSET_NAMES, sha256


class PublishReleaseTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = pathlib.Path(self.directory.name)
        self.folder = self.root / 'android/build/release'
        self.folder.mkdir(parents=True)
        (self.root / 'android/version.properties').write_text('VERSION_NAME=1.1.1\nVERSION_CODE=4\n')
        notes = self.root / 'android/docs/releases/v1.1.1.md'
        notes.parent.mkdir(parents=True)
        notes.write_text('Verified migration release\n')
        (self.folder / 'fitnotes.apk').write_bytes(b'signed-apk-fixture')
        (self.folder / 'fitlogs-source.tar.gz').write_bytes(b'source-fixture')
        (self.folder / 'fitnotes-update.json').write_text(json.dumps({
            'versionName': '1.1.1', 'versionCode': 4,
            'sha256': sha256(self.folder / 'fitnotes.apk'),
            'sourceSha256': sha256(self.folder / 'fitlogs-source.tar.gz'),
        }))
        (self.folder / 'SHA256SUMS').write_text(''.join(f'{sha256(self.folder / name)}  {name}\n' for name in ASSET_NAMES[:3]))
        self.calls = []
        self.uploaded = False
        self.published = False
        self.existing_published = False
        self.wrong_digest = False
        self.wrong_tag = False

    def api(self, path):
        if '/git/ref/' in path:
            return {'object': {'type': 'commit', 'sha': 'different'}} if self.wrong_tag else None
        if path.endswith('/releases/latest'):
            return self.release() if self.published else None
        if '/releases/tags/' in path:
            if self.existing_published:
                return {'draft': False}
            return self.release() if self.uploaded else None
        raise AssertionError('Unexpected API call: ' + path)

    def release(self):
        assets = [{'name': name, 'size': (self.folder / name).stat().st_size,
                   'digest': 'sha256:' + sha256(self.folder / name)} for name in ASSET_NAMES]
        if self.wrong_digest:
            assets[0]['digest'] = 'sha256:' + '0' * 64
        return {'id': 123, 'draft': not self.published, 'prerelease': False,
                'html_url': 'https://github.com/fit-logs/fitlogs/releases/tag/v1.1.1', 'assets': assets}

    def run_gh(self, *args):
        self.calls.append(args)
        if args[:2] == ('release', 'create'):
            self.uploaded = True
        if '--draft=false' in args:
            self.published = True
        return ''

    def invoke(self):
        with patch.object(publisher, 'ROOT', self.root), patch.object(publisher, 'api', self.api), \
                patch.object(publisher, 'run', self.run_gh), patch('builtins.print'), patch.object(publisher.subprocess, 'check_output', return_value='commit\n'):
            publisher.publish('v1.1.1', 'commit')

    def test_success_is_stable_latest_and_has_all_updater_assets(self):
        self.invoke()
        self.assertTrue(self.published)
        self.assertIn('--draft', self.calls[0])
        self.assertIn('--latest', self.calls[-1])
        self.assertIn('--prerelease=false', self.calls[-1])

    def test_wrong_uploaded_digest_never_publishes_the_draft(self):
        self.wrong_digest = True
        with self.assertRaisesRegex(ValueError, 'Uploaded asset verification'):
            self.invoke()
        self.assertTrue(self.uploaded)
        self.assertFalse(self.published)

    def test_published_release_and_conflicting_tag_are_immutable(self):
        for field in ('existing_published', 'wrong_tag'):
            with self.subTest(field=field):
                setattr(self, field, True)
                with self.assertRaises(ValueError):
                    self.invoke()
                self.assertEqual([], self.calls)
                setattr(self, field, False)

    def test_changed_apk_is_rejected_before_any_release_mutation(self):
        (self.folder / 'fitnotes.apk').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'APK checksum'):
            self.invoke()
        self.assertEqual([], self.calls)
