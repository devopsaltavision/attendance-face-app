#!/usr/bin/env python3
"""Local-only human reviewer for anonymous suspicious BetterHR SFace pairs.

The server deliberately binds to 127.0.0.1 only.  It reads local source
images solely to render them in the loopback browser session; it neither
writes image data nor sends anything over a network.
"""
import argparse
import csv
import html
import json
import mimetypes
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse


VALID_LABELS = {"SAME", "DIFFERENT", "UNKNOWN"}


def canonical(left, right):
    return tuple(sorted((left, right)))


def source_paths(input_dir):
    images = sorted(
        path for path in input_dir.rglob("*")
        if path.is_file() and path.suffix.lower() in {".jpg", ".jpeg", ".png"}
    )
    return {f"P{ordinal:04d}": path for ordinal, path in enumerate(images, 1)}


def load_queue(audit_path):
    audit = json.loads(audit_path.read_text(encoding="utf-8"))
    rows = audit.get("top_100_image_diagnostics", [])
    pairs, seen = [], set()
    for row in sorted(rows, key=lambda row: float(row["sface_cosine"]), reverse=True):
        key = canonical(row["left"], row["right"])
        if key not in seen:
            pairs.append({"left": key[0], "right": key[1], "score": float(row["sface_cosine"])})
            seen.add(key)
    return pairs


def load_labels(labels_path):
    if not labels_path.exists():
        return {}
    with labels_path.open(newline="", encoding="utf-8") as stream:
        return {
            canonical(row["left_id"], row["right_id"]): {"label": row["label"].strip().upper(), "score": row.get("score", "")}
            for row in csv.DictReader(stream)
            if row.get("left_id") and row.get("right_id") and row.get("label", "").strip().upper() in VALID_LABELS
        }


def save_labels(labels_path, queue, labels):
    scores = {canonical(row["left"], row["right"]): row["score"] for row in queue}
    labels_path.parent.mkdir(parents=True, exist_ok=True)
    with labels_path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=("left_id", "right_id", "label", "score"))
        writer.writeheader()
        for left, right in sorted(labels):
            record = labels[(left, right)]
            score = scores.get((left, right), record["score"])
            writer.writerow({"left_id": left, "right_id": right, "label": record["label"], "score": f"{score:.9f}" if isinstance(score, float) else score})


