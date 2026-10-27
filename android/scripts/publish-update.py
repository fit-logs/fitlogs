#!/usr/bin/env python3
"""Build and prepare a matching APK/source release; --publish explicitly uploads it."""
import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "android"
REPOSITORY = "avivajpeyi/fitnotes"
SIGNER = "7ab64af6c9ab261ba03a9471b6a3adc59fa33d684d5d1c4ef1f06a6bc693ddce"


def run(args, capture=True, env=None):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, capture_output=capture, env=env)


def source_files():
    paths = run(["git", "ls-files", "--cached", "--others", "--exclude-standard", "--", "android", "LICENSE"]).stdout.splitlines()
    exact = {"LICENSE", "android/LICENSE", "android/THIRD_PARTY_NOTICES.md", "android/README.md", "android/build.gradle.kts",
             "android/settings.gradle.kts", "android/gradle.properties", "android/gradlew", "android/gradlew.bat",
             "android/version.properties", "android/app/build.gradle.kts", "android/scripts/publish-update.py", "android/docs/app-updates.md", "android/docs/automatic-backup.md"}
    prefixes = ("android/app/src/", "android/app/schemas/", "android/gradle/", "android/licenses/")
    return sorted({path for path in paths if (path in exact or path.startswith(prefixes)) and (ROOT / path).is_file()})


