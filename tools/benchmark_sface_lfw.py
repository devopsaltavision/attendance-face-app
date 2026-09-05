#!/usr/bin/env python3
"""Evaluate the OpenCV YuNet -> alignCrop -> SFace -> FR_COSINE pipeline on local LFW.

This script never downloads data and writes only aggregate scores/metrics (no images or feature
vectors). Obtain LFW yourself, review its terms, then explicitly acknowledge that review with
--terms-verified. The expected pair-file format is the official LFW `pairs.txt` format.

Example (Windows):
  py tools/benchmark_sface_lfw.py --terms-verified ^
    --lfw-root D:\datasets\lfw_funneled --pairs D:\datasets\pairs.txt ^
    --yunet app\src\main\assets\face_detection_yunet_2023mar.onnx ^
    --sface app\src\main\assets\face_recognition_sface_2021dec.onnx ^
    --output .tmp\lfw-sface-report.json
"""
import argparse
import json
from pathlib import Path

import cv2 as cv
import numpy as np


def parse_pairs(path: Path):
    lines = [line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if lines and len(lines[0].split()) in (1, 2):
        lines = lines[1:]
    for line in lines:
        fields = line.split()
        if len(fields) == 3:
            name, first, second = fields
            yield True, name, int(first), name, int(second)
        elif len(fields) == 4:
            first_name, first, second_name, second = fields
            yield False, first_name, int(first), second_name, int(second)
        else:
            raise ValueError(f"Unsupported pairs line: {line!r}")


def image_path(root: Path, name: str, number: int) -> Path:
    return root / name / f"{name}_{number:04d}.jpg"


def feature_for(image_path_: Path, detector, recognizer):
    image = cv.imread(str(image_path_))
    if image is None:
        return None, "image_unavailable"
    detector.setInputSize((image.shape[1], image.shape[0]))
    _, faces = detector.detect(image)
    if faces is None or len(faces) != 1:
        return None, "not_exactly_one_face"
    aligned = recognizer.alignCrop(image, faces[0])
    if aligned is None or aligned.size == 0:
        return None, "align_crop_failed"
    feature = recognizer.feature(aligned)
    return feature.copy(), None


def stats(values):
    a = np.asarray(values, dtype=np.float64)
    return {"count": int(a.size), "min": float(a.min()), "mean": float(a.mean()), "max": float(a.max()), "stddev": float(a.std())}


def roc(same, different):
    scores = np.concatenate((np.asarray(same), np.asarray(different)))
    thresholds = np.unique(scores)
    points = []
    for threshold in thresholds:
        tpr = float(np.mean(np.asarray(same) >= threshold))
        fmr = float(np.mean(np.asarray(different) >= threshold))
        fnmr = 1.0 - tpr
        points.append({"threshold": float(threshold), "true_match_rate": tpr, "false_match_rate": fmr, "false_non_match_rate": fnmr})
    best = max(points, key=lambda p: p["true_match_rate"] - p["false_match_rate"])
    eer = min(points, key=lambda p: abs(p["false_match_rate"] - p["false_non_match_rate"]))
    return {"operating_points": points, "youden_best": best, "approximate_eer": eer}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lfw-root", required=True, type=Path)
    parser.add_argument("--pairs", required=True, type=Path)
    parser.add_argument("--yunet", required=True, type=Path)
    parser.add_argument("--sface", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--terms-verified", action="store_true", help="Required acknowledgement; this script does not verify or download LFW.")
    args = parser.parse_args()
    if not args.terms_verified:
        parser.error("Review the dataset terms first, then pass --terms-verified.")
    for path in (args.lfw_root, args.pairs, args.yunet, args.sface):
        if not path.exists():
            parser.error(f"Missing path: {path}")

    detector = cv.FaceDetectorYN.create(str(args.yunet), "", (320, 320), 0.9, 0.3, 5000)
    recognizer = cv.FaceRecognizerSF.create(str(args.sface), "")
    cache = {}
    failures = {}

    def get(name, number):
        key = (name, number)
        if key not in cache:
            cache[key] = feature_for(image_path(args.lfw_root, name, number), detector, recognizer)
        return cache[key]

    same, different = [], []
    requested = 0
    for is_same, name_a, index_a, name_b, index_b in parse_pairs(args.pairs):
        requested += 1
        feature_a, error_a = get(name_a, index_a)
        feature_b, error_b = get(name_b, index_b)
        if error_a or error_b:
            for error in (error_a, error_b):
                if error:
                    failures[error] = failures.get(error, 0) + 1
            continue
        score = float(recognizer.match(feature_a, feature_b, cv.FaceRecognizerSF_FR_COSINE))
        (same if is_same else different).append(score)

    if not same or not different:
        raise RuntimeError("No valid same and different pairs were produced.")
    report = {
        "pipeline": "YuNet -> FaceRecognizerSF.alignCrop -> FaceRecognizerSF.feature -> FaceRecognizerSF.match(FR_COSINE)",
        "dataset_downloaded_by_script": False,
        "terms_verified_by_operator": True,
        "pairs_requested": requested,
        "pairs_scored": len(same) + len(different),
        "pair_failures": failures,
        "same_person": stats(same),
        "different_person": stats(different),
        "roc": roc(same, different),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("pairs_requested", "pairs_scored", "pair_failures", "same_person", "different_person")}, indent=2))


if __name__ == "__main__":
    main()
