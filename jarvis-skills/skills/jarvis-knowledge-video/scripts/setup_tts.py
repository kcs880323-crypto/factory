#!/usr/bin/env python3
"""지식영상 생성용 음성 환경을 한 번만 만든다 (파이썬 가상환경 + 고정된 한국어 음성 모델).

  python setup_tts.py            없는 것만 만든다. 이미 있으면 아무것도 내려받지 않는다. (기본 음성: Supertonic 3)
  python setup_tts.py --with-kss 옛 음성(KSS, 약 67MB)도 함께 받는다 (비교용·옛 영상 재현용. 학습 데이터가 비상업 조건일 가능성)
  python setup_tts.py --check    상태만 출력한다 (아무것도 설치하지 않는다).

위치: ~/.jarvis-knowledge-video  (JARVIS_KV_HOME 환경변수로 바꿀 수 있다). 지우면 모두 사라진다.
마지막 줄에 PY=<가상환경 파이썬>, MODEL=<Supertonic 폴더> (그리고 --with-kss 이면 KSS_MODEL=<KSS 폴더>) 를 출력한다.
"""
import hashlib, os, pathlib, subprocess, sys, tarfile, tempfile, urllib.request, venv

HOME = pathlib.Path(os.environ.get("JARVIS_KV_HOME", pathlib.Path.home() / ".jarvis-knowledge-video"))
# 시험한 값으로 고정한다. sherpa-onnx 2.0.0 은 이 모델에서 "no frontend available" 로 실패해서 1.13.8 을 쓴다.
# 기본 음성: Supertonic 3 (Supertone, 온디바이스, 31개 언어·한국어 포함). 코드 MIT, 모델 가중치 OpenRAIL-M(상업 이용 가능, 사용 제한 있음). 약 128MB.
# (이전 기본이던 Supertonic 2 는 목소리 번호가 다르다. 받지 않는다.)
ST_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"
ST_SHA256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427"
ST_NAME = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
# 옛 음성: KSS 한국어 데이터로 학습된 mimic3 (여성 한 명). 받아쓰기 시험에서 Supertonic 보다 오류가 2배 가까이 많았다.
MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-mimic3-ko_KO-kss_low.tar.bz2"
MODEL_SHA256 = "f015d1d15a52ed00d6fe22757c5ef4a74283c53daf829c838fa5c22616ed789c"
MODEL_NAME = "vits-mimic3-ko_KO-kss_low"
PACKAGES = ["sherpa-onnx==1.13.8", "sherpa-onnx-core==1.13.8", "numpy", "fonttools", "brotli"]

VENV = HOME / "venv"
PY = VENV / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
MODEL = HOME / "models" / ST_NAME          # 기본(Supertonic)
KSS_MODEL = HOME / "models" / MODEL_NAME     # 옛 음성(선택)


def packages_ok():
    if not PY.exists():
        return False
    code = "import sherpa_onnx, numpy, fontTools, brotli; assert sherpa_onnx.__version__ == '1.13.8'"
    return subprocess.run([str(PY), "-I", "-c", code], capture_output=True).returncode == 0


def model_ok():
    return all((MODEL / n).exists() for n in ("duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
                                              "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin"))


def kss_ok():
    return all((KSS_MODEL / n).exists() for n in ("ko_KO-kss_low.onnx", "tokens.txt", "espeak-ng-data"))


def fetch_model(url, sha256, dest_parent, label, ok):
    dest_parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=HOME) as tmp:
        tar_path = pathlib.Path(tmp) / "model.tar.bz2"
        print(f"{label} 내려받기: {url}")
        with urllib.request.urlopen(url, timeout=180) as r, open(tar_path, "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
        got = hashlib.sha256(tar_path.read_bytes()).hexdigest()
        if got != sha256:
            raise SystemExit(f"{label}의 SHA-256 이 시험한 값과 다릅니다 (받은 값 {got}). 설치하지 않았습니다.")
        safe_extract(tar_path, dest_parent)
    if not ok():
        raise SystemExit(f"{label} 압축을 풀었지만 필요한 파일이 없습니다.")


def safe_extract(tar_path, dest):
    dest = pathlib.Path(dest).resolve()
    with tarfile.open(tar_path, "r:bz2") as tf:
        for m in tf.getmembers():
            target = (dest / m.name).resolve()
            if not str(target).startswith(str(dest) + os.sep) or m.issym() or m.islnk() or m.isdev():
                raise SystemExit(f"위험한 경로가 들어 있는 압축 파일입니다: {m.name}")
        tf.extractall(dest, filter="data") if hasattr(tarfile, "data_filter") else tf.extractall(dest)


def main():
    with_kss = "--with-kss" in sys.argv
    if "--check" in sys.argv:
        print(f"HOME={HOME}\nvenv/패키지: {'있음' if packages_ok() else '없음'}\n음성 모델(Supertonic): {'있음' if model_ok() else '없음'}\n옛 음성(KSS): {'있음' if kss_ok() else '없음'}")
        sys.exit(0 if packages_ok() and model_ok() else 1)
    HOME.mkdir(parents=True, exist_ok=True)
    if not packages_ok():
        if not PY.exists():
            print(f"가상환경 만들기: {VENV}")
            venv.create(VENV, with_pip=True)
        print("패키지 설치:", " ".join(PACKAGES))
        subprocess.run([str(PY), "-m", "pip", "install", "--quiet", "--disable-pip-version-check", *PACKAGES], check=True)
        if not packages_ok():
            raise SystemExit("패키지 설치 후에도 확인에 실패했습니다.")
    if not model_ok():
        fetch_model(ST_URL, ST_SHA256, MODEL.parent, "음성 모델 Supertonic 3 (약 128MB)", model_ok)
    if with_kss and not kss_ok():
        fetch_model(MODEL_URL, MODEL_SHA256, KSS_MODEL.parent, "옛 음성 KSS (약 67MB)", kss_ok)
    print(f"PY={PY}")
    print(f"MODEL={MODEL}")
    if kss_ok():
        print(f"KSS_MODEL={KSS_MODEL}")


if __name__ == "__main__":
    main()
