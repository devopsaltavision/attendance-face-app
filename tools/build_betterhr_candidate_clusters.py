#!/usr/bin/env python3
"""Build local-only, human-review candidate identity clusters from SFace graph edges."""
import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path

import cv2 as cv


def key(left, right): return tuple(sorted((left, right)))


class Components:
    def __init__(self, members): self.groups = [{member} for member in members]
    def group_index(self, member): return next(index for index, group in enumerate(self.groups) if member in group)
    def merge(self, left, right, different):
        a, b = self.group_index(left), self.group_index(right)
        if a == b: return not any(key(x, y) in different for x in self.groups[a] for y in self.groups[a] if x < y)
        combined = self.groups[a] | self.groups[b]
        if any(key(x, y) in different for x in combined for y in combined if x < y): return False
        self.groups[a] = combined; self.groups.pop(b); return True


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument('--audit', type=Path, default=root / '.tmp' / 'betterhr-dataset-audit.json')
    parser.add_argument('--labels', type=Path, default=root / '.tmp' / 'betterhr-pair-labels.csv')
    parser.add_argument('--benchmark', type=Path, default=root / '.tmp' / 'betterhr-sface-benchmark.json')
    parser.add_argument('--input', type=Path, default=root / '.tmp' / 'face-database')
    parser.add_argument('--yunet', type=Path, default=root / 'app' / 'src' / 'main' / 'assets' / 'face_models' / 'face_detection_yunet_2023mar.onnx')
    parser.add_argument('--sface', type=Path, default=root / 'app' / 'src' / 'main' / 'assets' / 'face_models' / 'face_recognition_sface_2021dec.onnx')
    parser.add_argument('--output', type=Path, default=root / '.tmp' / 'betterhr-candidate-clusters.json')
    args = parser.parse_args()
    audit, benchmark = json.loads(args.audit.read_text(encoding='utf-8')), json.loads(args.benchmark.read_text(encoding='utf-8'))
    failures = {identity for identities in benchmark['failure_ids'].values() for identity in identities}
    image_paths = sorted(path for path in args.input.rglob('*') if path.is_file() and path.suffix.lower() in {'.jpg', '.jpeg', '.png'})
    valid_ids = [f'P{index:04d}' for index in range(1, len(image_paths) + 1) if f'P{index:04d}' not in failures]
    labels, different, same_scores = {}, set(), {}
    with args.labels.open(newline='', encoding='utf-8') as stream:
        for row in csv.DictReader(stream):
            label, pair = row.get('label', '').strip().upper(), key(row.get('left_id', ''), row.get('right_id', ''))
            if label not in {'SAME', 'DIFFERENT', 'UNKNOWN'}: continue
            labels[pair] = label
            if label == 'DIFFERENT': different.add(pair)
            if label == 'SAME' and row.get('score'): same_scores[pair] = float(row['score'])
    components = Components(valid_ids)
    blocked = []
    # Existing >= .90 graph components are candidate edges; pair labels are authoritative constraints.
    strong_edges = []
    for cluster in audit['sface_clusters_0_90']:
        for index, left in enumerate(cluster):
            for right in cluster[index + 1:]: strong_edges.append((left, right, 'SFACE_GTE_0_90'))
    for left, right, _source in strong_edges + [(left, right, 'HUMAN_SAME') for left, right in labels if labels[(left, right)] == 'SAME']:
        if not components.merge(left, right, different): blocked.append({'left': left, 'right': right, 'reason': 'would_merge_confirmed_different'})
    candidates = [sorted(group) for group in components.groups if len(group) >= 2]
    # Re-extract current corrected SFace features only to calculate internal review score ranges; no features are written.
    path_by_id = {f'P{index:04d}': path for index, path in enumerate(image_paths, 1)}
    detector = cv.FaceDetectorYN.create(str(args.yunet), '', (320, 320), .80, .30, 5000)
    recognizer = cv.FaceRecognizerSF.create(str(args.sface), '')
    features = {}
    for identity in {member for group in candidates for member in group}:
        image = cv.imread(str(path_by_id[identity])); detector.setInputSize((image.shape[1], image.shape[0])); _, faces = detector.detect(image)
        if faces is None or len(faces) != 1: raise RuntimeError(f'Candidate no longer has one face: {identity}')
        features[identity] = recognizer.feature(recognizer.alignCrop(image, faces[0])).copy()
    records = []
    for number, members in enumerate(sorted(candidates, key=lambda group: (-len(group), group)), 1):
        scores = []
        for index, left in enumerate(members):
            for right in members[index + 1:]: scores.append((float(recognizer.match(features[left], features[right], cv.FaceRecognizerSF_FR_COSINE)), left, right))
        internal_different = [{'left': left, 'right': right} for left, right in different if left in members and right in members]
        records.append({'cluster_id': f'C{number:04d}', 'members': members, 'image_count': len(members),
                        'pairwise_score_minimum': min(score for score, _, _ in scores), 'pairwise_score_maximum': max(score for score, _, _ in scores),
                        'internal_pairs_descending': [{'left': left, 'right': right, 'score': score, 'human_label': labels.get(key(left, right), 'UNREVIEWED')} for score, left, right in sorted(scores, reverse=True)],
                        'contains_confirmed_different_conflict': bool(internal_different), 'confirmed_different_pairs': internal_different})
    report = {'purpose': 'Candidate clusters only; SFace >= .90 edges are not identity truth.', 'candidate_cluster_count': len(records), 'candidate_image_count': sum(row['image_count'] for row in records),
              'human_label_count': len(labels), 'human_same_count': sum(value == 'SAME' for value in labels.values()), 'human_different_count': sum(value == 'DIFFERENT' for value in labels.values()),
              'blocked_edges_due_to_confirmed_different': blocked, 'clusters': records, 'no_images': True, 'no_features': True}
    args.output.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({'candidate_clusters': len(records), 'candidate_images': report['candidate_image_count'], 'blocked_edges': len(blocked)}, indent=2))


if __name__ == '__main__': main()
