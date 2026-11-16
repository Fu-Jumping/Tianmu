"""Exploratory pixel-difference probe; output is not an inference or safety gate.

Usage: python frame_change_probe.py <directory-of-extracted-frames>
Requires Pillow. Frames and ground truth are kept outside this source file.
"""
import json
import sys
from pathlib import Path
from PIL import Image, ImageChops, ImageFilter, ImageStat

PAIRS = [
    ("mapping-250", "mapping-260", "new handwriting and person movement"),
    ("mapping-260", "mapping-270", "new handwriting and person movement"),
    ("mapping-300", "mapping-310", "same board, person moves"),
    ("floor-600", "floor-610", "new lower-board formula and person movement"),
    ("running-1", "running-3", "person enters frame"),
    ("running-3", "running-5", "runner moves across frame"),
]

def image(path):
    with Image.open(path) as value:
        return value.convert("L").resize((240, 135)).filter(ImageFilter.GaussianBlur(1))

def main(directory):
    results = []
    for before, after, label in PAIRS:
        delta = ImageChops.difference(image(directory / (before + ".jpg")), image(directory / (after + ".jpg")))
        histogram = delta.histogram()
        results.append({"before": before, "after": after, "observation": label,
                        "meanAbsoluteDifference": round(ImageStat.Stat(delta).mean[0], 3),
                        "fractionPixelsAbove25": round(sum(histogram[25:]) / sum(histogram), 5)})
    print(json.dumps({"method": "grayscale 240x135, Gaussian radius 1, absolute pixel difference", "pairs": results}, indent=2))

if __name__ == "__main__":
    main(Path(sys.argv[1]))
