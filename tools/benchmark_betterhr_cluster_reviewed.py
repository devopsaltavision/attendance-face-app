#!/usr/bin/env python3
"""PC-only SFace benchmark from local human-confirmed BetterHR identity groups."""
import argparse
import csv
import json
import math
from itertools import combinations
from pathlib import Path

import cv2 as cv
import numpy as np

THRESHOLDS = (.30, .35, .363, .40, .45, .50, .55, .60, .65, .70, .75, .80, .85, .90)


def pair_key(left, right): return tuple(sorted((left, right)))


def stats(scores, percentiles):
    values = np.asarray(scores, dtype=np.float64)
    result = {'count': int(len(values))}
    if not len(values): return result
    result.update({'minimum': float(values.min()), 'mean': float(values.mean()), 'median': float(np.median(values)), 'standard_deviation': float(values.std()), 'maximum': float(values.max())})
    result.update({f'p{str(point).replace(".", "_")}': float(np.percentile(values, point)) for point in percentiles})
    return result


def rates(threshold, genuine, impostor):
    fnmr = sum(score < threshold for score, *_ in genuine) / len(genuine)
    fmr = sum(score >= threshold for score, *_ in impostor) / len(impostor)
    return fmr, fnmr, 1 - fnmr


def operating_point(rate, genuine, impostor):
    descending = sorted((score for score, *_ in impostor), reverse=True)
    allowed = math.floor(len(descending) * rate)
    threshold = math.nextafter(descending[0], math.inf) if allowed == 0 else math.nextafter(descending[allowed - 1], math.inf)
    fmr, fnmr, _ = rates(threshold, genuine, impostor)
    return {'target_fmr_percent': rate * 100, 'threshold': threshold, 'observed_fmr_percent': fmr * 100, 'observed_fnmr_percent': fnmr * 100, 'impostor_comparisons': len(impostor), 'sufficient_for_rate_estimate': len(impostor) * rate >= 10}


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument('--groups', type=Path, default=root / '.tmp' / 'betterhr-identity-groups.json')
    parser.add_argument('--state', type=Path, default=root / '.tmp' / 'betterhr-cluster-review-state.json')
    parser.add_argument('--labels', type=Path, default=root / '.tmp' / 'betterhr-pair-labels.csv')
    parser.add_argument('--benchmark', type=Path, default=root / '.tmp' / 'betterhr-sface-benchmark.json')
    parser.add_argument('--input', type=Path, default=root / '.tmp' / 'face-database')
    parser.add_argument('--yunet', type=Path, default=root / 'app' / 'src' / 'main' / 'assets' / 'face_models' / 'face_detection_yunet_2023mar.onnx')
    parser.add_argument('--sface', type=Path, default=root / 'app' / 'src' / 'main' / 'assets' / 'face_models' / 'face_recognition_sface_2021dec.onnx')
    parser.add_argument('--output', type=Path, default=root / '.tmp' / 'betterhr-cluster-reviewed-sface-benchmark.json')
    args = parser.parse_args()
    groups, state = json.loads(args.groups.read_text(encoding='utf-8')), json.loads(args.state.read_text(encoding='utf-8'))
    if not groups: raise SystemExit('No confirmed identity groups; no output was written.')
    if any(value not in {'ALL_SAME', 'MIXED', 'UNKNOWN'} for value in state.values()): raise SystemExit('Unexpected cluster review state.')
    different, same = set(), set()
    with args.labels.open(newline='', encoding='utf-8') as stream:
        for row in csv.DictReader(stream):
            label = row.get('label', '').strip().upper()
            if label == 'DIFFERENT': different.add(pair_key(row['left_id'], row['right_id']))
            if label == 'SAME': same.add(pair_key(row['left_id'], row['right_id']))
    conflicts = []
    for group_id, members in groups.items():
        for left, right in combinations(members, 2):
            if pair_key(left, right) in different: conflicts.append({'group': group_id, 'left': left, 'right': right})
    if conflicts: raise SystemExit('Confirmed group contains DIFFERENT label: ' + json.dumps(conflicts))
    member_group = {member: group_id for group_id, members in groups.items() for member in members}
    cross_group_same = [{'left': left, 'right': right, 'left_group': member_group[left], 'right_group': member_group[right]}
                        for left, right in same if left in member_group and right in member_group and member_group[left] != member_group[right]]
    conflicted_groups = {entry['left_group'] for entry in cross_group_same} | {entry['right_group'] for entry in cross_group_same}
    original = json.loads(args.benchmark.read_text(encoding='utf-8'))
    failures = {identity for entries in original['failure_ids'].values() for identity in entries}
    paths = sorted(path for path in args.input.rglob('*') if path.is_file() and path.suffix.lower() in {'.jpg', '.jpeg', '.png'})
    path_by_id = {f'P{index:04d}': path for index, path in enumerate(paths, 1)}
    confirmed_members = {member for members in groups.values() for member in members}
    valid_ids = set(path_by_id) - failures
    if not confirmed_members <= valid_ids: raise SystemExit('Group contains non-valid image ID.')
    provisional = sorted(valid_ids - confirmed_members)
    needed = confirmed_members | set(provisional)
    detector = cv.FaceDetectorYN.create(str(args.yunet), '', (320, 320), .80, .30, 5000)
    recognizer = cv.FaceRecognizerSF.create(str(args.sface), '')
    features = {}
    for identity in sorted(needed):
        image = cv.imread(str(path_by_id[identity])); detector.setInputSize((image.shape[1], image.shape[0])); _, faces = detector.detect(image)
        if faces is None or len(faces) != 1: raise RuntimeError(f'Image no longer has exactly one face: {identity}')
        aligned = recognizer.alignCrop(image, faces[0])
        if aligned is None or aligned.size == 0: raise RuntimeError(f'Could not align: {identity}')
        raw = recognizer.feature(aligned).copy()
        flat = raw.reshape(-1).astype(np.float64)
        features[identity] = (raw, flat / np.linalg.norm(flat))
    # FR_COSINE is normalized dot product.  Validate this equivalent vectorized
    # calculation against OpenCV before using it for the large all-pairs set.
    validation_pairs = list(combinations(sorted(features), 2))[:3]
    validation_error = max((abs(float(recognizer.match(features[left][0], features[right][0], cv.FaceRecognizerSF_FR_COSINE)) - float(np.dot(features[left][1], features[right][1]))) for left, right in validation_pairs), default=0.0)
    if validation_error > 1e-6: raise RuntimeError(f'FR_COSINE validation failed: {validation_error}')
    def score(left, right): return float(np.dot(features[left][1], features[right][1]))
    genuine = [(score(left, right), left, right) for members in groups.values() for left, right in combinations(sorted(members), 2)]
    group_items = sorted(groups.items())
    high_confidence_group_items = [(group_id, members) for group_id, members in group_items if group_id not in conflicted_groups]
    impostor = [(score(left, right), left, right, group_a, group_b) for index, (group_a, a_members) in enumerate(high_confidence_group_items) for group_b, b_members in high_confidence_group_items[index + 1:] for left in a_members for right in b_members]
    provisional_impostor = [(score(left, right), left, right, group_id) for group_id, members in high_confidence_group_items for left in members for right in provisional]
    genuine.sort(); impostor.sort(reverse=True); provisional_impostor.sort(reverse=True)
    threshold_rows = []
    for threshold in THRESHOLDS:
        fmr, fnmr, tmr = rates(threshold, genuine, impostor)
        threshold_rows.append({'threshold': threshold, 'fmr_percent': fmr * 100, 'fnmr_percent': fnmr * 100, 'tmr_percent': tmr * 100})
    candidate_thresholds = sorted({score for score, *_ in genuine + impostor} | {0.0, 1.0})
    eer_threshold = min(candidate_thresholds, key=lambda threshold: abs(rates(threshold, genuine, impostor)[0] - rates(threshold, genuine, impostor)[1]))
    eer_fmr, eer_fnmr, _ = rates(eer_threshold, genuine, impostor)
    roc = []
    for threshold in np.linspace(0, 1, 201):
        fmr, fnmr, tmr = rates(float(threshold), genuine, impostor); roc.append({'threshold': float(threshold), 'fmr': fmr, 'tmr': tmr})
    report = {'pipeline': 'FaceDetectorYN.detect -> FaceRecognizerSF.alignCrop -> FaceRecognizerSF.feature -> FaceRecognizerSF.match(FR_COSINE)', 'human_review_summary': {'candidate_clusters': len(state), 'all_same': sum(value == 'ALL_SAME' for value in state.values()), 'mixed': sum(value == 'MIXED' for value in state.values()), 'unknown': sum(value == 'UNKNOWN' for value in state.values()), 'unreviewed': 0},
              'confirmed_identity_count': len(groups), 'confirmed_group_image_count': len(confirmed_members), 'group_size_distribution': {str(size): sum(len(members) == size for members in groups.values()) for size in sorted({len(members) for members in groups.values()})}, 'confirmed_different_conflicts': conflicts, 'cross_group_same_label_conflicts': cross_group_same, 'groups_excluded_from_primary_impostor_calibration': sorted(conflicted_groups),
              'genuine_statistics': stats([score for score, *_ in genuine], (1, 5, 10, 25, 50, 75, 90, 95)), 'lowest_20_genuine_pairs': [{'left': left, 'right': right, 'score': score} for score, left, right in genuine[:20]],
              'high_confidence_impostor_statistics': stats([score for score, *_ in impostor], (90, 95, 99, 99.5, 99.9)), 'highest_20_high_confidence_impostor_pairs': [{'left': left, 'right': right, 'group_left': group_a, 'group_right': group_b, 'score': score} for score, left, right, group_a, group_b in impostor[:20]],
              'provisional_group_vs_single_impostor_statistics': stats([score for score, *_ in provisional_impostor], (90, 95, 99, 99.5, 99.9)), 'provisional_ungrouped_single_count': len(provisional),
              'fr_cosine_dot_product_validation_max_abs_error': validation_error, 'approximate_eer': {'threshold': eer_threshold, 'fmr_percent': eer_fmr * 100, 'fnmr_percent': eer_fnmr * 100, 'eer_percent': (eer_fmr + eer_fnmr) * 50}, 'roc_curve_0_to_1_step_0_005': roc,
              'threshold_table': threshold_rows, 'attendance_safe_operating_points': [operating_point(rate, genuine, impostor) for rate in (.05, .01, .005, .001)], 'no_raw_images': True, 'no_feature_vectors': True}
    args.output.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({'groups': len(groups), 'genuine_pairs': len(genuine), 'high_confidence_impostor_pairs': len(impostor), 'provisional_impostor_pairs': len(provisional_impostor), 'eer_percent': report['approximate_eer']['eer_percent']}, indent=2))


if __name__ == '__main__': main()
