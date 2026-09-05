#!/usr/bin/env python3
"""PC-only SFace impostor benchmark for a local one-image-per-person collection.

Pipeline exactly follows OpenCV's public API contract:
FaceDetectorYN.detect(image) -> FaceRecognizerSF.alignCrop(image, faceRow) ->
FaceRecognizerSF.feature(aligned) -> FaceRecognizerSF.match(..., FR_COSINE).

No images or feature vectors are written. The output contains anonymous P/F identifiers only.
"""
import argparse
import json
import math
from collections import Counter, defaultdict
from pathlib import Path

import cv2 as cv
import numpy as np


THRESHOLDS = (0.15, 0.20, 0.25, 0.30, 0.35, 0.363, 0.40, 0.45, 0.50, 0.55, 0.60)


def percentile(values, p):
    return float(np.percentile(np.asarray(values, dtype=np.float64), p))


def statistics(values):
    array = np.asarray(values, dtype=np.float64)
    result = {
        "count": int(array.size), "minimum": float(array.min()), "mean": float(array.mean()),
        "median": float(np.median(array)), "standard_deviation": float(array.std()),
        "p90": percentile(values, 90), "p95": percentile(values, 95), "p99": percentile(values, 99),
        "p99_5": percentile(values, 99.5), "maximum": float(array.max()),
    }
    if array.size >= 1000:
        result["p99_9"] = percentile(values, 99.9)
    return result


def low_fmr_threshold(scores, rate):
    """Smallest threshold whose observed FMR is at most `rate`."""
    descending = sorted(scores, reverse=True)
    allowed = math.floor(len(descending) * rate)
    if allowed == 0:
        return {"estimated": False, "threshold": math.nextafter(descending[0], math.inf), "accepted_pairs": 0,
                "total_pairs": len(descending), "fmr_percent": 0.0, "note": "No observed false matches; threshold is above observed maximum."}
    # Move just above the boundary score. This intentionally rejects all tied boundary scores
    # so the reported observed FMR is truly <= the requested rate.
    threshold = math.nextafter(descending[allowed - 1], math.inf)
    accepted = sum(score >= threshold for score in scores)
    return {"estimated": True, "threshold": float(threshold), "accepted_pairs": accepted, "total_pairs": len(descending),
            "fmr_percent": 100.0 * accepted / len(descending)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--yunet", required=True, type=Path)
    parser.add_argument("--sface", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    for path in (args.input, args.yunet, args.sface):
        if not path.exists():
            parser.error(f"Missing path: {path}")

    detector = cv.FaceDetectorYN.create(str(args.yunet), "", (320, 320), 0.80, 0.30, 5000)
    recognizer = cv.FaceRecognizerSF.create(str(args.sface), "")
    image_paths = sorted(path for path in args.input.rglob("*") if path.is_file() and path.suffix.lower() in (".jpg", ".jpeg", ".png"))
    categories = Counter()
    failure_ids = defaultdict(list)
    features = []

    for ordinal, path in enumerate(image_paths, start=1):
        anonymous_id = f"P{ordinal:04d}"
        if path.stat().st_size == 0:
            categories["ZERO_BYTE"] += 1
            failure_ids["ZERO_BYTE"].append(anonymous_id)
            continue
        image = cv.imread(str(path))
        if image is None:
            categories["DECODE_FAILED"] += 1
            failure_ids["DECODE_FAILED"].append(anonymous_id)
            continue
        try:
            detector.setInputSize((image.shape[1], image.shape[0]))
            _, faces = detector.detect(image)
            count = 0 if faces is None else len(faces)
            if count == 0:
                categories["NO_FACE"] += 1
                failure_ids["NO_FACE"].append(anonymous_id)
                continue
            if count != 1:
                categories["MULTIPLE_FACES"] += 1
                failure_ids["MULTIPLE_FACES"].append(anonymous_id)
                continue
            aligned = recognizer.alignCrop(image, faces[0])
            if aligned is None or aligned.size == 0:
                categories["ALIGNMENT_FAILED"] += 1
                failure_ids["ALIGNMENT_FAILED"].append(anonymous_id)
                continue
            features.append((anonymous_id, recognizer.feature(aligned).copy()))
            categories["VALID_SINGLE_FACE"] += 1
        except cv.error:
            categories["OTHER_ERROR"] += 1
            failure_ids["OTHER_ERROR"].append(anonymous_id)

    scores, pairs = [], []
    for index, (left_id, left_feature) in enumerate(features):
        for right_id, right_feature in features[index + 1:]:
            score = float(recognizer.match(left_feature, right_feature, cv.FaceRecognizerSF_FR_COSINE))
            scores.append(score)
            pairs.append((score, left_id, right_id))

    if not scores:
        raise RuntimeError("No valid different-person pairs could be scored.")
    threshold_rows = []
    for threshold in THRESHOLDS:
        accepted = sum(score >= threshold for score in scores)
        threshold_rows.append({"threshold": threshold, "accepted_impostor_pairs": accepted, "total_pairs": len(scores),
                               "false_match_rate_percent": 100.0 * accepted / len(scores)})
    report = {
        "benchmark": "BETTERHR one-image-per-person impostor-only benchmark",
        "pipeline": "FaceDetectorYN.detect -> FaceRecognizerSF.alignCrop -> FaceRecognizerSF.feature -> FaceRecognizerSF.match(FR_COSINE)",
        "identity_assumption": "Each VALID_SINGLE_FACE image is one distinct physical person; no genuine distribution is inferred.",
        "input_counts": {"candidate_images": len(image_paths), **dict(categories)},
        "failure_ids": dict(failure_ids),
        "anonymous_distinct_identities": len(features),
        "different_person_comparisons": len(scores),
        "impostor_score_statistics": statistics(scores),
        "top_20_highest_impostor_pairs": [
            {"left": left, "right": right, "cosine_similarity": score}
            for score, left, right in sorted(pairs, reverse=True)[:20]
        ],
        "threshold_false_match_rates": threshold_rows,
        "low_fmr_operating_points": {
            "fmr_lte_5_percent": low_fmr_threshold(scores, 0.05),
            "fmr_lte_1_percent": low_fmr_threshold(scores, 0.01),
            "fmr_lte_0_5_percent": low_fmr_threshold(scores, 0.005),
            "fmr_lte_0_1_percent": low_fmr_threshold(scores, 0.001),
        },
        "raw_images_written": False,
        "feature_vectors_written": False,
    }
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"valid_identities": len(features), "comparisons": len(scores), "statistics": report["impostor_score_statistics"]}, indent=2))


if __name__ == "__main__":
    main()
