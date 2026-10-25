#!/usr/bin/env python3
"""절(clause)마다 나레이션 WAV 를 만든다. 반드시 setup_tts.py 가 만든 가상환경 파이썬으로, -I 를 붙여 실행한다.

  <PY> -I tts_clauses.py <모델 폴더> <clauses.json> <출력 폴더> [속도=1.25]

clauses.json: [{"id":"1a","line":1,"spoken":"낭독문","caption":"자막문"}, ...]
  spoken  소리 내어 읽는 글 (숫자는 읽는 형태로: 천구백칠십삼 년, 8자는 팔자)
  caption 화면에 보이는 글 (숫자 그대로 써도 된다)
"""
import json, pathlib, sys, wave
import numpy as np
import sherpa_onnx

model_dir, clauses_json, out_dir = sys.argv[1], sys.argv[2], pathlib.Path(sys.argv[3])
speed = float(sys.argv[4]) if len(sys.argv) > 4 else 1.25
out_dir.mkdir(parents=True, exist_ok=True)
cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    vits=sherpa_onnx.OfflineTtsVitsModelConfig(
        model=f"{model_dir}/ko_KO-kss_low.onnx", tokens=f"{model_dir}/tokens.txt", data_dir=f"{model_dir}/espeak-ng-data"),
    num_threads=2))
tts = sherpa_onnx.OfflineTts(cfg)
total = 0.0
for c in json.load(open(clauses_json, encoding="utf-8")):
    a = tts.generate(c["spoken"], sid=0, speed=speed)
    x = np.asarray(a.samples, dtype=np.float32)
    if len(x) == 0:
        raise SystemExit(f"{c['id']}: 음성이 비어 있습니다. 낭독문을 확인하세요: {c['spoken']}")
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2")
    with wave.open(str(out_dir / f"{c['id']}.wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(tts.sample_rate); w.writeframes(pcm.tobytes())
    d = len(x) / tts.sample_rate
    total += d
    print(f"{c['id']} {d:.2f}s")
print(f"낭독 합계 {total:.2f}s (속도 {speed})")
