#!/usr/bin/env python3
"""절(clause)마다 나레이션 WAV 를 만든다. 반드시 setup_tts.py 가 만든 가상환경 파이썬으로, -I 를 붙여 실행한다.

  <PY> -I tts_clauses.py <모델 폴더> <clauses.json> <출력 폴더> [속도] [톤] [목소리=5]

모델 폴더로 엔진이 정해진다 (setup_tts.py 가 출력하는 MODEL= 이 Supertonic 3, KSS_MODEL= 이 옛 KSS):
  Supertonic 3 (기본)  속도 기본 1.2, 목소리 번호 0~9 (아래 표). 44.1kHz. 추론 단계 5
  KSS (옛 음성)        속도 기본 1.25, 목소리 번호는 무시 (한 명)

Supertonic 3 목소리 (같은 대본 19절을 속도 1.2 로 합성해 잰 평균 음높이. 성별은 소리로 확인하지 못했고 음높이로 구분):
  0·1·3  높은 목소리 (약 230~280Hz). 1 = 가장 높음(279), 0 = 또렷하고 밝은 음색(2900Hz대), 3 = 차분한 음색
  2·4    중간 (약 185~190Hz)
  5·6    중간~낮음 (약 175~180Hz). 5 = 기본(사용자가 샘플을 듣고 고름. 음높이 변화 보통), 6 = 음높이 변화 폭이 가장 크다
  7·8·9  낮은 목소리 (약 115~135Hz)
  (Supertonic 2 와는 번호가 같아도 다른 목소리다. 옛 모델 폴더를 직접 쓰면 이 표가 맞지 않는다.)

clauses.json: [{"id":"1a","line":1,"spoken":"낭독문","caption":"자막문"}, ...]
  spoken  소리 내어 읽는 글 (숫자는 읽는 형태로: 천구백칠십삼 년, 8자는 팔자)
  caption 화면에 보이는 글 (숫자 그대로 써도 된다)

톤 (합성 뒤에 ffmpeg 로 높낮이와 음색을 바꾼다. 낭독 길이는 그대로):
  natural  합성 그대로 (Supertonic 기본: 이미 또렷하고, 아래 처리를 하면 받아쓰기 오류가 늘었다. Supertonic 2 목소리 6 에서 20.8% → 28.1%)
  bright   음높이 +2 반음, 3kHz 근처와 고음을 살짝 키우고 가볍게 눌러 고르게 한다 (KSS 기본)
  cheer    더 밝고 높게: 음높이 +3.5 반음 (같은 처리)
참고: KSS 는 느낌표(!)를 읽지 못하고 건너뛴다 ("Skip unknown phonemes U+0021"). 느낌표로 억양을 바꿀 수는 없다.
"""
import json, pathlib, shutil, subprocess, sys, wave
import numpy as np
import sherpa_onnx

TONES = {"natural": None, "bright": 2.0, "cheer": 3.5}
EQ = "equalizer=f=3200:width_type=o:width=1.2:g=3,highshelf=f=5500:g=3,acompressor=threshold=-20dB:ratio=2.5:attack=5:release=80:makeup=2,alimiter=limit=0.95"

model_dir, clauses_json, out_dir = sys.argv[1], sys.argv[2], pathlib.Path(sys.argv[3])
is_st = (pathlib.Path(model_dir) / "duration_predictor.int8.onnx").exists()      # Supertonic 인가, KSS 인가
speed = float(sys.argv[4]) if len(sys.argv) > 4 else (1.2 if is_st else 1.25)
tone = sys.argv[5] if len(sys.argv) > 5 else ("natural" if is_st else "bright")
voice = int(sys.argv[6]) if len(sys.argv) > 6 else 5
if is_st and not 0 <= voice <= 9:
    raise SystemExit("Supertonic 목소리 번호는 0~9 입니다.")
if tone not in TONES:
    raise SystemExit(f"톤은 {', '.join(TONES)} 중 하나여야 합니다: {tone}")
out_dir.mkdir(parents=True, exist_ok=True)

semis = TONES[tone]
ffmpeg = shutil.which("ffmpeg")
if semis is not None:
    if not ffmpeg:
        raise SystemExit("ffmpeg 가 없습니다 (톤 처리에 필요). 설치하거나 톤을 natural 로 하세요.")
    has_rb = "rubberband" in subprocess.run([ffmpeg, "-hide_banner", "-filters"], capture_output=True, text=True).stdout
    ratio = 2 ** (semis / 12)


def shift(src, dst, sr):
    """높낮이를 올리고 밝게 만든다. rubberband 가 있으면 그것을, 없으면 asetrate+atempo 로 (길이는 같다)."""
    if has_rb:
        af = f"rubberband=pitch={ratio:.4f}:tempo=1:formant=shifted:pitchq=quality,{EQ}"
    else:
        af = f"asetrate={sr}*{ratio:.4f},aresample={sr},atempo={1 / ratio:.4f},{EQ}"
    subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(src), "-af", af, "-ar", str(sr), "-ac", "1", "-c:a", "pcm_s16le", str(dst)], check=True)


if is_st:
    D = model_dir
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
            duration_predictor=f"{D}/duration_predictor.int8.onnx", text_encoder=f"{D}/text_encoder.int8.onnx",
            vector_estimator=f"{D}/vector_estimator.int8.onnx", vocoder=f"{D}/vocoder.int8.onnx",
            tts_json=f"{D}/tts.json", unicode_indexer=f"{D}/unicode_indexer.bin", voice_style=f"{D}/voice.bin"),
        num_threads=2))
    gen = sherpa_onnx.GenerationConfig(); gen.sid = voice; gen.speed = speed; gen.num_steps = 5; gen.extra = {"lang": "ko"}
else:
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(
            model=f"{model_dir}/ko_KO-kss_low.onnx", tokens=f"{model_dir}/tokens.txt", data_dir=f"{model_dir}/espeak-ng-data"),
        num_threads=2))
tts = sherpa_onnx.OfflineTts(cfg)
total = 0.0
for c in json.load(open(clauses_json, encoding="utf-8")):
    a = tts.generate(c["spoken"], gen) if is_st else tts.generate(c["spoken"], sid=0, speed=speed)
    x = np.asarray(a.samples, dtype=np.float32)
    if len(x) == 0:
        raise SystemExit(f"{c['id']}: 음성이 비어 있습니다. 낭독문을 확인하세요: {c['spoken']}")
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2")
    dst = out_dir / f"{c['id']}.wav"
    raw = dst if semis is None else out_dir / f"{c['id']}.raw.wav"
    with wave.open(str(raw), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(a.sample_rate); w.writeframes(pcm.tobytes())
    if semis is not None:
        shift(raw, dst, a.sample_rate)
        raw.unlink()
    with wave.open(str(dst)) as w:
        d = w.getnframes() / w.getframerate()
    total += d
    print(f"{c['id']} {d:.2f}s")
print(f"낭독 합계 {total:.2f}s (엔진 {'Supertonic 목소리 ' + str(voice) if is_st else 'KSS'}, 속도 {speed}, 톤 {tone})")
