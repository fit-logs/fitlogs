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
CATALOG = ROOT / "web/public/catalog"
SCHEMA = ROOT / "data/programs/schema-v1.json"


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
    manifest = load(directory / "manifest.json")
    assert manifest["schemaVersion"] == 1
    assert re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", manifest["catalogVersion"])
    assert manifest["license"]["id"] == "CC-BY-4.0"
    assert manifest["license"]["attribution"]
    refs = load(directory / "exercises.json")["exercises"]
    assert refs == manifest["exercises"], "Manifest/exercise reference drift"
    references = {e["id"]: e for e in refs}
    assert len(references) == len(refs) and len({e["name"] for e in refs}) == len(refs)
    entries = manifest["programs"]
    assert len(entries) == 8 and len({e["id"] for e in entries}) == len(entries)
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
        assert p["license"] == manifest["license"] and e["license"] == "CC-BY-4.0"
        assert p["source"]["author"] and p["source"]["attribution"] and p["source"]["url"].startswith("https://")
        assert p["schedule"] and p["progression"] and p["notes"]
        assert not {"workouts", "actualSets", "userId", "history"}.intersection(p)
        total = 0
        for d in p["days"]:
            assert d["durationMinutes"] == e["minutesPerDay"] and 15 <= e["minutesPerDay"] <= 90
            assert len({m["canonicalId"] for m in d["exercises"]}) == len(d["exercises"])
            for m in d["exercises"]:
                reference = references[m["canonicalId"]]
                assert reference["name"] == m["name"] and reference["equipment"] in p["equipment"]
                assert all(s in {r["name"] for r in refs} for s in m.get("substitutions", []))
                assert 1 <= len(m["sets"]) <= 4
                for s in m["sets"]:
                    assert 3 <= s["repsMin"] <= s["repsMax"] <= 15
                    assert 60 <= s["restSeconds"] <= 300 and 2 <= s["rir"] <= 4
                    assert s.get("weight") is None and s.get("unit") is None
                    assert s["loadMode"] == "external" and not s.get("isWarmUp", False)
                    total += 1
        assert 12 <= total <= 80, (e["id"], total)
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
        assert archived["license"] == manifest["license"]
        for day in archived["days"]:
            for move in day["exercises"]:
                assert references[move["canonicalId"]]["name"] == move["name"]
                for target in move["sets"]:
                    assert target.get("weight") is None and target.get("unit") is None
                    assert target["repsMin"] <= target["repsMax"]
    return len(entries)


def validate_published_versions(base):
    assert re.fullmatch(r"[A-Za-z0-9/_.-]+", base) and ".." not in base
    names = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", base, "--", "web/public/catalog/programs"], cwd=ROOT, text=True).splitlines()
    for name in names:
        before = subprocess.check_output(["git", "show", f"{base}:{name}"], cwd=ROOT)
        assert (ROOT / name).read_bytes() == before, f"Published version changed/deleted: {name}. Add a new version and retain the old file."


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--export", action="store_true")
    parser.add_argument("--base", help="Git ref whose published program bytes must remain unchanged")
    args = parser.parse_args()
    if args.prepare:
        shutil.copyfile(SCHEMA, CATALOG / "schema-v1.json")
    count = validate()
    if args.base:
        validate_published_versions(args.base)
    if args.export:
        exported = ROOT / "web/out/catalog"
        validate(exported)
        for p in CATALOG.rglob("*"):
            if p.is_file():
                assert (exported / p.relative_to(CATALOG)).read_bytes() == p.read_bytes(), p
        assert (exported / "schema-v1.json").read_bytes() == SCHEMA.read_bytes()
    print(f"Validated {count} original programs, shared schema, exercise references and licensing.")
