"""Validate public prescriptions against the same schema used by paste imports.

No personal CSV/history is read. --prepare copies the canonical schema for Pages;
--export checks that the static build contains every public JSON byte-for-byte.
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "catalog"
SCHEMA = ROOT / "data/programs/schema-v1.json"
MANIFEST = "manifest-v2.json"
SOURCED_PROGRAMS = {
    "ms-spring-shred-8week": "8-week-spring-shred-program",
    "ms-lean-mass-6week": "6-week-workout-program-to-build-lean-muscle",
    "ms-mass-hypertrophy-8week": "8-week-hypertrophy-workout",
}


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        assert key not in result, f"Duplicate JSON key: {key}"
        result[key] = value
    return result


def load(path):
    return json.loads(path.read_text(), object_pairs_hook=unique_object)


def validate(directory=CATALOG):
    validator = Draft202012Validator(load(SCHEMA))
    validator.check_schema(validator.schema)
    # Keep the pre-catalog schema fixture compatible, including optional fields.
    validator.validate(load(ROOT / "data/programs/examples/four-day.json"))
    manifest = load(directory / MANIFEST)
    original_license = load(directory / "manifest.json")["license"]
    assert manifest["schemaVersion"] == 1
    assert re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", manifest["catalogVersion"])
    assert manifest["license"]["id"] == "CC-BY-4.0"
    assert manifest["license"]["attribution"]
    refs = load(directory / "exercises.json")["exercises"]
    assert refs == manifest["exercises"], "Manifest/exercise reference drift"
    references = {e["id"]: e for e in refs}
    assert len(references) == len(refs) and len({e["name"] for e in refs}) == len(refs)
    entries = manifest["programs"]
    assert 1 <= len(entries) <= 64 and len({e["id"] for e in entries}) == len(entries)
    assert sum(e["starter"] for e in entries) == 3
    paths = set()
    for e in entries:
        assert re.fullmatch(r"[a-z0-9][a-z0-9-]*", e["id"])
        assert e["path"] == f"programs/{e['id']}-v{e['programVersion']}.json"
        path = directory / e["path"]
        paths.add(path.name)
        assert len(path.read_bytes()) <= 256000
        assert hashlib.sha256(path.read_bytes()).hexdigest() == e["sha256"], path
        p = load(path)
        validator.validate(p)
        assert p["id"] == e["id"] and p["programVersion"] == e["programVersion"]
        assert p["name"] == e["title"] and p["description"] == e["description"]
        assert p["level"] == e["level"] and p["goals"] == [e["goal"]]
        assert sorted(p["equipment"]) == sorted(e["equipment"])
        assert len(set(p["equipment"])) == len(p["equipment"])
        assert len(p["days"]) == p["daysPerWeek"] == e["daysPerWeek"]
        assert len({d["id"] for d in p["days"]}) == len(p["days"])
        sourced = e["id"] in SOURCED_PROGRAMS
        assert e["license"] == p["license"]["id"] == "CC-BY-4.0"
        if sourced:
            assert p["license"]["url"] == manifest["license"]["url"]
            assert "source articles" in p["license"]["attribution"]
            assert p["source"]["url"] == "https://www.muscleandstrength.com/workouts/" + SOURCED_PROGRAMS[e["id"]]
            assert not e["starter"]
        else:
            assert p["license"] == original_license
        assert p["source"]["author"] and p["source"]["attribution"] and p["source"]["url"].startswith("https://")
        assert p["schedule"] and p["progression"] and p["notes"]
        assert not {"workouts", "actualSets", "userId", "history"}.intersection(p)
        total = 0
        for d in p["days"]:
            assert d["durationMinutes"] == e["minutesPerDay"] and 0 <= e["minutesPerDay"] <= 720
            if not sourced:
                assert 15 <= e["minutesPerDay"] <= 90
            assert len({m["canonicalId"] for m in d["exercises"]}) == len(d["exercises"])
            for m in d["exercises"]:
                reference = references[m["canonicalId"]]
                assert reference["name"] == m["name"] and reference["equipment"] in p["equipment"]
                assert all(s in {r["name"] for r in refs} for s in m.get("substitutions", []))
                assert 1 <= len(m["sets"]) <= 100
                for s in m["sets"]:
                    low, high = s.get("repsMin"), s.get("repsMax")
                    assert (low is None and high is None and m.get("notes")) or (low is not None and high is not None and 1 <= low <= high <= 1000)
                    assert s.get("restSeconds") is None or 0 <= s["restSeconds"] <= 3600
                    assert s.get("weight") is None and s.get("unit") is None
                    if sourced:
                        assert s.get("rir") is None and s.get("rpe") is None, "Source does not prescribe RIR/RPE"
                        assert s["loadMode"] in {"external", "bodyweight", "unknown", "reps_only"}
                    else:
                        assert len(m["sets"]) <= 4
                        assert low is not None and 3 <= low <= high <= 15
                        assert 60 <= s["restSeconds"] <= 300 and 2 <= s["rir"] <= 4
                        assert s["loadMode"] == "external" and not s.get("isWarmUp", False)
                    total += 1
            groups = {m["supersetGroup"] for m in d["exercises"] if m.get("supersetGroup")}
            for group in groups:
                positions = [i for i, m in enumerate(d["exercises"]) if m.get("supersetGroup") == group]
                assert len(positions) == 2 and positions[1] == positions[0] + 1, "Superset pairs must be adjacent"
        assert 12 <= total <= (500 if sourced else 80), (e["id"], total)
    # Retain previously published version paths when the manifest moves to v2.
    # They still obey the shared import schema and cannot contain personal loads.
    latest = {e["id"]: e for e in entries}
    for file in (directory / "programs").glob("*.json"):
        if file.name in paths:
            continue
        archived = load(file)
        validator.validate(archived)
        assert archived["id"] in latest
        assert archived["programVersion"] != latest[archived["id"]]["programVersion"]
        assert file.name == f"{archived['id']}-v{archived['programVersion']}.json"
        assert archived["license"]["id"] == "CC-BY-4.0"
        if archived["id"] not in SOURCED_PROGRAMS:
            assert archived["license"] == original_license
        for day in archived["days"]:
            for move in day["exercises"]:
                assert references[move["canonicalId"]]["name"] == move["name"]
                for target in move["sets"]:
                    assert target.get("weight") is None and target.get("unit") is None
                    low, high = target.get("repsMin"), target.get("repsMax")
                    assert low is None and high is None or low is not None and high is not None and low <= high
    # Released clients require positive durations and fully numeric preview
    # targets. Keep their original endpoint usable while new clients use v2.
    legacy = load(directory / "manifest.json")
    assert legacy["schemaVersion"] == 1
    assert len(legacy["programs"]) == 8
    assert all(r == references[r["id"]] for r in legacy["exercises"])
    for entry in legacy["programs"]:
        assert entry["id"] not in SOURCED_PROGRAMS and entry["minutesPerDay"] > 0
        file = directory / entry["path"]
        assert hashlib.sha256(file.read_bytes()).hexdigest() == entry["sha256"]
        validator.validate(load(file))
    return len(entries)


def validate_published_versions(base):
    assert re.fullmatch(r"[A-Za-z0-9/_.-]+", base) and ".." not in base
    names = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", base, "--", "catalog/programs"], cwd=ROOT, text=True).splitlines()
    names.append("catalog/manifest.json")
    for name in names:
        before = subprocess.check_output(["git", "show", f"{base}:{name}"], cwd=ROOT)
        assert (ROOT / name).read_bytes() == before, f"Published version changed/deleted: {name}. Add a new version and retain the old file."


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--base", help="Git ref whose published program bytes must remain unchanged")
    args = parser.parse_args()
    if args.prepare:
        shutil.copyfile(SCHEMA, CATALOG / "schema-v1.json")
    count = validate()
    if args.base:
        validate_published_versions(args.base)
    print(f"Validated {count} programs, shared schema, exercise references and attribution.")
