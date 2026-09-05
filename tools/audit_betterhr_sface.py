#!/usr/bin/env python3
"""Local-only audit for suspicious BetterHR SFace pairs; never writes feature vectors or source names."""
import argparse
import csv
import hashlib
import html
import json
from collections import Counter, defaultdict
from pathlib import Path

import cv2 as cv
import numpy as np


def phash(image):
    gray = cv.cvtColor(image, cv.COLOR_BGR2GRAY)
    resized = cv.resize(gray, (32, 32), interpolation=cv.INTER_AREA).astype(np.float32)
    dct = cv.dct(resized)[:8, :8]
    median = np.median(dct[1:, :])
    return (dct > median).flatten()


def hash_distance(left, right):
    return int(np.count_nonzero(left != right))


def union_find_clusters(ids, edges):
    parent = {item: item for item in ids}
    def find(item):
        while parent[item] != item:
            parent[item] = parent[parent[item]]
            item = parent[item]
        return item
    def union(left, right):
        left, right = find(left), find(right)
        if left != right:
            parent[right] = left
    for left, right in edges:
        union(left, right)
    groups = defaultdict(list)
    for item in ids:
        groups[find(item)].append(item)
    return sorted((sorted(group) for group in groups.values() if len(group) > 1), key=lambda group: (-len(group), group))


