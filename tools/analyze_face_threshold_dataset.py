#!/usr/bin/env python3
"""Gallery/query calibration over human-reviewed BetterHR identity groups.

Reuses the exact PC benchmark pipeline from benchmark_betterhr_sface.py.
Only anonymous P/G identifiers and numeric scores are written.
"""
import hashlib
import json
from collections import Counter
from pathlib import Path

import cv2 as cv
import numpy as np


ROOT = Path(__file__).resolve().parents[1]
TMP = ROOT / ".tmp"
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png"}


def percentile(values, point):
    return float(np.percentile(np.asarray(values, dtype=np.float64), point))


def stats(values, points):
    if not values:
        return {"count": 0}
    values = [float(value) for value in values]
    return {
        "count": len(values), "minimum": min(values), "mean": float(np.mean(values)),
        "median": float(np.median(values)), "maximum": max(values),
        **{f"p{str(point).replace('.', '_')}": percentile(values, point) for point in points},
    }


def main():
    groups = json.loads((TMP / "betterhr-identity-groups.json").read_text(encoding="utf-8"))
    old = json.loads((TMP / "betterhr-sface-benchmark.json").read_text(encoding="utf-8"))
    paths = sorted(path for path in (TMP / "face-database").rglob("*")
                   if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS)
    path_by_id = {f"P{index:04d}": path for index, path in enumerate(paths, 1)}
    invalid = {identity for entries in old["failure_ids"].values() for identity in entries}
    valid = set(path_by_id) - invalid
    member_to_group = {member: group for group, members in groups.items() for member in members}
    group_members = {group: sorted(member for member in members if member in valid)
                     for group, members in groups.items()}
    eligible = {group: members for group, members in group_members.items() if len(members) >= 2}

    hashes = {}
    for identity, path in path_by_id.items():
        if path.stat().st_size:
            hashes.setdefault(hashlib.sha256(path.read_bytes()).hexdigest(), []).append(identity)
    exact_duplicates = [sorted(ids) for ids in hashes.values() if len(ids) > 1]
    labelled_screenshots = sorted(path.name for path in TMP.rglob("*") if path.is_file()
                                 and path.suffix.lower() in IMAGE_EXTENSIONS
                                 and path.name.lower().startswith("e"))
    audit = {
        "source": "human-reviewed BetterHR groups plus local .tmp audit",
        "raw_face_database_images": len(paths),
        "usable_raw_face_database_images": len(valid),
        "pipeline_validity_source": "betterhr-sface-benchmark.json VALID_SINGLE_FACE",
        "human_reviewed_identity_groups": len(groups),
        "usable_reviewed_identity_groups": len(eligible),
        "reviewed_group_members": sum(len(members) for members in group_members.values()),
        "unassigned_usable_raw_images": len(valid - set(member_to_group)),
        "employee_labelled_tmp_images": labelled_screenshots,
        "employee_labelled_images_used": False,
        "employee_labelled_images_reason": "HF-X05 UI screenshots, not independently labelled raw gallery/query images",
        "exact_duplicate_groups": exact_duplicates,
        "near_duplicates": old.get("visual_near_duplicates", []),
        "unsafe_to_assign": "usable raw images outside human-reviewed groups; employee-labelled screenshots",
        "no_features": True,
    }
    (TMP / "face-threshold-dataset-audit.json").write_text(json.dumps(audit, indent=2), encoding="utf-8")

    # Every fifth reviewed identity is fully held out as UNKNOWN.  Remaining
    # groups contribute one gallery image and different query images.
    ordered = sorted(eligible.items())
    unknown_groups = {group for index, (group, _) in enumerate(ordered) if index % 5 == 0}
    gallery, queries = [], []
    for group, members in ordered:
        if group in unknown_groups:
            queries.extend({"image": member, "groundTruthType": "UNKNOWN", "trueIdentity": None}
                           for member in members)
        else:
            gallery.append({"identity": group, "image": members[0]})
            queries.extend({"image": member, "groundTruthType": "KNOWN", "trueIdentity": group}
                           for member in members[1:])
    manifest = {
        "dataset_version": 1,
        "identity_source": "betterhr-identity-groups.json human-reviewed groups",
        "pipeline": "FaceDetectorYN.detect -> FaceRecognizerSF.alignCrop -> FaceRecognizerSF.feature -> FaceRecognizerSF.match(FR_COSINE)",
        "gallery": gallery, "queries": queries,
        "ambiguous_queries": [],
        "ambiguous_reason": "No human-labelled E338/E247 (or other deliberate duplicate-identity) raw images available.",
        "unclassified_count": len(valid - set(member_to_group)),
        "same_image_in_gallery_and_query": False,
        "no_features": True,
    }
    (TMP / "face-threshold-dataset.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    yunet = ROOT / "app/src/main/assets/face_models/face_detection_yunet_2023mar.onnx"
    sface = ROOT / "app/src/main/assets/face_models/face_recognition_sface_2021dec.onnx"
    detector = cv.FaceDetectorYN.create(str(yunet), "", (320, 320), .80, .30, 5000)
    recognizer = cv.FaceRecognizerSF.create(str(sface), "")
    features = {}
    required = {row["image"] for row in gallery} | {row["image"] for row in queries}
    for image_id in sorted(required):
        image = cv.imread(str(path_by_id[image_id]))
        detector.setInputSize((image.shape[1], image.shape[0]))
        _, faces = detector.detect(image)
        if faces is None or len(faces) != 1:
            raise RuntimeError(f"Benchmark validity changed for {image_id}")
        aligned = recognizer.alignCrop(image, faces[0])
        if aligned is None or aligned.size == 0:
            raise RuntimeError(f"Alignment failed for {image_id}")
        features[image_id] = recognizer.feature(aligned).copy()

    gallery_features = [(entry["identity"], entry["image"], features[entry["image"]]) for entry in gallery]
    events = []
    for query in queries:
        ranked = sorted(((float(recognizer.match(features[query["image"]], feature, cv.FaceRecognizerSF_FR_COSINE)), identity)
                         for identity, _, feature in gallery_features), reverse=True)
        top_score, top_identity = ranked[0]
        second_score, second_identity = (ranked[1] if len(ranked) > 1 else (None, None))
        correct = next((score for score, identity in ranked if identity == query["trueIdentity"]), None)
        kind = query["groundTruthType"]
        classification = "UNKNOWN" if kind == "UNKNOWN" else ("GENUINE_TOP1" if top_identity == query["trueIdentity"] else "MISIDENTIFIED")
        events.append({"groundTruthType": kind, "trueIdentity": query["trueIdentity"], "topIdentity": top_identity,
                       "topScore": top_score, "secondIdentity": second_identity, "secondScore": second_score,
                       "margin": None if second_score is None else top_score - second_score,
                       "correctIdentityScore": correct, "classification": classification})
    genuine = [event for event in events if event["classification"] == "GENUINE_TOP1"]
    wrong = [event for event in events if event["classification"] == "MISIDENTIFIED"]
    unknown = [event for event in events if event["classification"] == "UNKNOWN"]
    result = {
        "dataset_version": 1, "pipeline": manifest["pipeline"], "gallery_count": len(gallery),
        "events": events, "statistics": {
            "known_genuine_top1": stats([event["topScore"] for event in genuine], (5, 10)),
            "known_misidentified_top_score": stats([event["topScore"] for event in wrong], ()),
            "unknown_top_score": stats([event["topScore"] for event in unknown], (90, 95, 99)),
            "genuine_margin": stats([event["margin"] for event in genuine if event["margin"] is not None], (5, 10)),
            "ambiguous_margin": {"count": 0},
        },
        "classification_counts": dict(Counter(event["classification"] for event in events)),
        "threshold_search_performed": False,
        "threshold_search_reason": "No deliberate ambiguous duplicate-identity queries and no HF-X05 employee-labelled gallery/query set.",
        "no_features": True,
    }
    (TMP / "face-threshold-results.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({"gallery": len(gallery), "queries": len(queries), "counts": result["classification_counts"]}, indent=2))


if __name__ == "__main__":
    main()
