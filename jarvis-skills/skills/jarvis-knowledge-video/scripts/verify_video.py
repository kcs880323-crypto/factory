#!/usr/bin/env python3
"""렌더한 영상을 측정해서 합격/불합격을 알려 준다 (소리는 들을 수 없고 측정만 한다).

  python verify_video.py <영상.mp4> <timing.json>
검사: 1920x1080 30fps H.264, 오디오 있음, 길이 30~40초, 소리 크기 -17~-13 LUFS, 절마다 소리가 들어 있음(RMS > -35 dBFS).
"""
import json, re, subprocess, sys
import numpy as np

video, timing_path = sys.argv[1], sys.argv[2]
timing = json.load(open(timing_path, encoding="utf-8"))
pr = json.loads(subprocess.run(["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", video], capture_output=True, text=True, check=True).stdout)
v = next(s for s in pr["streams"] if s["codec_type"] == "video")
au = next((s for s in pr["streams"] if s["codec_type"] == "audio"), None)
dur = float(pr["format"]["duration"])
fails = []
def check(ok, msg):
    print(("PASS " if ok else "FAIL ") + msg)
    if not ok: fails.append(msg)
check((v["width"], v["height"]) == (1920, 1080), f"해상도 {v['width']}x{v['height']}")
check(v["r_frame_rate"] == "30/1" and v["codec_name"] == "h264", f"{v['codec_name']} {v['r_frame_rate']}")
check(au is not None, "오디오 스트림 있음")
check(30.0 <= dur <= 40.1, f"길이 {dur:.2f}초")
if au:
    out = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", video, "-af", "ebur128=peak=true", "-f", "null", "-"], capture_output=True, text=True).stderr
    summ = out[out.rfind("Summary:"):]
    lufs = float(re.search(r"I:\s+(-?[\d.]+) LUFS", summ).group(1)); peak = float(re.search(r"Peak:\s+(-?[\d.]+) dBFS", summ).group(1))
    check(-17 <= lufs <= -13, f"소리 크기 {lufs} LUFS")
    check(peak <= -1.0, f"최대 {peak} dBFS")
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", video, "-f", "s16le", "-ac", "1", "-ar", "16000", "-"], capture_output=True).stdout
    x = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768; sr = 16000
    for t in timing:
        seg = x[int(t["start"] * sr): int(t["end"] * sr)]
        db = 20 * np.log10(np.sqrt(np.mean(seg ** 2) + 1e-12))
        check(db > -35, f"절 {t['id']} {t['start']:.1f}-{t['end']:.1f}s RMS {db:.1f} dBFS")
print("모두 합격" if not fails else f"불합격 {len(fails)}건")
sys.exit(1 if fails else 0)
