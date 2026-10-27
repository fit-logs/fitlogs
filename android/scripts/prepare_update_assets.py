#!/usr/bin/env python3
"""Prepare updater metadata and corresponding source for a verified signed APK."""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess

from release_metadata import VERSION_FILE, read_version

ROOT = pathlib.Path(__file__).resolve().parents[2]
ASSET_NAMES = ("fitnotes.apk", "fitnotes-update.json", "fitlogs-source.tar.gz", "SHA256SUMS")


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare(apk, output):
    name, code = read_version((ROOT / VERSION_FILE).read_text())
    metadata = json.loads((ROOT / "android/app/build/outputs/apk/release/output-metadata.json").read_text())["elements"][0]
    if (metadata["versionName"], metadata["versionCode"]) != (name, code):
        raise ValueError("Built APK metadata does not match version.properties.")
    if subprocess.run(["git", "diff", "--quiet", "HEAD", "--"], cwd=ROOT).returncode:
        raise ValueError("Commit source changes before packaging corresponding source.")
    untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "--", "android", "catalog", "data/programs", "scripts", ".github/workflows", "LICENSE", "README.md"], cwd=ROOT, text=True)
    if untracked.strip():
        raise ValueError("Commit new source files before packaging corresponding source.")
    output.mkdir(parents=True, exist_ok=True)
    target = output / ASSET_NAMES[0]
    if apk.resolve() != target.resolve():
        shutil.copy2(apk, target)
    source = output / ASSET_NAMES[2]
    archive = subprocess.check_output([
        "git", "archive", "--format=tar.gz", "--prefix=fitlogs-source/", "HEAD", "--",
        "android/app", "android/gradle", "android/scripts", "android/docs",
        "android/build.gradle.kts", "android/settings.gradle.kts", "android/gradle.properties",
        "android/gradlew", "android/gradlew.bat", VERSION_FILE, "android/LICENSE",
        "android/THIRD_PARTY_NOTICES.md", "android/README.md", "catalog", "data/programs",
        "scripts", ".github/workflows", "LICENSE", "README.md",
    ], cwd=ROOT)
    source.write_bytes(archive)
    manifest = {"schemaVersion": 1, "packageName": "com.example.fitnotes", "versionCode": code,
                "versionName": name, "minSdk": 24, "apkName": target.name,
                "size": target.stat().st_size, "sha256": sha256(target),
                "sourceArchive": source.name, "sourceSha256": sha256(source)}
    (output / ASSET_NAMES[1]).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    (output / ASSET_NAMES[3]).write_text("".join(f"{sha256(output / item)}  {item}\n" for item in ASSET_NAMES[:3]))
    print(f"Prepared stable updater bundle {name} ({code}) at {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "android/build/release")
    args = parser.parse_args()
    prepare(args.apk, args.output)