def page(pair, index, total, labels):
    key = canonical(pair["left"], pair["right"])
    current = labels.get(key, {}).get("label", "UNLABELED")
    return f"""<!doctype html><html><head><meta charset=\"utf-8\"><title>BetterHR pair review</title>
<style>body{{font-family:system-ui,sans-serif;margin:24px;background:#f6f7f9;color:#16202a}}main{{max-width:1200px;margin:auto}}.meta{{font-size:20px;margin:12px 0}}.images{{display:flex;gap:20px}}.images figure{{width:50%;margin:0;text-align:center}}img{{max-width:100%;max-height:66vh;border:1px solid #b8c0c8;background:white}}button,a.nav{{font:inherit;padding:12px 18px;margin:12px 8px 0 0;border-radius:6px;border:1px solid #667;background:#fff;color:#111;text-decoration:none;cursor:pointer}}button.same{{background:#c9f2d5}}button.different{{background:#ffd1d1}}button.unknown{{background:#eee}}.status{{font-weight:700}}small{{color:#555}}</style></head><body><main>
<h1>Local-only BetterHR pair review</h1><div class=meta>Pair {index + 1} / {total} &mdash; <b>{html.escape(pair['left'])}</b> vs <b>{html.escape(pair['right'])}</b> &mdash; SFace cosine <b>{pair['score']:.6f}</b></div>
<div class=status>Current label: {html.escape(current)}</div><div class=images><figure><img src=\"/image/{quote(pair['left'])}\"><figcaption>{html.escape(pair['left'])}</figcaption></figure><figure><img src=\"/image/{quote(pair['right'])}\"><figcaption>{html.escape(pair['right'])}</figcaption></figure></div>
<form method=\"post\" action=\"/label\"><input type=hidden name=\"index\" value=\"{index}\"><button class=same name=\"label\" value=\"SAME\">SAME PERSON (S)</button><button class=different name=\"label\" value=\"DIFFERENT\">DIFFERENT PERSON (D)</button><button class=unknown name=\"label\" value=\"UNKNOWN\">UNKNOWN / SKIP (U)</button></form>
<a class=nav href=\"/?index={max(0, index - 1)}\">&larr; Previous</a><a class=nav href=\"/?index={min(total - 1, index + 1)}\">Next &rarr;</a><p><small>Labels save immediately to the local CSV. Arrow keys navigate; S/D/U label and advance. This server is bound only to 127.0.0.1.</small></p>
<script>addEventListener('keydown',e=>{{if(['INPUT','TEXTAREA'].includes(document.activeElement.tagName))return;let u=new URL(location);if(e.key==='ArrowLeft')location='/?index={max(0,index-1)}';if(e.key==='ArrowRight')location='/?index={min(total-1,index+1)}';let v={{s:'SAME',d:'DIFFERENT',u:'UNKNOWN'}}[e.key.toLowerCase()];if(v){{let f=document.querySelector('form');let i=document.createElement('input');i.type='hidden';i.name='label';i.value=v;f.append(i);f.submit()}}}});</script></main></body></html>"""


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="Local-only BetterHR SFace pair reviewer")
    parser.add_argument("--input", type=Path, default=root / ".tmp" / "face-database")
    parser.add_argument("--audit", type=Path, default=root / ".tmp" / "betterhr-dataset-audit.json")
    parser.add_argument("--labels", type=Path, default=root / ".tmp" / "betterhr-pair-labels.csv")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--check", action="store_true", help="Validate inputs and print queue/label counts without serving")
    args = parser.parse_args()
    queue, sources, labels = load_queue(args.audit), source_paths(args.input), load_labels(args.labels)
    missing = {item for pair in queue for item in (pair["left"], pair["right"])} - set(sources)
    if missing:
        raise SystemExit(f"Missing source images for: {', '.join(sorted(missing))}")
    if args.check:
        print(json.dumps({"queued_pairs": len(queue), "existing_labels": len(labels), "first_unlabeled_index": next((i + 1 for i, pair in enumerate(queue) if canonical(pair['left'], pair['right']) not in labels), None)}, indent=2))
        return

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *values):
            print("[review] " + format % values)

        def do_GET(self):
            parsed = urlparse(self.path)
            if parsed.path.startswith("/image/"):
                identity = parsed.path.rsplit("/", 1)[-1]
                path = sources.get(identity)
                if path is None:
                    self.send_error(HTTPStatus.NOT_FOUND)
                    return
                content = path.read_bytes()
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", mimetypes.guess_type(path.name)[0] or "application/octet-stream")
                self.send_header("Content-Length", str(len(content)))
                self.end_headers(); self.wfile.write(content)
                return
            query = parse_qs(parsed.query)
            try: index = min(max(int(query.get("index", ["-1"])[0]), 0), len(queue) - 1)
            except ValueError: index = -1
            if index < 0:
                index = next((i for i, pair in enumerate(queue) if canonical(pair["left"], pair["right"]) not in labels), 0)
            content = page(queue[index], index, len(queue), labels).encode("utf-8")
            self.send_response(HTTPStatus.OK); self.send_header("Content-Type", "text/html; charset=utf-8"); self.send_header("Content-Length", str(len(content))); self.end_headers(); self.wfile.write(content)

        def do_POST(self):
            if self.path != "/label": self.send_error(HTTPStatus.NOT_FOUND); return
            size = int(self.headers.get("Content-Length", 0)); form = parse_qs(self.rfile.read(size).decode("utf-8"))
            try: index = int(form["index"][0]); label = form["label"][-1].upper()
            except (KeyError, ValueError, IndexError): self.send_error(HTTPStatus.BAD_REQUEST); return
            if not 0 <= index < len(queue) or label not in VALID_LABELS: self.send_error(HTTPStatus.BAD_REQUEST); return
            pair = queue[index]; labels[canonical(pair["left"], pair["right"])] = {"label": label, "score": pair["score"]}; save_labels(args.labels, queue, labels)
            next_index = next((i for i in range(index + 1, len(queue)) if canonical(queue[i]["left"], queue[i]["right"]) not in labels), min(index + 1, len(queue) - 1))
            self.send_response(HTTPStatus.SEE_OTHER); self.send_header("Location", f"/?index={next_index}"); self.end_headers()

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Review URL: http://127.0.0.1:{args.port}/  (queued={len(queue)}, loaded_labels={len(labels)})")
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()


if __name__ == "__main__":
    main()
