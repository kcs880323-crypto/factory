#!/usr/bin/env python3
"""렌더한 영상이 "오리만 나오는 영상"이 아닌지, 본편에 그림이 실제로 나오고 움직이는지 화면을 직접 재서 알려 준다.

  <PY> -I check_frames.py <영상.mp4> <parts.json>      (parts.json 은 build_track.py 가 만든 audio/parts.json)

원리: 영상을 초당 2장씩 작게 뽑아서
  - 찰스의 노란 배경(#FCE06B 계열)이 화면의 절반 넘게 차지하는 장면을 센다.
  - 오프닝 구간과 엔딩 구간에는 그런 장면이 있어야 하고 (찰스가 나옴),
  - 본편 구간에는 거의 없어야 하고 (그림 장면이 나와야 함, 오리가 본편을 차지하면 불합격),
  - 본편 화면이 시간에 따라 바뀌어야 한다 (바뀌는 표본이 25% 미만이거나 6초 넘게 멈춰 있으면 불합격).
    (참고 측정: 설계표 방식 꿀벌 28/51장·최장 정지 1.5초, 직접 코드 방식 꿀벌 40/51장·1.0초, 오리만 나오는 영상 0/51장·25초)
소리와 그림이 맞는지, 그림이 보기 좋은지는 재지 못한다. snapshot 으로 눈으로 보는 것을 대신하지 않는다.
"""
import json, subprocess, sys
import numpy as np

if len(sys.argv) < 3:
    raise SystemExit(__doc__)
video, parts_path = sys.argv[1], sys.argv[2]
parts = json.load(open(parts_path, encoding="utf-8"))
FPS, W, H = 2, 96, 54
raw = subprocess.run(["ffmpeg", "-v", "error", "-i", video, "-vf", f"fps={FPS},scale={W}:{H}", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                     capture_output=True, check=True).stdout
fr = np.frombuffer(raw, dtype=np.uint8).reshape(-1, H, W, 3).astype(np.int16)
n = len(fr)
t = (np.arange(n) + 0.5) / FPS
r, g, b = fr[..., 0], fr[..., 1], fr[..., 2]
yellow = ((r > 215) & (g > 165) & (b < 160) & (r - b > 85)).mean(axis=(1, 2))      # 찰스 배경 색 비율
diff = np.r_[0.0, np.abs(fr[1:] - fr[:-1]).mean(axis=(1, 2, 3))]                      # 직전 표본과의 평균 차이

fails = []


def check(ok, msg):
    print(("PASS " if ok else "FAIL ") + msg)
    if not ok: fails.append(msg)


def window(name, margin):
    a, e = parts[name]["start"] + margin, parts[name]["end"] - margin
    return (t >= a) & (t <= e)


op, bd, en = window("opening", 0.2), window("body", 0.6), window("ending", 0.2)
check(op.sum() > 0 and (yellow[op] > 0.5).any(), f"오프닝에 찰스 화면이 있음 ({int((yellow[op] > 0.5).sum())}/{int(op.sum())}장)")
check(en.sum() > 0 and (yellow[en] > 0.5).any(), f"엔딩에 찰스 화면이 있음 ({int((yellow[en] > 0.5).sum())}/{int(en.sum())}장)")
duck = int((yellow[bd] > 0.5).sum()); tot = int(bd.sum())
check(tot > 0 and duck <= max(1, int(0.1 * tot)), f"본편에 오리(노란 찰스 화면)가 {duck}/{tot}장 (10% 이하여야 함. 본편은 그림 장면으로 채운다)")
mv = diff[bd] > 0.6
moving = int(mv.sum())
best = cur = 0
for x in mv:                                    # 화면이 안 바뀌는 가장 긴 구간
    cur = 0 if x else cur + 1; best = max(best, cur)
check(tot > 0 and moving >= 0.25 * tot, f"본편 화면이 바뀌는 표본 {moving}/{tot}장 (25% 이상이어야 함. 정지 화면이면 불합격)")
check(tot > 0 and best / FPS <= 6.0, f"본편에서 화면이 안 바뀌는 가장 긴 구간 {best / FPS:.1f}초 (6초 이하여야 함)")
dark = float(np.mean([f.mean() for f in fr[bd]])) if tot else 0
print(f"참고: 본편 평균 밝기 {dark:.0f}/255 (어두운 바탕 + 주제 색 구성이면 보통 10~60)")
sys.exit(1 if fails else 0)
