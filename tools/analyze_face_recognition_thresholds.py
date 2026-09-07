#!/usr/bin/env python3
"""Analyze only authoritative manual local reviews from the threshold server."""
import csv, json
from pathlib import Path
import numpy as np

ROOT=Path(__file__).resolve().parents[1]; WORK=ROOT/'.tmp/face-threshold-workflow'; OUT=WORK/'results'
def pct(v,p): return float(np.percentile(np.asarray(v,dtype=float),p))
def stats(v,ps):
    if not v:return {'count':0}
    return {'count':len(v),'min':min(v),'mean':float(np.mean(v)),'median':float(np.median(v)),'max':max(v),**{f'p{str(p).replace(".","_")}':pct(v,p) for p in ps}}
def records():
    latest={}; p=WORK/'state/reviews.jsonl'
    if p.exists():
      for line in p.read_text(encoding='utf-8').splitlines():
       if line.strip(): x=json.loads(line);latest[x['queryId']]=x
    return list(latest.values())
def write_csv(name, rows, fields):
    with (OUT/name).open('w',newline='',encoding='utf-8') as f:
      w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(rows)
def main():
 OUT.mkdir(parents=True,exist_ok=True); r=records(); g=[x for x in r if x['classification']=='GENUINE_TOP1']; u=[x for x in r if x['classification']=='UNKNOWN']; w=[x for x in r if x['classification']=='MISIDENTIFIED']; a=[x for x in r if x['classification']=='AMBIGUOUS']; s=[x for x in r if x['classification']=='SKIPPED']
 summary={'manual_records':len(r),'genuine_top1_score':stats([x['topScore'] for x in g],[1,5,10,25]),'genuine_margin':stats([x['margin'] for x in g],[1,5,10]),'unknown_top1_score':stats([x['topScore'] for x in u],[90,95,97,99]),'unknown_margin':stats([x['margin'] for x in u],[90,95,99]),'misidentified_top1_score':stats([x['topScore'] for x in w],[95]),'misidentified_correct_score':stats([x['correctIdentityScore'] for x in w if x['correctIdentityScore'] is not None],[]),'misidentified_wrong_vs_correct_margin':stats([x['topScore']-x['correctIdentityScore'] for x in w if x['correctIdentityScore'] is not None],[]),'ambiguous_top1_score':stats([x['topScore'] for x in a],[]),'ambiguous_margin':stats([x['margin'] for x in a],[90,95]),'skipped':len(s),'recommendation':'NOT ENOUGH MANUAL REVIEWS' if len(r)<20 or not g or not u else 'See combined-policy-search.csv','bootstrap':'DEFERRED until at least 10 independently reviewed known identities and 10 unknown identities are present.'}
 thresholds=[]
 for i in range(60,201):
  t=i/200; known=g+w
  thresholds.append({'threshold':t,'known_TAR':sum(x['topScore']>=t for x in g)/len(g) if g else '', 'known_FRR':sum(x['topScore']<t for x in g)/len(g) if g else '', 'unknown_FAR':sum(x['topScore']>=t for x in u)/len(u) if u else '', 'unknown_TRR':sum(x['topScore']<t for x in u)/len(u) if u else '', 'known_misidentification_rate':sum(x['topScore']>=t for x in w)/len(known) if known else ''})
 margins=[]; combined=[]
 for t in [x/200 for x in range(60,201)]:
  for m in [x/1000 for x in range(0,301,5)]:
   accept=lambda x:x['topScore']>=t and x['margin']>=m
   if t==0.3:margins.append({'margin':m,'genuine_accepted':sum(accept(x) for x in g)/len(g) if g else '', 'wrong_blocked':sum(not accept(x) for x in w)/len(w) if w else '', 'unknown_blocked':sum(not accept(x) for x in u)/len(u) if u else '', 'ambiguous_blocked':sum(not accept(x) for x in a)/len(a) if a else ''})
   combined.append({'match_threshold':t,'min_match_margin':m,'genuine_accepted':sum(accept(x) for x in g)/len(g) if g else '', 'wrong_person_accepted':(sum(accept(x) for x in w)+sum(accept(x) for x in u))/(len(w)+len(u)) if w or u else '', 'unknown_false_accepted':sum(accept(x) for x in u)/len(u) if u else '', 'ambiguous_rejected':sum(not accept(x) for x in a)/len(a) if a else ''})
 write_csv('threshold-search.csv',thresholds,list(thresholds[0]));write_csv('margin-search.csv',margins,list(margins[0]));write_csv('combined-policy-search.csv',combined,list(combined[0]));write_json= lambda p,x:p.write_text(json.dumps(x,indent=2),encoding='utf-8');write_json(OUT/'summary.json',summary)
 (OUT/'report.md').write_text('# HF-X05 local threshold review\n\nManual reviews only. No threshold is recommended until enough independently reviewed KNOWN, UNKNOWN and AMBIGUOUS records exist.\n\n```json\n'+json.dumps(summary,indent=2)+'\n```\n\nSource rotations are described in `../source_rotations.json`; repeated query images across rotations are not independent evidence.\n',encoding='utf-8')
 print(json.dumps(summary,indent=2))
if __name__=='__main__':main()
