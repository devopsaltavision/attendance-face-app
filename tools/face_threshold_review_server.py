#!/usr/bin/env python3
"""Local-only manual review server for the HF-X05 SFace threshold experiment."""
import argparse, itertools, json, mimetypes, threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import cv2 as cv
import numpy as np

ROOT = Path(__file__).resolve().parents[1]; TMP = ROOT / '.tmp'; WORK = TMP / 'face-threshold-workflow'
DB = TMP / 'face-database'; EXTS = {'.jpg', '.jpeg', '.png'}
LOCK = threading.Lock()

def files(): return sorted(p for p in DB.rglob('*') if p.is_file() and p.suffix.lower() in EXTS)
def load_json(path): return json.loads(path.read_text(encoding='utf-8'))
def write_json(path, value): path.write_text(json.dumps(value, indent=2), encoding='utf-8')

def prepare():
    for part in ('source','search','state','results','cache'): (WORK / part).mkdir(parents=True, exist_ok=True)
    groups = load_json(TMP / 'betterhr-identity-groups.json')
    old = load_json(TMP / 'betterhr-sface-benchmark.json')
    by_id = {f'P{i:04d}': p for i,p in enumerate(files(), 1)}
    invalid = {item for values in old['failure_ids'].values() for item in values}
    usable = {g: sorted(i for i in members if i in by_id and i not in invalid) for g,members in groups.items()}
    # Only complete 3-template source sets faithfully model Android MEAN_3.
    source_groups = {g:m for g,m in usable.items() if len(m) >= 4}
    source = [{'sourceIdentity': g, 'images': m[:3], 'reviewedIdentityGroup': g} for g,m in sorted(source_groups.items())]
    source_ids = set(source_groups)
    eligible_unknown = [(g,m) for g,m in sorted(usable.items()) if g not in source_ids and len(m) >= 2][:10]
    search = []
    for row in source:
        for image in usable[row['sourceIdentity']][3:]:
            search.append({'queryId': f'Q-{image}', 'image': image, 'expectedIdentity': row['sourceIdentity'], 'groundTruthHint':'KNOWN'})
    for group,members in eligible_unknown:
        for image in members:
            search.append({'queryId': f'Q-{image}', 'image': image, 'expectedIdentity': None, 'groundTruthHint':'UNKNOWN'})
    write_json(WORK/'source_manifest.json', {'version':1, 'scoring':'MEAN_3 over exactly 3 source templates; query feature repeated 3 times to model current 3-frame mean with a static image', 'source':source})
    write_json(WORK/'search_manifest.json', {'version':1, 'queries':search, 'ambiguousImagesInitiallyAvailable':0, 'manualAmbiguousSupported':True})
    rotations = {'version':1, 'note':'Optional source-template rotations; do not treat repeated queries as independent evidence.', 'identities':[
      {'identity':g, 'rotations':[{'sourceImages':list(c), 'searchImages':[x for x in m if x not in c]} for c in itertools.combinations(m,3)]}
      for g,m in sorted(source_groups.items())]}
    write_json(WORK/'source_rotations.json', rotations)
    return by_id

class Engine:
    def __init__(self, by_id):
        self.by_id=by_id; self.source=load_json(WORK/'source_manifest.json')['source']; self.queries=load_json(WORK/'search_manifest.json')['queries']
        self.detector=cv.FaceDetectorYN.create(str(ROOT/'app/src/main/assets/face_models/face_detection_yunet_2023mar.onnx'),'',(320,320),.80,.30,5000)
        self.recognizer=cv.FaceRecognizerSF.create(str(ROOT/'app/src/main/assets/face_models/face_recognition_sface_2021dec.onnx'),'')
        self.cache={}
    def feature(self, image_id):
        if image_id in self.cache:return self.cache[image_id]
        image=cv.imread(str(self.by_id[image_id])); self.detector.setInputSize((image.shape[1],image.shape[0])); _,faces=self.detector.detect(image)
        if faces is None or len(faces)!=1: raise ValueError(f'{image_id}: expected exactly one face')
        aligned=self.recognizer.alignCrop(image,faces[0])
        if aligned is None or not aligned.size: raise ValueError(f'{image_id}: alignment failed')
        value=self.recognizer.feature(aligned).copy(); self.cache[image_id]=value; return value
    def rank(self, query):
        q=self.feature(query['image']); ranked=[]
        for source in self.source:
            # Android SFaceDebugIdentification: mean of three query scores, each
            # MEAN_3 over exactly three enrolled templates. Static query repeats q.
            templates=[self.feature(i) for i in source['images']]
            score=float(np.mean([self.recognizer.match(q,t,cv.FaceRecognizerSF_FR_COSINE) for t in templates]))
            ranked.append({'identity':source['sourceIdentity'],'score':score,'image':source['images'][0]})
        return sorted(ranked,key=lambda x:x['score'],reverse=True)

def reviews():
    path=WORK/'state/reviews.jsonl'; out={}
    if path.exists():
        for line in path.read_text(encoding='utf-8').splitlines():
            if line.strip():
                item=json.loads(line); out[item['queryId']]=item
    return out

