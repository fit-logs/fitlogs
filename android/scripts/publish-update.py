#!/usr/bin/env python3
"""Prepare a signed release bundle; --publish also publishes its stable update."""
import argparse
import os
import pathlib
import subprocess

from prepare_update_assets import ROOT
from publish_release import publish
from release_metadata import VERSION_FILE, read_version

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--publish', action='store_true')
parser.add_argument('--notes-file', type=pathlib.Path, help='Must match the committed version release notes')
args = parser.parse_args()
version, _ = read_version((ROOT / VERSION_FILE).read_text())
notes = ROOT / 'android/docs/releases' / f'v{version}.md'
if not notes.is_file() or args.notes_file and args.notes_file.read_text() != notes.read_text():
    raise SystemExit('Commit matching notes under android/docs/releases before packaging.')
env = os.environ.copy()
jbr = pathlib.Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home')
if 'JAVA_HOME' not in env and jbr.is_dir():
    env['JAVA_HOME'] = str(jbr)
if env.get('JAVA_HOME'):
    env['PATH'] = env['JAVA_HOME'] + '/bin:' + env['PATH']
subprocess.run(['bash', 'android/scripts/package-release.sh'], cwd=ROOT, env=env, check=True)
if args.publish:
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    publish('v' + version, commit)
else:
    print('Prepared android/build/release. No assets uploaded.')
