#!/usr/bin/env python3
"""Select a stable updater release from a main push or manual invocation."""

import os
import pathlib
import re
import subprocess


BUILD_FILE = "android/app/build.gradle.kts"
VERSION_FILE = "android/version.properties"
VERSION_PATTERN = r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?"


def read_version(source):
    names = re.findall(r"^VERSION_NAME=([^\s]+)$", source, re.MULTILINE)
    codes = re.findall(r"^VERSION_CODE=([0-9]+)$", source, re.MULTILINE)
    if not names and not codes:  # Compare against pre-migration commits too.
        names = re.findall(r'^\s*versionName\s*=\s*"([^"\n]+)"\s*$', source, re.MULTILINE)
        codes = re.findall(r"^\s*versionCode\s*=\s*([0-9]+)\s*$", source, re.MULTILINE)
    if len(names) != 1 or len(codes) != 1:
        raise ValueError("Expected exactly one literal version name and version code.")
    if not re.fullmatch(VERSION_PATTERN, names[0]) or not 1 <= int(codes[0]) <= 2100000000:
        raise ValueError("Invalid Android release version.")
    return names[0], int(codes[0])


def plan_release(source, previous_source, event, requested_tag=""):
    name, code = read_version(source)
    tag = "v" + name
    if "-" in name:
        raise ValueError("The in-app updater requires a stable version, e.g. 1.1.1.")
    if event == "workflow_dispatch":
        if requested_tag and requested_tag != tag:
            raise ValueError(f"Requested {requested_tag}, but this source builds {tag}.")
        return True, tag
    if event != "push":
        raise ValueError("Releases require a main push or a manual invocation.")
    if previous_source is None:
        return False, tag  # Initial imports require one manual release.
    previous_name, previous_code = read_version(previous_source)
    if (name, code) == (previous_name, previous_code):
        return False, tag
    if name == previous_name or code <= previous_code:
        raise ValueError("A release must change versionName and increase versionCode together.")
    return True, tag


def select_release(root, event, before="", requested_tag=""):
    previous_source = None
    if event == "push":
        if not re.fullmatch(r"[0-9a-f]{40}", before):
            raise ValueError("A main push must have a valid previous commit; retry manually.")
        if before != "0" * 40:
            for path in (VERSION_FILE, BUILD_FILE):
                result = subprocess.run(["git", "show", f"{before}:{path}"], cwd=root, text=True, capture_output=True)
                if result.returncode == 0:
                    previous_source = result.stdout
                    break
    path = root / VERSION_FILE
    publish, tag = plan_release(
        (path if path.is_file() else root / BUILD_FILE).read_text(), previous_source, event, requested_tag
    )
    if publish and not (root / "android/docs/releases" / f"{tag}.md").is_file():
        raise ValueError(f"Missing release notes for {tag}.")
    return publish, tag


def main():
    if os.environ.get("GITHUB_REF") != "refs/heads/main":
        raise ValueError("Android releases can only run from main.")
    root = pathlib.Path(__file__).resolve().parents[2]
    publish, tag = select_release(
        root,
        os.environ["RELEASE_EVENT"],
        os.environ.get("BASE_SHA", ""),
        os.environ.get("REQUESTED_TAG", ""),
    )
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"publish={str(publish).lower()}\ntag={tag}\n")
    print(f"Selected {tag}." if publish else "Android version unchanged; no release needed.")


if __name__ == "__main__":
    main()
