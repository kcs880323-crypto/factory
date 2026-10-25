#!/usr/bin/env python3
"""지식영상 생성용 음성 환경을 한 번만 만든다 (파이썬 가상환경 + 고정된 한국어 음성 모델).

  python setup_tts.py            없는 것만 만든다. 이미 있으면 아무것도 내려받지 않는다.
  python setup_tts.py --check    상태만 출력한다 (아무것도 설치하지 않는다).

위치: ~/.jarvis-knowledge-video  (JARVIS_KV_HOME 환경변수로 바꿀 수 있다). 지우면 모두 사라진다.
마지막 두 줄에 PY=<가상환경 파이썬>, MODEL=<모델 폴더> 를 출력한다.
"""
import hashlib, os, pathlib, subprocess, sys, tarfile, tempfile, urllib.request, venv

HOME = pathlib.Path(os.environ.get("JARVIS_KV_HOME", pathlib.Path.home() / ".jarvis-knowledge-video"))
# 시험한 값으로 고정한다. sherpa-onnx 2.0.0 은 이 모델에서 "no frontend available" 로 실패해서 1.13.8 을 쓴다.
MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-mimic3-ko_KO-kss_low.tar.bz2"
MODEL_SHA256 = "f015d1d15a52ed00d6fe22757c5ef4a74283c53daf829c838fa5c22616ed789c"
MODEL_NAME = "vits-mimic3-ko_KO-kss_low"
PACKAGES = ["sherpa-onnx==1.13.8", "sherpa-onnx-core==1.13.8", "numpy", "fonttools", "brotli"]

VENV = HOME / "venv"
PY = VENV / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
MODEL = HOME / "models" / MODEL_NAME


def packages_ok():
    if not PY.exists():
        return False
    code = "import sherpa_onnx, numpy, fontTools, brotli; assert sherpa_onnx.__version__ == '1.13.8'"
    return subprocess.run([str(PY), "-I", "-c", code], capture_output=True).returncode == 0


def model_ok():
    return all((MODEL / n).exists() for n in ("ko_KO-kss_low.onnx", "tokens.txt", "espeak-ng-data"))


def safe_extract(tar_path, dest):
    dest = pathlib.Path(dest).resolve()
    with tarfile.open(tar_path, "r:bz2") as tf:
        for m in tf.getmembers():
            target = (dest / m.name).resolve()
            if not str(target).startswith(str(dest) + os.sep) or m.issym() or m.islnk() or m.isdev():
                raise SystemExit(f"위험한 경로가 들어 있는 압축 파일입니다: {m.name}")
        tf.extractall(dest, filter="data") if hasattr(tarfile, "data_filter") else tf.extractall(dest)


def main():
    if "--check" in sys.argv:
        print(f"HOME={HOME}\nvenv/패키지: {'있음' if packages_ok() else '없음'}\n모델: {'있음' if model_ok() else '없음'}")
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
        MODEL.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=HOME) as tmp:
            tar_path = pathlib.Path(tmp) / "model.tar.bz2"
            print(f"음성 모델 내려받기(약 67MB): {MODEL_URL}")
            with urllib.request.urlopen(MODEL_URL, timeout=120) as r, open(tar_path, "wb") as f:
                while chunk := r.read(1 << 20):
                    f.write(chunk)
            got = hashlib.sha256(tar_path.read_bytes()).hexdigest()
            if got != MODEL_SHA256:
                raise SystemExit(f"모델의 SHA-256 이 시험한 값과 다릅니다 (받은 값 {got}). 설치하지 않았습니다.")
            safe_extract(tar_path, MODEL.parent)
        if not model_ok():
            raise SystemExit("모델 압축을 풀었지만 필요한 파일이 없습니다.")
    print(f"PY={PY}")
    print(f"MODEL={MODEL}")


if __name__ == "__main__":
    main()
