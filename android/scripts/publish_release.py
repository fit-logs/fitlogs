#!/usr/bin/env python3
"""Publish a stable updater release after verifying every uploaded asset."""
import argparse
import json
import os
import subprocess

from prepare_update_assets import ASSET_NAMES, ROOT, sha256
from release_metadata import VERSION_FILE, read_version

REPOSITORY = "fit-logs/fitlogs"


def run(*args):
    return subprocess.check_output(["gh", *args], cwd=ROOT, text=True)


def api(path):
    result = subprocess.run(["gh", "api", path], cwd=ROOT, capture_output=True, text=True)
    if result.returncode:
        if "HTTP 404" in result.stderr:
            return None
        raise RuntimeError("GitHub API check failed: " + result.stderr)
    return json.loads(result.stdout)


def release_by_tag(tag):
    published = api(f"repos/{REPOSITORY}/releases/tags/{tag}")
    if published is not None:
        return published
    # The REST tag endpoint can omit drafts. The CLI resolves their database ID.
    result = subprocess.run(["gh", "release", "view", tag, "--repo", REPOSITORY,
                             "--json", "databaseId"], cwd=ROOT, capture_output=True, text=True)
    if result.returncode:
        if "release not found" in result.stderr.lower():
            return None
        raise RuntimeError("Could not resolve draft release: " + result.stderr)
    identifier = json.loads(result.stdout)["databaseId"]
    if not isinstance(identifier, int) or identifier <= 0:
        raise ValueError("Invalid GitHub release ID.")
    draft = api(f"repos/{REPOSITORY}/releases/{identifier}")
    if not draft or draft["tag_name"] != tag:
        raise ValueError("Draft release identity could not be verified.")
    return draft


def publish(tag, commit):
    if os.environ.get("GITHUB_REPOSITORY", REPOSITORY) != REPOSITORY:
        raise ValueError("Publishing is configured only for fit-logs/fitlogs.")
    version, code = read_version((ROOT / VERSION_FILE).read_text())
    if tag != "v" + version or "-" in version:
        raise ValueError("Tag must match a stable Android source version.")
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if commit != head:
        raise ValueError("Release target must be the built checkout commit.")
    folder = ROOT / "android/build/release"
    manifest = json.loads((folder / "fitnotes-update.json").read_text())
    if (manifest["versionName"], manifest["versionCode"]) != (version, code):
        raise ValueError("Update manifest version differs from source.")
    for name in ASSET_NAMES:
        if not (folder / name).is_file():
            raise ValueError("Missing release asset " + name)
    if manifest["sha256"] != sha256(folder / "fitnotes.apk"):
        raise ValueError("APK checksum differs from the update manifest.")
    if manifest["sourceSha256"] != sha256(folder / "fitlogs-source.tar.gz"):
        raise ValueError("Source archive checksum differs from the manifest.")
    lines = (folder / "SHA256SUMS").read_text().splitlines()
    if len(lines) != 3 or {line.split()[-1] for line in lines} != set(ASSET_NAMES[:3]):
        raise ValueError("Expected checksums for APK, manifest and source.")
    for line in lines:
        checksum, name = line.split()
        if name not in ASSET_NAMES[:3] or sha256(folder / name) != checksum:
            raise ValueError("Release asset checksum mismatch.")
    release = release_by_tag(tag)
    if release and not release["draft"]:
        raise ValueError("Release already published; refusing to overwrite it.")
    ref = api(f"repos/{REPOSITORY}/git/ref/tags/{tag}")
    if ref:
        obj = ref["object"]
        if obj["type"] == "tag":
            obj = api(f"repos/{REPOSITORY}/git/tags/{obj['sha']}")["object"]
        if obj["sha"] != commit:
            raise ValueError("Tag points to different source; refusing to retag.")
    latest = api(f"repos/{REPOSITORY}/releases/latest")
    if latest:
        item = next((a for a in latest["assets"] if a["name"] == "fitnotes-update.json"), None)
        if not item:
            raise ValueError("Latest stable release has no update manifest; review it first.")
        previous = json.loads(run("api", f"repos/{REPOSITORY}/releases/assets/{item['id']}", "-H", "Accept: application/octet-stream"))
        if code <= previous["versionCode"]:
            raise ValueError("VERSION_CODE must exceed the latest published release.")
    notes = ROOT / "android/docs/releases" / f"{tag}.md"
    if not notes.is_file():
        raise ValueError("Missing release notes.")
    common = ("--repo", REPOSITORY, "--target", commit, "--title", f"FitLogs {version}", "--notes-file", str(notes), "--prerelease=false")
    if release:
        run("release", "edit", tag, *common)
        run("release", "upload", tag, *[str(folder / name) for name in ASSET_NAMES], "--repo", REPOSITORY, "--clobber")
    else:
        run("release", "create", tag, *[str(folder / name) for name in ASSET_NAMES], *common, "--draft", "--latest=false")
    uploaded = release_by_tag(tag)
    remote = {a["name"]: a for a in uploaded["assets"]}
    if set(remote) != set(ASSET_NAMES):
        raise ValueError("Unexpected/missing assets; release remains draft.")
    for name in ASSET_NAMES:
        item, path = remote[name], folder / name
        if item["size"] != path.stat().st_size or item.get("digest") != "sha256:" + sha256(path):
            raise ValueError("Uploaded asset verification failed; release remains draft.")
    run("release", "edit", tag, "--repo", REPOSITORY, "--draft=false", "--prerelease=false", "--latest")
    published = api(f"repos/{REPOSITORY}/releases/latest")
    if not published or published["id"] != uploaded["id"] or published["draft"] or published["prerelease"]:
        raise ValueError("Release is not discoverable as the latest stable update.")
    print("Published and verified " + published["html_url"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    publish(args.tag, args.commit)
