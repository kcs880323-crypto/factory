#!/usr/bin/env python3
"""프로젝트의 assets 에 GSAP(로컬 파일)와 한글 글꼴(Pretendard woff2)을 넣는다. 이미 있는 파일은 덮어쓰지 않는다.

  <PY> -I prepare_assets.py <프로젝트 폴더>

필요: npm (Node.js). 고정 버전: gsap@3.14.2 (렌더 때 CDN 을 쓰지 않기 위해 로컬로 둔다), pretendard@1.3.9 (OFL-1.1).
"""
import pathlib, shutil, subprocess, sys, tarfile, tempfile
from fontTools.ttLib import woff2, TTFont

proj = pathlib.Path(sys.argv[1]); assets = proj / "assets"; fonts = assets / "fonts"
fonts.mkdir(parents=True, exist_ok=True)
npm = shutil.which("npm")
if not npm:
    raise SystemExit("npm 이 없습니다. Node.js 를 먼저 설치하세요 (자동 설치하지 않습니다).")
want_gsap = assets / "gsap.min.js"
want_fonts = {w: fonts / f"Pretendard-{w}.woff2" for w in ("Regular", "Bold", "ExtraBold")}
if want_gsap.exists() and all(p.exists() for p in want_fonts.values()):
    print("이미 있음: gsap.min.js, Pretendard woff2 3종"); sys.exit(0)

def pack(spec, tmp):
    r = subprocess.run([npm, "pack", spec, "--silent"], cwd=tmp, capture_output=True, text=True, check=True)
    tgz = pathlib.Path(tmp) / r.stdout.strip().splitlines()[-1]
    dest = pathlib.Path(tmp) / spec.replace("@", "_").replace("/", "_")
    dest.mkdir()
    with tarfile.open(tgz) as tf:
        for m in tf.getmembers():
            if not str((dest / m.name).resolve()).startswith(str(dest.resolve())) or m.issym() or m.islnk():
                raise SystemExit(f"위험한 경로: {m.name}")
        tf.extractall(dest, filter="data") if hasattr(tarfile, "data_filter") else tf.extractall(dest)
    return dest / "package"

with tempfile.TemporaryDirectory() as tmp:
    if not want_gsap.exists():
        shutil.copy(pack("gsap@3.14.2", tmp) / "dist" / "gsap.min.js", want_gsap)
        print("넣음:", want_gsap)
    if not all(p.exists() for p in want_fonts.values()):
        pkg = pack("pretendard@1.3.9", tmp)
        for w, out in want_fonts.items():
            if out.exists():
                continue
            f = TTFont(str(pkg / "dist" / "public" / "static" / f"Pretendard-{w}.otf"))
            f.flavor = "woff2"; f.save(str(out))
            print("넣음:", out)
