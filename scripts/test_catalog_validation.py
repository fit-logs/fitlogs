"""Negative fixtures exercise the CI gate without changing public files."""
import hashlib
import json
import shutil
import tempfile
import unittest
from jsonschema.exceptions import ValidationError
from pathlib import Path

from validate_catalog import CATALOG, MANIFEST, validate


class CatalogValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / "catalog"
        shutil.copytree(CATALOG, self.directory)

    def manifest(self, change):
        file = self.directory / MANIFEST
        value = json.loads(file.read_text())
        change(value)
        file.write_text(json.dumps(value))

    def program(self, change):
        manifest = json.loads((self.directory / MANIFEST).read_text())
        entry = manifest["programs"][0]
        file = self.directory / entry["path"]
        value = json.loads(file.read_text())
        change(value)
        file.write_text(json.dumps(value))
        # Update the digest so semantic checks, rather than only integrity, run.
        self.manifest(lambda m: m["programs"][0].update(sha256=hashlib.sha256(file.read_bytes()).hexdigest()))

    def rejected(self):
        with self.assertRaises((AssertionError, ValueError, KeyError, FileNotFoundError, ValidationError)):
            validate(self.directory)

    def test_original_release_and_prior_import_fixture_pass(self):
        self.assertEqual(11, validate(self.directory))

    def test_malformed_json_is_rejected(self):
        (self.directory / MANIFEST).write_text("{")
        self.rejected()

    def test_duplicate_ids_are_rejected(self):
        self.manifest(lambda m: m["programs"][1].update(id=m["programs"][0]["id"]))
        self.rejected()

    def test_unsupported_schema_is_rejected(self):
        self.program(lambda p: p.update(schemaVersion=2))
        self.rejected()

    def test_missing_exercise_reference_is_rejected(self):
        self.program(lambda p: p["days"][0]["exercises"][0].update(canonicalId="missing"))
        self.rejected()

    def test_personal_weight_is_rejected(self):
        self.program(lambda p: p["days"][0]["exercises"][0]["sets"][0].update(weight="80", unit="kg"))
        self.rejected()

    def test_reversed_rep_range_is_rejected(self):
        self.program(lambda p: p["days"][0]["exercises"][0]["sets"][0].update(repsMin=10, repsMax=6))
        self.rejected()

    def test_missing_file_is_rejected(self):
        next((self.directory / "programs").glob("*.json")).unlink()
        self.rejected()

    def test_license_drift_is_rejected(self):
        self.program(lambda p: p.update(license={"id": "unknown"}))
        self.rejected()

    def test_unpaired_superset_is_rejected(self):
        self.program(lambda p: p["days"][0]["exercises"][0].update(supersetGroup="A"))
        self.rejected()

    def test_invalid_superset_label_is_rejected(self):
        self.program(lambda p: p["days"][0]["exercises"][0].update(supersetGroup="x" * 41))
        self.rejected()

    def test_legacy_catalog_endpoint_keeps_existing_eight_entries(self):
        legacy = json.loads((self.directory / "manifest.json").read_text())
        self.assertEqual(8, len(legacy['programs']))
        self.assertTrue(all(e['minutesPerDay'] > 0 and not e['id'].startswith('ms-') for e in legacy['programs']))
        legacy['programs'].pop()
        (self.directory / "manifest.json").write_text(json.dumps(legacy))
        self.rejected()

    def test_old_version_paths_remain_valid_beside_a_new_manifest(self):
        manifest = json.loads((self.directory / MANIFEST).read_text())
        entry = manifest["programs"][0]
        previous = json.loads((self.directory / entry["path"]).read_text())
        previous["programVersion"] = "0"
        (self.directory / "programs" / f"{entry['id']}-v0.json").write_text(json.dumps(previous))
        self.assertEqual(11, validate(self.directory))
        previous["days"][0]["exercises"][0]["sets"][0].update(weight="80", unit="kg")
        (self.directory / "programs" / f"{entry['id']}-v0.json").write_text(json.dumps(previous))
        self.rejected()