def source_hash(paths):
    digest = hashlib.sha256()
    for name in paths:
        digest.update(name.encode()); digest.update(b"\0"); digest.update((ROOT / name).read_bytes()); digest.update(b"\0")
    return digest.hexdigest()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publish", action="store_true", help="Upload and publish a public GitHub release after verifying every uploaded asset")
    parser.add_argument("--notes-file", type=Path, help="Feature notes to include in the release")
    args = parser.parse_args()
    env = os.environ.copy()
    jbr = Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    if "JAVA_HOME" not in env and jbr.is_dir():
        env["JAVA_HOME"] = str(jbr)
    files = source_files(); before = source_hash(files)
    # Keep the existing local signing key. Never copy it into this bundle or GitHub.
    run([str(ANDROID / "gradlew"), "-p", str(ANDROID), ":app:assembleDebug", ":app:testDebugUnitTest", ":app:lintDebug", "--console=plain"], capture=False, env=env)
    if files != source_files() or before != source_hash(files):
        raise SystemExit("Android source changed during the build. Finish those edits, then prepare again.")
    sdk = Path(env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT") or Path.home() / "Library/Android/sdk")
    local = ANDROID / "local.properties"
    if local.is_file():
        match = re.search(r"^sdk\.dir=(.+)$", local.read_text(), re.M)
        if match:
            sdk = Path(match.group(1).replace("\\ ", " "))
    tools = sorted((sdk / "build-tools").iterdir(), key=lambda path: tuple(int(x) if x.isdigit() else 0 for x in path.name.split(".")))[-1]
    apk = ANDROID / "app/build/outputs/apk/debug/app-debug.apk"
    cert = run([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], env=env).stdout
    if "certificate SHA-256 digest: " + SIGNER not in cert:
        raise SystemExit("Signing key changed. Keep the original key; no update was published.")
    badging = run([str(tools / "aapt2"), "dump", "badging", str(apk)]).stdout
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package or package[1] != "com.example.fitnotes":
        raise SystemExit("Expected the normal FitNotes application, not a preview APK.")
    minimum = re.search(r"minSdkVersion:'(\d+)'", badging)
    name, code, version = package[1], int(package[2]), package[3]
    tag = f"android-v{version}-{code}"
    output = ANDROID / "build/update-release" / tag
    output.mkdir(parents=True, exist_ok=True)
    target = output / "fitnotes.apk"; shutil.copy2(apk, target)
    source = output / "fitnotes-source.tar.gz"
    with source.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w") as archive:
            for path in files:
                data = (ROOT / path).read_bytes()
                info = tarfile.TarInfo("fitnotes-source/" + path)
                info.size = len(data); info.mode = 0o755 if path.endswith(("gradlew", "publish-update.py")) else 0o644
                archive.addfile(info, io.BytesIO(data))
    manifest = output / "fitnotes-update.json"
    manifest.write_text(json.dumps({"schemaVersion": 1, "packageName": name, "versionCode": code, "versionName": version,
        "minSdk": int(minimum[1]), "apkName": target.name, "size": target.stat().st_size, "sha256": digest(target),
        "sourceArchive": source.name, "sourceSha256": digest(source)}, indent=2, sort_keys=True) + "\n")
    sums = output / "SHA256SUMS"
    assets = [target, manifest, source]
    sums.write_text("".join(f"{digest(path)}  {path.name}\n" for path in assets))
    assets.append(sums)
    notes = output / "release-notes.md"
    feature_notes = args.notes_file.read_text().strip() if args.notes_file else "Update FitNotes while retaining your training data."
    notes.write_text(feature_notes + f"\n\nDownload **fitnotes.apk** for the first installation of this updater. Future versions can use **Settings → Update app**. Android still asks for install permission and confirmation.\n\nVersion: {version} ({code}). Signed with the existing FitNotes certificate.\n\nThe matching Android source snapshot is attached as **fitnotes-source.tar.gz**, including build scripts and third-party licence notices. It is the corresponding source for this APK; GitHub's automatic source ZIP follows the base Git tag. No databases, photos, exports, credentials or signing keys are included.\n")
    print(f"Prepared {output}")
    print(f"APK SHA-256: {digest(target)}")
    print(f"Source: {len(files)} files; SHA-256 {digest(source)}")
    if not args.publish:
        print("Nothing uploaded. Review this bundle, then rerun with --publish to publish it publicly.")
        return
    latest = subprocess.run(["gh", "api", f"repos/{REPOSITORY}/releases/latest"], cwd=ROOT, capture_output=True, text=True)
    if latest.returncode == 0:
        latest_assets = json.loads(latest.stdout)["assets"]
        previous = next((item for item in latest_assets if item["name"] == "fitnotes-update.json"), None)
        if previous:
            data = run(["gh", "api", f"repos/{REPOSITORY}/releases/assets/{previous['id']}", "-H", "Accept: application/octet-stream"]).stdout
            if code <= int(json.loads(data)["versionCode"]):
                raise SystemExit("VERSION_CODE must be higher than the latest published Android release.")
    elif "404" not in latest.stderr:
        raise SystemExit("Could not check the current GitHub release. Nothing was published.")
    # Refuse an existing release/tag; releases are immutable inputs for the updater.
    existing = subprocess.run(["gh", "release", "view", tag, "--repo", REPOSITORY], cwd=ROOT, capture_output=True)
    if existing.returncode == 0:
        raise SystemExit(f"Release {tag} already exists. Increase VERSION_CODE and VERSION_NAME; never replace a released APK.")
    base = run(["git", "rev-parse", "HEAD"]).stdout.strip()
    run(["gh", "release", "create", tag, *map(str, assets), "--repo", REPOSITORY, "--target", base,
         "--title", f"FitNotes Android {version}", "--notes-file", str(notes), "--draft"], capture=False)
    release = json.loads(run(["gh", "api", f"repos/{REPOSITORY}/releases/tags/{tag}"]).stdout)
    remote = {asset["name"]: asset for asset in release["assets"]}
    for path in assets:
        item = remote.get(path.name)
        if not item or item["size"] != path.stat().st_size or item.get("digest") != "sha256:" + digest(path):
            raise SystemExit("Uploaded asset verification failed. The release remains an unpublished draft.")
    run(["gh", "release", "edit", tag, "--repo", REPOSITORY, "--draft=false", "--latest"], capture=False)
    print(f"Published https://github.com/{REPOSITORY}/releases/tag/{tag}")


if __name__ == "__main__":
    main()
