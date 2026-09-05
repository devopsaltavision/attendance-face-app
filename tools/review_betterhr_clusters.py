#!/usr/bin/env python3
"""Local-only reviewer for BetterHR candidate identity clusters."""
import argparse
import html
import json
import mimetypes
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse


def image_sources(input_dir):
    paths = sorted(path for path in input_dir.rglob('*') if path.is_file() and path.suffix.lower() in {'.jpg', '.jpeg', '.png'})
    return {f'P{index:04d}': path for index, path in enumerate(paths, 1)}


def load_json(path, fallback):
    return json.loads(path.read_text(encoding='utf-8')) if path.exists() else fallback


def save_state(path, state): path.write_text(json.dumps(state, indent=2), encoding='utf-8')


def write_groups(path, clusters, state):
    confirmed = [cluster for cluster in clusters if state.get(cluster['cluster_id']) == 'ALL_SAME']
    path.write_text(json.dumps({f'G{index:04d}': cluster['members'] for index, cluster in enumerate(confirmed, 1)}, indent=2), encoding='utf-8')


def page(cluster, index, total, state):
    status = state.get(cluster['cluster_id'], 'UNREVIEWED')
    conflict = cluster['contains_confirmed_different_conflict']
    cards = ''.join(f'<figure><img src="/image/{quote(member)}"><figcaption>{html.escape(member)}</figcaption></figure>' for member in cluster['members'])
    disabled = 'disabled title="This cluster contains a confirmed DIFFERENT pair"' if conflict else ''
    return f'''<!doctype html><meta charset="utf-8"><title>BetterHR cluster review</title><style>body{{font-family:system-ui;margin:20px;background:#f5f6f8}}main{{max-width:1400px;margin:auto}}.grid{{display:flex;flex-wrap:wrap;gap:12px}}figure{{width:260px;margin:0;background:white;padding:8px;text-align:center}}img{{max-width:250px;max-height:300px}}button,a{{font:inherit;padding:11px;margin:10px 6px 0 0;border-radius:6px;border:1px solid #667;text-decoration:none;color:#111;background:white}}.same{{background:#c8f2d4}}.mixed{{background:#ffd1d1}}.unknown{{background:#eee}}.warn{{color:#a00;font-weight:bold}}</style><main><h1>Local-only candidate-cluster review</h1><p>Cluster {index+1} / {total}: <b>{cluster['cluster_id']}</b>, {cluster['image_count']} images, pairwise SFace {cluster['pairwise_score_minimum']:.6f}&ndash;{cluster['pairwise_score_maximum']:.6f}. Current: <b>{status}</b></p>{'<p class="warn">Contains a confirmed DIFFERENT pair. ALL SAME is disabled.</p>' if conflict else ''}<div class="grid">{cards}</div><form method="post" action="/label"><input type="hidden" name="index" value="{index}"><button class="same" name="decision" value="ALL_SAME" {disabled}>ALL SAME PERSON (A)</button><button class="mixed" name="decision" value="MIXED">SPLIT / MIXED PEOPLE (M)</button><button class="unknown" name="decision" value="UNKNOWN">UNKNOWN (U)</button></form><a href="/?index={max(0,index-1)}">&larr; Previous</a><a href="/?index={min(total-1,index+1)}">Next &rarr;</a><p>Mixed clusters are added to the local internal-pair queue for later pair review. Keys: A/M/U, arrows.</p><script>addEventListener('keydown',e=>{{let v={{a:'ALL_SAME',m:'MIXED',u:'UNKNOWN'}}[e.key.toLowerCase()];if(e.key==='ArrowLeft')location='/?index={max(0,index-1)}';if(e.key==='ArrowRight')location='/?index={min(total-1,index+1)}';if(v){{let f=document.querySelector('form');let x=document.createElement('input');x.type='hidden';x.name='decision';x.value=v;f.append(x);f.submit()}}}})</script></main>'''


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument('--clusters', type=Path, default=root / '.tmp' / 'betterhr-candidate-clusters.json')
    parser.add_argument('--input', type=Path, default=root / '.tmp' / 'face-database')
    parser.add_argument('--state', type=Path, default=root / '.tmp' / 'betterhr-cluster-review-state.json')
    parser.add_argument('--groups', type=Path, default=root / '.tmp' / 'betterhr-identity-groups.json')
    parser.add_argument('--mixed-pair-queue', type=Path, default=root / '.tmp' / 'betterhr-mixed-cluster-pairs.json')
    parser.add_argument('--port', type=int, default=8766)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args(); clusters = load_json(args.clusters, {})['clusters']; state, sources = load_json(args.state, {}), image_sources(args.input)
    if args.check:
        print(json.dumps({'candidate_clusters': len(clusters), 'existing_decisions': len(state), 'first_unreviewed': next((index + 1 for index, cluster in enumerate(clusters) if cluster['cluster_id'] not in state), None)}, indent=2)); return
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *values): print('[cluster-review] ' + format % values)
        def do_GET(self):
            parsed = urlparse(self.path)
            if parsed.path.startswith('/image/'):
                path = sources.get(parsed.path.rsplit('/', 1)[-1])
                if not path: self.send_error(404); return
                content = path.read_bytes(); self.send_response(200); self.send_header('Content-Type', mimetypes.guess_type(path.name)[0] or 'application/octet-stream'); self.send_header('Content-Length', str(len(content))); self.end_headers(); self.wfile.write(content); return
            query = parse_qs(parsed.query)
            try: index = min(max(int(query.get('index', ['-1'])[0]), 0), len(clusters)-1)
            except ValueError: index = -1
            if index < 0: index = next((i for i, cluster in enumerate(clusters) if cluster['cluster_id'] not in state), 0)
            content = page(clusters[index], index, len(clusters), state).encode(); self.send_response(200); self.send_header('Content-Type','text/html; charset=utf-8'); self.send_header('Content-Length',str(len(content))); self.end_headers(); self.wfile.write(content)
        def do_POST(self):
            if self.path != '/label': self.send_error(404); return
            data=parse_qs(self.rfile.read(int(self.headers.get('Content-Length',0))).decode())
            try: index=int(data['index'][0]); decision=data['decision'][-1]
            except (KeyError,ValueError,IndexError): self.send_error(400); return
            if not 0 <= index < len(clusters) or decision not in {'ALL_SAME','MIXED','UNKNOWN'}: self.send_error(400); return
            cluster=clusters[index]
            if decision == 'ALL_SAME' and cluster['contains_confirmed_different_conflict']: self.send_error(409); return
            state[cluster['cluster_id']]=decision; save_state(args.state,state); write_groups(args.groups,clusters,state)
            mixed=[{'cluster_id':item['cluster_id'],'pairs':item['internal_pairs_descending']} for item in clusters if state.get(item['cluster_id'])=='MIXED']; save_state(args.mixed_pair_queue,{'mixed_clusters':mixed})
            next_index=next((i for i in range(index+1,len(clusters)) if clusters[i]['cluster_id'] not in state), min(index+1,len(clusters)-1)); self.send_response(303); self.send_header('Location',f'/?index={next_index}'); self.end_headers()
    server=ThreadingHTTPServer(('127.0.0.1',args.port),Handler); print(f'Cluster review URL: http://127.0.0.1:{args.port}/ (clusters={len(clusters)}, decisions={len(state)})')
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()


if __name__ == '__main__': main()