class MuscleAndStrengthSourceTest(unittest.TestCase):
    """Factual table checks against the three linked articles (28 Oct 2026).

    Each row is canonical identity | warm-up reps | working reps in set order.
    'failure' deliberately has no invented rep/time count. These assertions
    catch missing exercises, variant swaps, rounded ranges and flattened sets.
    """
    TABLES = {
        "ms-spring-shred-8week": [
            """incline-barbell-bench-press|8-10,8-10|10,10,10
dumbbell-fly|10|10-12,10-12,10-12
weighted-dip|10|10-12,10-12,10-12
pec-deck|10|12,12,12
cable-crossover||12,12,12
push-up||12,12,12
hanging-leg-raise||12,12,12
decline-sit-up||15,15,15
dumbbell-side-bend||15,15,15
plank||failure,failure,failure
rope-cable-crunch||15,15,15""",
            """neutral-grip-pull-up|8-10,8-10|failure,failure,failure
barbell-row|10|10-12,10-12,10-12
reverse-grip-lat-pulldown|10|8-10,8-10,8-10
straight-arm-pulldown|10|8-10,8-10,8-10
wide-grip-seated-cable-row|10|12,12,12
rack-deadlift|10|15,15,15""",
            """barbell-upright-row|10|10,10,10
seated-dumbbell-lateral-raise|10|10,10,10
seated-smith-shoulder-press|10|10-12,10-12,10-12
front-plate-raise|10|12,12,12
rope-face-pull|10|12,12,12""",
            """ez-bar-preacher-curl|10|10-12,10-12,10-12
close-grip-bench-press|10|10-12,10-12,10-12
dumbbell-hammer-curl|10|12,12,12
overhead-rope-extension|10|12,12,12
standing-cable-curl|10|15,15,15
reverse-grip-pushdown|10|15,15,15""",
            """squat|10|8-10,8-10,8-10
leg-press|15|15,15,15
single-leg-extension|15|15,15,15
stiff-legged-deadlift|15|15,15,15
seated-leg-curl|15|15,15,15
lying-leg-curl|15|15,15,15
standing-calf-raise|20|20,20,20
seated-calf-raise|20|20,20,20""",
        ],
        "ms-lean-mass-6week": [
            """squat||10,10,10,10
machine-hack-squat||12,12,12
stiff-legged-deadlift||10,10,10,10
lying-leg-curl||12,12,12
dumbbell-lunge||8,8,8
leg-press-calf-raise||12,12,12
seated-calf-raise||12,12,12""",
            """bench-press||10,10,10,10
incline-barbell-bench-press||12,12,12
cable-crossover||12,12,12
hammer-strength-chest-press||8,8,8
barbell-curl||10,10,10,10
rope-cable-hammer-curl||12,12,12
barbell-preacher-curl||10,10,10""",
            """deadlift||10,10,10,10
barbell-row||12,12,12
lat-pulldown||8,8,8,8,8
seated-cable-row||12,12,12
pull-up||10,10,10
hyperextension||12,12,12""",
            """seated-barbell-shoulder-press||10,10,10,10
dumbbell-lateral-raise||12,12,12
dumbbell-front-raise||12,12,12
reverse-pec-deck||12,12,12
barbell-shrug||12,12,12,12
dip||10,10,10,10
seated-french-press||12,12,12""",
        ],
        "ms-mass-hypertrophy-8week": [
            """incline-barbell-bench-press||12,10,12
dumbbell-bench-press||12,10,15
cable-crossover||12,12,12
seated-dumbbell-lateral-raise||12,12,12
single-arm-cable-lateral-raise||12,12,12""",
            """barbell-row||12,10,12
dumbbell-pullover||12,10,15
wide-grip-lat-pulldown||12,12,12
dumbbell-rear-delt-fly||12,12,12
rope-face-pull||12,12,12
dumbbell-shrug||12,12,12""",
            """close-grip-bench-press||12,10,12
weighted-dip||12,10,12
rope-triceps-pressdown||12,12,12
lying-leg-raise||12,12,12
rope-cable-crunch||12,12,12
barbell-curl||12,12,12
dumbbell-hammer-curl||12,10,12
standing-cable-curl||12,12,12""",
            """deadlift||12,10,12
lying-leg-curl||12,10,12
dumbbell-walking-lunge||12,12,12
front-squat||12,10,12
leg-extension||12,12,12
dumbbell-side-lunge||12,12,12
seated-calf-raise||12,12,12
leg-press-calf-raise||12,12,12""",
        ],
    }

    def program(self, key):
        return json.loads((CATALOG / "programs" / f"{key}-v1.json").read_text())

    def test_source_metadata_and_program_lengths(self):
        expected = {
            'ms-spring-shred-8week': (8, 5, 60, 'Roger “Rock” Lockridge'),
            'ms-lean-mass-6week': (6, 4, 45, 'Josh England'),
            'ms-mass-hypertrophy-8week': (8, 4, 0, 'Roger “Rock” Lockridge'),
        }
        for key, (weeks, days, duration, author) in expected.items():
            p = self.program(key)
            self.assertEqual((weeks, days), (p['weeks'], p['daysPerWeek']))
            self.assertEqual(author, p['source']['author'])
            self.assertTrue(all(d['durationMinutes'] == duration for d in p['days']))
            self.assertIn('Muscle & Strength', p['source']['attribution'])

    @staticmethod
    def reps(target):
        low, high = target["repsMin"], target["repsMax"]
        return "failure" if low is None else str(low) if low == high else f"{low}-{high}"

    def test_every_source_table_preserves_exercise_order_variants_warmups_and_reps(self):
        for key, days in self.TABLES.items():
            with self.subTest(program=key):
                p = self.program(key)
                actual = []
                for day in p["days"]:
                    rows = []
                    for move in day["exercises"]:
                        warm = ",".join(self.reps(s) for s in move["sets"] if s["isWarmUp"])
                        work = ",".join(self.reps(s) for s in move["sets"] if not s["isWarmUp"])
                        rows.append(f"{move['canonicalId']}|{warm}|{work}")
                    actual.append("\n".join(rows))
                self.assertEqual(days, actual)

    def test_spring_rest_phases_superset_rounds_and_failure_guidance(self):
        p = self.program("ms-spring-shred-8week")
        self.assertEqual([(1, 2, 120), (3, 4, 90), (5, 6, 60), (7, 8, 45)],
                         [(r['fromWeek'], r['toWeek'], r['restSeconds']) for r in p['restByWeek']])
        pairs = [[(4, 5), (7, 8)], [(2, 3)], [(0, 1)], [(4, 5)], [(3, 4), (6, 7)]]
        warm_count = 0
        for day, expected in zip(p['days'], pairs):
            moves = day['exercises']
            for a, b in expected:
                self.assertEqual(moves[a]['supersetGroup'], moves[b]['supersetGroup'])
                self.assertTrue(all(s['restSeconds'] == 0 for s in moves[a]['sets'] if not s['isWarmUp']))
            for move in moves:
                warm_count += sum(s['isWarmUp'] for s in move['sets'])
                self.assertIn('weeks 7–8 = 45s', move['notes'])
                if not move.get('supersetGroup') and move['canonicalId'] != 'plank':
                    self.assertIn('Final working set:', move['notes'])
        self.assertEqual(31, warm_count)
        self.assertIn('3–4 times/week', p['notes'])

    def test_hypertrophy_final_set_symbols_and_source_gaps(self):
        p = self.program('ms-mass-hypertrophy-8week')
        expected_methods = [
            ['*', '+', '^', None, None],
            ['*', '+', '^', None, None, None],
            ['*', '+', '^', None, None, '*', '+', '^'],
            ['*', '+', None, '*', '+', None, '^', '^'],
        ]
        for day, methods in zip(p['days'], expected_methods):
            self.assertEqual(0, day['durationMinutes'])
            for move, symbol in zip(day['exercises'], methods):
                self.assertTrue(all(s['restSeconds'] == 90 for s in move['sets']))
                if symbol:
                    self.assertIn(f'Final working set ({symbol})', move['notes'])
                else:
                    self.assertEqual('', move['notes'])
        self.assertIn('3–5s negatives', p['notes'])
        self.assertIn('Wednesday off', p['schedule'])
        self.assertIn('3–4 times/week', p['notes'])

    def test_lean_mass_uses_source_recommended_rest_and_tempo_without_fake_effort(self):
        p = self.program('ms-lean-mass-6week')
        self.assertEqual(6, p['weeks'])
        self.assertIn('deload', p['progression'])
        for day in p['days']:
            for move in day['exercises']:
                self.assertIn('2/0/2', move['notes'])
                self.assertIn('30–60s', move['notes'])
                self.assertTrue(all(s['restSeconds'] == 45 for s in move['sets']))
        for key in self.TABLES:
            for day in self.program(key)['days']:
                for move in day['exercises']:
                    self.assertTrue(all(s.get('rir') is None and s.get('rpe') is None and s.get('weight') is None for s in move['sets']))


if __name__ == "__main__":
    unittest.main()
