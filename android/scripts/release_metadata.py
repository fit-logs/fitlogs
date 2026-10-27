#!/usr/bin/env python3
"""Select a beta release from a main push or a manual workflow invocation."""

import os
import pathlib
import re
import subprocess


BUILD_FILE = "android/app/build.gradle.kts"
VERSION_PATTERN = r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?"


def read_version(source):
    names = re.findall(r'^\s*versionName\s*=\s*"([^"\n]+)"\s*$', source, re.MULTILINE)
    codes = re.findall(r"^\s*versionCode\s*=\s*([0-9]+)\s*$", source, re.MULTILINE)
    if len(names) != 1 or len(codes) != 1:
        raise ValueError("Expected one literal Android versionName and versionCode.")
    if not re.fullmatch(VERSION_PATTERN, names[0]) or int(codes[0]) < 1:
        raise ValueError("Invalid Android release version.")
    return names[0], int(codes[0])


def plan_release(source, previous_source, event, requested_tag=""):
    name, code = read_version(source)
    tag = "v" + name
    if event == "workflow_dispatch":
        if requested_tag and requested_tag != tag:
            raise ValueError(f"Requested {requested_tag}, but this source builds {tag}.")
        return True, tag
    if event != "push":
        raise ValueError("Releases require a main push or a manual invocation.")
    if previous_source is None:
        raise ValueError("Previous main version is unavailable; retry manually after review.")
    previous_name, previous_code = read_version(previous_source)
    if (name, code) == (previous_name, previous_code):
        return False, tag
    if name == previous_name or code <= previous_code:
        raise ValueError("A release must change versionName and increase versionCode together.")
    return True, tag


def select_release(root, event, before="", requested_tag=""):
    previous_source = None
    if event == "push":
        if not re.fullmatch(r"[0-9a-f]{40}", before) or before == "0" * 40:
            raise ValueError("A main push must have a valid previous commit; retry manually.")
        previous_source = subprocess.check_output(
            ["git", "show", f"{before}:{BUILD_FILE}"], cwd=root, text=True
        )
    publish, tag = plan_release(
        (root / BUILD_FILE).read_text(), previous_source, event, requested_tag
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
