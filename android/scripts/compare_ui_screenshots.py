#!/usr/bin/env python3
"""Compare stable emulator scenes with reviewed PNGs (requires Pillow).

System bars are excluded. App content is compared at native resolution; differing
screens, missing files and larger changes fail instead of updating the baseline.
"""
import argparse
import json
from pathlib import Path
from PIL import Image, ImageChops

SCENES = ("train-empty", "plans-empty", "plans-list", "plan-detail", "history-empty", "progress-empty")


def compare(reference, candidate):
    results = []
    for scene in SCENES:
        a_path, b_path = reference / f"{scene}.png", candidate / f"{scene}.png"
        if not a_path.is_file() or not b_path.is_file():
            results.append({"scene": scene, "passed": False, "reason": "missing screenshot"})
            continue
        with Image.open(a_path) as a, Image.open(b_path) as b:
            if a.size != b.size or a.size != (1080, 2400):
                results.append({"scene": scene, "passed": False, "reason": "expected 1080 x 2400"})
                continue
            # API 37 fixture at 420 dpi: exclude status/navigation bars only.
            bounds = (0, 84, 1080, 2352)
            difference = ImageChops.difference(a.convert("RGB").crop(bounds), b.convert("RGB").crop(bounds))
            red, green, blue = difference.split()
            maximum = ImageChops.lighter(ImageChops.lighter(red, green), blue)
            changed = sum(maximum.histogram()[9:])
            fraction = changed / (difference.width * difference.height)
            results.append({"scene": scene, "changed_fraction": round(fraction, 6), "passed": fraction <= 0.002})
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("candidate", type=Path)
    args = parser.parse_args()
    results = compare(args.reference, args.candidate)
    print(json.dumps(results, indent=2))
    raise SystemExit(0 if all(row["passed"] for row in results) else 1)


if __name__ == "__main__":
    main()
