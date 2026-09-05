#!/usr/bin/env python3
"""Generate a local, label-only BetterHR SFace summary after human review."""
import argparse
import csv
import json
from pathlib import Path

import numpy as np

THRESHOLDS = (.20, .25, .30, .35, .363, .40, .45, .50, .55, .60, .70, .80, .90, .95)


def canonical(left, right): return tuple(sorted((left, right)))


def stats(values):
    array = np.asarray(values, dtype=np.float64)
    if not len(array): return {"count": 0}
    result = {"count": int(len(array)), "minimum": float(array.min()), "mean": float(array.mean()), "median": float(np.median(array)), "p90": float(np.percentile(array, 90)), "p95": float(np.percentile(array, 95)), "p99": float(np.percentile(array, 99)), "maximum": float(array.max())}
    return result


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument("--audit", type=Path, default=root / ".tmp" / "betterhr-dataset-audit.json")
    parser.add_argument("--labels", type=Path, default=root / ".tmp" / "betterhr-pair-labels.csv")
    parser.add_argument("--output", type=Path, default=root / ".tmp" / "betterhr-human-reviewed-benchmark.json")
    args = parser.parse_args()
    audit = json.loads(args.audit.read_text(encoding="utf-8"))
    scores = {canonical(row["left"], row["right"]): float(row["sface_cosine"]) for row in audit["top_100_image_diagnostics"]}
    labels = {}
    with args.labels.open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            label = row.get("label", "").strip().upper(); key = canonical(row.get("left_id", ""), row.get("right_id", ""))
            if label in {"SAME", "DIFFERENT", "UNKNOWN"} and key in scores: labels[key] = label
    confirmed = {label: [scores[key] for key, value in labels.items() if value == label] for label in ("SAME", "DIFFERENT", "UNKNOWN")}
    if not confirmed["SAME"] and not confirmed["DIFFERENT"]:
        raise SystemExit("No confirmed SAME or DIFFERENT labels yet; no output was written.")
    different = confirmed["DIFFERENT"]
    report = {"source": "Human labels for the suspicious top-100 SFace pairs only", "thresholds_are_not_android_production_thresholds": True,
              "label_counts": {label: len(values) for label, values in confirmed.items()}, "unreviewed_suspicious_pairs": 100 - len(labels),
              "confirmed_different_statistics": stats(different), "confirmed_same_statistics": stats(confirmed["SAME"]),
              "confirmed_different_threshold_false_match_rates": [{"threshold": threshold, "accepted_pairs": sum(score >= threshold for score in different), "total_pairs": len(different), "false_match_rate_percent": (100 * sum(score >= threshold for score in different) / len(different)) if different else None} for threshold in THRESHOLDS],
              "high_risk_sface_collisions": [{"left": left, "right": right, "score": score} for (left, right), score in scores.items() if labels.get((left, right)) == "DIFFERENT" and score >= .90],
              "raw_images_written": False, "feature_vectors_written": False}
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(args.output), "labels": report["label_counts"], "high_risk_confirmed_different": len(report["high_risk_sface_collisions"])}, indent=2))


if __name__ == "__main__": main()