def page(): return '''<!doctype html><meta charset="utf-8"><title>Face threshold review</title><style>body{font:16px sans-serif;max-width:1100px;margin:auto}img{max-width:300px;max-height:300px}button,select{padding:9px;margin:4px}.row{display:flex;gap:35px}.card{padding:12px;border:1px solid #ccc;margin:10px 0}</style><h1>Local Face Threshold Review</h1><div id="app"></div><script>
let data,idx=0,filter='UNREVIEWED'; async function api(p,o){return (await fetch(p,o)).json()};
async function load(){data=await api('/api/queries?filter='+filter); idx=Math.min(idx,Math.max(0,data.items.length-1)); render()}
function render(){let q=data.items[idx]; if(!q){app.innerHTML='No queries';return} let ranks=q.ranks.map((r,i)=>`<li>Top ${i+1}: <b>${r.identity}</b> ${r.score.toFixed(6)}<br><img src="/image?id=${r.image}"></li>`).join('');app.innerHTML=`<p>Reviewed: ${data.reviewed}/${data.total} &nbsp; Filter <select onchange="filter=this.value;idx=0;load()"><option>UNREVIEWED</option><option>ALL</option><option>KNOWN</option><option>UNKNOWN</option><option>AMBIGUOUS</option><option>MISIDENTIFIED</option></select></p><div class=row><div><h2>${q.queryId}</h2><p>Hint: ${q.groundTruthHint}; expected: ${q.expectedIdentity||'—'}</p><img src="/image?id=${q.image}"></div><div><h2>Ranked source candidates</h2><ol>${ranks}</ol><p>Margin: ${q.margin.toFixed(6)}</p></div></div><div class=card><button onclick="save('TOP1')">TOP 1 IS CORRECT</button><select id=other>${q.candidates.map(x=>`<option>${x.identity}</option>`).join('')}</select><button onclick="save('OTHER')">SELECT CORRECT IDENTITY</button><button onclick="save('UNKNOWN')">UNKNOWN / NOT REGISTERED</button><button onclick="save('AMBIGUOUS')">AMBIGUOUS</button><button onclick="save('SKIPPED')">SKIP / UNCLASSIFIED</button></div><button onclick="idx=Math.max(0,idx-1);render()">PREVIOUS</button><button onclick="idx++;render()">NEXT UNREVIEWED</button>`}
async function save(action){let q=data.items[idx],trueIdentity=action==='TOP1'?q.ranks[0].identity:action==='OTHER'?other.value:null;await api('/api/review',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({queryId:q.queryId,action,trueIdentity})});idx++;await load()};load();</script>'''

def run(engine):
 class H(BaseHTTPRequestHandler):
  def log_message(self,*_): pass
  def sendj(self,x): self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(json.dumps(x).encode())
  def do_GET(self):
   u=urlparse(self.path); qs=parse_qs(u.query)
   if u.path=='/': self.send_response(200);self.send_header('Content-Type','text/html');self.end_headers();self.wfile.write(page().encode());return
   if u.path=='/image':
    p=engine.by_id.get(qs.get('id',[''])[0]);
    if not p:self.send_error(404);return
    self.send_response(200);self.send_header('Content-Type',mimetypes.guess_type(str(p))[0] or 'image/jpeg');self.end_headers();self.wfile.write(p.read_bytes());return
   if u.path=='/api/queries':
    state=reviews(); kind=qs.get('filter',['UNREVIEWED'])[0]; items=[]
    for q in engine.queries:
     r=state.get(q['queryId']); classification=(r or {}).get('classification')
     include=kind=='ALL' or (kind=='UNREVIEWED' and not r) or q['groundTruthHint']==kind or classification==kind
     if include:
      ranks=engine.rank(q); items.append({**q,'ranks':ranks[:3],'candidates':ranks,'topScore':ranks[0]['score'],'secondScore':ranks[1]['score'],'margin':ranks[0]['score']-ranks[1]['score'],'review':r})
    self.sendj({'items':items,'reviewed':len(state),'total':len(engine.queries)});return
   self.send_error(404)
  def do_POST(self):
   if self.path!='/api/review':self.send_error(404);return
   body=json.loads(self.rfile.read(int(self.headers['Content-Length']))); q=next(x for x in engine.queries if x['queryId']==body['queryId']); ranks=engine.rank(q); action=body['action']; true=body.get('trueIdentity')
   top,second=ranks[0],ranks[1]; correct=next((x for x in ranks if x['identity']==true),None)
   classification={'UNKNOWN':'UNKNOWN','AMBIGUOUS':'AMBIGUOUS','SKIPPED':'SKIPPED'}.get(action, 'GENUINE_TOP1' if top['identity']==true else 'MISIDENTIFIED')
   item={'queryId':q['queryId'],'groundTruth':'KNOWN' if action in {'TOP1','OTHER'} else action,'trueIdentity':true,'topIdentity':top['identity'],'topScore':top['score'],'secondIdentity':second['identity'],'secondScore':second['score'],'margin':top['score']-second['score'],'correctIdentityScore':None if not correct else correct['score'],'correctIdentityRank':None if not correct else ranks.index(correct)+1,'classification':classification,'reviewedAt':datetime.now(timezone.utc).isoformat()}
   with LOCK: (WORK/'state/reviews.jsonl').open('a',encoding='utf-8').write(json.dumps(item)+'\n')
   self.sendj({'ok':True})
 server=ThreadingHTTPServer(('127.0.0.1',8765),H);print('http://127.0.0.1:8765');server.serve_forever()

if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--prepare-only',action='store_true');args=parser.parse_args(); by_id=prepare()
 if args.prepare_only: print('prepared',WORK); raise SystemExit
 run(Engine(by_id))