def panel(image, face, title):
    canvas = np.full((430, 360, 3), 245, dtype=np.uint8)
    scale = min(340 / image.shape[1], 340 / image.shape[0])
    shown = cv.resize(image, (round(image.shape[1] * scale), round(image.shape[0] * scale)))
    x, y = (360 - shown.shape[1]) // 2, 55 + (340 - shown.shape[0]) // 2
    canvas[y:y + shown.shape[0], x:x + shown.shape[1]] = shown
    x1, y1, w, h = face[:4]
    cv.rectangle(shown, (round(x1), round(y1)), (round(x1 + w), round(y1 + h)), (0, 180, 0), 2)
    canvas[y:y + shown.shape[0], x:x + shown.shape[1]] = shown
    cv.putText(canvas, title, (12, 30), cv.FONT_HERSHEY_SIMPLEX, .65, (0, 0, 0), 2, cv.LINE_AA)
    return canvas


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', required=True, type=Path)
    parser.add_argument('--yunet', required=True, type=Path)
    parser.add_argument('--sface', required=True, type=Path)
    parser.add_argument('--audit-output', required=True, type=Path)
    parser.add_argument('--review-dir', required=True, type=Path)
    parser.add_argument('--labels-csv', required=True, type=Path)
    args = parser.parse_args()
    detector = cv.FaceDetectorYN.create(str(args.yunet), '', (320, 320), .80, .30, 5000)
    recognizer = cv.FaceRecognizerSF.create(str(args.sface), '')
    image_paths = sorted(path for path in args.input.rglob('*') if path.is_file() and path.suffix.lower() in ('.jpg', '.jpeg', '.png'))
    decoded, valid, failures = {}, {}, Counter()

    for ordinal, path in enumerate(image_paths, 1):
        identity = f'P{ordinal:04d}'
        if path.stat().st_size == 0:
            failures['ZERO_BYTE'] += 1
            continue
        image = cv.imread(str(path))
        if image is None:
            failures['DECODE_FAILED'] += 1
            continue
        decoded[identity] = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'phash': phash(image), 'image': image}
        try:
            detector.setInputSize((image.shape[1], image.shape[0]))
            _, faces = detector.detect(image)
            count = 0 if faces is None else len(faces)
            if count != 1:
                failures['NO_FACE' if count == 0 else 'MULTIPLE_FACES'] += 1
                continue
            face = faces[0]
            aligned = recognizer.alignCrop(image, face)
            if aligned is None or aligned.size == 0:
                failures['ALIGNMENT_FAILED'] += 1
                continue
            x, y, w, h = (float(value) for value in face[:4])
            reasonable = x >= 0 and y >= 0 and w > 1 and h > 1 and x + w <= image.shape[1] and y + h <= image.shape[0]
            valid[identity] = {**decoded[identity], 'feature': recognizer.feature(aligned).copy(), 'face': [x, y, w, h],
                               'aligned': aligned, 'bbox_reasonable': reasonable}
        except cv.error:
            failures['OTHER_ERROR'] += 1

    ids, pairs = sorted(valid), []
    for index, left in enumerate(ids):
        for right in ids[index + 1:]:
            score = float(recognizer.match(valid[left]['feature'], valid[right]['feature'], cv.FaceRecognizerSF_FR_COSINE))
            pairs.append((score, left, right))
    pairs.sort(reverse=True)
    top100, top50 = pairs[:100], pairs[:50]

    exact_groups = defaultdict(list)
    for identity, item in decoded.items():
        exact_groups[item['sha256']].append(identity)
    exact_groups = sorted((sorted(group) for group in exact_groups.values() if len(group) > 1), key=lambda group: (-len(group), group))
    classifications, high_low_phash = [], []
    for score, left, right in top100:
        exact = valid[left]['sha256'] == valid[right]['sha256']
        distance = hash_distance(valid[left]['phash'], valid[right]['phash'])
        label = 'EXACT_DUPLICATE' if exact else ('NEAR_DUPLICATE' if distance <= 8 else 'VISUALLY_DIFFERENT')
        record = {'left': left, 'right': right, 'sface_cosine': score, 'image_hash_distance': distance, 'image_diagnostic': label}
        classifications.append(record)
        if score > .95 and label == 'VISUALLY_DIFFERENT':
            high_low_phash.append(record)

    args.review_dir.mkdir(parents=True, exist_ok=True)
    aligned_dir = args.review_dir / 'aligned'
    aligned_dir.mkdir(exist_ok=True)
    review_metadata = []
    for rank, (score, left, right) in enumerate(top50, 1):
        left_item, right_item = valid[left], valid[right]
        combined = np.hstack((panel(left_item['image'], left_item['face'], left), panel(right_item['image'], right_item['face'], right)))
        cv.putText(combined, f'SFace cosine: {score:.6f}', (220, 415), cv.FONT_HERSHEY_SIMPLEX, .65, (0, 0, 0), 2, cv.LINE_AA)
        file_name = f'pair-{rank:02d}-{left}-{right}.jpg'
        cv.imwrite(str(args.review_dir / file_name), combined)
        for identity, item in ((left, left_item), (right, right_item)):
            target = aligned_dir / f'{identity}.jpg'
            if not target.exists():
                cv.imwrite(str(target), item['aligned'])
        review_metadata.append({'rank': rank, 'left': left, 'right': right, 'sface_cosine': score, 'file': file_name,
                                'left_bbox_reasonable': left_item['bbox_reasonable'], 'right_bbox_reasonable': right_item['bbox_reasonable'],
                                'left_exactly_one_face': True, 'right_exactly_one_face': True, 'left_aligned_non_empty': True, 'right_aligned_non_empty': True})

    html_rows = '\n'.join(f'<li><a href="{html.escape(row["file"])}">#{row["rank"]} {row["left"]} / {row["right"]} — {row["sface_cosine"]:.6f}</a></li>' for row in review_metadata)
    (args.review_dir / 'index.html').write_text(f'<!doctype html><meta charset="utf-8"><title>BetterHR top SFace pairs</title><h1>Local-only review</h1><p>Review images manually; no automatic same-person label has been applied.</p><ol>{html_rows}</ol>', encoding='utf-8')
    if not args.labels_csv.exists():
        args.labels_csv.write_text('left_id,right_id,label\n', encoding='utf-8')

    report = {
        'original_benchmark_preserved': True,
        'exact_duplicate_groups': exact_groups,
        'exact_duplicate_group_count': len(exact_groups),
        'files_in_exact_duplicate_groups': sum(len(group) for group in exact_groups),
        'top_100_image_diagnostics': classifications,
        'top_50_review_metadata': review_metadata,
        'top_50_detector_alignment_error_count': sum(not all((row['left_bbox_reasonable'], row['right_bbox_reasonable'], row['left_exactly_one_face'], row['right_exactly_one_face'], row['left_aligned_non_empty'], row['right_aligned_non_empty'])) for row in review_metadata),
        'sface_clusters_0_90': union_find_clusters(ids, ((left, right) for score, left, right in pairs if score >= .90)),
        'sface_clusters_0_95': union_find_clusters(ids, ((left, right) for score, left, right in pairs if score >= .95)),
        'high_sface_low_image_similarity_pairs': high_low_phash,
        'failure_counts': dict(failures),
        'manual_labels_csv': str(args.labels_csv.name),
        'no_raw_images_in_json': True,
        'no_feature_vectors_in_json': True,
    }
    args.audit_output.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({'valid': len(ids), 'pairs': len(pairs), 'exact_duplicate_groups': len(exact_groups), 'top100': Counter(item['image_diagnostic'] for item in classifications)}, indent=2))


if __name__ == '__main__':
    main()
