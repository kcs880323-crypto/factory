#!/usr/bin/env python3
"""절마다 여러 번 합성한 것 중, 한국어 음성인식의 받아쓰기가 원문(caption)과 가장 비슷한 것을 골라 모은다.

사용 (반드시 가상환경 파이썬 PY 로, -I 를 붙여):
  ASRDIR=<음성인식 모델 폴더> CLAUSES=<clauses-full.json> <PY> -I pick_best_take.py <runs 폴더> <출력 폴더>

- <runs 폴더> 안에는 d0, d1, … 폴더만 둔다 (각각 tts_clauses.py 를 한 번 돌린 출력). 이름이 d 로 시작하는 파일이 있으면 오류가 난다.
- ASRDIR: sherpa-onnx-zipformer-korean-2024-06-24 (encoder/decoder/joiner-epoch-99-avg-1*.onnx, tokens.txt, bpe.model 이 들어 있는 폴더)
- 오프닝 끝말 "입니다"(op2)와 엔딩 끝말 "였습니다"(en3)가 안 들리면 오류에 5를 더해 떨어뜨린다.
- 이것은 음성인식의 받아쓰기이지 사람 귀의 평가가 아니다. 이름 "찰스"는 인식기가 자주 틀려서 세지 않는다.
"""
import sys, os, re, json, wave, shutil, numpy as np, sherpa_onnx as so
ASR=os.environ["ASRDIR"]; CL=json.load(open(os.environ["CLAUSES"],encoding="utf-8")); root=sys.argv[1]; out=sys.argv[2]
asr = so.OfflineRecognizer.from_transducer(encoder=f"{ASR}/encoder-epoch-99-avg-1.int8.onnx", decoder=f"{ASR}/decoder-epoch-99-avg-1.onnx",
    joiner=f"{ASR}/joiner-epoch-99-avg-1.int8.onnx", tokens=f"{ASR}/tokens.txt", num_threads=4, sample_rate=16000, feature_dim=80,
    decoding_method="greedy_search", modeling_unit="bpe", bpe_vocab=f"{ASR}/bpe.model")
norm=lambda t: re.sub(r"[^가-힣a-zA-Z0-9]","",t)
def lev(a,b):
    d=list(range(len(b)+1))
    for i,ca in enumerate(a,1):
        p,d[0]=d[0],i
        for j,cb in enumerate(b,1): p,d[j]=d[j],min(d[j]+1,d[j-1]+1,p+(ca!=cb))
    return d[len(b)]
def hear(p):
    with wave.open(p) as w:
        sr=w.getframerate(); x=np.frombuffer(w.readframes(w.getnframes()),dtype="<i2").astype(np.float32)/32768
    x16=np.interp(np.linspace(0,len(x)-1,int(len(x)*16000/sr)),np.arange(len(x)),x).astype(np.float32)
    s=asr.create_stream(); s.accept_waveform(16000,x16); asr.decode_stream(s); return s.result.text
dirs=sorted(d for d in os.listdir(root) if d.startswith("d")); os.makedirs(out,exist_ok=True); tot_before=tot_after=0; log=[]
for c in CL:
    ref=norm(c["caption"]); best=None; sc0=None
    for d in dirs:
        t=hear(f"{root}/{d}/{c['id']}.wav"); e=lev(ref,norm(t))
        if c["id"]=="op2" and not re.search(r"니다|습니|입니|임니|있다|입인|잇다",t): e+=5
        if c["id"]=="en3" and not re.search(r"니다|습니|였습|였스|여씀",t): e+=5
        if d=="d0": sc0=e
        if best is None or e<best[0]: best=(e,d,t)
    shutil.copy(f"{root}/{best[1]}/{c['id']}.wav",f"{out}/{c['id']}.wav"); tot_before+=sc0; tot_after+=best[0]
    log.append(f"{c['id']:5s} 처음 {sc0:2d} → 고른 것 {best[0]:2d} ({best[1]}) {best[2][:14]}")
print("\n".join(log)); print(f"오류 합계(글자): 처음 {tot_before} → 고른 뒤 {tot_after}")
