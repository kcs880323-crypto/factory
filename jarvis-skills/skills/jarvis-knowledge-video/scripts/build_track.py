#!/usr/bin/env python3
"""절별 WAV 를 간격을 두고 이어 붙여 나레이션 한 트랙과 절별 시각표(timing.json)를 만든다.

  <PY> -I build_track.py <clauses.json> <절 WAV 폴더> <출력 폴더> [--lead 0.5] [--intra 0.14] [--inter 0.4] [--min 35] [--max 45]

출력: <출력 폴더>/narration.wav (48kHz 모노, ffmpeg loudnorm -16 LUFS), duration.txt,
      timing.json (자막 단위: cap_join 절은 앞 절에 합쳐진다), clauses_timing.json (절마다), parts.json (오프닝·본편·엔딩 구간)
영상 길이는 (마지막 낭독이 끝나는 시각 + --tail 초)를 올림해 --min..--max 안으로 맞춘다.
낭독이 --max-tail 초를 넘으면 대본을 줄이거나 속도를 올리라고 알리고 실패한다.
(오프닝·엔딩이 붙으면 약 11초가 고정이라 기본 범위는 35~45초이고, 마지막 장면을 2초 이상 보여 주려고 tail 은 2초.)
"""
import argparse, json, math, pathlib, shutil, subprocess, wave
import numpy as np

ap = argparse.ArgumentParser()
ap.add_argument("clauses"); ap.add_argument("clause_dir"); ap.add_argument("out_dir")
ap.add_argument("--lead", type=float, default=0.5)    # 영상 시작 후 첫 낭독까지
ap.add_argument("--intra", type=float, default=0.14)  # 같은 줄 안 절 사이
ap.add_argument("--inter", type=float, default=0.4)   # 줄 사이
ap.add_argument("--min", type=float, default=35.0, dest="tmin")
ap.add_argument("--max", type=float, default=45.0, dest="tmax")
ap.add_argument("--tail", type=float, default=2.0)
a = ap.parse_args()
out = pathlib.Path(a.out_dir); out.mkdir(parents=True, exist_ok=True)

clauses = json.load(open(a.clauses, encoding="utf-8"))
t = a.lead; parts = []; timing = []; sr = None; prev = None
for c in clauses:
    with wave.open(str(pathlib.Path(a.clause_dir) / f"{c['id']}.wav")) as w:
        sr = w.getframerate()
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768
    if prev is not None:
        t += c["gap_before"] if "gap_before" in c else (a.inter if c["line"] != prev else a.intra)
    d = len(x) / sr
    timing.append({"id": c["id"], "line": c["line"], "start": round(t, 3), "end": round(t + d, 3), "caption": c["caption"],
                   "part": c.get("part"), "cap_join": bool(c.get("cap_join"))})
    parts.append((t, x)); t += d; prev = c["line"]
speech_end = timing[-1]["end"]
if speech_end > a.tmax - a.tail:
    raise SystemExit(f"낭독이 {speech_end:.1f}초에 끝납니다 (최대 {a.tmax - a.tail:.0f}초). 본편 대본을 줄이거나 속도를 올리세요.")
total = float(min(a.tmax, max(a.tmin, math.ceil(speech_end + a.tail))))
track = np.zeros(int(total * sr), dtype=np.float32)
for st, x in parts:
    i = int(round(st * sr)); track[i:i + len(x)] += x[: len(track) - i]
raw = out / "narration_raw.wav"
with wave.open(str(raw), "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
    w.writeframes((np.clip(track, -1, 1) * 32767).astype("<i2").tobytes())
if not shutil.which("ffmpeg"):
    raise SystemExit("ffmpeg 가 없습니다. 설치한 뒤 다시 실행하세요 (자동 설치하지 않습니다).")
subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(raw), "-af", "loudnorm=I=-16:TP=-1.5:LRA=7", "-ar", "48000", "-ac", "1", str(out / "narration.wav")], check=True)
json.dump(timing, open(out / "clauses_timing.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
caps = []
for r in timing:                      # 자막 단위: cap_join 절은 앞 자막의 끝을 늘린다
    if r["cap_join"] and caps:
        caps[-1]["end"] = r["end"]
    else:
        caps.append(dict(r))
json.dump(caps, open(out / "timing.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
parts = {}
for r in timing:
    if r["part"]:
        p = parts.setdefault(r["part"], {"start": r["start"], "end": r["end"]})
        p["end"] = r["end"]
json.dump(parts, open(out / "parts.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
(out / "duration.txt").write_text(f"{total:g}\n")
print(f"낭독 끝 {speech_end:.2f}s, 영상 길이 {total:g}s")
for r in timing:
    print(r["id"], r["start"], r["end"])
