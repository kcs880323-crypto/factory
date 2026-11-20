#!/usr/bin/env python3
"""URL 하나를 안드로이드 앱(APK)으로 만든다 — Hermit 의 '라이트 앱'과 같은 방식.

  python url_app.py doctor              준비 상태 확인 (파이썬, 자바, 빌드 도구, 서명 키)
  python url_app.py setup               구글 공식 빌드 도구를 받아 해시를 확인하고 설치 (처음 한 번, 약 130MB 받음)
  python url_app.py build <URL> [옵션]   APK 만들기 (같은 URL 을 다시 만들면 버전이 올라가 '업데이트'로 설치된다)
  python url_app.py list                만든 앱 목록
  python url_app.py selftest            자체 시험 (빌드 도구가 있으면 실제 APK 까지 만들어 검사)

표준 라이브러리만 쓴다. 자바(JDK 17 이상)가 필요하다.
작업 폴더: ~/.jarvis-url-app (환경변수 JARVIS_URL_APP_HOME 으로 바꿀 수 있다)
  toolchain/  구글 빌드 도구 (aapt2, d8, apksigner, android.jar)
  signing/    서명 키 (urlapp.p12 + password.txt, 나만 읽게). 잃어버리면 같은 앱을 '업데이트'할 수 없다
  apps.json   만든 앱 목록과 버전
  apk/        만든 APK
"""
import argparse
import base64
import hashlib
import html.parser
import io
import json
import os
import re
import secrets
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SHELL_DIR = HERE / "shell"  # 안드로이드 껍데기 자바 코드 (MainActivity, WidgetProvider)
DOWNLOAD_JS = HERE / "shell" / "download.js"  # 페이지가 만든 파일(blob:) 받기 도우미. 앱마다 assets 로 들어간다
NOTIFY_JS = HERE / "shell" / "notify.js"  # --notify: 웹 알림(Notification)을 폰 알림으로 바꾸는 도우미
SEAL_TOOL = HERE / "UrlAppSeal.java"  # --update-encrypt: 업데이트 파일 암호화 (AES-256-GCM)
HOTWORD_DIR = HERE / "hotword"  # --hotword: '하이 자비스' 호출어 엔진(VoskEngine)·채움말 (이 앱들에만 따로 들어간다)
MAVEN = "https://repo1.maven.org/maven2/"
HOTWORD_LIBS = (  # (Maven 경로, 크기, SHA-256): Vosk 안드로이드(Apache-2.0)와 그것이 쓰는 JNA(Apache-2.0/LGPL 이중)
    ("com/alphacephei/vosk-android/0.3.75/vosk-android-0.3.75.aar", 13472638,
     "ab2f8b91ac8051561aa325546b35fed9a68b36b8121bac5c6fb927525c4adfad"),
    ("net/java/dev/jna/jna/5.18.1/jna-5.18.1.aar", 522677,
     "7f053e3ec99e14dd71259c82c1c8a02738d64a13c31226b2acc170f3060951e0"),
)
HOTWORD_ABIS = ("arm64-v8a", "armeabi-v7a")
# 폰이 '받기'를 누를 때 받는 음성 모델 (APK 에는 넣지 않는다). 받은 파일은 크기·SHA-256 이 맞을 때만 푼다.
HOTWORD_MODEL = {"model_url": "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip",
                 "model_size": 86914329,
                 "model_sha256": "eea36124087fed26c59996a4761519458e3bd185e8ea9d9865ad8760c4a1d989",
                 "model_dir": "vosk-model-small-ko-0.22"}
# --watch: 워치(Wear OS) 앱 — 타일 + 음성 질문. 폰과 워치는 Google Play 서비스 Wearable(Data Layer)로만 주고받는다.
WATCH_DIR = HERE / "watch"          # 워치 앱 (WatchTile·WatchAskActivity·WatchListener …)
WATCHLINK_DIR = HERE / "watchlink"  # 폰 쪽 워치 연결 (GmsWatchLink·PhoneWatchService) — 이 앱들에만 따로
GMAVEN = "https://dl.google.com/dl/android/maven2/"
WATCH_MIN_SDK = 30                  # Wear OS 3 이상
GMS_LIBS = (  # (저장소, 경로, 크기, SHA-256) — Google Play 서비스 Wearable 과 그것이 쓰는 기본 묶음 (Android SDK 라이선스)
    (GMAVEN, "com/google/android/gms/play-services-wearable/20.0.1/play-services-wearable-20.0.1.aar", 588168,
     "41976c694586ef58b3e50fb858248fa7e39aa7dde70eaaa132fe75082eca39d4"),
    (GMAVEN, "com/google/android/gms/play-services-base/18.5.0/play-services-base-18.5.0.aar", 439602,
     "59a5c0c2da12311d75d965ce1f419498536b1a167fb28ff7dfc2dfd9cefa4157"),
    (GMAVEN, "com/google/android/gms/play-services-basement/18.4.0/play-services-basement-18.4.0.aar", 286283,
     "ce5c936fd66814b3602f5c6a5e929911ff973d4b05f6de9996da596bef97cad2"),
    (GMAVEN, "com/google/android/gms/play-services-tasks/18.2.0/play-services-tasks-18.2.0.aar", 80343,
     "7f2aaa8f502068eaf54356ca92aec04271d6e7c416c52c45c0d23440fcbd1654"),
)
TILES_LIBS = (  # 워치 타일: Tiles 1.1 (Java 판, Apache-2.0) — 최신 Kotlin 판은 라이브러리가 30개가 넘어 이 빌드에 맞지 않는다
    (GMAVEN, "androidx/wear/tiles/tiles/1.1.0/tiles-1.1.0.aar", 213450,
     "d869b13f1b1cdccc1bb302fa965fbe7e4a609d018b7e4d08d75bdf490b440d6b"),
    (GMAVEN, "androidx/wear/tiles/tiles-proto/1.1.0/tiles-proto-1.1.0.jar", 1177293,
     "c4d24e70cb6e9c93ae6c54fb0ce038273ea4d6f8ee690a8fe2a5bf25908c52d0"),
    (GMAVEN, "com/google/guava/listenablefuture/1.0/listenablefuture-1.0.jar", 3149,
     "e4ad7607e5c0477c6f890ef26a49cb8d1bb4dffb650bab4502afee64644e3069"),
)
SPEECH_JS = HERE / "shell" / "speech.js"  # --mic: 웹 음성 기능을 폰의 음성 인식·읽어 주기로 대신하는 도우미
WIDGET_RUNNER = HERE / "shell" / "widget-runner.js"  # 위젯 스크립트를 감싸 페이지에서 돌리는 실행기
WIDGET_SLOT = "(/*__WIDGET_FN__*/ null)"
WIDGET_ROWS_SLOT = "/*__WIDGET_ROWS__*/4"
WIDGET_ROWS_RE = re.compile(r"^\s*//\s*urlapp:rows\s+(\d+)\s*$", re.M)
WIDGET_ALT_ROWS_RE = re.compile(r"^\s*//\s*urlapp:alt-rows\s+(\d+)\s*$", re.M)
WIDGET_ALT_ROWS_SLOT = "/*__WIDGET_ALT_ROWS__*/0"
WIDGET_REFRESH_RE = re.compile(r"^\s*//\s*urlapp:refresh-min\s+(\d+)\s*$", re.M)
WIDGET_LABEL_RE = re.compile(r"^\s*//\s*urlapp:(label|alt-label)[ \t]+(\S[^\n]*?)\s*$", re.M)
ICON_TOOL = HERE / "UrlAppIcon.java"
SHELL_CLASS = "app.jarvis.urlapp.MainActivity"
MIN_SDK = 24      # 안드로이드 7.0
TARGET_SDK = 36   # 안드로이드 16
MIN_JAVA = 17

# 구글 공식 SDK 저장소의 파일. 크기와 SHA-1 은 구글의 저장소 목록(repository2-3.xml)에 적힌 값,
# SHA-256 은 2026-11-16 에 직접 받아 계산한 값이다. 하나라도 다르면 설치하지 않는다.
REPO = "https://dl.google.com/android/repository/"
TOOLCHAIN_ID = "bt36.1.0-android36r02"
BUILD_TOOLS = {
    "linux": ("build-tools_r36.1_linux.zip", 64195204, "936a0d6bd5ae3e2118a7567dddbc95ff67ed46e9",
              "a7b5889e4a79fcf3b0976bef40d401f4240fb1eed891d9d91169da1111e11d78"),
    "windows": ("build-tools_r36.1_windows.zip", 59366211, "95f048ae758dad90617975fe3f787f038b03338c",
                "23189d2d52b40a070a05e9cf7e497c9563f67fee76902e8fd3135ef29ef4dbeb"),
    "macosx": ("build-tools_r36.1_macosx.zip", 80527175, "d365b05ca1810ca92b428b92717865ad4188b5e1",
               "cc56be1d4ea95041f32f4ebf3a4f5c6b990c7749d706852395006b5654eb82ff"),
}
PLATFORM = ("platform-36_r02.zip", 65878410, "2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576",
            "37607369a28c5b640b3a7998868d45898ebcb777565a0e85f9acf36f29631d2e")

USER_AGENT = ("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
              "Chrome/130.0 Mobile Safari/537.36 JarvisUrlApp/1.0")
LETTER_PALETTE = ["#1A73E8", "#188038", "#D93025", "#E37400", "#9334E6", "#007B83", "#C5221F", "#3949AB"]
APP_ID_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$")
JAVA_KEYWORDS = set("""abstract assert boolean break byte case catch char class const continue default do double
else enum extends final finally float for goto if implements import instanceof int interface long native new
package private protected public return short static strictfp super switch synchronized this throw throws
transient try void volatile while true false null""".split())


class Fail(Exception):
    """사용자에게 그대로 보여 줄 실패."""


def say(msg=""):
    print(msg, flush=True)


# ---------------------------------------------------------------- 경로

def home_dir():
    return Path(os.environ.get("JARVIS_URL_APP_HOME") or (Path.home() / ".jarvis-url-app")).expanduser()


def toolchain_dir(home):
    override = os.environ.get("JARVIS_URL_APP_TOOLCHAIN")
    return Path(override).expanduser() if override else home / "toolchain" / TOOLCHAIN_ID


def host_os():
    if sys.platform.startswith("win"):
        return "windows"
    if sys.platform == "darwin":
        return "macosx"
    return "linux"


def tool_paths(tc):
    exe = ".exe" if host_os() == "windows" else ""
    return {
        "aapt2": tc / ("aapt2" + exe),
        "d8": tc / "d8.jar",
        "apksigner": tc / "apksigner.jar",
        "lambda_stubs": tc / "core-lambda-stubs.jar",
        "android_jar": tc / "android.jar",
    }


def toolchain_ready(tc):
    return (tc / "READY.json").is_file() and all(p.is_file() for p in tool_paths(tc).values())


# ---------------------------------------------------------------- 자바

def find_java():
    """(java, javac, keytool) 경로. JAVA_HOME 이 있으면 그것을 먼저 쓴다."""
    exe = ".exe" if host_os() == "windows" else ""
    jh = os.environ.get("JAVA_HOME")
    if jh:
        b = Path(jh) / "bin"
        if (b / ("javac" + exe)).is_file():
            return str(b / ("java" + exe)), str(b / ("javac" + exe)), str(b / ("keytool" + exe))
    java, javac = shutil.which("java"), shutil.which("javac")
    if not java or not javac:
        return None
    keytool = shutil.which("keytool") or str(Path(java).parent / ("keytool" + exe))
    return java, javac, keytool


def java_major(cmd):
    try:
        r = subprocess.run([cmd, "-version"], capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(r'(?:version "|javac )(\d+)(?:\.(\d+))?', r.stderr + r.stdout)
    if not m:
        return None
    major = int(m.group(1))
    return int(m.group(2) or 0) if major == 1 else major


def require_java():
    j = find_java()
    if not j:
        raise Fail("자바(JDK)를 찾지 못했습니다. JDK 17 이상을 설치하세요 "
                   "(윈도우: winget install Microsoft.OpenJDK.21 / 맥: brew install openjdk@21 / "
                   "리눅스: apt install openjdk-21-jdk-headless). JRE 만으로는 안 됩니다 (javac 필요).")
    for cmd in j[:2]:
        v = java_major(cmd)
        if v is None or v < MIN_JAVA:
            raise Fail(f"{cmd} 의 버전이 {v} 입니다. JDK {MIN_JAVA} 이상이 필요합니다.")
    return j


def run(cmd, cwd, what, timeout=600, merge=False):
    """외부 도구 실행. 항상 작업 폴더(cwd)에서 절대 경로로 부른다. merge=True 면 표준 오류 출력도 돌려준다."""
    r = subprocess.run([str(c) for c in cmd], cwd=str(cwd), capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=timeout)
    if r.returncode != 0:
        tail = "\n".join(l for l in (r.stdout + "\n" + r.stderr).splitlines()
                         if l.strip() and not l.startswith("Picked up JAVA_TOOL_OPTIONS"))[-3000:]
        raise Fail(f"{what} 실패 (종료 코드 {r.returncode}):\n{tail}")
    return r.stdout + r.stderr if merge else r.stdout


# ---------------------------------------------------------------- 내려받기

def http_get(url, max_bytes, timeout=20):
    """(최종 주소, content-type, 바이트). http/https 만, max_bytes 를 넘으면 실패."""
    if urllib.parse.urlsplit(url).scheme not in ("http", "https"):
        raise Fail(f"http/https 주소만 받습니다: {url}")
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = resp.read(max_bytes + 1)
        if len(data) > max_bytes:
            raise Fail(f"너무 큽니다 (>{max_bytes} 바이트): {url}")
        return resp.geturl(), resp.headers.get("Content-Type", ""), data


def download_verified(name, size, sha1, sha256, dest_dir):
    dest = dest_dir / name
    url = REPO + name
    if dest.is_file() and dest.stat().st_size == size and file_hashes(dest) == (sha1, sha256):
        say(f"  이미 받아 둠: {name}")
        return dest
    say(f"  받는 중: {url} ({size / 1e6:.0f}MB)")
    tmp = dest.with_suffix(".part")
    req = urllib.request.Request(url, headers={"User-Agent": "JarvisUrlApp/1.0"})
    got, next_mark = 0, 0.25
    with urllib.request.urlopen(req, timeout=60) as resp, open(tmp, "wb") as f:
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            got += len(chunk)
            if got > size:
                break
            f.write(chunk)
            if got / size >= next_mark:
                say(f"    {int(next_mark * 100)}%")
                next_mark += 0.25
    if got != size or file_hashes(tmp) != (sha1, sha256):
        tmp.unlink(missing_ok=True)
        raise Fail(f"{name} 의 크기나 해시가 고정해 둔 값과 다릅니다. 설치하지 않았습니다 "
                   f"(구글이 파일을 바꿨거나 받는 도중 바뀌었습니다).")
    tmp.replace(dest)
    return dest


def file_hashes(path):
    h1, h2 = hashlib.sha1(), hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h1.update(chunk)
            h2.update(chunk)
    return h1.hexdigest(), h2.hexdigest()


def extract_member(zf, suffix, dest, depth=None):
    names = [n for n in zf.namelist() if n.endswith("/" + suffix)]
    if depth is not None:
        names = [n for n in names if n.count("/") == depth]
    if len(names) != 1:
        raise Fail(f"압축 파일 안에서 {suffix} 를 하나로 찾지 못했습니다 ({len(names)}개).")
    with zf.open(names[0]) as src, open(dest, "wb") as out:
        shutil.copyfileobj(src, out)


# ---------------------------------------------------------------- setup / doctor

def cmd_setup(args):
    home = home_dir()
    java, _, keytool = require_java()
    tc = toolchain_dir(home)
    home.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(home, 0o700)
    except OSError:
        pass
    if toolchain_ready(tc):
        say(f"빌드 도구: 이미 설치됨 ({tc})")
    else:
        if os.environ.get("JARVIS_URL_APP_TOOLCHAIN"):
            raise Fail("JARVIS_URL_APP_TOOLCHAIN 이 가리키는 폴더에 빌드 도구가 없습니다.")
        say("빌드 도구 설치 (구글 공식 SDK 저장소, 해시 확인)")
        dl = home / "downloads"
        dl.mkdir(exist_ok=True)
        bt = download_verified(*BUILD_TOOLS[host_os()], dl)
        pf = download_verified(*PLATFORM, dl)
        staging = Path(tempfile.mkdtemp(prefix="toolchain-", dir=home))
        p = tool_paths(staging)
        with zipfile.ZipFile(bt) as z:
            extract_member(z, p["aapt2"].name, p["aapt2"], depth=1)
            extract_member(z, "lib/d8.jar", p["d8"])
            extract_member(z, "lib/apksigner.jar", p["apksigner"])
            extract_member(z, "core-lambda-stubs.jar", p["lambda_stubs"], depth=1)
        with zipfile.ZipFile(pf) as z:
            extract_member(z, "android.jar", p["android_jar"], depth=1)
        if host_os() != "windows":
            os.chmod(p["aapt2"], 0o755)
        version = run([p["aapt2"], "version"], staging, "aapt2 실행 시험", merge=True).strip()
        (staging / "READY.json").write_text(json.dumps({
            "toolchain": TOOLCHAIN_ID, "os": host_os(), "aapt2": version,
            "installed_at": time.strftime("%Y-%m-%d %H:%M:%S")}, ensure_ascii=False), encoding="utf-8")
        if tc.exists():
            shutil.rmtree(tc)
        tc.parent.mkdir(parents=True, exist_ok=True)
        staging.replace(tc)
        for f in (bt, pf):
            f.unlink(missing_ok=True)  # 압축 파일은 지운다 (필요한 5개 파일만 남김)
        say(f"빌드 도구 설치 완료: {tc} ({version})")
    ensure_keystore(home, keytool)
    say("준비 끝. 이제 build <URL> 로 앱을 만들 수 있습니다.")
    return 0


def ensure_keystore(home, keytool):
    sd = home / "signing"
    ks, pw = sd / "urlapp.p12", sd / "password.txt"
    if ks.is_file() and pw.is_file():
        return ks, pw
    if ks.exists() or pw.exists():
        raise Fail(f"서명 키 폴더({sd})에 파일이 하나만 있습니다. 새로 만들면 기존 앱을 업데이트할 수 없게 되므로 "
                   "멈췄습니다. 백업에서 두 파일(urlapp.p12, password.txt)을 함께 되돌려 놓으세요.")
    sd.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(sd, 0o700)
    except OSError:
        pass
    fd = os.open(pw, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(secrets.token_urlsafe(24))
    run([keytool, "-genkeypair", "-keystore", ks, "-storetype", "PKCS12", "-storepass:file", pw,
         "-alias", "urlapp", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10950",
         "-dname", "CN=Jarvis URL App"], sd, "서명 키 만들기")
    try:
        os.chmod(ks, 0o600)
    except OSError:
        pass
    say(f"서명 키를 만들었습니다: {ks} (이 폴더를 백업해 두세요. 잃어버리면 만든 앱을 업데이트할 수 없습니다)")
    return ks, pw


def cmd_doctor(args):
    home = home_dir()
    ok = True
    say(f"파이썬 {sys.version.split()[0]} ({'OK' if sys.version_info >= (3, 9) else '3.9 이상 필요'})")
    ok &= sys.version_info >= (3, 9)
    j = find_java()
    if not j:
        say("자바(JDK): 없음 — JDK 17 이상 필요 (윈도우: winget install Microsoft.OpenJDK.21)")
        ok = False
    else:
        for cmd in j[:2]:
            v = java_major(cmd)
            good = v is not None and v >= MIN_JAVA
            ok &= good
            say(f"{Path(cmd).name}: {v} ({'OK' if good else f'{MIN_JAVA} 이상 필요'}) — {cmd}")
    tc = toolchain_dir(home)
    if toolchain_ready(tc):
        info = json.loads((tc / "READY.json").read_text(encoding="utf-8"))
        say(f"빌드 도구: OK ({info.get('aapt2', '')}, {tc})")
    else:
        say("빌드 도구: 없음 — setup 을 실행하세요 (약 130MB 받음)")
        ok = False
    sd = home / "signing"
    say(f"서명 키: {'OK' if (sd / 'urlapp.p12').is_file() else '없음 (setup 이 만든다)'} ({sd})")
    if host_os() == "windows" and not str(home).isascii():
        say("주의: 작업 폴더 경로에 한글 등 영문이 아닌 글자가 있습니다. aapt2 가 실패할 수 있으니 "
            "JARVIS_URL_APP_HOME=C:\\jarvis-url-app 처럼 영문 경로를 지정하세요.")
    say("준비 끝" if ok else "준비 안 됨")
    return 0 if ok else 1


# ---------------------------------------------------------------- 웹 페이지 읽기

class MetaParser(html.parser.HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.title, self._in_title = "", False
        self.meta = {}           # name/property -> content (처음 것)
        self.theme = []          # (media, content)
        self.links = []          # dict(rel, href, sizes, type)

    def handle_starttag(self, tag, attrs):
        a = {k.lower(): (v or "") for k, v in attrs}
        if tag == "title":
            self._in_title = True
        elif tag == "meta":
            key = (a.get("name") or a.get("property") or "").strip().lower()
            if key == "theme-color":
                self.theme.append((a.get("media", "").lower(), a.get("content", "")))
            elif key and key not in self.meta:
                self.meta[key] = a.get("content", "")
        elif tag == "link":
            rel = a.get("rel", "").lower().split()
            if rel and a.get("href"):
                self.links.append({"rel": rel, "href": a["href"], "sizes": a.get("sizes", ""),
                                   "type": a.get("type", "").lower()})

    def handle_endtag(self, tag):
        if tag == "title":
            self._in_title = False

    def handle_data(self, data):
        if self._in_title and len(self.title) < 300:
            self.title += data


def parse_css_color(s):
    """'#abc', '#aabbcc', '#aabbccdd', 'rgb(1,2,3)', 'white' 등 -> '#RRGGBB' 또는 None."""
    if not s:
        return None
    s = s.strip().lower()
    named = {"white": "#FFFFFF", "black": "#000000", "red": "#FF0000", "blue": "#0000FF", "green": "#008000"}
    if s in named:
        return named[s]
    m = re.fullmatch(r"#([0-9a-f]{3,4})", s)
    if m:
        return "#" + "".join(c * 2 for c in m.group(1)[:3]).upper()
    m = re.fullmatch(r"#([0-9a-f]{6})(?:[0-9a-f]{2})?", s)
    if m:
        return "#" + m.group(1).upper()
    m = re.fullmatch(r"rgba?\(\s*(\d{1,3})[\s,]+(\d{1,3})[\s,]+(\d{1,3})(?:[\s,/]+[\d.%]+)?\s*\)", s)
    if m and all(int(x) <= 255 for x in m.groups()):
        return "#%02X%02X%02X" % tuple(int(x) for x in m.groups())
    return None


def size_hint(sizes):
    best = 0
    for part in (sizes or "").lower().split():
        m = re.fullmatch(r"(\d+)x(\d+)", part)
        if m:
            best = max(best, min(int(m.group(1)), int(m.group(2))))
        elif part == "any":
            best = max(best, 512)
    return best


def clean_text(s, limit=40):
    s = re.sub(r"[\x00-\x1f\x7f\u200b-\u200f\u202a-\u202e\u2066-\u2069]", "", s or "")
    s = re.sub(r"\s+", " ", s).strip()
    return s[:limit].strip()


def short_title(title):
    t = clean_text(title, 200)
    for sep in (" | ", " - ", " — ", " – ", " · ", " :: "):
        if sep in t:
            t = t.split(sep)[0]
    return clean_text(t)


def read_site(url):
    """페이지를 읽어 이름·색·아이콘 후보를 모은다. 실패해도 빈 값으로 계속한다 (사이트 내용은 정보일 뿐)."""
    info = {"name": None, "theme": None, "theme_dark": None, "bg": None, "icons": [], "notes": []}
    try:
        final, ctype, body = http_get(url, 3_000_000)
    except Exception as e:  # noqa: BLE001 — 어떤 오류든 '못 읽음'으로 처리
        info["notes"].append(f"페이지를 읽지 못함 ({type(e).__name__}: {str(e)[:120]}) — 기본값으로 만든다")
        return info
    m = re.search(r"charset=([\w-]+)", ctype or "", re.I) or \
        re.search(rb"<meta[^>]+charset=[\"']?([\w-]+)", body[:4096], re.I)
    charset = m.group(1) if m else "utf-8"
    charset = charset.decode("ascii", "replace") if isinstance(charset, bytes) else charset
    try:
        text = body.decode(charset, errors="replace")
    except LookupError:
        text = body.decode("utf-8", errors="replace")
    p = MetaParser()
    try:
        p.feed(text)
    except Exception:  # noqa: BLE001 — 망가진 HTML
        pass
    manifest = {}
    for l in p.links:
        if "manifest" in l["rel"]:
            try:
                _, _, mbody = http_get(urllib.parse.urljoin(final, l["href"]), 500_000)
                manifest = json.loads(mbody.decode("utf-8", "replace"))
                if not isinstance(manifest, dict):
                    manifest = {}
                manifest["_base"] = urllib.parse.urljoin(final, l["href"])
            except Exception:  # noqa: BLE001
                info["notes"].append("웹 앱 매니페스트를 읽지 못함")
            break
    names = [manifest.get("short_name"), manifest.get("name"), p.meta.get("apple-mobile-web-app-title"),
             p.meta.get("application-name"), p.meta.get("og:site_name")]
    for n in names:
        if isinstance(n, str) and clean_text(n):
            info["name"] = clean_text(n)
            break
    else:
        if short_title(p.title):
            info["name"] = short_title(p.title)
    for media, content in p.theme:
        c = parse_css_color(content)
        if c and "dark" in media:
            info["theme_dark"] = info["theme_dark"] or c
        elif c:
            info["theme"] = info["theme"] or c
    info["theme"] = info["theme"] or parse_css_color(str(manifest.get("theme_color", "")))
    info["bg"] = parse_css_color(str(manifest.get("background_color", "")))

    icons = []  # (우선순위, 크기, 주소, maskable)
    for ic in manifest.get("icons", []) if isinstance(manifest.get("icons"), list) else []:
        if not isinstance(ic, dict) or not isinstance(ic.get("src"), str):
            continue
        if "svg" in str(ic.get("type", "")).lower() or ic["src"].lower().endswith(".svg"):
            continue
        purpose = str(ic.get("purpose", "any")).lower().split()
        u = urllib.parse.urljoin(manifest["_base"], ic["src"])
        sz = size_hint(str(ic.get("sizes", "")))
        if "maskable" in purpose:
            icons.append((0, sz, u, True))
        if "any" in purpose or not purpose:
            icons.append((1, sz, u, False))
    for l in p.links:
        u = urllib.parse.urljoin(final, l["href"])
        if "svg" in l["type"] or u.lower().split("?")[0].endswith(".svg"):
            continue
        if "apple-touch-icon" in l["rel"] or "apple-touch-icon-precomposed" in l["rel"]:
            icons.append((1, size_hint(l["sizes"]) or 180, u, False))
        elif "icon" in l["rel"]:
            icons.append((2, size_hint(l["sizes"]), u, False))
    icons.append((3, 180, urllib.parse.urljoin(final, "/apple-touch-icon.png"), False))
    icons.append((4, 0, urllib.parse.urljoin(final, "/favicon.ico"), False))
    seen, ordered = set(), []
    for prio, sz, u, mask in sorted(icons, key=lambda x: (x[0], -x[1])):
        if (u, mask) not in seen:
            seen.add((u, mask))
            ordered.append((u, mask))
    info["icons"] = ordered[:8]
    return info


# ---------------------------------------------------------------- 이미지

def image_size(data):
    """PNG/GIF/JPEG/BMP 의 (가로, 세로). 모르는 형식이면 None."""
    if data[:8] == b"\x89PNG\r\n\x1a\n" and len(data) >= 24:
        return struct.unpack(">II", data[16:24])
    if data[:6] in (b"GIF87a", b"GIF89a") and len(data) >= 10:
        return struct.unpack("<HH", data[6:10])
    if data[:2] == b"BM" and len(data) >= 26:
        w, h = struct.unpack("<ii", data[18:26])
        return abs(w), abs(h)
    if data[:2] == b"\xff\xd8":
        i = 2
        while i + 9 < len(data):
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
                i += 2
                continue
            seglen = struct.unpack(">H", data[i + 2:i + 4])[0]
            if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
                h, w = struct.unpack(">HH", data[i + 5:i + 9])
                return w, h
            i += 2 + seglen
    return None


def png_from_ico(data):
    """ICO 안에 PNG 로 들어 있는 가장 큰 그림 (BMP 형식 항목은 건너뛴다)."""
    if data[:4] != b"\x00\x00\x01\x00" or len(data) < 6:
        return None
    count = struct.unpack("<H", data[4:6])[0]
    best = None
    for k in range(min(count, 64)):
        off = 6 + 16 * k
        if off + 16 > len(data):
            break
        size, offset = struct.unpack("<II", data[off + 8:off + 16])
        chunk = data[offset:offset + size]
        if chunk[:8] == b"\x89PNG\r\n\x1a\n":
            dims = image_size(chunk)
            if dims and (best is None or min(dims) > best[0]):
                best = (min(dims), chunk)
    return best[1] if best else None


def pick_icon(candidates, workdir):
    """후보를 내려받아 64px 이상인 것 중 가장 좋은 것. (파일, maskable, 주소) 또는 None."""
    got = []
    for u, mask in candidates:
        try:
            _, _, data = http_get(u, 5_000_000, timeout=15)
        except Exception:  # noqa: BLE001
            continue
        if data[:4] == b"\x00\x00\x01\x00":
            data = png_from_ico(data) or b""
        dims = image_size(data)
        if not dims or min(dims) < 64:
            continue
        got.append((min(dims), mask, u, data))
        if mask and min(dims) >= 192:
            break  # 꽉 채운 아이콘용(maskable)으로 충분히 큰 것이 있으면 그것으로
    if not got:
        return None
    masks = [g for g in got if g[1] and g[0] >= 192]
    best = masks[0] if masks else max((g for g in got if not g[1]), key=lambda g: g[0], default=got[0])
    path = workdir / "icon-source.img"
    path.write_bytes(best[3])
    return path, best[1], best[2]


def letter_candidates(name, host):
    first = next((ch for ch in name if ch.isalnum()), "")
    ascii_first = next((ch for ch in host if ch.isalnum()), "A")
    return "|".join(x for x in (first.upper(), ascii_first.upper()) if x) or "A"


def is_near_white(hex_color):
    r, g, b = (int(hex_color[i:i + 2], 16) for i in (1, 3, 5))
    return min(r, g, b) > 225


def is_dark(hex_color):
    r, g, b = (int(hex_color[i:i + 2], 16) for i in (1, 3, 5))
    return 0.299 * r + 0.587 * g + 0.114 * b < 80


# ---------------------------------------------------------------- 앱 정보

def normalize_url(raw):
    raw = raw.strip()
    if not re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*://", raw):
        raw = "https://" + raw
    u = urllib.parse.urlsplit(raw)
    if u.scheme.lower() not in ("http", "https"):
        raise Fail(f"http 또는 https 주소만 앱으로 만들 수 있습니다: {raw}")
    if not u.hostname:
        raise Fail(f"주소에 사이트 이름(호스트)이 없습니다: {raw}")
    if u.username or u.password:
        raise Fail("주소에 아이디·비밀번호(user:pass@)가 들어 있습니다. 앱 파일 안에 그대로 남으므로 받지 않습니다.")
    path = u.path or "/"
    return urllib.parse.urlunsplit((u.scheme.lower(), u.netloc.lower(), path, u.query, u.fragment))


def derive_app_id(url):
    u = urllib.parse.urlsplit(url)
    host = u.hostname or "site"
    if u.port:
        host += f"_{u.port}"
    seg = re.sub(r"[^a-z0-9]+", "_", host.lower()).strip("_") or "site"
    if not seg[0].isalpha():
        seg = "h" + seg
    rest = u.path.rstrip("/") + ("?" + u.query if u.query else "")
    if rest:
        seg += "_" + hashlib.sha1(rest.encode()).hexdigest()[:6]
    if seg in JAVA_KEYWORDS:
        seg += "_app"
    return "app.jarvis.web." + seg[:120]


def check_app_id(app_id):
    if not APP_ID_RE.match(app_id) or any(part in JAVA_KEYWORDS for part in app_id.split(".")):
        raise Fail(f"앱 id 형식이 아닙니다: {app_id} (예: app.jarvis.web.mysite — 영문으로 시작, 점으로 나눈 두 마디 이상)")
    return app_id


def own_hosts(url, extra):
    host = urllib.parse.urlsplit(url).hostname.lower()
    hosts = [host]
    if host.startswith("www."):
        hosts.append(host[4:])
    for h in extra or []:
        h = h.strip().lower()
        if h and h not in hosts:
            hosts.append(h)
    return hosts


def android_string(s):
    """aapt2 문자열 리소스용 이스케이프."""
    s = s.replace("\\", "\\\\").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if s[:1] in ("@", "?"):
        s = "\\" + s
    return s


# ---------------------------------------------------------------- 프로젝트 파일

def manifest_xml(app_id, cleartext, orientation, widget=False, cards=None, widget_label=False, widget_alt=False,
                 view_hosts=(), update=False, widget_extra_sizes=0, mic=False, notify=False, hotword=False,
                 voice_icon=False, watch=False):
    """cards: None(없음) | "intent"(MacroDroid 가 보내 줌) | "listener"(앱이 직접 알림 읽기).
    widget_label: 첫 위젯에 따로 이름(@string/widget_name)이 있는지. widget_alt: 두 번째 위젯(@string/widget_alt_name)."""
    orient = {"portrait": "portrait", "landscape": "sensorLandscape"}.get(orientation)
    orient_attr = f'\n            android:screenOrientation="{orient}"' if orient else ""
    # 안드로이드 16 은 큰 화면(태블릿)에서 방향 고정을 무시한다. 고정을 원하면 임시 예외 속성을 넣는다.
    prop = ('\n            <property android:name="android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY"'
            ' android:value="true" />') if orient else ""
    def widget_receiver(cls, label, info="urlapp_widget_info"):
        return f"""
        <receiver
            android:name="app.jarvis.urlapp.{cls}"
            android:exported="false"
            android:label="{label}">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/{info}" />
        </receiver>"""
    # 자기 사이트 주소로 앱을 여는 길 (크롬에서 Google 로그인을 마친 사이트가 "앱으로 돌아가기"로 부를 때).
    # autoVerify 를 쓰지 않으므로 다른 앱·브라우저의 일반 링크를 가로채지 않고, 패키지를 지정한 intent:// 링크로만 열린다.
    view_filter = "".join(f"""
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="https" android:host="{h}" />
            </intent-filter>""" for h in view_hosts if re.fullmatch(r"[a-z0-9.-]+", h or ""))
    receiver = ""
    if widget:
        receiver = widget_receiver("WidgetProvider", "@string/widget_name" if widget_label else "@string/app_name")
        if widget_alt:
            receiver += widget_receiver("WidgetProviderAlt", "@string/widget_alt_name", "urlapp_widget_alt_info")
        for i in range(2, 2 + min(widget_extra_sizes, 3)):  # 같은 내용의 다른 크기 (위젯 목록에 크기별로)
            receiver += widget_receiver(f"WidgetProviderS{i}", "@string/widget_name" if widget_label else "@string/app_name",
                                        f"urlapp_widget_s{i}_info")
        receiver += """
        <activity
            android:name="app.jarvis.urlapp.WidgetSettingsActivity"
            android:exported="true"
            android:label="@string/widget_settings"
            android:excludeFromRecents="true"
            android:taskAffinity="">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_CONFIGURE" />
            </intent-filter>
        </activity>
        <service
            android:name="app.jarvis.urlapp.WidgetRefreshJob"
            android:exported="false"
            android:permission="android.permission.BIND_JOB_SERVICE" />"""
    # 카드 자동 등록: 알림 듣기(사용자가 '알림 접근'을 켜야 동작), 설정 화면, 되돌리기 단추, 다시 보내기 작업
    listener = """
        <service
            android:name="app.jarvis.urlapp.CardListenerService"
            android:exported="true"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>""" if cards == "listener" else ""
    # MacroDroid 가 보내는 브로드캐스트를 받는 곳 (비밀 키를 확인한다). 민감한 권한이 없어 Play 프로텍트가 막지 않는다.
    intent_rx = """
        <receiver
            android:name="app.jarvis.urlapp.CardIntentReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="app.jarvis.urlapp.action.CARD_NOTIFICATION" />
            </intent-filter>
        </receiver>""" if cards == "intent" else ""
    card_parts = (listener + intent_rx + """
        <service
            android:name="app.jarvis.urlapp.CardRetryJob"
            android:exported="false"
            android:permission="android.permission.BIND_JOB_SERVICE" />
        <receiver
            android:name="app.jarvis.urlapp.CardUndoReceiver"
            android:exported="false" />
        <activity
            android:name="app.jarvis.urlapp.CardSettingsActivity"
            android:exported="false"
            android:label="@string/sc_cards" />""") if cards else ""
    card_perm = '\n    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />' if cards else ""
    # 앱 안 업데이트 (--update-url): 설치 요청 권한(사용자가 '이 출처 허용'을 한 번 켠다)과 설치 결과 받는 곳 (이 앱 안에서만)
    if update:
        card_perm += '\n    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />'
        card_parts += """
        <receiver
            android:name="app.jarvis.urlapp.UpdateReceiver"
            android:exported="false" />"""
    # --notify: 알림 권한(안드로이드 13+ 은 처음 열 때 묻는다), 폰을 다시 켜도 15분 확인을 잇는 권한, 확인 작업
    if notify:
        if "POST_NOTIFICATIONS" not in card_perm:
            card_perm += '\n    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />'
        card_perm += '\n    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />'
        card_parts += """
        <service
            android:name="app.jarvis.urlapp.NotifyJob"
            android:exported="false"
            android:permission="android.permission.BIND_JOB_SERVICE" />"""
    # --mic: 마이크 권한(앱이 처음 쓸 때 묻는다)과 폰의 음성 인식·읽어 주기 서비스를 찾는 허락 (안드로이드 11+)
    queries = ""
    if mic:
        # 위젯 마이크의 대화 창: 앱 화면과 다른 작업(task)으로 홈 화면 위에 뜨고 최근 앱 목록에는 안 남는다
        card_parts += f"""
        <activity
            android:name="app.jarvis.urlapp.VoiceActivity"
            android:exported="false"
            android:theme="@style/VoiceTheme"
            android:taskAffinity="{app_id}.voice"
            android:launchMode="singleTask"
            android:excludeFromRecents="true"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden" />"""
        if voice_icon:
            # --voice-icon: 대화 창을 바로 여는 두 번째 앱 아이콘 (빅스비 '빠른 명령어'·'OO 열어줘'가 이 이름으로 연다).
            # 아이콘으로 여는 길만 열려 있다 (MAIN/LAUNCHER). 창 자체는 그대로 exported=false.
            card_parts += """
        <activity-alias
            android:name="app.jarvis.urlapp.VoiceLauncher"
            android:targetActivity="app.jarvis.urlapp.VoiceActivity"
            android:exported="true"
            android:label="@string/voice_icon_name"
            android:icon="@mipmap/ic_voice"
            android:roundIcon="@mipmap/ic_voice">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>"""
        card_perm += '\n    <uses-permission android:name="android.permission.RECORD_AUDIO" />'
        queries = """
    <queries>
        <intent>
            <action android:name="android.speech.RecognitionService" />
        </intent>
        <intent>
            <action android:name="android.intent.action.TTS_SERVICE" />
        </intent>
    </queries>"""
    # --hotword: '하이 자비스' 상시 듣기 = 마이크 전경 서비스(늘 '듣는 중' 알림), 부르면 창을 바로 띄우는 '다른 앱 위에 표시'
    # (사용자가 설정에서 켠다), 폰을 다시 켜거나 업데이트한 뒤 '다시 켜기' 알림, 짧은 진동
    if hotword:
        for perm in ("FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MICROPHONE", "SYSTEM_ALERT_WINDOW", "POST_NOTIFICATIONS",
                     "RECEIVE_BOOT_COMPLETED", "VIBRATE", "USE_FULL_SCREEN_INTENT"):
            if f'"android.permission.{perm}"' not in card_perm:
                card_perm += f'\n    <uses-permission android:name="android.permission.{perm}" />'
        card_parts += """
        <service
            android:name="app.jarvis.urlapp.HotwordService"
            android:exported="false"
            android:foregroundServiceType="microphone" />
        <activity
            android:name="app.jarvis.urlapp.HotwordActivity"
            android:exported="false"
            android:label="@string/sc_hotword" />
        <receiver
            android:name="app.jarvis.urlapp.HotwordService$Boot"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>"""
    # --watch: 워치가 보낸 질문·새로고침을 받는 곳(Google Play 서비스가 깨움, 같은 패키지·서명의 워치 앱 메시지만),
    # 질문을 사이트에 묻는 작업, Play 서비스 버전 표시와 오류 안내 화면(Play 서비스 묶음이 요구)
    if watch:
        card_parts += """
        <meta-data
            android:name="com.google.android.gms.version"
            android:value="@integer/google_play_services_version" />
        <activity
            android:name="com.google.android.gms.common.api.GoogleApiActivity"
            android:exported="false"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
        <service
            android:name="app.jarvis.urlapp.PhoneWatchService"
            android:exported="true">
            <intent-filter>
                <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
                <data android:scheme="wear" android:host="*" android:pathPrefix="/urlapp/" />
            </intent-filter>
        </service>
        <service
            android:name="app.jarvis.urlapp.WatchAskJob"
            android:exported="false"
            android:permission="android.permission.BIND_JOB_SERVICE" />"""
    # 호출어 앱의 네이티브 라이브러리(Vosk·JNA)는 설치할 때 풀어 둔다 (APK 안에 압축해 넣으므로)
    native_attr = '\n        android:extractNativeLibs="true"' if hotword else ""
    return f"""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{app_id}">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />{card_perm}{queries}
    <application
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher"
        android:theme="@style/AppTheme"
        android:allowBackup="false"
        android:supportsRtl="true"
        android:usesCleartextTraffic="{'true' if cleartext else 'false'}"{native_attr}
        android:enableOnBackInvokedCallback="true">
        <activity
            android:name="{SHELL_CLASS}"
            android:exported="true"
            android:launchMode="singleTask"{orient_attr}
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden|navigation"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>{view_filter}
            <meta-data android:name="android.app.shortcuts" android:resource="@xml/shortcuts" />{prop}
        </activity>{receiver}{card_parts}
    </application>
</manifest>
"""


def write_resources(res, app_id, name, theme, bg, icon_bg, cards=False, update=False, mic=False, hotword=None):
    def w(rel, text):
        p = res / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")

    w("values/strings.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">{android_string(name)}</string>
    <string name="sc_home">처음 화면</string>
    <string name="sc_browser">브라우저에서 열기</string>
    <string name="sc_cards">카드 자동 등록</string>
    <string name="sc_update">업데이트 확인</string>
    <string name="sc_mic">마이크 모드</string>
    <string name="sc_hotword">{android_string(hotword or "호출어")}</string>
</resources>
""")
    w("values-en/strings.xml", """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="sc_home">Home</string>
    <string name="sc_browser">Open in browser</string>
    <string name="sc_cards">Card auto-entry</string>
    <string name="sc_update">Check for update</string>
    <string name="sc_mic">Voice mode</string>
    <string name="sc_hotword">Wake word</string>
</resources>
""")
    w("values/colors.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="app_theme">{theme}</color>
    <color name="app_bg">{bg}</color>
    <color name="icon_bg">{icon_bg}</color>
</resources>
""")
    items = """        <item name="android:windowBackground">@color/app_bg</item>
        <item name="android:statusBarColor">@color/app_theme</item>
        <item name="android:navigationBarColor">@color/app_bg</item>
"""
    # VoiceTheme: 위젯 마이크의 대화 창 (홈 화면 위에 투명하게 떠서 아래쪽에 대화 판만 — VoiceActivity, --mic 앱)
    w("values/styles.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="AppTheme" parent="@android:style/Theme.DeviceDefault.Light.NoActionBar">
{items}    </style>
    <style name="VoiceTheme" parent="@android:style/Theme.Translucent.NoTitleBar">
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowIsTranslucent">true</item>
    </style>
</resources>
""")
    daynight = f"""    <style name="AppTheme" parent="@android:style/Theme.DeviceDefault.DayNight">
        <item name="android:windowActionBar">false</item>
        <item name="android:windowNoTitle">true</item>
{items}"""
    w("values-v29/styles.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
{daynight}    </style>
</resources>
""")
    w("values-v31/styles.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
{daynight}        <item name="android:windowSplashScreenBackground">@color/app_bg</item>
    </style>
</resources>
""")
    w("mipmap-anydpi-v26/ic_launcher.xml", """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/icon_bg" />
    <foreground android:drawable="@mipmap/ic_launcher_fg" />
</adaptive-icon>
""")
    shortcut = """    <shortcut android:shortcutId="{sid}" android:enabled="true" android:icon="@mipmap/ic_launcher"
        android:shortcutShortLabel="@string/{label}">
        <intent android:action="app.jarvis.urlapp.action.{action}"
            android:targetPackage="{pkg}" android:targetClass="{cls}" />
    </shortcut>
"""
    # 런처는 바로가기를 4개까지 보여 준다 → 필요한 것부터 (호출어가 있으면 '브라우저에서 열기'가 빠진다)
    picks = [("mic", "sc_mic", "MIC", mic), ("hotword", "sc_hotword", "HOTWORD", bool(hotword)),
             ("cards", "sc_cards", "CARDS", cards), ("update", "sc_update", "UPDATE", update),
             ("home", "sc_home", "HOME", True), ("browser", "sc_browser", "BROWSER", True)]
    picks = [x for x in picks if x[3]][:4]
    order = ["mic", "hotword", "home", "browser", "cards", "update"]  # 보이는 순서는 예전과 같게
    picks.sort(key=lambda x: order.index(x[0]))
    w("xml/shortcuts.xml", '<?xml version="1.0" encoding="utf-8"?>\n'
      '<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">\n'
      + "".join(shortcut.format(sid=sid, label=label, action=action, pkg=app_id, cls=SHELL_CLASS)
                for sid, label, action, _ in picks)
      + "</shortcuts>\n")


# ---------------------------------------------------------------- 빌드

def watch_manifest_xml(app_id):
    """워치 앱 매니페스트: 폰 앱과 같은 패키지 이름 (Data Layer 는 같은 패키지·서명끼리만). 사이트 주소는 들어 있지 않다."""
    return f"""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{app_id}">
    <uses-feature android:name="android.hardware.type.watch" />
    <queries>
        <intent>
            <action android:name="android.speech.action.RECOGNIZE_SPEECH" />
        </intent>
        <intent>
            <action android:name="android.intent.action.TTS_SERVICE" />
        </intent>
        <intent>
            <action android:name="androidx.wear.tiles.action.BIND_UPDATE_REQUESTER" />
        </intent>
    </queries>
    <application
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:allowBackup="false"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar">
        <meta-data
            android:name="com.google.android.wearable.standalone"
            android:value="false" />
        <meta-data
            android:name="com.google.android.gms.version"
            android:value="@integer/google_play_services_version" />
        <activity
            android:name="app.jarvis.urlapp.WatchAskActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service
            android:name="app.jarvis.urlapp.WatchTile"
            android:exported="true"
            android:label="@string/app_name"
            android:icon="@mipmap/ic_launcher"
            android:permission="com.google.android.wearable.permission.BIND_TILE_PROVIDER">
            <intent-filter>
                <action android:name="androidx.wear.tiles.action.BIND_TILE_PROVIDER" />
            </intent-filter>
            <meta-data
                android:name="androidx.wear.tiles.PREVIEW"
                android:resource="@drawable/urlapp_tile_preview" />
        </service>
        <service
            android:name="app.jarvis.urlapp.WatchListener"
            android:exported="true">
            <intent-filter>
                <action android:name="com.google.android.gms.wearable.DATA_CHANGED" />
                <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
                <data android:scheme="wear" android:host="*" android:pathPrefix="/urlapp/" />
            </intent-filter>
        </service>
        <activity
            android:name="com.google.android.gms.common.api.GoogleApiActivity"
            android:exported="false"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
    </application>
</manifest>
"""


def write_watch_resources(res, name, icon_bg, accent, phone_res):
    """워치 앱 리소스: 이름·색·폰 앱과 같은 아이콘·타일 미리보기·Data Layer 능력(urlapp_watch)."""
    def w(rel, text):
        f = res / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(text, encoding="utf-8")
    w("values/strings.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">{android_string(name)}</string>
</resources>
""")
    w("values/colors.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="icon_bg">{icon_bg}</color>
    <color name="watch_accent">{accent}</color>
</resources>
""")
    w("values/wear.xml", """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="android_wear_capabilities" translatable="false">
        <item>urlapp_watch</item>
    </string-array>
</resources>
""")
    for d in phone_res.glob("mipmap-*"):
        for f in d.glob("ic_launcher*"):
            (res / d.name).mkdir(parents=True, exist_ok=True)
            shutil.copyfile(f, res / d.name / f.name)
    # 타일 고르는 화면의 미리보기 (검은 원 + 제목·줄 자리 + 강조색 '말하기' 단추)
    w("drawable/urlapp_tile_preview.xml", """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="192dp" android:height="192dp"
    android:viewportWidth="192" android:viewportHeight="192">
    <path android:fillColor="#FF000000" android:pathData="M96,0a96,96 0,1 1,0 192a96,96 0,1 1,0 -192z" />
    <path android:fillColor="#FFFFFFFF" android:pathData="M62,46h68a5,5 0,0 1,0 10h-68a5,5 0,0 1,0 -10z" />
    <path android:fillColor="#FF9AA0A6" android:pathData="M44,70h70a4,4 0,0 1,0 8h-70a4,4 0,0 1,0 -8z" />
    <path android:fillColor="@color/watch_accent" android:pathData="M124,70h24a4,4 0,0 1,0 8h-24a4,4 0,0 1,0 -8z" />
    <path android:fillColor="#FF9AA0A6" android:pathData="M44,86h60a4,4 0,0 1,0 8h-60a4,4 0,0 1,0 -8z" />
    <path android:fillColor="#FFFFFFFF" android:pathData="M116,86h32a4,4 0,0 1,0 8h-32a4,4 0,0 1,0 -8z" />
    <path android:fillColor="@color/watch_accent" android:pathData="M64,110h64a16,16 0,0 1,0 32h-64a16,16 0,0 1,0 -32z" />
    <path android:fillColor="#FF0B1220" android:pathData="M82,120h28a3,3 0,0 1,0 6h-28a3,3 0,0 1,0 -6z" />
    <path android:fillColor="#FF5F6368" android:pathData="M74,152h44a3,3 0,0 1,0 6h-44a3,3 0,0 1,0 -6z" />
</vector>
""")


def build_watch_apk(home, tc, java, javac, ks, pw, app_id, name, icon_bg, accent, phone_res, version_code, version_name,
                    out_dir, workdir):
    """--watch: 워치 앱 APK (같은 패키지·같은 서명). 타일 + 대화 화면 + 폰과의 Data Layer. 사이트 주소는 넣지 않는다."""
    p = tool_paths(tc)
    wd = workdir / "watch"
    res = wd / "res"
    res.mkdir(parents=True)
    write_watch_resources(res, name, icon_bg, accent, phone_res)
    libs = maven_libs(home, GMS_LIBS + TILES_LIBS)
    jars = [lib["jar"] for lib in libs]
    lib_zips, pkgs = lib_resources(p, wd, libs, "watch")
    run([p["aapt2"], "compile", "--dir", res, "-o", wd / "res.zip"], wd, "워치 리소스 컴파일(aapt2 compile)")
    (wd / "AndroidManifest.xml").write_text(watch_manifest_xml(app_id), encoding="utf-8")
    gen = wd / "gen"
    gen.mkdir()
    unsigned = wd / "unsigned.apk"
    run([p["aapt2"], "link", "-o", unsigned, "-I", p["android_jar"], "--manifest", wd / "AndroidManifest.xml",
         "--min-sdk-version", str(WATCH_MIN_SDK), "--target-sdk-version", str(TARGET_SDK),
         "--version-code", str(version_code), "--version-name", version_name, "--auto-add-overlay",
         "--java", gen, "--extra-packages", ":".join(pkgs), wd / "res.zip", *lib_zips], wd, "워치 앱 묶기(aapt2 link)")
    app = app_dex(tc, javac, java, wd, "워치 앱", sorted(WATCH_DIR.glob("*.java")), ["app/jarvis/urlapp/"], jars, gen, pkgs,
                  WATCH_MIN_SDK)
    lib_dex = dex_jars(home, tc, java, jars, WATCH_MIN_SDK, wd)
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as z:
        z.write(app, "classes.dex")
        for i, f in enumerate(lib_dex, start=2):
            z.write(f, f"classes{i}.dex")
    apk = out_dir / f"{app_id.split('.')[-1]}-watch-v{version_code}.apk"
    run([java, "-jar", p["apksigner"], "sign", "--ks", ks, "--ks-pass", f"file:{pw}", "--ks-key-alias", "urlapp",
         "--min-sdk-version", str(WATCH_MIN_SDK), "--v4-signing-enabled", "false", "--out", apk, unsigned],
        wd, "워치 앱 서명(apksigner)")
    run([java, "-jar", p["apksigner"], "verify", "--min-sdk-version", str(WATCH_MIN_SDK), apk], wd, "워치 앱 서명 검사")
    bad = check_alignment(apk)
    if bad:
        raise Fail(f"워치 APK 정렬 문제: {', '.join(bad[:5])}")
    b = badging(p["aapt2"], apk, wd)
    if b.get("package") != app_id or b.get("version_code") != version_code:
        raise Fail(f"만든 워치 APK 의 정보가 예상과 다릅니다: {b}")
    return apk


def write_voice_icon(res, label, bg, accent):
    """--voice-icon: 대화 창을 바로 여는 두 번째 앱 아이콘(이름 label). 바탕 bg 위 강조색 동그라미 + 마이크."""
    def w(rel, text):
        p = res / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
    w("values/voice_icon.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="voice_icon_name">{android_string(label)}</string>
    <color name="voice_icon_bg">{bg}</color>
    <color name="voice_icon_accent">{accent}</color>
</resources>
""")
    mic = ("M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 "
           "-5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")
    # 108×108 캔버스(적응형 아이콘), 가운데 지름 52 동그라미 + 1.6배 마이크 (Material Icons "mic", Apache-2.0)
    w("drawable/urlapp_voice_fg.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="@color/voice_icon_accent" android:pathData="M54,28a26,26 0,1 1,0 52a26,26 0,1 1,0 -52z" />
    <group android:translateX="34.8" android:translateY="34.8" android:scaleX="1.6" android:scaleY="1.6">
        <path android:fillColor="@color/voice_icon_bg" android:pathData="{mic}" />
    </group>
</vector>
""")
    w("mipmap-anydpi-v26/ic_voice.xml", """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/voice_icon_bg" />
    <foreground android:drawable="@drawable/urlapp_voice_fg" />
</adaptive-icon>
""")
    w("mipmap/ic_voice.xml", """<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item><shape android:shape="oval"><solid android:color="@color/voice_icon_bg" /></shape></item>
    <item android:drawable="@drawable/urlapp_voice_fg" />
</layer-list>
""")


HOTWORD_ICON = """<?xml version="1.0" encoding="utf-8"?>
<!-- '하이 자비스' 듣는 중 알림의 작은 아이콘 (마이크, Material Icons "mic", Apache-2.0) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z" />
</vector>
"""

NOTIFY_ICON = """<?xml version="1.0" encoding="utf-8"?>
<!-- 앱 알림의 작은 아이콘 (종 모양, Material Icons "notifications", Apache-2.0) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z" />
</vector>
"""

CARD_NOTIFY_ICON = """<?xml version="1.0" encoding="utf-8"?>
<!-- 카드 자동 등록 알림의 작은 아이콘 (영수증 모양, Material Icons "receipt", Apache-2.0) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M18,17H6v-2h12V17zM18,13H6v-2h12V13zM18,9H6V7h12V9zM3,22l1.5,-1.5L6,22l1.5,-1.5L9,22l1.5,-1.5L12,22l1.5,-1.5L15,22l1.5,-1.5L18,22l1.5,-1.5L21,22V2l-1.5,1.5L18,2l-1.5,1.5L15,2l-1.5,1.5L12,2l-1.5,1.5L9,2L7.5,3.5L6,2L4.5,3.5L3,2V22z" />
</vector>
"""


def load_cards_config(path):
    """카드 자동 등록 설정(사이트별 JSON)을 읽고 꼭 필요한 칸을 확인한다."""
    try:
        c = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise Fail(f"카드 설정을 읽을 수 없습니다: {path} ({e})")
    owners = c.get("owners")
    if not (isinstance(owners, list) and owners and all(isinstance(o, str) and o.strip() for o in owners)):
        raise Fail("카드 설정의 owners(담당자 목록)가 비었거나 이상합니다.")
    for k in ("add", "history", "delete"):
        if not (isinstance(c.get(k), dict) and isinstance(c[k].get("path"), str) and c[k]["path"].startswith("/")):
            raise Fail(f"카드 설정의 {k}.path 가 없습니다 (/로 시작하는 사이트 안 주소).")
    if not isinstance(c["add"].get("body"), dict):
        raise Fail("카드 설정의 add.body 가 없습니다.")
    d = c.get("default_category")
    if not (isinstance(d, list) and len(d) == 2):
        raise Fail("카드 설정의 default_category 는 [대분류, 중분류] 두 칸이어야 합니다.")
    for r in c.get("rules", []):
        if not (isinstance(r, dict) and r.get("large") and isinstance(r.get("keywords"), list)):
            raise Fail(f"카드 설정의 규칙 모양이 이상합니다: {str(r)[:80]}")
    return c


def mix(hex_color, other, t):
    a = [int(hex_color[i:i + 2], 16) for i in (1, 3, 5)]
    b = [int(other[i:i + 2], 16) for i in (1, 3, 5)]
    return "#%02X%02X%02X" % tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def parse_widget_sizes(text):
    """--widget-sizes "4x2,3x2,2x2,4x1" → [(4, 2), (3, 2), (2, 2), (4, 1)] (가로 2~5칸, 세로 1~4칸, 1~4개, 겹치지 않게)."""
    if not text or text == "none":
        return []
    out = []
    for part in re.split(r"[,\s]+", str(text).strip().lower()):
        if not part:
            continue
        m = re.fullmatch(r"([2-5])[x×*]([1-4])", part)
        if not m:
            raise Fail(f"--widget-sizes 는 4x2,3x2,2x2 처럼 가로x세로 칸 수입니다 (가로 2~5, 세로 1~4): {part}")
        size = (int(m.group(1)), int(m.group(2)))
        if size in out:
            raise Fail(f"--widget-sizes 에 같은 크기가 두 번 있습니다: {part}")
        out.append(size)
    if not 1 <= len(out) <= 4:
        raise Fail("--widget-sizes 는 1~4개입니다.")
    return out


def size_rows(size, rows):
    """그 크기에 들어가는 줄 수: 세로 한 칸에 약 2줄, 가로 2칸이면 1줄 덜 (제목·아래 줄 자리).
    세로 1칸(4×1 등)은 가로 한 줄 모양이라 첫 줄 하나만."""
    w, h = size
    if h == 1:
        return 1
    fit = max(1, h * 2 - (1 if w <= 2 else 0))
    return max(1, min(rows, fit))


def widget_rows(script_text, alt=False):
    """위젯 스크립트가 "// urlapp:rows N" 으로 정한 줄 수 (기본 4, 1~8).
    alt=True: 두 번째 위젯의 줄 수 "// urlapp:alt-rows N" (없으면 첫 위젯과 같다)."""
    m = (WIDGET_ALT_ROWS_RE if alt else WIDGET_ROWS_RE).search(script_text or "")
    if not m:
        return widget_rows(script_text) if alt else 4
    n = int(m.group(1))
    if not 1 <= n <= 8:
        raise Fail(f"위젯 스크립트의 urlapp:{'alt-rows' if alt else 'rows'} 는 1~8 이어야 합니다: {n}")
    return n


def widget_refresh_min(script_text):
    """위젯 스크립트가 "// urlapp:refresh-min N" 으로 정한 자동 새로고침 간격(분). 없으면 0 (단추·자동 새로고침 없음).
    5~1440 분. 앱을 열지 않고 보이지 않는 웹 화면으로 사이트를 열어 새로 고치므로, 앱의 로그인 쿠키가 살아 있어야 한다."""
    m = WIDGET_REFRESH_RE.search(script_text or "")
    if not m:
        return 0
    n = int(m.group(1))
    if not 5 <= n <= 1440:
        raise Fail(f"위젯 스크립트의 urlapp:refresh-min 은 5~1440 분이어야 합니다: {n}")
    return n


def widget_labels(script_text):
    """위젯 목록에 보일 이름: "// urlapp:label 이름"(첫 위젯), "// urlapp:alt-label 이름"(두 번째 위젯, 있으면 생김)."""
    out = {"label": None, "alt-label": None}
    for kind, value in WIDGET_LABEL_RE.findall(script_text or ""):
        if len(value) > 30:
            raise Fail(f"위젯 이름은 30자까지입니다: {value}")
        out[kind] = value
    return out["label"], out["alt-label"]


def write_widget_resources(res, accent, rows=4, label=None, alt_label=None, alt_rows=None, dark_bg=None,
                           sizes=(), mic=False):
    """홈 화면 위젯의 모양 (RemoteViews 로 그릴 수 있는 LinearLayout·TextView·ImageView 만 쓴다).
    dark_bg 가 있으면(--widget-style dark) 기기의 밝은/어두운 모드와 상관없이 그 바탕색의 어두운 위젯으로 만든다.
    rows: 줄 수. 4줄이 넘으면 처음 크기를 세 칸 높이로 한다 (이미 놓인 위젯은 사용자가 늘린다).
    alt_label 이 있으면 두 번째 위젯의 모양(urlapp_widget_alt, 줄 수 alt_rows)과 크기 정보도 따로 만든다.
    sizes 가 있으면 첫 크기는 첫 위젯, 두·세 번째 크기는 같은 내용의 위젯(urlapp_widget_s2·_s3)으로 크기별로 만든다.
    mic 이면 제목 줄에 마이크 단추(w_mic: 앱을 열고 음성 대화)를 둔다."""
    def w(rel, text):
        f = res / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(text, encoding="utf-8")

    def colors(vals):
        return ('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                + "".join(f'    <color name="{k}">{v}</color>\n' for k, v in vals.items()) + "</resources>\n")

    night = {"widget_bg": "#1E293B", "widget_title": "#F1F5F9", "widget_label": "#94A3B8", "widget_value": "#F8FAFC",
             "widget_note": "#64748B", "widget_accent": mix(accent, "#FFFFFF", 0.35), "widget_negative": "#FB7185"}
    if dark_bg:
        night = dict(night, widget_bg=dark_bg, widget_label=mix(dark_bg, "#FFFFFF", 0.6),
                     widget_note=mix(dark_bg, "#FFFFFF", 0.42), widget_accent=accent)
        w("values/widget_colors.xml", colors(night))
    else:
        w("values/widget_colors.xml", colors({
            "widget_bg": "#FFFFFF", "widget_title": "#0F172A", "widget_label": "#64748B", "widget_value": "#0F172A",
            "widget_note": "#94A3B8", "widget_accent": accent, "widget_negative": "#E11D48"}))
    w("values-night/widget_colors.xml", colors(night))
    names = "".join(f'    <string name="{k}">{android_string(v)}</string>\n'
                    for k, v in (("widget_name", label), ("widget_alt_name", alt_label)) if v)
    w("values/widget_strings.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="widget_desc">앱의 요약을 홈 화면에 보여 줍니다</string>
    <string name="widget_hint">앱을 열고 로그인하면 여기에 요약이 보여요</string>
    <string name="widget_refresh">새로 고침</string>
    <string name="widget_mic">마이크 모드</string>
    <string name="widget_settings">위젯 꾸미기</string>
{names}</resources>
""")
    w("values-en/widget_strings.xml", """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="widget_desc">Shows the app\\'s summary on the home screen</string>
    <string name="widget_hint">Open the app (and log in) to fill this widget</string>
    <string name="widget_refresh">Refresh</string>
    <string name="widget_mic">Voice mode</string>
    <string name="widget_settings">Widget style</string>
</resources>
""")
    w("drawable/urlapp_widget_refresh.xml", """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp" android:height="20dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="@color/widget_label"
        android:pathData="M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z" />
</vector>
""")
    if mic:
        # 마이크 단추: 강조색 동그라미 + 마이크 (Material Icons "mic", Apache-2.0)
        w("drawable/urlapp_widget_mic_bg.xml", """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/widget_accent" />
</shape>
""")
        w("drawable/urlapp_widget_mic.xml", """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp" android:height="20dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="@color/widget_bg"
        android:pathData="M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z" />
</vector>
""")
    w("drawable/urlapp_widget_bg.xml", """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/widget_bg" />
    <corners android:radius="22dp" />
</shape>
""")
    def layout(n, name, info, size=None):
        compact = bool(size and size[0] <= 2)  # 가로 2칸: 글자를 줄이고 앱 아이콘을 뺀다
        label_sp, value_sp, pad = (12, 14, 12) if compact else (13, 16, 16)
        rows_xml = "".join(f"""
    <LinearLayout
        android:id="@+id/w_row{i}"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:visibility="gone">
        <TextView
            android:id="@+id/w_label{i}"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:ellipsize="end"
            android:maxLines="1"
            android:textColor="@color/widget_label"
            android:textSize="{label_sp}sp" />
        <TextView
            android:id="@+id/w_value{i}"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="6dp"
            android:maxLines="1"
            android:textColor="@color/widget_value"
            android:textSize="{value_sp}sp"
            android:textStyle="bold" />
    </LinearLayout>""" for i in range(1, n + 1))
        icon_xml = "" if compact else """
        <ImageView
            android:layout_width="22dp"
            android:layout_height="22dp"
            android:importantForAccessibility="no"
            android:src="@mipmap/ic_launcher" />"""
        mic_xml = """
        <ImageView
            android:id="@+id/w_mic"
            android:layout_width="32dp"
            android:layout_height="32dp"
            android:layout_marginStart="4dp"
            android:background="@drawable/urlapp_widget_mic_bg"
            android:contentDescription="@string/widget_mic"
            android:padding="6dp"
            android:src="@drawable/urlapp_widget_mic"
            android:visibility="gone" />""" if mic else ""
        # 바탕은 따로 그린 그림(w_bg) — 위젯 설정의 투명도를 그 그림의 알파로 (RemoteViews 는 배경 알파를 못 바꾼다)
        if size and size[1] == 1:
            # 세로 1칸 (예: 4×1, 2026-11-20 사용자 요청): 가로 한 줄 — [아이콘] 제목 / 첫 줄(또는 안내) [새로고침][마이크].
            # 그리는 코드가 만지는 id(w_title·w_row1·w_label1·w_value1·w_note·w_refresh·w_mic)는 모두 있어야 한다 (없으면 위젯이 깨짐).
            w(f"layout/{name}.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/w_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
<ImageView
    android:id="@+id/w_bg"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:importantForAccessibility="no"
    android:scaleType="fitXY"
    android:src="@drawable/urlapp_widget_bg" />
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:gravity="center_vertical"
    android:orientation="horizontal"
    android:paddingStart="{pad}dp"
    android:paddingTop="4dp"
    android:paddingEnd="{pad - 4 if mic else pad}dp"
    android:paddingBottom="4dp">
{icon_xml}
    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="{0 if compact else 10}dp"
        android:layout_weight="1"
        android:orientation="vertical">
        <TextView
            android:id="@+id/w_title"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:ellipsize="end"
            android:maxLines="1"
            android:text="@string/app_name"
            android:textColor="@color/widget_title"
            android:textSize="14sp"
            android:textStyle="bold" />
        <LinearLayout
            android:id="@+id/w_row1"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:visibility="gone">
            <TextView
                android:id="@+id/w_label1"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:ellipsize="end"
                android:maxLines="1"
                android:textColor="@color/widget_label"
                android:textSize="12sp" />
            <TextView
                android:id="@+id/w_value1"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="6dp"
                android:maxLines="1"
                android:textColor="@color/widget_value"
                android:textSize="13sp"
                android:textStyle="bold" />
        </LinearLayout>
        <TextView
            android:id="@+id/w_note"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:ellipsize="end"
            android:maxLines="1"
            android:text="@string/widget_hint"
            android:textColor="@color/widget_note"
            android:textSize="10sp" />
    </LinearLayout>
    <ImageView
        android:id="@+id/w_refresh"
        android:layout_width="30dp"
        android:layout_height="30dp"
        android:padding="5dp"
        android:contentDescription="@string/widget_refresh"
        android:src="@drawable/urlapp_widget_refresh"
        android:visibility="gone" />{mic_xml}
</LinearLayout>
</FrameLayout>
""")
        else:
            w(f"layout/{name}.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/w_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
<ImageView
    android:id="@+id/w_bg"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:importantForAccessibility="no"
    android:scaleType="fitXY"
    android:src="@drawable/urlapp_widget_bg" />
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:paddingStart="{pad}dp"
    android:paddingTop="12dp"
    android:paddingEnd="{pad - 4 if mic else pad}dp"
    android:paddingBottom="10dp">
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginBottom="4dp"
        android:gravity="center_vertical"
        android:orientation="horizontal">
{icon_xml}
        <TextView
            android:id="@+id/w_title"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="{0 if compact else 8}dp"
            android:layout_weight="1"
            android:ellipsize="end"
            android:maxLines="1"
            android:text="@string/app_name"
            android:textColor="@color/widget_title"
            android:textSize="{13 if compact else 14}sp"
            android:textStyle="bold" />
        <ImageView
            android:id="@+id/w_refresh"
            android:layout_width="30dp"
            android:layout_height="30dp"
            android:padding="5dp"
            android:contentDescription="@string/widget_refresh"
            android:src="@drawable/urlapp_widget_refresh"
            android:visibility="gone" />{mic_xml}
    </LinearLayout>{rows_xml}
    <TextView
        android:id="@+id/w_note"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:ellipsize="end"
        android:maxLines="1"
        android:text="@string/widget_hint"
        android:textColor="@color/widget_note"
        android:textSize="10sp" />
</LinearLayout>
</FrameLayout>
""")
        tall = n > 4
        cw, ch = size or (3, 3 if tall else 2)
        # 기본 크기. 예전 계산(n칸 = 70n-30dp)은 칸이 좁던 시절 값이라, 칸이 넓은 요즘 런처(삼성 4·5열 등)에서는
        # 4칸 위젯이 3칸에 들어가 '3×2'로 보였다 (2026-11-20 사용자 "4X2 위젯도 만들어줘"). 4·5열 격자 모두에서
        # 원하는 칸 수가 되도록 고른 값 (안드로이드 12+ 는 targetCell 도 함께 본다).
        min_w = {1: 40, 2: 130, 3: 210, 4: 300, 5: 380}[cw]
        min_h = {1: 50, 2: 150, 3: 270, 4: 400}[ch]
        w(f"xml/{info}.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/widget_desc"
    android:initialLayout="@layout/{name}"
    android:minWidth="{min_w}dp"
    android:minHeight="{min_h}dp"
    android:minResizeWidth="{min(110, min_w)}dp"
    android:minResizeHeight="{min(130 if tall else 90, min_h)}dp"
    android:previewLayout="@layout/{name}"
    android:resizeMode="horizontal|vertical"
    android:targetCellWidth="{cw}"
    android:targetCellHeight="{ch}"
    android:configure="app.jarvis.urlapp.WidgetSettingsActivity"
    android:widgetFeatures="reconfigurable|configuration_optional"
    android:updatePeriodMillis="0"
    android:widgetCategory="home_screen" />
""")

    main_rows = size_rows(sizes[0], rows) if sizes else rows
    layout(main_rows, "urlapp_widget", "urlapp_widget_info", sizes[0] if sizes else None)
    counts = f'    <integer name="urlapp_widget_rows">{main_rows}</integer>\n'
    if sizes and sizes[0][1] == 1:
        counts += '    <integer name="urlapp_widget_oneline">1</integer>\n'
    for i, size in enumerate(sizes[1:4], start=2):  # 같은 내용, 다른 크기
        n_i = size_rows(size, rows)
        layout(n_i, f"urlapp_widget_s{i}", f"urlapp_widget_s{i}_info", size)
        counts += f'    <integer name="urlapp_widget_s{i}_rows">{n_i}</integer>\n'
        if size[1] == 1:  # 가로 한 줄 모양: 줄이 없을 때만 아래 안내(w_note)를 보인다
            counts += f'    <integer name="urlapp_widget_s{i}_oneline">1</integer>\n'
    if alt_label:
        alt_n = alt_rows or rows
        layout(alt_n, "urlapp_widget_alt", "urlapp_widget_alt_info")
        counts += f'    <integer name="urlapp_widget_alt_rows">{alt_n}</integer>\n'
    w("values/widget_rows.xml", '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + counts + "</resources>\n")


PARAM_RE = re.compile(r"__PARAM_([A-Z0-9_]+)__")


def widget_asset(script_text, params=()):
    """사이트별 위젯 스크립트(함수 하나)를 실행기에 넣는다. __PARAM_이름__ 자리는 --widget-param 값으로 채운다."""
    values = {}
    for item in params or []:
        name, sep, value = str(item).partition("=")
        if not sep or not re.fullmatch(r"[A-Z0-9_]+", name):
            raise Fail(f"--widget-param 은 이름=값 모양이어야 합니다 (이름은 영문 대문자·숫자·_): {name}")
        values[name] = value
    body = PARAM_RE.sub(lambda m: json.dumps(values[m.group(1)], ensure_ascii=False)[1:-1]
                        if m.group(1) in values else m.group(0), script_text)
    missing = sorted(set(PARAM_RE.findall(body)))
    if missing:
        raise Fail("위젯 스크립트에 채워야 할 값이 있습니다: " + ", ".join(missing)
                   + " (--widget-param 이름=값 으로 준다)")
    body = body.strip().rstrip(";").strip()
    if not body:
        raise Fail("위젯 스크립트가 비어 있습니다.")
    runner = WIDGET_RUNNER.read_text(encoding="utf-8")
    if runner.count(WIDGET_SLOT) != 1 or runner.count(WIDGET_ROWS_SLOT) != 1 or runner.count(WIDGET_ALT_ROWS_SLOT) != 1:
        raise Fail("widget-runner.js 의 자리 표시가 없습니다.")
    runner = runner.replace(WIDGET_ROWS_SLOT, str(widget_rows(script_text)))
    runner = runner.replace(WIDGET_ALT_ROWS_SLOT, str(widget_rows(script_text, alt=True)))
    return runner.replace(WIDGET_SLOT, "(\n" + body + "\n)")


def shell_dex(home, tc, javac, java, workdir):
    """껍데기 코드는 모든 앱이 같으므로 한 번만 컴파일해 둔다 (소스·도구가 바뀌면 다시)."""
    p = tool_paths(tc)
    sources = sorted(SHELL_DIR.glob("*.java"))
    key = hashlib.sha256(b"".join(f.read_bytes() for f in sources) + TOOLCHAIN_ID.encode()).hexdigest()[:16]
    cache = home / "cache" / f"shell-{key}"
    dex = cache / "classes.dex"
    if dex.is_file():
        return dex
    classes = workdir / "classes"
    classes.mkdir()
    boot = os.pathsep.join([str(p["android_jar"]), str(p["lambda_stubs"])])
    run([javac, "-source", "8", "-target", "8", "-Xlint:-options", "-encoding", "UTF-8",
         "-bootclasspath", boot, "-d", classes, *sources], workdir, "껍데기 자바 컴파일(javac)")
    class_files = sorted(classes.rglob("*.class"))
    out = workdir / "dex"
    out.mkdir()
    run([java, "-cp", p["d8"], "com.android.tools.r8.D8", "--release", "--min-api", str(MIN_SDK),
         "--lib", p["android_jar"], "--output", out, *class_files], workdir, "dex 변환(d8)")
    cache.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(out / "classes.dex", dex)
    return dex


def fetch_verified(url, dest, size, sha256):
    """주소에서 받아 크기·SHA-256 을 확인한다 (이미 맞는 파일이 있으면 그대로)."""
    if dest.is_file() and dest.stat().st_size == size and file_hashes(dest)[1] == sha256:
        return dest
    say(f"  받는 중: {url} ({size / 1e6:.1f}MB)")
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(".part")
    req = urllib.request.Request(url, headers={"User-Agent": "JarvisUrlApp/1.0"})
    got = 0
    with urllib.request.urlopen(req, timeout=60) as resp, open(tmp, "wb") as f:
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            got += len(chunk)
            if got > size:
                break
            f.write(chunk)
    if got != size or file_hashes(tmp)[1] != sha256:
        tmp.unlink(missing_ok=True)
        raise Fail(f"받은 파일이 예상과 다릅니다 (크기·SHA-256): {url}")
    tmp.replace(dest)
    return dest


def hotword_libs(home):
    """--hotword: Vosk·JNA (Maven Central, 고정한 SHA-256) → 자바 묶음(jar)들과 ABI 별 네이티브 라이브러리(.so)."""
    deps = home / "deps"
    jars, libs = [], {abi: [] for abi in HOTWORD_ABIS}
    for path, size, sha in HOTWORD_LIBS:
        name = path.rsplit("/", 1)[-1]
        aar = fetch_verified(MAVEN + path, deps / name, size, sha)
        out = deps / (name[:-4] + "-" + sha[:12])
        if not (out / "classes.jar").is_file():
            tmp = deps / (out.name + ".tmp")
            shutil.rmtree(tmp, ignore_errors=True)
            tmp.mkdir(parents=True)
            with zipfile.ZipFile(aar) as z:
                for n in z.namelist():
                    if n == "classes.jar" or re.fullmatch(r"jni/[a-z0-9_-]+/lib[a-z0-9_]+\.so", n):
                        (tmp / n).parent.mkdir(parents=True, exist_ok=True)
                        (tmp / n).write_bytes(z.read(n))
            shutil.rmtree(out, ignore_errors=True)
            tmp.replace(out)
        jars.append(out / "classes.jar")
        for abi in HOTWORD_ABIS:
            libs[abi] += sorted((out / "jni" / abi).glob("*.so"))
    for abi, files in libs.items():
        names = {f.name for f in files}
        if not {"libvosk.so", "libjnidispatch.so"} <= names:
            raise Fail(f"호출어 라이브러리에 {abi} 용 파일이 없습니다: {sorted(names)}")
    return jars, libs


def hotword_dex(home, tc, javac, java, workdir, jars):
    """호출어 엔진(VoskEngine) + Vosk + JNA → classes2.dex (껍데기 classes.dex 와 따로, 이 앱들에만)."""
    p = tool_paths(tc)
    sources = sorted(HOTWORD_DIR.glob("*.java"))
    iface = SHELL_DIR / "HotwordEngine.java"
    key = hashlib.sha256(b"".join(f.read_bytes() for f in sources + [iface]) + TOOLCHAIN_ID.encode()
                         + "".join(sha for _, _, sha in HOTWORD_LIBS).encode()).hexdigest()[:16]
    cache = home / "cache" / f"hotword-{key}"
    dex = cache / "classes2.dex"
    if dex.is_file():
        return dex
    classes = workdir / "hw-classes"
    classes.mkdir()
    boot = os.pathsep.join([str(p["android_jar"]), str(p["lambda_stubs"])])
    run([javac, "-source", "8", "-target", "8", "-Xlint:-options", "-encoding", "UTF-8", "-bootclasspath", boot,
         "-cp", os.pathsep.join(str(j) for j in jars), "-d", classes, *sources, iface],
        workdir, "호출어 엔진 컴파일(javac)")
    engine = sorted(f for f in classes.rglob("*.class") if f.name.startswith("VoskEngine"))
    if not engine:
        raise Fail("호출어 엔진 클래스가 만들어지지 않았습니다.")
    out = workdir / "hw-dex"
    out.mkdir()
    run([java, "-cp", p["d8"], "com.android.tools.r8.D8", "--release", "--min-api", str(MIN_SDK),
         "--lib", p["android_jar"], "--classpath", classes, "--output", out, *engine, *jars],
        workdir, "호출어 dex 변환(d8)")
    made = sorted(out.glob("*.dex"))
    if [f.name for f in made] != ["classes.dex"]:
        raise Fail(f"호출어 dex 가 예상과 다릅니다: {[f.name for f in made]}")
    cache.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(made[0], dex)
    return dex


def maven_lib(home, repo, path, size, sha):
    """Maven 묶음 하나 (SHA-256 고정): {jar, res(폴더 또는 None), package(AAR 매니페스트의 패키지)}."""
    name = path.rsplit("/", 1)[-1]
    f = fetch_verified(repo + path, home / "deps" / "maven" / name, size, sha)
    if name.endswith(".jar"):
        return {"name": name, "jar": f, "res": None, "package": None}
    out = home / "deps" / "maven" / (name[:-4] + "-" + sha[:12])
    if not (out / "classes.jar").is_file():
        tmp = out.with_name(out.name + ".tmp")
        shutil.rmtree(tmp, ignore_errors=True)
        tmp.mkdir(parents=True)
        with zipfile.ZipFile(f) as z:
            for n in z.namelist():
                if n.endswith("/") or ".." in n.split("/"):
                    continue
                if n in ("classes.jar", "AndroidManifest.xml", "R.txt") or n.startswith("res/"):
                    (tmp / n).parent.mkdir(parents=True, exist_ok=True)
                    (tmp / n).write_bytes(z.read(n))
        shutil.rmtree(out, ignore_errors=True)
        tmp.replace(out)
    m = re.search(r'package="([A-Za-z0-9_.]+)"', (out / "AndroidManifest.xml").read_text(encoding="utf-8"))
    res = out / "res"
    rtxt = out / "R.txt"
    return {"name": name, "jar": out / "classes.jar", "package": m.group(1) if m else None,
            "res": res if res.is_dir() and any(res.rglob("*.*")) else None,
            "has_r": rtxt.is_file() and rtxt.stat().st_size > 0}


def maven_libs(home, specs):
    return [maven_lib(home, *spec) for spec in specs]


def lib_resources(p, workdir, libs, tag):
    """묶음들의 리소스를 컴파일 → (aapt2 link 에 넣을 zip 들, R 을 만들 패키지들)."""
    zips, pkgs = [], []
    for i, lib in enumerate(libs):
        if lib["res"]:
            z = workdir / f"{tag}-lib{i}.zip"
            run([p["aapt2"], "compile", "--dir", lib["res"], "-o", z], workdir, f"묶음 리소스 컴파일({lib['name']})")
            zips.append(z)
        if lib.get("has_r") and lib["package"] and lib["package"] not in pkgs:
            pkgs.append(lib["package"])
    return zips, pkgs


def dex_jars(home, tc, java, jars, min_api, workdir):
    """묶음(jar)들 → dex 파일들 (같은 묶음·도구면 한 번만 변환해 둔다)."""
    p = tool_paths(tc)
    key = hashlib.sha256(("|".join(str(file_hashes(j)[1]) for j in jars) + TOOLCHAIN_ID + str(min_api)).encode()).hexdigest()[:16]
    cache = home / "cache" / f"libdex-{key}"
    if not (cache / "classes.dex").is_file():
        out = workdir / f"libdex-{key}"
        out.mkdir()
        run([java, "-cp", p["d8"], "com.android.tools.r8.D8", "--release", "--min-api", str(min_api),
             "--lib", p["android_jar"], "--output", out, *jars], workdir, "묶음 dex 변환(d8)", timeout=900)
        shutil.rmtree(cache, ignore_errors=True)
        shutil.copytree(out, cache)
    return sorted(cache.glob("classes*.dex"), key=lambda f: int(re.sub(r"\D", "", f.stem) or 1))


def app_dex(tc, javac, java, workdir, tag, sources, keep, jars, gen, pkgs, min_api):
    """sources 를 (gen 의 묶음 R 과 함께) 컴파일해 keep(클래스 이름 앞부분들)과 묶음 R 만 dex 로."""
    p = tool_paths(tc)
    classes = workdir / f"{tag}-classes"
    classes.mkdir()
    r_files = [f for pkg in pkgs for f in [gen / Path(*pkg.split(".")) / "R.java"] if f.is_file()]
    boot = os.pathsep.join([str(p["android_jar"]), str(p["lambda_stubs"])])
    run([javac, "-source", "8", "-target", "8", "-Xlint:-options", "-nowarn", "-encoding", "UTF-8", "-bootclasspath", boot,
         "-cp", os.pathsep.join(str(j) for j in jars), "-d", classes, *sources, *r_files], workdir, f"{tag} 컴파일(javac)")
    picked = []
    for f in sorted(classes.rglob("*.class")):
        rel = f.relative_to(classes).as_posix()
        if any(rel.startswith(k) for k in keep) or any(
                rel.startswith(pkg.replace(".", "/") + "/R.") or rel.startswith(pkg.replace(".", "/") + "/R$") for pkg in pkgs):
            picked.append(f)
    out = workdir / f"{tag}-dex"
    out.mkdir()
    run([java, "-cp", p["d8"], "com.android.tools.r8.D8", "--release", "--min-api", str(min_api), "--lib", p["android_jar"],
         "--classpath", classes, *[x for j in jars for x in ("--classpath", j)], "--output", out, *picked],
        workdir, f"{tag} dex 변환(d8)")
    made = sorted(out.glob("*.dex"))
    if [f.name for f in made] != ["classes.dex"]:
        raise Fail(f"{tag} dex 가 예상과 다릅니다: {[f.name for f in made]}")
    return made[0]


def hotword_phrases(name, given):
    """호출어 문장: --hotword-phrase 로 준 것, 없으면 앱 이름이 한글 한 낱말일 때 '하이·헤이·안녕 <이름>'."""
    out = []
    for g in given or []:
        for part in str(g).split(","):
            t = re.sub(r"[\s.!?~]+", " ", part).strip()
            if t and t not in out:
                out.append(t)
    if not out:
        if not re.fullmatch(r"[가-힣]{2,6}", name or ""):
            raise Fail("호출어 문장을 정할 수 없습니다: 앱 이름이 한글 한 낱말이 아니면 --hotword-phrase \"하이 OO\" 를 주세요.")
        out = [f"하이 {name}", f"헤이 {name}", f"안녕 {name}"]
    for t in out:
        if not re.fullmatch(r"[가-힣]{1,6}( [가-힣]{1,6}){1,2}", t):
            raise Fail(f"호출어 문장은 한글 낱말 2~3개여야 합니다 (예: '하이 자비스'): {t}")
    if len(out) > 5:
        raise Fail("호출어 문장은 5개까지입니다.")
    return out


def check_alignment(apk):
    """압축하지 않은 항목이 4바이트 경계에 있는지 (안드로이드 11+ 설치 조건). 문제 있는 이름 목록."""
    bad = []
    with zipfile.ZipFile(apk) as z, open(apk, "rb") as f:
        for info in z.infolist():
            if info.filename == "resources.arsc" and info.compress_type != zipfile.ZIP_STORED:
                bad.append("resources.arsc(압축됨)")
            if info.compress_type != zipfile.ZIP_STORED:
                continue
            f.seek(info.header_offset)
            hdr = f.read(30)
            n, m = struct.unpack("<HH", hdr[26:30])
            if (info.header_offset + 30 + n + m) % 4:
                bad.append(info.filename)
    return bad


def badging(aapt2, apk, cwd):
    out = run([aapt2, "dump", "badging", apk], cwd, "aapt2 dump badging")
    info = {}
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out)
    if m:
        info.update(package=m.group(1), version_code=int(m.group(2)), version_name=m.group(3))
    for key, pat in (("min_sdk", r"^(?:min)?[sS]dkVersion:'(\d+)'"), ("target_sdk", r"^targetSdkVersion:'(\d+)'"),
                     ("label", r"^application-label:'(.*)'$")):
        m = re.search(pat, out, re.M)
        if m:
            info[key] = m.group(1)
    if "label" in info:
        info["label"] = info["label"].replace('\\"', '"')  # badging 은 큰따옴표를 \" 로 찍는다
    info["permissions"] = re.findall(r"uses-permission: name='([^']+)'", out)
    return info


def cert_digest(java, apksigner, apk, cwd):
    out = run([java, "-jar", apksigner, "verify", "--print-certs", apk], cwd, "인증서 읽기")
    m = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", out)
    return m.group(1) if m else None


def load_registry(home):
    p = home / "apps.json"
    if p.is_file():
        return json.loads(p.read_text(encoding="utf-8"))
    return {"apps": {}}


def save_registry(home, reg):
    p = home / "apps.json"
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(reg, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(p)


EXPLICIT_KEYS = ("name", "icon", "icon_color", "accent", "widget", "widget_param", "cards", "cards_via", "color", "bg", "orientation", "external", "allow_host", "desktop",
                 "fullscreen", "keep_awake", "dark", "id", "update_url", "live", "widget_style", "mic", "widget_sizes", "notify",
                 "update_encrypt", "hotword", "hotword_phrase", "voice_icon", "voice_icon_name", "watch")
DEFAULTS = {"orientation": "auto", "external": "browser", "allow_host": [], "desktop": False,
            "fullscreen": False, "keep_awake": False, "dark": "auto", "live": False, "widget_style": "auto", "mic": False, "notify": False,
            "update_encrypt": False, "hotword": False, "hotword_phrase": [], "voice_icon": False, "voice_icon_name": None, "watch": False}


def cmd_build(args):
    home = home_dir()
    java, javac, keytool = require_java()
    tc = toolchain_dir(home)
    if not toolchain_ready(tc):
        raise Fail("빌드 도구가 없습니다. 먼저 setup 을 실행하세요.")
    p = tool_paths(tc)
    ks, pw = ensure_keystore(home, keytool)

    url = normalize_url(args.url)
    if args.icon and args.icon not in ("auto", "letter") and not re.match(r"^https?://", args.icon):
        args.icon = str(Path(args.icon).expanduser().resolve())  # 다시 만들 때도 찾을 수 있게 절대 경로로 기억
    if args.widget and args.widget != "none":
        args.widget = str(Path(args.widget).expanduser().resolve())
    if args.cards and args.cards != "none":
        args.cards = str(Path(args.cards).expanduser().resolve())
    reg = load_registry(home)
    prev_id = next((k for k, v in reg["apps"].items() if v.get("url") == url), None)
    app_id = check_app_id(args.id or prev_id or derive_app_id(url))
    prev = reg["apps"].get(app_id, {})
    if prev and prev.get("url") != url and not args.id:
        raise Fail(f"{app_id} 는 이미 다른 주소({prev.get('url')})의 앱입니다. --id 로 다른 id 를 주세요.")
    explicit = dict(prev.get("explicit", {}))
    for k in EXPLICIT_KEYS:
        v = getattr(args, k, None)
        if v is not None and v != []:
            explicit[k] = v
    opt = {k: explicit.get(k, DEFAULTS.get(k)) for k in EXPLICIT_KEYS}

    workdir = Path(tempfile.mkdtemp(prefix="urlapp-"))
    try:
        site = {"name": None, "theme": None, "theme_dark": None, "bg": None, "icons": [], "notes": []}
        if not args.no_fetch:
            say(f"페이지 읽는 중: {url}")
            site = read_site(url)
        for n in site["notes"]:
            say(f"  참고: {n}")
        host = urllib.parse.urlsplit(url).hostname
        plain_host = host[4:] if host.startswith("www.") else host
        name = clean_text(opt["name"] or site["name"] or plain_host) or plain_host
        theme = parse_css_color(opt["color"] or "") or site["theme"] or "#FFFFFF"
        bg = parse_css_color(opt["bg"] or "") or site["bg"] or "#FFFFFF"
        if opt["color"] and not parse_css_color(opt["color"]):
            raise Fail(f"--color 형식이 아닙니다: {opt['color']} (예: #1A73E8)")
        if opt["bg"] and not parse_css_color(opt["bg"]):
            raise Fail(f"--bg 형식이 아닙니다: {opt['bg']} (예: #FFFFFF)")
        icon_color = parse_css_color(opt["icon_color"] or "")
        if opt["icon_color"] and not icon_color:
            raise Fail(f"--icon-color 형식이 아닙니다: {opt['icon_color']} (예: #4F46E5)")
        accent = parse_css_color(opt["accent"] or "")
        if opt["accent"] and not accent:
            raise Fail(f"--accent 형식이 아닙니다: {opt['accent']} (예: #4F46E5)")

        # 위젯 (사이트별 위젯 스크립트가 있을 때만)
        widget_js = None
        widget_n = 4
        widget_label = widget_alt_label = None
        widget_text = ""
        widget_refresh = 0
        if opt["widget"] and opt["widget"] != "none":
            ws = Path(opt["widget"]).expanduser()
            if not ws.is_file():
                raise Fail(f"위젯 스크립트 파일이 없습니다: {ws}")
            keep_w = home / "widgets" / (app_id + ".js")
            if ws.resolve() != keep_w.resolve():
                keep_w.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ws, keep_w)
            explicit["widget"] = str(keep_w)
            widget_text = keep_w.read_text(encoding="utf-8")
            widget_js = widget_asset(widget_text, opt["widget_param"] or [])
            widget_n = widget_rows(widget_text)
            widget_refresh = widget_refresh_min(widget_text)
            widget_label, widget_alt_label = widget_labels(widget_text)

        widget_sizes = parse_widget_sizes(opt["widget_sizes"]) if widget_js else []

        watch = bool(opt["watch"])  # 워치 앱 (타일 + 음성 질문)

        # 대화 창 아이콘 (--voice-icon): 빅스비 빠른 명령어로 부르는 길 (위젯 마이크와 같은 창이라 --mic 필요)
        voice_icon = None
        if opt["voice_icon"]:
            if not opt["mic"]:
                raise Fail("--voice-icon 은 --mic 와 같이 씁니다 (아이콘이 마이크 대화 창을 연다).")
            voice_icon = clean_text(opt["voice_icon_name"] or f"{name} 대화", 20)
            if len(voice_icon) < 2:
                raise Fail("--voice-icon-name 이 너무 짧습니다.")

        # '하이 자비스' 호출어 (--hotword): 위젯 마이크와 같은 대화 창을 쓰므로 --mic 가 필요하다
        hotword = None
        if opt["hotword"]:
            if not opt["mic"]:
                raise Fail("--hotword 는 --mic 와 같이 씁니다 (부르면 마이크 대화 창이 뜬다).")
            hotword = dict(HOTWORD_MODEL, phrases=hotword_phrases(name, opt["hotword_phrase"]))

        # 앱 안 업데이트: 새 버전 정보 파일 주소 (https). 'none' 이면 뺀다.
        update_url = opt["update_url"] if opt["update_url"] and opt["update_url"] != "none" else None
        if update_url and not re.fullmatch(r"https://[A-Za-z0-9.-]+/[^\s?#]+\.json", update_url):
            raise Fail("--update-url 은 https://…/<파일>.json 모양이어야 합니다 (url_app.py publish 가 만드는 정보 파일).")

        # 카드 결제 자동 등록 (사이트별 설정이 있을 때만)
        cards_cfg = None
        cards_via = opt["cards_via"] or "listener"  # 사용자가 고른 기본: 앱이 직접 알림을 읽는다
        if cards_via not in ("intent", "listener"):
            raise Fail("--cards-via 는 intent(MacroDroid) 또는 listener(앱이 직접 알림 읽기) 입니다.")
        if opt["cards"] and opt["cards"] != "none":
            cs = Path(opt["cards"]).expanduser()
            if not cs.is_file():
                raise Fail(f"카드 설정 파일이 없습니다: {cs}")
            keep_c = home / "cards" / (app_id + ".json")
            if cs.resolve() != keep_c.resolve():
                keep_c.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(cs, keep_c)
            explicit["cards"] = str(keep_c)
            cards_cfg = load_cards_config(keep_c)
            cards_cfg["via"] = cards_via

        # 아이콘
        icon_choice = opt["icon"] or "auto"
        src, mode, icon_from = None, "letter", "글자 아이콘"
        if icon_choice not in ("auto", "letter"):
            if re.match(r"^https?://", icon_choice):
                _, _, data = http_get(icon_choice, 5_000_000)
                src = workdir / "icon-source.img"
                src.write_bytes(png_from_ico(data) or data)
            else:
                src = Path(icon_choice).expanduser()
                if not src.is_file():
                    raise Fail(f"아이콘 파일이 없습니다: {src}")
                # 다시 만들 때도 쓸 수 있게 작업 폴더에 사본을 두고 그 경로를 기억한다 (원본이 지워져도 되도록)
                keep = home / "icons" / (app_id + (src.suffix.lower() or ".img"))
                if src.resolve() != keep.resolve():
                    keep.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(src, keep)
                    src = keep
                explicit["icon"] = str(keep)
            mode, icon_from = "image", icon_choice
        elif icon_choice == "auto" and site["icons"]:
            picked = pick_icon(site["icons"], workdir)
            if picked:
                src, masked, icon_from = picked[0], picked[1], picked[2]
                mode = "maskable" if masked else "image"
        letter_bg = icon_color or (theme if not is_near_white(theme) else LETTER_PALETTE[
            int(hashlib.sha1(app_id.encode()).hexdigest(), 16) % len(LETTER_PALETTE)])
        res = workdir / "res"
        res.mkdir()
        # 글자 아이콘은 색 있는 바탕, 투명한 로고 그림은 테마색(흰색이면 흰 바탕) 위에 그린다.
        icon_cmd = [java, ICON_TOOL, res, letter_candidates(name, host),
                    letter_bg if mode == "letter" else (icon_color or theme), mode]
        if src:
            icon_cmd.append(src)
        icon_out = run(icon_cmd, workdir, "아이콘 만들기")
        icon_info = json.loads(icon_out.strip().splitlines()[-1])
        if icon_info["used"] == "letter":
            icon_from = "글자 아이콘" + (" (그림이 64px 보다 작거나 읽을 수 없는 형식이라)" if src else "")
        write_resources(res, app_id, name, theme, bg, icon_info["icon_bg"], cards=bool(cards_cfg), update=bool(update_url),
                        mic=bool(opt["mic"]), hotword=hotword["phrases"][0] if hotword else None)
        if hotword:
            (res / "drawable").mkdir(exist_ok=True)
            (res / "drawable" / "urlapp_hotword.xml").write_text(HOTWORD_ICON, encoding="utf-8")
        if watch:  # 워치가 이 폰의 앱을 찾는 표시 (Data Layer 능력)
            (res / "values" / "urlapp_wear.xml").write_text("""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="android_wear_capabilities" translatable="false">
        <item>urlapp_phone</item>
    </string-array>
</resources>
""", encoding="utf-8")
        if voice_icon:
            write_voice_icon(res, voice_icon, icon_info["icon_bg"],
                             accent or icon_color or (theme if not is_near_white(theme) else "#1A73E8"))
        if cards_cfg:
            (res / "drawable").mkdir(exist_ok=True)
            (res / "drawable" / "urlapp_card_notify.xml").write_text(CARD_NOTIFY_ICON, encoding="utf-8")
        if opt["notify"]:
            (res / "drawable").mkdir(exist_ok=True)
            (res / "drawable" / "urlapp_notify.xml").write_text(NOTIFY_ICON, encoding="utf-8")
        widget_accent = accent or icon_color or (theme if not is_near_white(theme) else "#1A73E8")
        if widget_js:
            dark_bg = None
            if opt["widget_style"] == "dark":  # 바탕색이 어두우면 그 색, 밝으면 기본 어두운 색
                dark_bg = bg if is_dark(bg) else "#0F172A"
            write_widget_resources(res, widget_accent, widget_n, widget_label, widget_alt_label,
                                   widget_rows(widget_text, alt=True), dark_bg, widget_sizes, bool(opt["mic"]))

        # 설정과 매니페스트
        version_code = int(prev.get("version_code", 0)) + 1
        version_name = f"1.{version_code}"
        cfg = {"url": url, "hosts": own_hosts(url, opt["allow_host"]), "external": opt["external"],
               "theme_color": theme, "background_color": bg,
               "theme_color_dark": None if opt["color"] else site["theme_dark"],
               "desktop": bool(opt["desktop"]),
               "fullscreen": bool(opt["fullscreen"]), "keep_awake": bool(opt["keep_awake"]), "dark": opt["dark"],
               "live": bool(opt["live"]), "mic": bool(opt["mic"]), "notify": bool(opt["notify"]), "accent": accent, "widget_refresh_min": widget_refresh if widget_js else 0}
        if hotword:
            cfg["hotword"] = hotword
        if watch:
            cfg["watch"] = True
        if update_url:
            cfg["update_url"] = update_url
            if opt["update_encrypt"]:  # 업데이트 파일을 푸는 열쇠 (이 컴퓨터와 앱 안에만)
                cfg["update_key"] = update_key(home, app_id, create=True)
        elif opt["update_encrypt"]:
            raise Fail("--update-encrypt 는 --update-url 과 같이 씁니다.")
        assets = workdir / "assets"
        assets.mkdir()
        (assets / "urlapp.json").write_text(json.dumps(cfg, ensure_ascii=False, indent=1), encoding="utf-8")
        shutil.copyfile(DOWNLOAD_JS, assets / "urlapp-download.js")
        if widget_js:
            (assets / "urlapp-widget.js").write_text(widget_js, encoding="utf-8")
        if opt["mic"]:
            shutil.copyfile(SPEECH_JS, assets / "urlapp-speech.js")
        if opt["notify"]:
            shutil.copyfile(NOTIFY_JS, assets / "urlapp-notify.js")
        if hotword:
            shutil.copyfile(HOTWORD_DIR / "fillers-ko.txt", assets / "hotword-fillers.txt")
        if cards_cfg:
            (assets / "urlapp-cards.json").write_text(json.dumps(cards_cfg, ensure_ascii=False, indent=1),
                                                      encoding="utf-8")
        cleartext = urllib.parse.urlsplit(url).scheme == "http"
        (workdir / "AndroidManifest.xml").write_text(manifest_xml(app_id, cleartext, opt["orientation"], bool(widget_js),
                                                                  cards_via if cards_cfg else None,
                                                                  widget_label=bool(widget_js and widget_label),
                                                                  widget_alt=bool(widget_js and widget_alt_label),
                                                                  view_hosts=cfg["hosts"], update=bool(update_url),
                                                                  widget_extra_sizes=max(0, len(widget_sizes) - 1),
                                                                  mic=bool(opt["mic"]), notify=bool(opt["notify"]),
                                                                  hotword=bool(hotword), voice_icon=bool(voice_icon),
                                                                  watch=watch),
                                                     encoding="utf-8")

        say("APK 만드는 중")
        run([p["aapt2"], "compile", "--dir", res, "-o", workdir / "res.zip"], workdir, "리소스 컴파일(aapt2 compile)")
        unsigned = workdir / "unsigned.apk"
        link_extra, gms, gen, pkgs = [], [], workdir / "gen", []
        if watch:  # Play 서비스 묶음의 리소스와 그 R (묶음 코드가 쓴다)
            gms = maven_libs(home, GMS_LIBS)
            zips, pkgs = lib_resources(p, workdir, gms, "phone")
            gen.mkdir()
            link_extra = ["--auto-add-overlay", "--java", gen, "--extra-packages", ":".join(pkgs), *zips]
        run([p["aapt2"], "link", "-o", unsigned, "-I", p["android_jar"], "--manifest", workdir / "AndroidManifest.xml",
             "-A", assets, "--min-sdk-version", str(MIN_SDK), "--target-sdk-version", str(TARGET_SDK),
             "--version-code", str(version_code), "--version-name", version_name, workdir / "res.zip", *link_extra],
            workdir, "앱 묶기(aapt2 link)")
        dex = shell_dex(home, tc, javac, java, workdir)
        extra, dexes = [], []  # (APK 안 이름, 파일), classes2.dex 부터 붙일 dex
        if hotword:
            jars, libs = hotword_libs(home)
            dexes.append(hotword_dex(home, tc, javac, java, workdir, jars))
            for abi, files in libs.items():
                extra += [(f"lib/{abi}/{f.name}", f) for f in files]
        if watch:  # 폰 쪽 워치 연결(GmsWatchLink·PhoneWatchService)과 Play 서비스 묶음
            gjars = [lib["jar"] for lib in gms]
            dexes.append(app_dex(tc, javac, java, workdir, "워치 연결", sorted(WATCHLINK_DIR.glob("*.java")) + sorted(SHELL_DIR.glob("*.java")),
                                 ["app/jarvis/urlapp/GmsWatchLink", "app/jarvis/urlapp/PhoneWatchService"], gjars, gen, pkgs, MIN_SDK))
            dexes += dex_jars(home, tc, java, gjars, MIN_SDK, workdir)
        extra += [(f"classes{i}.dex", f) for i, f in enumerate(dexes, start=2)]
        with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as z:
            z.write(dex, "classes.dex")
            for arc, f in extra:
                z.write(f, arc)

        out_dir = Path(args.out).expanduser() if args.out else home / "apk"
        out_dir.mkdir(parents=True, exist_ok=True)
        apk = out_dir / f"{app_id.split('.')[-1]}-v{version_code}.apk"
        run([java, "-jar", p["apksigner"], "sign", "--ks", ks, "--ks-pass", f"file:{pw}", "--ks-key-alias", "urlapp",
             "--min-sdk-version", str(MIN_SDK), "--v4-signing-enabled", "false", "--out", apk, unsigned],
            workdir, "서명(apksigner)")

        # 검사
        run([java, "-jar", p["apksigner"], "verify", "--min-sdk-version", str(MIN_SDK), apk], workdir, "서명 검사")
        bad = check_alignment(apk)
        if bad:
            raise Fail(f"APK 정렬 문제: {', '.join(bad[:5])}")
        b = badging(p["aapt2"], apk, workdir)
        if b.get("package") != app_id or b.get("version_code") != version_code:
            raise Fail(f"만든 APK 의 정보가 예상과 다릅니다: {b}")
        digest = cert_digest(java, p["apksigner"], apk, workdir)
        if prev.get("cert_sha256") and prev["cert_sha256"] != digest:
            say("주의: 이전 버전과 서명 키가 다릅니다. 이미 설치된 앱 위에 업데이트되지 않으니, "
                "기존 앱을 지우고 설치해야 합니다 (앱 안의 로그인 정보도 지워집니다).")
        watch_apk = None
        if watch:
            say("워치 앱 만드는 중")
            watch_apk = build_watch_apk(home, tc, java, javac, ks, pw, app_id, name, icon_info["icon_bg"],
                                        accent or icon_color or (theme if not is_near_white(theme) else "#2DD4DA"),
                                        res, version_code, version_name, out_dir, workdir)
            if cert_digest(java, p["apksigner"], watch_apk, workdir) != digest:
                raise Fail("워치 앱의 서명이 폰 앱과 다릅니다 (Data Layer 가 연결되지 않음).")

        reg["apps"][app_id] = {
            "url": url, "name": name, "version_code": version_code, "version_name": version_name,
            "theme_color": theme, "background_color": bg, "icon_from": icon_from, "explicit": explicit,
            "apk": str(apk), "apk_sha256": file_hashes(apk)[1], "cert_sha256": digest, "widget": bool(widget_js),
            "cards": bool(cards_cfg), "watch_apk": str(watch_apk) if watch_apk else None,
            "built_at": time.strftime("%Y-%m-%d %H:%M:%S")}
        save_registry(home, reg)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)

    say("")
    say(f"완성: {apk} ({apk.stat().st_size / 1024:.0f}KB)")
    say(f"  이름 {name} | id {app_id} | 버전 {version_name} (코드 {version_code}) | 안드로이드 7.0 이상")
    say(f"  색 {theme} / 배경 {bg} | 아이콘: {icon_from}")
    if cards_cfg:
        how = ("MacroDroid 가 카드 알림을 넘겨준다 (설정 화면에 키·넣을 값)" if cards_via == "intent"
               else "앱이 직접 알림을 읽는다 (알림 접근 권한. 직접 설치는 Play 프로텍트가 막을 수 있음)")
        say(f"  카드 결제 자동 등록: 있음 (담당자 {', '.join(cards_cfg['owners'])}; {how}). 앱이 처음 열릴 때 설정을 묻는다")
    if widget_js:
        kinds = (f" 2종: '{widget_label or name}', '{widget_alt_label}'" if widget_alt_label else "")
        if widget_sizes:
            kinds += " 크기별: " + ", ".join(f"{w}×{h}" for w, h in widget_sizes)
        if widget_refresh:
            kinds += f", 새로고침 단추 + 화면이 켜져 있을 때 {widget_refresh}분마다 자동 새로고침"
        if opt["mic"]:
            kinds += ", 마이크 단추(음성 대화)"
        say(f"  홈 화면 위젯: 있음{kinds} (설치 후 홈 화면을 길게 눌러 '위젯'에서 이 앱을 고른다. 앱을 열 때마다 새로 읽는다)")
    if opt["notify"]:
        say("  앱 알림: 있음 (사이트의 /urlapp-notify.json 을 15분마다, 웹 알림 → 폰 알림. 안드로이드 13+ 은 처음 열 때 권한을 묻는다)")
    if update_url:
        say(f"  앱 안 업데이트: {update_url}" + (" (암호화: 열쇠는 ~/.jarvis-url-app/update-keys/ 와 앱 안에만)"
                                              if opt["update_encrypt"] else ""))
    if watch_apk:
        say(f"  워치 앱: {watch_apk} ({watch_apk.stat().st_size / 1024:.0f}KB, Wear OS 3+, 같은 서명) — 타일(위젯 요약 + '말하기') + "
            "대화 화면(워치에서 말하면 폰이 사이트에 묻고 답을 워치에). 워치에는 직접 설치(adb)한다. 사이트 주소는 워치 앱에 없다")
        if not widget_js:
            say("  주의: 위젯 스크립트(--widget)가 없어 워치 타일에 보일 요약이 없다 (말하기는 된다)")
    if voice_icon:
        say(f"  대화 창 아이콘: '{voice_icon}' (누르면 바로 듣는 대화 창. 빅스비 빠른 명령어에서 이 앱을 열게 하면 "
            f"'하이 빅스비, …' 로 부를 수 있다)")
    if hotword:
        say(f"  호출어: ‘{hotword['phrases'][0]}’ 등 {len(hotword['phrases'])}개 (앱 아이콘 길게 누르기 → 설정에서 켠다. "
            f"음성 모델 약 {hotword['model_size'] // 1_000_000}MB 는 폰이 그때 받는다. 소리는 폰 안에서만, 화면이 켜져 있을 때 듣는 것이 기본)")
    if opt["mic"]:
        say("  마이크·음성 대화: 있음 (마이크 권한은 처음 쓸 때 묻는다. 웹 음성 기능 → 폰의 음성 인식·읽어 주기, "
            "마이크 모드 = 사이트의 urlappMic 또는 urlappAsk)")
    say(f"  다른 사이트 링크: {'브라우저로' if opt['external'] == 'browser' else '앱 안에서'} | 앱 안에서 여는 사이트: "
        + ", ".join(cfg["hosts"]))
    say("설치: 이 APK 를 태블릿·폰으로 옮겨 파일을 누르고 '출처를 알 수 없는 앱 설치'를 그 앱(파일 관리자 등)에만 허용한다."
        + (" 같은 키로 서명했으므로 기존 앱 위에 업데이트된다 (로그인 유지)." if version_code > 1 else ""))
    return 0


def link_file_json(cards_cfg):
    """사이트에 둘 연동 약속 파일(/urlapp.json)의 내용 — 카드 등록이 쓰는 주소와 칸 이름."""
    keep = {k: cards_cfg[k] for k in ("add", "history", "delete", "default_category") if k in cards_cfg}
    return {
        "_comment": "우리집 가계부 앱(안드로이드)과의 연동 약속. 지출 추가·월별 내역·삭제 API 의 주소나 칸 이름을 바꾸면 "
                    "이 파일도 같이 바꿔 주세요. 앱은 이 파일을 읽어 따라갑니다.",
        "version": 1,
        "cards": keep,
    }


def update_key(home, app_id, create=False):
    """앱마다 하나인 업데이트 열쇠 (32바이트, base64). ~/.jarvis-url-app/update-keys/<앱 id>.key (나만 읽게).
    서명 키처럼 잃어버리면 그 앱은 앱 안 업데이트를 받지 못한다 (파일로 다시 설치하면 새 열쇠로)."""
    d = home / "update-keys"
    f = d / (app_id + ".key")
    if f.is_file():
        return f.read_text(encoding="utf-8").strip()
    if not create:
        raise Fail(f"업데이트 열쇠가 없습니다: {f}")
    d.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(d, 0o700)
    except OSError:
        pass
    key = base64.b64encode(os.urandom(32)).decode("ascii")
    fd = os.open(f, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as out:
        out.write(key + "\n")
    return key


def seal_file(home, app_id, src, dst, mode="seal"):
    java, _, _ = require_java()
    keyfile = home / "update-keys" / (app_id + ".key")
    if not keyfile.is_file():
        raise Fail(f"업데이트 열쇠가 없습니다: {keyfile}")
    r = subprocess.run([java, str(SEAL_TOOL), mode, str(keyfile), app_id, str(src), str(dst)],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise Fail("업데이트 파일 암호화 실패: " + (r.stderr or r.stdout).strip()[-300:])


def cmd_publish(args):
    """최신 APK 를 out_dir/<앱 id>-v<버전>.apk 로 두고(그 앱의 옛 APK 는 지움) out_dir/<앱 id>.json 을 쓴다.
    정보 파일의 apk 주소는 앱에 넣은 update_url 과 같은 곳(폴더)이다. 올리기(git push 등)는 사람이·자비스가 한다."""
    reg = load_registry(home_dir())
    t = args.target.strip()
    found = [(k, v) for k, v in reg["apps"].items() if k == t or v.get("url", "").rstrip("/") == t.rstrip("/")]
    if not found:
        raise Fail(f"만든 앱 중에 없습니다: {t} (url_app.py list 로 확인)")
    app_id, rec = found[0]
    update_url = (rec.get("explicit") or {}).get("update_url")
    if not update_url or update_url == "none":
        raise Fail("이 앱은 --update-url 없이 만들었습니다. --update-url 을 주고 다시 build 한 뒤 publish 하세요.")
    if not update_url.rsplit("/", 1)[1] == app_id + ".json":
        raise Fail(f"--update-url 의 파일 이름은 {app_id}.json 이어야 합니다: {update_url}")
    apk = Path(rec["apk"])
    if not apk.is_file():
        raise Fail(f"APK 가 없습니다: {apk} (다시 build 하세요)")
    out = Path(args.out_dir).expanduser()
    out.mkdir(parents=True, exist_ok=True)
    encrypt = bool((rec.get("explicit") or {}).get("update_encrypt"))
    # 암호화한 업데이트는 .bin (내용·주소를 볼 수 없다), 아니면 .apk
    name = f"{app_id}-v{rec['version_code']}." + ("bin" if encrypt else "apk")
    for old in list(out.glob(f"{app_id}-v*.apk")) + list(out.glob(f"{app_id}-v*.bin")):
        if old.name != name:
            old.unlink()
    if encrypt:
        seal_file(home_dir(), app_id, apk, out / name)
    else:
        shutil.copyfile(apk, out / name)
    notes = args.notes.strip()
    if len(notes) > 300:
        raise Fail("--notes 는 300자까지입니다.")
    info = {"package": app_id, "version_code": rec["version_code"], "version_name": rec["version_name"],
            "apk": update_url.rsplit("/", 1)[0] + "/" + name, "sha256": file_hashes(out / name)[1],
            "size": (out / name).stat().st_size, "notes": notes,
            "published_at": time.strftime("%Y-%m-%d %H:%M")}
    if encrypt:
        info.update({"encrypted": "aes-256-gcm", "apk_sha256": file_hashes(apk)[1]})
    (out / (app_id + ".json")).write_text(json.dumps(info, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    say(f"올릴 파일: {out / name} , {out / (app_id + '.json')}")
    say(f"  {rec['name']} {rec['version_name']} (코드 {rec['version_code']}) — 이 두 파일을 올리면 앱이 6시간 안에(또는 '업데이트 확인') 묻는다")
    return 0


def cmd_link_file(args):
    print(json.dumps(link_file_json(load_cards_config(Path(args.cards).expanduser())), ensure_ascii=False, indent=2))
    return 0


def static_part(path):
    return path.split("{")[0].split("?")[0]


def cmd_link_check(args):
    """재배포 뒤 연동 점검: 사이트의 /urlapp.json 과 사이트 코드(자바스크립트)만 본다. 가계 데이터는 받지 않는다."""
    url = normalize_url(args.url)
    origin = "{0.scheme}://{0.netloc}".format(urllib.parse.urlsplit(url))
    problems, notes = [], []
    link = None
    try:
        _, _, body = http_get(origin + "/urlapp.json", 200_000)
        if body.lstrip()[:1] == b"<":
            raise ValueError("아직 없음 (사이트가 화면 페이지를 돌려줌)")
        link = json.loads(body.decode("utf-8"))
        if not isinstance(link, dict) or not isinstance(link.get("cards"), dict):
            problems.append("/urlapp.json 에 cards 가 없습니다")
            link = None
        else:
            notes.append("/urlapp.json 있음")
    except Exception as e:  # noqa: BLE001
        why = str(e) if isinstance(e, ValueError) and "아직 없음" in str(e) else type(e).__name__
        problems.append(f"/urlapp.json 을 읽지 못함 ({why}) — 앱은 앱 기본 설정을 쓴다")
    cards = (link or {}).get("cards") or {}
    if args.cards:
        base_cfg = load_cards_config(Path(args.cards).expanduser())
        for k in ("add", "history", "delete"):
            cards.setdefault(k, base_cfg[k])
    for k in ("add", "history", "delete"):
        v = cards.get(k)
        if not (isinstance(v, dict) and isinstance(v.get("path"), str) and v["path"].startswith("/")
                and not v["path"].startswith("//")):
            problems.append(f"cards.{k}.path 가 없거나 같은 사이트 주소가 아님")
    # 사이트 코드에 그 주소·칸 이름이 아직 있는지 (화면이 같은 API 를 쓰는지)
    js_text = ""
    try:
        _, _, page = http_get(url, 3_000_000)
        for src in re.findall(r'<script[^>]+src="([^"]+\.js)"', page.decode("utf-8", "replace")):
            if src.startswith("http") and urllib.parse.urlsplit(src).netloc != urllib.parse.urlsplit(origin).netloc:
                continue
            _, _, js = http_get(urllib.parse.urljoin(url, src), 8_000_000)
            js_text += js.decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        problems.append(f"사이트 코드를 읽지 못함 ({type(e).__name__})")
    if js_text:
        for k in ("add", "history", "delete"):
            v = cards.get(k)
            if isinstance(v, dict) and isinstance(v.get("path"), str):
                sp = static_part(v["path"])
                (notes if sp in js_text else problems).append(
                    f"{k}: 사이트 코드에 {sp} " + ("있음" if sp in js_text else "없음 — API 가 바뀌었을 수 있음"))
        body = (cards.get("add") or {}).get("body") or {}
        for field in body:
            if field not in js_text:
                problems.append(f"add: 칸 이름 {field} 가 사이트 코드에 없음 — 칸 이름이 바뀌었을 수 있음")
    # 폰 상태 받는 곳 (있을 때만): 주소 모양, 그리고 GET 으로 그 주소가 서버에 있는지 (로그인 없이 401·403·405 면 있음, 404 면 없음)
    st = cards.get("status")
    if st is not None:
        if not (isinstance(st, dict) and isinstance(st.get("path"), str) and st["path"].startswith("/")
                and not st["path"].startswith("//")):
            problems.append("cards.status.path 가 없거나 같은 사이트 주소가 아님")
        else:
            try:
                http_get(urllib.parse.urljoin(origin, st["path"]), 200_000)
                notes.append(f"status: {st['path']} 있음 (로그인 없이 열림 — 서버가 앱 연동 키를 확인하는지 봐 주세요)")
            except urllib.error.HTTPError as e:
                if e.code in (401, 403, 405):
                    notes.append(f"status: {st['path']} 있음 (로그인·키 없이는 {e.code})")
                else:
                    problems.append(f"status: {st['path']} 가 서버에 없음 (HTTP {e.code}) — 폰 상태를 받지 못함")
            except Exception as e:  # noqa: BLE001
                problems.append(f"status: {st['path']} 확인 못 함 ({type(e).__name__})")
    for n in notes:
        say(f"  ✔ {n}")
    for p_ in problems:
        say(f"  ✖ {p_}")
    say("연동 점검: " + ("문제 없음" if not problems else f"확인할 것 {len(problems)}개"))
    return 0 if not problems else 1


def cmd_list(args):
    reg = load_registry(home_dir())
    if not reg["apps"]:
        say("아직 만든 앱이 없습니다.")
        return 0
    for app_id, a in sorted(reg["apps"].items(), key=lambda kv: kv[1].get("built_at", "")):
        say(f"- {a['name']}  ({a['url']})")
        say(f"    id {app_id} | 버전 {a['version_name']} | {a['built_at']} | {a['apk']}")
    return 0


# ---------------------------------------------------------------- 자체 시험

def _png(size, rgba_fn):
    import zlib
    raw = b"".join(b"\x00" + b"".join(bytes(rgba_fn(x, y, size)) for x in range(size)) for y in range(size))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def _png_rect(w, h, rgba):
    import zlib
    raw = b"".join(b"\x00" + bytes(rgba) * w for _ in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def cmd_selftest(args):
    import http.server
    import threading

    results = []

    def check(label, cond, detail=""):
        results.append(bool(cond))
        say(f"  [{'통과' if cond else '실패'}] {label}" + (f" — {detail}" if detail and not cond else ""))

    def raises(fn):
        try:
            fn()
        except Fail:
            return True
        return False

    say("1) 단위 시험")
    check("id: 호스트", derive_app_id("https://my-site.vercel.app/") == "app.jarvis.web.my_site_vercel_app")
    check("id: 경로가 다르면 다른 id", derive_app_id("https://a.com/x") != derive_app_id("https://a.com/y"))
    check("id: 숫자로 시작하는 호스트", derive_app_id("http://192.168.0.5:8080/").startswith("app.jarvis.web.h192_168_0_5_8080"))
    check("id: 잘못된 --id 거부", raises(lambda: check_app_id("1bad.id")) and raises(lambda: check_app_id("app.new.x")))
    check("주소: 스킴 없으면 https", normalize_url("example.com") == "https://example.com/")
    check("주소: ftp 거부", raises(lambda: normalize_url("ftp://example.com")))
    check("주소: 아이디·비밀번호 거부", raises(lambda: normalize_url("https://u:p@example.com/")))
    check("색: #abc", parse_css_color("#abc") == "#AABBCC")
    check("색: rgb()", parse_css_color("rgb(26, 115, 232)") == "#1A73E8")
    check("색: 이상한 값", parse_css_color("var(--x)") is None)
    check("문자열 이스케이프", android_string("@Tom's \"app\" & <b>") == "\\@Tom\\'s \\\"app\\\" &amp; &lt;b&gt;")
    check("제목 줄이기", short_title("찰스 노트 | 오늘의 지식") == "찰스 노트")
    check("제어 문자 제거", clean_text("A\u202eB\x00C") == "ABC")
    png = _png(96, lambda x, y, s: (200, 40, 40, 255))
    check("PNG 크기 읽기", image_size(png) == (96, 96))
    ico = struct.pack("<HHH", 0, 1, 1) + struct.pack("<BBBBHHII", 96, 96, 0, 0, 1, 32, len(png), 22) + png
    check("ICO 안의 PNG 꺼내기", png_from_ico(ico) == png)
    mx = manifest_xml("app.jarvis.web.x", True, "landscape")
    check("매니페스트: 평문 http 허용·방향", 'usesCleartextTraffic="true"' in mx and "sensorLandscape" in mx)
    check("매니페스트: 방향 자동이면 예외 속성 없음", "PROPERTY_COMPAT" not in manifest_xml("a.b", False, "auto"))

    home = home_dir()
    tc = toolchain_dir(home)
    if not toolchain_ready(tc) or not find_java():
        say("2) 실제 빌드 시험: 건너뜀 (setup 을 먼저 실행해야 함)")
    else:
        say("2) 실제 빌드 시험 (임시 폴더, 로컬 시험 서버)")
        tmp_home = Path(tempfile.mkdtemp(prefix="urlapp-selftest-"))
        site = Path(tempfile.mkdtemp(prefix="urlapp-site-"))
        (site / "app").mkdir()
        (site / "app" / "index.html").write_text(
            "<!doctype html><html><head><title>Charles Notes | daily</title>"
            '<meta name="theme-color" content="#1a73e8">'
            '<meta name="theme-color" media="(prefers-color-scheme: dark)" content="#202124">'
            '<link rel="apple-touch-icon" href="/icon.png"><link rel="manifest" href="/m.json">'
            "</head><body>hi</body></html>", encoding="utf-8")
        (site / "m.json").write_text(json.dumps({
            "short_name": "찰스 노트", "background_color": "#ffffff",
            "icons": [{"src": "/mask.png", "sizes": "256x256", "type": "image/png", "purpose": "maskable"}]}),
            encoding="utf-8")
        (site / "icon.png").write_bytes(_png(180, lambda x, y, s: (255, 200, 0, 255)))
        (site / "mask.png").write_bytes(_png(256, lambda x, y, s: (
            (20, 120, 60, 255) if (x - s / 2) ** 2 + (y - s / 2) ** 2 > (s / 4) ** 2 else (255, 255, 255, 255))))

        class Quiet(http.server.SimpleHTTPRequestHandler):
            def __init__(self, *a, **k):
                super().__init__(*a, directory=str(site), **k)

            def log_message(self, *a):
                pass

        srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Quiet)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{srv.server_address[1]}"
        old_env = {k: os.environ.get(k) for k in ("JARVIS_URL_APP_HOME", "JARVIS_URL_APP_TOOLCHAIN")}
        os.environ["JARVIS_URL_APP_HOME"] = str(tmp_home)
        os.environ["JARVIS_URL_APP_TOOLCHAIN"] = str(tc)
        java, _, _ = find_java()
        tp = tool_paths(tc)
        parser = build_parser()
        try:
            def build(*argv):
                return cmd_build(parser.parse_args(["build", *argv]))

            build(base + "/app/")
            reg = load_registry(tmp_home)
            app_id, rec = next(iter(reg["apps"].items()))
            apk = Path(rec["apk"])
            b = badging(tp["aapt2"], apk, tmp_home)
            check("빌드 1: 이름은 매니페스트 short_name", b.get("label") == "찰스 노트", str(b))
            check("빌드 1: 버전 코드 1, SDK 24/36", (b.get("version_code"), b.get("min_sdk"), b.get("target_sdk"))
                  == (1, "24", "36"), str(b))
            check("빌드 1: 인터넷 권한만 (+ 설치 때 자동 허용되는 네트워크 상태 확인)", b.get("permissions") == [
                "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"], str(b))
            check("빌드 1: 색은 페이지의 theme-color", rec["theme_color"] == "#1A73E8", rec["theme_color"])
            check("빌드 1: maskable 아이콘 사용", rec["icon_from"].endswith("/mask.png"), rec["icon_from"])
            with zipfile.ZipFile(apk) as z:
                names = set(z.namelist())
                cfg = json.loads(z.read("assets/urlapp.json"))
            check("빌드 1: dex·설정·다운로드 도우미·아이콘 포함", {"classes.dex", "assets/urlapp.json",
                                                    "assets/urlapp-download.js", "resources.arsc",
                                            "AndroidManifest.xml"} <= names and any("ic_launcher_fg" in n for n in names))
            check("빌드 1: 설정의 주소·호스트", cfg["url"] == base + "/app/" and cfg["hosts"] == ["127.0.0.1"], str(cfg))
            check("빌드 1: 정렬", not check_alignment(apk))
            mf1 = run([tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)], tmp_home, "xmltree")
            check("빌드 1: 자기 사이트 주소로 앱을 여는 길 (BROWSABLE, 자동 확인 없음 → 다른 링크를 가로채지 않음)",
                  "android.intent.action.VIEW" in mf1 and "android.intent.category.BROWSABLE" in mf1
                  and '"127.0.0.1"' in mf1 and "autoVerify" not in mf1, mf1[-500:])
            check("빌드 1: 서명 v2 이상 검증", "Verified using v2 scheme (APK Signature Scheme v2): true" in run(
                [java, "-jar", tp["apksigner"], "verify", "-v", apk], tmp_home, "verify"))

            build(base + "/app/", "--orientation", "portrait")
            rec2 = load_registry(tmp_home)["apps"][app_id]
            b2 = badging(tp["aapt2"], Path(rec2["apk"]), tmp_home)
            check("빌드 2: 같은 주소는 같은 id, 버전 2", b2.get("package") == app_id and b2.get("version_code") == 2)
            check("빌드 2: 같은 서명 키 (업데이트 설치 가능)", rec2["cert_sha256"] == rec["cert_sha256"])
            check("빌드 2: 지정한 옵션은 기억", rec2["explicit"].get("orientation") == "portrait")

            build(base + "/other", "--no-fetch", "--name", "@Tom's \"앱\" & <b>", "--icon", "letter",
                  "--external", "app", "--fullscreen", "--icon-color", "#4f46e5")
            reg3 = load_registry(tmp_home)
            id3 = next(k for k in reg3["apps"] if k != app_id)
            b3 = badging(tp["aapt2"], Path(reg3["apps"][id3]["apk"]), tmp_home)
            check("빌드 3: 특수문자 이름이 그대로", b3.get("label") == "@Tom's \"앱\" & <b>", str(b3.get("label")))
            check("빌드 3: 글자 아이콘", reg3["apps"][id3]["icon_from"].startswith("글자"))
            res3 = run([tp["aapt2"], "dump", "resources", reg3["apps"][id3]["apk"]], tmp_home, "dump resources")
            check("빌드 3: --icon-color 가 아이콘 바탕색", re.search(r"color/icon_bg\n\s+\(\) #ff4f46e5", res3) is not None)
            check("빌드 3: 다른 경로는 다른 id", id3 != app_id)
            check("잘못된 색 거부", raises(lambda: build(base + "/x", "--no-fetch", "--color", "blue-ish")))

            wide = site / "wide.png"  # 가로로 긴 꽉 찬 그림 (사용자가 준 사진·일러스트 같은)
            wide.write_bytes(_png_rect(300, 160, (40, 140, 200, 255)))
            build(base + "/wide", "--no-fetch", "--icon", str(wide))
            reg4 = load_registry(tmp_home)
            id4, rec4 = next((k, v) for k, v in reg4["apps"].items() if v["url"].endswith("/wide"))
            kept = Path(rec4["explicit"]["icon"])
            check("빌드 4: 아이콘 파일은 작업 폴더에 사본으로 기억",
                  kept.parent == tmp_home / "icons" and kept.read_bytes() == wide.read_bytes(), str(kept))
            check("빌드 4: 가로 그림도 글자 대신 그림으로", not rec4["icon_from"].startswith("글자"), rec4["icon_from"])
            wide.unlink()
            build(base + "/wide", "--no-fetch")
            check("빌드 4: 원본을 지워도 다시 만들기 됨",
                  load_registry(tmp_home)["apps"][id4]["version_code"] == 2)

            wjs = site / "w.js"
            wjs.write_text("// 시험용 위젯\nasync function () { return {title: 'T', rows: [{label: 'a', value: '1'}]}; };",
                           encoding="utf-8")
            build(base + "/widget", "--no-fetch", "--widget", str(wjs), "--accent", "#4f46e5")
            id5, rec5 = next((k, v) for k, v in load_registry(tmp_home)["apps"].items() if v["url"].endswith("/widget"))
            mf = run([tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", rec5["apk"]], tmp_home, "xmltree")
            with zipfile.ZipFile(rec5["apk"]) as z:
                names5 = set(z.namelist())
                wasset = z.read("assets/urlapp-widget.js").decode("utf-8")
            res5 = run([tp["aapt2"], "dump", "resources", rec5["apk"]], tmp_home, "dump resources")
            check("빌드 5: 위젯 receiver 와 provider 정보", "app.jarvis.urlapp.WidgetProvider" in mf
                  and "android.appwidget.action.APPWIDGET_UPDATE" in mf and "android.appwidget.provider" in mf)
            check("빌드 5: 위젯 스크립트가 실행기 안에", WIDGET_SLOT not in wasset and "async function ()" in wasset
                  and '"__URLAPP_TOKEN__"' in wasset)
            check("빌드 5: 위젯 화면·밤 색", "layout/urlapp_widget" in res5 and "xml/urlapp_widget_info" in res5
                  and re.search(r"color/widget_accent\n\s+\(\) #ff4f46e5", res5) is not None
                  and "(night)" in res5)
            check("빌드 5: 설정에 강조색", json.loads(zipfile.ZipFile(rec5["apk"]).read("assets/urlapp.json"))["accent"]
                  == "#4F46E5")
            check("빌드 5: 기본 위젯은 4줄·두 칸 높이·한 종류", "id/w_row4" in res5 and "id/w_row5" not in res5
                  and "var MAX_ROWS = 4;" in wasset and "WidgetProviderAlt" not in mf
                  and "layout/urlapp_widget_alt" not in res5)
            w6 = site / "w6.js"
            w6.write_text("// urlapp:rows 6\n// urlapp:label 전체 & '요약'\n// urlapp:alt-label 카드 사용\n// urlapp:alt-rows 4\n"
                          "// urlapp:refresh-min 10\n"
                          "async function (site) { return null; }", encoding="utf-8")
            build(base + "/widget6", "--no-fetch", "--widget", str(w6))
            rec56 = next(v for v in load_registry(tmp_home)["apps"].values() if v["url"].endswith("/widget6"))
            res56 = run([tp["aapt2"], "dump", "resources", rec56["apk"]], tmp_home, "dump resources")
            with zipfile.ZipFile(rec56["apk"]) as z:
                a56 = z.read("assets/urlapp-widget.js").decode("utf-8")
                info56 = run([tp["aapt2"], "dump", "xmltree", "--file", "res/xml/urlapp_widget_info.xml", rec56["apk"]],
                             tmp_home, "xmltree")
            check("빌드 5-6: urlapp:rows 6 이면 6줄·세 칸 높이", "id/w_row6" in res56 and "id/w_row7" not in res56
                  and "var MAX_ROWS = 6;" in a56 and re.search(r"targetCellHeight.*=3", info56) is not None, info56[-400:])
            mf56 = run([tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", rec56["apk"]], tmp_home, "xmltree")
            check("빌드 5-6: alt-label 이면 위젯 2종 (각자 이름)", "app.jarvis.urlapp.WidgetProviderAlt" in mf56
                  and "app.jarvis.urlapp.WidgetProvider\"" in mf56 and "string/widget_name" in res56
                  and "string/widget_alt_name" in res56 and "전체 & 'x'".replace("x", "요약") in res56
                  and "카드 사용" in res56, res56[-600:])
            with zipfile.ZipFile(rec56["apk"]) as z:
                alt_info = run([tp["aapt2"], "dump", "xmltree", "--file", "res/xml/urlapp_widget_alt_info.xml", rec56["apk"]],
                               tmp_home, "xmltree")
            check("빌드 5-6: 두 번째 위젯은 자기 모양·4줄·3×2 (첫 위젯은 6줄·3×3)",
                  "layout/urlapp_widget_alt" in res56 and re.search(r"targetCellHeight.*=2", alt_info) is not None
                  and re.search(r"WidgetProviderAlt[\s\S]*?android:resource\(0x01010025\)=@" + re.escape(
                      (re.search(r"resource (0x[0-9a-f]+) xml/urlapp_widget_alt_info", res56) or [None, "?"])[1]), mf56)
                  is not None
                  and re.search(r"integer/urlapp_widget_alt_rows\n\s+\(\) 4", res56) is not None
                  and re.search(r"integer/urlapp_widget_rows\n\s+\(\) 6", res56) is not None
                  and "var ALT_ROWS = 4 ||" in a56, alt_info[-300:])
            cfg56 = json.loads(zipfile.ZipFile(rec56["apk"]).read("assets/urlapp.json"))
            cfg5 = json.loads(zipfile.ZipFile(rec5["apk"]).read("assets/urlapp.json"))
            check("빌드 5-6: urlapp:refresh-min 10 → 새로고침 단추·10분 자동 (기본 위젯은 0)",
                  cfg56.get("widget_refresh_min") == 10 and cfg5.get("widget_refresh_min") == 0
                  and "app.jarvis.urlapp.WidgetRefreshJob" in mf56 and "id/w_refresh" in res56
                  and "drawable/urlapp_widget_refresh" in res56, (cfg56.get("widget_refresh_min"), cfg5.get("widget_refresh_min")))
            wr = site / "wr.js"
            wr.write_text("// urlapp:refresh-min 2\nasync function () { return null; }", encoding="utf-8")
            check("새로고침 간격이 5~1440 분 밖이면 거부", raises(lambda: build(base + "/widgetr", "--no-fetch", "--widget", str(wr))))
            wl = site / "wl.js"
            wl.write_text("// urlapp:alt-label " + "가" * 31 + "\nasync function () { return null; }", encoding="utf-8")
            check("위젯 이름이 30자를 넘으면 거부", raises(lambda: build(base + "/widgetl", "--no-fetch", "--widget", str(wl))))
            # --widget-style dark: 늘 어두운 위젯 (앱 바탕색이 어두우면 그 색)
            build(base + "/wdark", "--no-fetch", "--widget", str(wjs), "--bg", "#021A20", "--widget-style", "dark")
            recd = next(v for v in load_registry(tmp_home)["apps"].values() if v["url"].endswith("/wdark"))
            resd = run([tp["aapt2"], "dump", "resources", recd["apk"]], tmp_home, "resources")
            bgs = re.findall(r"color/widget_bg\n((?:\s+\(.*\) #[0-9a-f]+\n)+)", resd)
            vals = re.findall(r"#[0-9a-f]+", bgs[0]) if bgs else []  # 같은 값이면 aapt2 가 하나로 합친다
            check("--widget-style dark: 밝은/어두운 모드 모두 앱 바탕색 위젯", vals and set(vals) == {"#ff021a20"}, bgs[:1])
            # --mic + --widget-sizes: 크기별 위젯 3종(같은 내용)·마이크 단추·마이크 권한·음성 도우미·'마이크 모드' 바로가기
            check("위젯 크기 형식이 틀리면 거부", raises(lambda: parse_widget_sizes("4x2,9x9"))
                  and raises(lambda: parse_widget_sizes("4x2,4x2")) and raises(lambda: parse_widget_sizes("4x2,3x2,2x2,4x1,2x1"))
                  and parse_widget_sizes("4x2,3x2,2x2,4x1") == [(4, 2), (3, 2), (2, 2), (4, 1)]
                  and parse_widget_sizes("4x2, 3×2 2x2") == [(4, 2), (3, 2), (2, 2)])
            check("크기별 줄 수 (2칸 높이: 4줄, 가로 2칸이면 3줄)", size_rows((4, 2), 4) == 4 and size_rows((2, 2), 4) == 3
                  and size_rows((3, 1), 4) == 1 and size_rows((4, 1), 4) == 1 and size_rows((4, 4), 6) == 6)
            build(base + "/micw", "--no-fetch", "--widget", str(wjs), "--mic", "--widget-sizes", "4x2,3x2,2x2,4x1")
            recm = next(v for v in load_registry(tmp_home)["apps"].values() if v["url"].endswith("/micw"))
            mfm = run([tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", recm["apk"]], tmp_home, "xmltree")
            resm = run([tp["aapt2"], "dump", "resources", recm["apk"]], tmp_home, "resources")
            with zipfile.ZipFile(recm["apk"]) as z:
                names_m = z.namelist()
                cfgm = json.loads(z.read("assets/urlapp.json"))
                scm = run([tp["aapt2"], "dump", "xmltree", "--file", "res/xml/shortcuts.xml", recm["apk"]], tmp_home, "xmltree")
                infos = {}
                for k in ("urlapp_widget_info", "urlapp_widget_s2_info", "urlapp_widget_s3_info", "urlapp_widget_s4_info"):
                    f = next((n for n in names_m if n.startswith("res/") and n.endswith(".xml")
                              and re.search(r"/" + k + r"\.xml$", n)), None)
                    infos[k] = run([tp["aapt2"], "dump", "xmltree", "--file", f, recm["apk"]], tmp_home, "xmltree") if f else ""
            cells = {k: re.findall(r"targetCell(?:Width|Height)\(0x[0-9a-f]+\)=(\d+)", v) for k, v in infos.items()}
            check("크기별 위젯: 4×2·3×2·2×2 세 가지가 위젯 목록에 따로", "app.jarvis.urlapp.WidgetProviderS2" in mfm
                  and "app.jarvis.urlapp.WidgetProviderS3" in mfm
                  and "app.jarvis.urlapp.WidgetProviderS4" in mfm
                  and cells == {"urlapp_widget_info": ["4", "2"], "urlapp_widget_s2_info": ["3", "2"],
                                "urlapp_widget_s3_info": ["2", "2"], "urlapp_widget_s4_info": ["4", "1"]}, cells)
            dps = {k: [int(float(x)) for x in re.findall(r"min(?:Width|Height)\(0x[0-9a-f]+\)=([0-9.]+)dp", v)]
                   for k, v in infos.items()}
            check("크기별 위젯: 넓은 칸 런처에서도 4×2 가 3칸으로 줄지 않게 (기본 너비 300·210·130·300dp, 높이 150·150·150·50dp)",
                  dps == {"urlapp_widget_info": [300, 150], "urlapp_widget_s2_info": [210, 150],
                          "urlapp_widget_s3_info": [130, 150], "urlapp_widget_s4_info": [300, 50]}, dps)
            with zipfile.ZipFile(recm["apk"]) as z:
                f41 = next((n for n in z.namelist() if re.search(r"layout/urlapp_widget_s4\.xml$", n)), None)
            lay41 = run([tp["aapt2"], "dump", "xmltree", "--file", f41, recm["apk"]], tmp_home, "xmltree") if f41 else ""
            need41 = ["w_root", "w_bg", "w_title", "w_row1", "w_label1", "w_value1", "w_note", "w_refresh", "w_mic"]
            idhex = dict((n, h) for h, n in re.findall(r"resource (0x[0-9a-f]+) id/(w_[a-z0-9]+)", resm))
            missing41 = [n for n in need41 if n not in idhex or f"=@{idhex[n]}" not in lay41]
            check("4×1 위젯: 가로 한 줄 모양 (첫 줄만, 줄이 없을 때 안내), 그리는 코드가 만지는 id 가 모두 있음",
                  f41 is not None and not missing41 and f"=@{idhex.get('w_row2', 'x')}" not in lay41
                  and re.search(r"integer/urlapp_widget_s4_rows\n\s+\(\) 1", resm) is not None
                  and re.search(r"integer/urlapp_widget_s4_oneline\n\s+\(\) 1", resm) is not None, missing41)
            check("위젯 꾸미기: 길게 눌러 '설정'(다시 설정 가능·놓을 때 안 물음)·투명도용 바탕 그림·설정 화면",
                  all("app.jarvis.urlapp.WidgetSettingsActivity" in v and re.search(r"widgetFeatures\(0x[0-9a-f]+\)=(\(type 0x11\))?0x0*5\b", v)
                      for v in infos.values())
                  and "android.appwidget.action.APPWIDGET_CONFIGURE" in mfm and "id/w_bg" in resm, infos["urlapp_widget_info"][-600:])
            check("크기별 위젯: 줄 수 4·4·3", re.search(r"integer/urlapp_widget_rows\n\s+\(\) 4", resm) is not None
                  and re.search(r"integer/urlapp_widget_s2_rows\n\s+\(\) 4", resm) is not None
                  and re.search(r"integer/urlapp_widget_s3_rows\n\s+\(\) 3", resm) is not None)
            va = mfm[mfm.find("app.jarvis.urlapp.VoiceActivity"):]
            va = va[:min([i for i in (va.find("E: activity"), va.find("E: receiver"), va.find("E: service"), len(va)) if i > 0])]
            check("마이크: 위젯 대화 창(VoiceActivity)은 앱 안에서만 열리고 따로 떠서 최근 앱에 안 남음",
                  "app.jarvis.urlapp.VoiceActivity" in mfm and "style/VoiceTheme" in resm
                  and re.search(r"exported\(0x[0-9a-f]+\)=(false|\(type 0x12\)0x0)", va) is not None
                  and re.search(r"excludeFromRecents\(0x[0-9a-f]+\)=(true|\(type 0x12\)0xffffffff)", va) is not None, va[:900])
            check("마이크: 권한·음성 서비스 찾기·도우미·설정·위젯 단추·바로가기", "android.permission.RECORD_AUDIO" in mfm
                  and "android.speech.RecognitionService" in mfm and "android.intent.action.TTS_SERVICE" in mfm
                  and "assets/urlapp-speech.js" in names_m and cfgm.get("mic") is True and "id/w_mic" in resm
                  and "drawable/urlapp_widget_mic" in resm and "action.MIC" in scm)
            sp_js = zipfile.ZipFile(recm["apk"]).read("assets/urlapp-speech.js").decode("utf-8")
            check("마이크: 음성 도우미에 토큰 자리", sp_js.count('"__URLAPP_TOKEN__"') == 1 and "UrlAppSpeech" in sp_js)
            mf_nomic = run([tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", recd["apk"]], tmp_home, "xmltree")
            check("--mic 없는 앱에는 마이크 권한·도우미·크기별 위젯 없음", "RECORD_AUDIO" not in mf_nomic
                  and "urlapp-speech.js" not in str(zipfile.ZipFile(recd["apk"]).namelist())
                  and "WidgetProviderS2" not in mf_nomic and json.loads(zipfile.ZipFile(recd["apk"]).read("assets/urlapp.json")).get("mic") is False)
            # 안드로이드 14+: 인터넷 조건을 건 작업 예약에는 ACCESS_NETWORK_STATE 가 꼭 있어야 한다 (없으면 앱이 꺼진다)
            raw = [f.name for f in SHELL_DIR.glob("*.java") if f.name != "Jobs.java" and "js.schedule(" in f.read_text(encoding="utf-8")]
            check("작업 예약: 네트워크 상태 권한이 늘 있고, 예약은 모두 Jobs.safe 로 (앱이 꺼지지 않게)",
                  'android.permission.ACCESS_NETWORK_STATE' in mf_nomic and "android.permission.ACCESS_NETWORK_STATE" in mfm
                  and not raw, raw)
            # --hotword: '하이 자비스' 호출어 — 엔진은 classes2.dex 로 따로, Vosk·JNA 네이티브 라이브러리, 마이크 전경 서비스
            check("호출어 문장: 기본은 앱 이름으로, 쉼표·여러 번, 형식이 틀리면 거부",
                  hotword_phrases("자비스", []) == ["하이 자비스", "헤이 자비스", "안녕 자비스"]
                  and hotword_phrases("x", ["헤이 찰스, 안녕 찰스!", "헤이 찰스"]) == ["헤이 찰스", "안녕 찰스"]
                  and raises(lambda: hotword_phrases("Jarvis", [])) and raises(lambda: hotword_phrases("자비스", ["자비스"]))
                  and raises(lambda: hotword_phrases("자비스", ["hey jarvis"])))
            check("--hotword 는 --mic 없이 거부", raises(lambda: build(base + "/hw0", "--no-fetch", "--name", "자비스", "--hotword")))
            for f in (home / "deps").glob("*.aar"):  # 이미 받아 둔 라이브러리는 다시 받지 않는다 (SHA-256 은 다시 확인)
                (tmp_home / "deps").mkdir(exist_ok=True)
                shutil.copyfile(f, tmp_home / "deps" / f.name)
            build(base + "/hw", "--no-fetch", "--name", "자비스", "--mic", "--hotword",
                  "--update-url", "https://raw.githubusercontent.com/o/r/app-releases/x.json")
            rech = next(v for v in load_registry(tmp_home)["apps"].values() if v["url"].endswith("/hw"))
            mfh = run([tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", rech["apk"]], tmp_home, "xmltree")
            resh = run([tp["aapt2"], "dump", "resources", rech["apk"]], tmp_home, "resources")
            bh = badging(tp["aapt2"], rech["apk"], tmp_home)
            bh_raw = run([tp["aapt2"], "dump", "badging", rech["apk"]], tmp_home, "badging")
            with zipfile.ZipFile(rech["apk"]) as z:
                names_h = z.namelist()
                cfgh = json.loads(z.read("assets/urlapp.json"))
                dex1, dex2 = z.read("classes.dex"), z.read("classes2.dex") if "classes2.dex" in names_h else b""
                fill_h = z.read("assets/hotword-fillers.txt").decode("utf-8") if "assets/hotword-fillers.txt" in names_h else ""
            sch = run([tp["aapt2"], "dump", "xmltree", "--file", "res/xml/shortcuts.xml", rech["apk"]], tmp_home, "xmltree")
            check("호출어: 엔진·Vosk·JNA 는 classes2.dex 에만, 껍데기(classes.dex)에는 서비스만",
                  b"Lorg/vosk/Model;" in dex2 and b"Lapp/jarvis/urlapp/VoskEngine;" in dex2 and b"Lcom/sun/jna/Native;" in dex2
                  and b"Lorg/vosk/Model;" not in dex1 and b"Lapp/jarvis/urlapp/HotwordService;" in dex1
                  and b"Lapp/jarvis/urlapp/HotwordService;" not in dex2.replace(b"Lapp/jarvis/urlapp/HotwordEngine;", b""))
            libs_h = sorted(n for n in names_h if n.startswith("lib/"))
            check("호출어: 64·32비트 ARM 의 libvosk·libjnidispatch 만 (x86 은 뺌), 설치 때 풀기",
                  libs_h == ["lib/arm64-v8a/libjnidispatch.so", "lib/arm64-v8a/libvosk.so",
                             "lib/armeabi-v7a/libjnidispatch.so", "lib/armeabi-v7a/libvosk.so"]
                  and re.search(r"extractNativeLibs\(0x[0-9a-f]+\)=(true|\(type 0x12\)0xffffffff)", mfh) is not None
                  and "native-code: 'arm64-v8a' 'armeabi-v7a'" in bh_raw, libs_h)
            hs = mfh[mfh.rfind("E: service", 0, mfh.find("app.jarvis.urlapp.HotwordService\"")):]
            hs = hs[:hs.find("E: ", 5) if hs.find("E: ", 5) > 0 else len(hs)]  # 그 서비스 요소의 속성들만
            check("호출어: 마이크 전경 서비스(앱 안에서만)·설정 화면·재부팅/업데이트 알림 받기",
                  re.search(r"foregroundServiceType\(0x[0-9a-f]+\)=(\(type 0x11\))?0x0*80\b", hs) is not None
                  and re.search(r"exported\(0x[0-9a-f]+\)=(false|\(type 0x12\)0x0)", hs) is not None
                  and "app.jarvis.urlapp.HotwordActivity" in mfh and "app.jarvis.urlapp.HotwordService$Boot" in mfh
                  and "android.intent.action.BOOT_COMPLETED" in mfh and "android.intent.action.MY_PACKAGE_REPLACED" in mfh, hs[:600])
            perms_h = bh["permissions"]
            check("호출어: 권한 (전경 서비스·마이크 유형·다른 앱 위에 표시·알림 한 번·진동·재부팅)",
                  all(f"android.permission.{x}" in perms_h for x in ("FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MICROPHONE",
                      "SYSTEM_ALERT_WINDOW", "POST_NOTIFICATIONS", "VIBRATE", "RECEIVE_BOOT_COMPLETED", "RECORD_AUDIO"))
                  and perms_h.count("android.permission.POST_NOTIFICATIONS") == 1, perms_h)
            hw_cfg = cfgh.get("hotword") or {}
            check("호출어: 설정(모델 주소·크기·SHA-256·문장)·채움말·알림 아이콘·바로가기 이름",
                  hw_cfg.get("phrases") == ["하이 자비스", "헤이 자비스", "안녕 자비스"]
                  and hw_cfg.get("model_sha256") == HOTWORD_MODEL["model_sha256"] and hw_cfg.get("model_url", "").startswith("https://")
                  and len([l for l in fill_h.splitlines() if l and not l.startswith("#")]) > 4000
                  and "drawable/urlapp_hotword" in resh and re.search(r"string/sc_hotword\n\s+\(\) \"?하이 자비스", resh) is not None,
                  hw_cfg)
            acts = re.findall(r"action\.([A-Z]+)\" \(Raw", sch)
            check("호출어: 바로가기 4개 (마이크·호출어·처음 화면·업데이트 — 브라우저는 빠짐)",
                  acts == ["MIC", "HOTWORD", "HOME", "UPDATE"], acts)
            with zipfile.ZipFile(recm["apk"]) as z:
                names_nh = z.namelist()
            check("--hotword 없는 앱에는 엔진·라이브러리·서비스·권한 없음", "classes2.dex" not in names_nh
                  and not any(n.startswith("lib/") for n in names_nh) and "HotwordService" not in mfm
                  and "FOREGROUND_SERVICE" not in mfm and "SYSTEM_ALERT_WINDOW" not in mfm)
            build(base + "/hw", "--no-fetch", "--no-hotword")  # 다시 빼면 (사용자가 빅스비 빠른 명령어로 바꿈, 2026-11-20)
            rech2 = next(v for v in load_registry(tmp_home)["apps"].values() if v["url"].endswith("/hw"))
            mfh2 = run([tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", rech2["apk"]], tmp_home, "xmltree")
            with zipfile.ZipFile(rech2["apk"]) as z:
                names_h2 = z.namelist()
                cfgh2 = json.loads(z.read("assets/urlapp.json"))
            check("호출어를 다시 빼면 엔진·라이브러리·서비스·권한·설정이 빠지고 마이크는 남음",
                  "classes2.dex" not in names_h2 and not any(n.startswith("lib/") for n in names_h2)
                  and "HotwordService" not in mfh2 and "FOREGROUND_SERVICE" not in mfh2 and "SYSTEM_ALERT_WINDOW" not in mfh2
                  and "hotword" not in cfgh2 and cfgh2.get("mic") is True and "android.permission.RECORD_AUDIO" in mfh2)
            # --voice-icon: 대화 창을 바로 여는 두 번째 아이콘 (빅스비 빠른 명령어가 연다)
            check("--voice-icon 은 --mic 없이 거부", raises(lambda: build(base + "/vi0", "--no-fetch", "--voice-icon")))
            build(base + "/vi", "--no-fetch", "--name", "자비스", "--mic", "--voice-icon")
            recv = next(v for v in load_registry(tmp_home)["apps"].values() if v["url"].endswith("/vi"))
            mfv = run([tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", recv["apk"]], tmp_home, "xmltree")
            resv = run([tp["aapt2"], "dump", "resources", recv["apk"]], tmp_home, "resources")
            al = mfv[mfv.rfind("E: activity-alias", 0, mfv.find("app.jarvis.urlapp.VoiceLauncher")):]
            al = al[:al.find("E: activity", 5) if al.find("E: activity", 5) > 0 else len(al)]
            va2 = mfv[mfv.rfind("E: activity", 0, mfv.find("app.jarvis.urlapp.VoiceActivity\"")):]
            va2 = va2[:va2.find("E: ", 5)]
            check("대화 창 아이콘: 앱 목록에 '자비스 대화'(MAIN/LAUNCHER) → 대화 창, 창 자체는 그대로 앱 안에서만",
                  "E: activity-alias" in mfv and "app.jarvis.urlapp.VoiceActivity" in al
                  and "android.intent.action.MAIN" in al and "android.intent.category.LAUNCHER" in al
                  and re.search(r"exported\(0x[0-9a-f]+\)=(true|\(type 0x12\)0xffffffff)", al) is not None
                  and re.search(r"exported\(0x[0-9a-f]+\)=(false|\(type 0x12\)0x0)", va2) is not None
                  and re.search(r"string/voice_icon_name\n\s+\(\) \"?자비스 대화", resv) is not None
                  and "mipmap/ic_voice" in resv and "drawable/urlapp_voice_fg" in resv, al[:700])
            build(base + "/vi", "--no-fetch", "--voice-icon-name", "자비스 음성")
            recv2 = next(v for v in load_registry(tmp_home)["apps"].values() if v["url"].endswith("/vi"))
            resv2 = run([tp["aapt2"], "dump", "resources", recv2["apk"]], tmp_home, "resources")
            check("대화 창 아이콘 이름 바꾸기 (옵션은 기억)", re.search(r"string/voice_icon_name\n\s+\(\) \"?자비스 음성", resv2) is not None)
            check("--voice-icon 없는 앱에는 두 번째 아이콘 없음", "VoiceLauncher" not in mfm and "activity-alias" not in mfm)
            # --watch: 워치 앱(타일 + 음성 질문) — 같은 패키지·같은 서명, 폰은 Data Layer 로 질문을 받는다
            (tmp_home / "deps" / "maven").mkdir(parents=True, exist_ok=True)
            for f in list((home / "deps" / "maven").glob("*.aar")) + list((home / "deps" / "maven").glob("*.jar")):
                shutil.copyfile(f, tmp_home / "deps" / "maven" / f.name)  # 이미 받은 묶음 (SHA-256 은 다시 확인)
            build(base + "/watch", "--no-fetch", "--name", "자비스", "--mic", "--widget", str(wjs), "--watch")
            recw = next(v for v in load_registry(tmp_home)["apps"].values() if v["url"].endswith("/watch"))
            mfw = run([tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", recw["apk"]], tmp_home, "xmltree")
            resw = run([tp["aapt2"], "dump", "resources", recw["apk"]], tmp_home, "resources")
            with zipfile.ZipFile(recw["apk"]) as z:
                dexw = b"".join(z.read(n) for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
                cfgw = json.loads(z.read("assets/urlapp.json"))
            pws = mfw[mfw.rfind("E: service", 0, mfw.find("app.jarvis.urlapp.PhoneWatchService")):]
            pws = pws[:pws.find("E: service", 5) if pws.find("E: service", 5) > 0 else len(pws)]
            check("워치(폰 쪽): 워치 메시지 받는 곳(/urlapp/ 만)·질문 작업·Play 서비스 버전·능력 urlapp_phone·연결 코드",
                  "com.google.android.gms.wearable.MESSAGE_RECEIVED" in pws and '"/urlapp/"' in pws and '"wear"' in pws
                  and "app.jarvis.urlapp.WatchAskJob" in mfw and "com.google.android.gms.version" in mfw
                  and '"urlapp_phone"' in resw and "integer/google_play_services_version" in resw
                  and b"Lapp/jarvis/urlapp/GmsWatchLink;" in dexw and b"Lcom/google/android/gms/common/R$string;" in dexw
                  and b"Lcom/google/android/gms/wearable/Wearable;" in dexw and cfgw.get("watch") is True, pws[:600])
            wapk = Path(recw.get("watch_apk") or "")
            check("워치 앱 APK 가 만들어지고 기록됨", wapk.is_file() and wapk.name.endswith(f"-watch-v{recw['version_code']}.apk"), recw.get("watch_apk"))
            if wapk.is_file():
                bw = badging(tp["aapt2"], wapk, tmp_home)
                bw_raw = run([tp["aapt2"], "dump", "badging", wapk], tmp_home, "badging")
                mfww = run([tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", wapk], tmp_home, "xmltree")
                resww = run([tp["aapt2"], "dump", "resources", wapk], tmp_home, "resources")
                with zipfile.ZipFile(wapk) as z:
                    allw = b"".join(z.read(n) for n in z.namelist())
                    dexww = b"".join(z.read(n) for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
                idw = next(k for k, v in load_registry(tmp_home)["apps"].items() if v["url"].endswith("/watch"))
                check("워치 앱: 같은 패키지·같은 서명·Wear OS 3+(최소 30)·워치 전용·폰 필요(standalone=false)",
                      bw.get("package") == idw and cert_digest(java, tp["apksigner"], wapk, tmp_home) == recw["cert_sha256"]
                      and bw.get("min_sdk") == "30" and "android.hardware.type.watch" in bw_raw
                      and "com.google.android.wearable.standalone" in mfww, bw)
                tl = mfww[mfww.rfind("E: service", 0, mfww.find("app.jarvis.urlapp.WatchTile")):]
                tl = tl[:tl.find("E: service", 5) if tl.find("E: service", 5) > 0 else len(tl)]
                check("워치 앱: 타일(시스템만 묶음·미리보기)·폰 소식 받는 곳·대화 화면(아이콘)·능력 urlapp_watch",
                      "com.google.android.wearable.permission.BIND_TILE_PROVIDER" in tl
                      and "androidx.wear.tiles.action.BIND_TILE_PROVIDER" in tl and "androidx.wear.tiles.PREVIEW" in tl
                      and "com.google.android.gms.wearable.DATA_CHANGED" in mfww and "app.jarvis.urlapp.WatchListener" in mfww
                      and "launchable-activity: name='app.jarvis.urlapp.WatchAskActivity'" in bw_raw and '"urlapp_watch"' in resww
                      and b"Lapp/jarvis/urlapp/WatchTile;" in dexww and b"Landroidx/wear/tiles/TileService;" in dexww, tl[:600])
                host_w = urllib.parse.urlsplit(recw["url"]).hostname.encode()
                check("워치 앱에는 사이트 주소·웹 화면 코드가 없음 (폰을 거쳐서만)", host_w not in allw
                      and b"Landroid/webkit/WebView;" not in dexww and b"assets/urlapp.json" not in allw, "")
            check("--watch 없는 앱에는 Play 서비스·워치 연결 없음", "PhoneWatchService" not in mfm
                  and "com.google.android.gms" not in mfm)
            # --notify + --update-encrypt: 앱 알림, 암호화한 앱 안 업데이트
            check("--update-encrypt 는 --update-url 없이 거부",
                  raises(lambda: build(base + "/enc0", "--no-fetch", "--update-encrypt")))
            build(base + "/enc", "--no-fetch")
            ide = next(k for k, v in load_registry(tmp_home)["apps"].items() if v["url"].endswith("/enc"))
            uenc = "https://raw.githubusercontent.com/o/r/app-releases/" + ide + ".json"
            build(base + "/enc", "--no-fetch", "--update-url", uenc, "--update-encrypt", "--notify")
            rece = load_registry(tmp_home)["apps"][ide]
            mfe = run([tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", rece["apk"]], tmp_home, "xmltree")
            rese = run([tp["aapt2"], "dump", "resources", rece["apk"]], tmp_home, "resources")
            with zipfile.ZipFile(rece["apk"]) as z:
                cfge = json.loads(z.read("assets/urlapp.json"))
                names_e = z.namelist()
            check("알림: 권한·재부팅 뒤 이어 하기·확인 작업·도우미·아이콘·설정", mfe.count('="android.permission.POST_NOTIFICATIONS"') == 1
                  and "android.permission.RECEIVE_BOOT_COMPLETED" in mfe and "app.jarvis.urlapp.NotifyJob" in mfe
                  and "assets/urlapp-notify.js" in names_e and "drawable/urlapp_notify" in rese and cfge.get("notify") is True)
            keyfile = tmp_home / "update-keys" / (ide + ".key")
            check("암호화 업데이트: 열쇠 파일(나만 읽기)과 앱 안 열쇠가 같음", keyfile.is_file()
                  and (os.name == "nt" or (keyfile.stat().st_mode & 0o077) == 0)
                  and cfge.get("update_key") == keyfile.read_text().strip()
                  and len(base64.b64decode(cfge["update_key"])) == 32)
            rel_e = tmp_home / "rel_e"
            cmd_publish(argparse.Namespace(target=ide, out_dir=str(rel_e), notes="고침"))
            info_e = json.loads((rel_e / (ide + ".json")).read_text(encoding="utf-8"))
            sealed = rel_e / f"{ide}-v{rece['version_code']}.bin"
            opened = tmp_home / "opened.apk"
            seal_file(tmp_home, ide, sealed, opened, mode="open")
            check("암호화 업데이트: .bin 만 올리고(APK 없음) 풀면 원래 APK, 정보 파일에 방식·APK 검사값",
                  sealed.is_file() and not list(rel_e.glob("*.apk")) and info_e.get("encrypted") == "aes-256-gcm"
                  and info_e["apk"].endswith(".bin") and info_e["sha256"] == file_hashes(sealed)[1]
                  and info_e.get("apk_sha256") == file_hashes(Path(rece["apk"]))[1]
                  and opened.read_bytes() == Path(rece["apk"]).read_bytes()
                  and b"PK\x03\x04" not in sealed.read_bytes()[:64], info_e)
            # --live: 캐시 없이 늘 새로 받기 (기억되고, --no-live 로 끈다)
            build(base + "/live", "--no-fetch", "--live")
            build(base + "/live", "--no-fetch")
            idl = next(k for k, v in load_registry(tmp_home)["apps"].items() if v["url"].endswith("/live"))
            cfgl = json.loads(zipfile.ZipFile(load_registry(tmp_home)["apps"][idl]["apk"]).read("assets/urlapp.json"))
            build(base + "/live", "--no-fetch", "--no-live")
            cfgl0 = json.loads(zipfile.ZipFile(load_registry(tmp_home)["apps"][idl]["apk"]).read("assets/urlapp.json"))
            check("--live 는 기억되고 --no-live 로 끔 (기본 앱은 꺼짐)", cfgl.get("live") is True and cfgl0.get("live") is False
                  and json.loads(zipfile.ZipFile(rec5["apk"]).read("assets/urlapp.json")).get("live") is False, (cfgl, cfgl0))
            # 앱 안 업데이트 (--update-url, publish)
            check("업데이트 주소가 https://…/x.json 이 아니면 거부",
                  raises(lambda: build(base + "/upd", "--no-fetch", "--update-url", "http://example.com/a.json")))
            check("--update-url 없이 만든 앱은 publish 거부", raises(lambda: cmd_publish(
                argparse.Namespace(target=base + "/app/", out_dir=str(tmp_home / "rel0"), notes=""))))
            build(base + "/upd", "--no-fetch")
            idu = next(k for k, v in load_registry(tmp_home)["apps"].items() if v["url"].endswith("/upd"))
            uurl = "https://raw.githubusercontent.com/o/r/app-releases/" + idu + ".json"
            build(base + "/upd", "--no-fetch", "--update-url", uurl)
            recu = load_registry(tmp_home)["apps"][idu]
            mfu = run([tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", recu["apk"]], tmp_home, "xmltree")
            with zipfile.ZipFile(recu["apk"]) as z:
                cfgu = json.loads(z.read("assets/urlapp.json"))
                scu = run([tp["aapt2"], "dump", "xmltree", "--file", "res/xml/shortcuts.xml", recu["apk"]], tmp_home, "xmltree")
            check("업데이트: 설치 요청 권한·결과 receiver·'업데이트 확인' 바로가기·주소", "REQUEST_INSTALL_PACKAGES" in mfu
                  and "app.jarvis.urlapp.UpdateReceiver" in mfu and "action.UPDATE" in scu and cfgu.get("update_url") == uurl,
                  cfgu.get("update_url"))
            mf0 = run([tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)], tmp_home, "xmltree")
            check("업데이트 주소가 없는 앱에는 설치 권한 없음", "REQUEST_INSTALL_PACKAGES" not in mf0 and "UpdateReceiver" not in mf0)
            rel = tmp_home / "rel"
            (rel).mkdir()
            (rel / (idu + "-v1.apk")).write_bytes(b"old")
            cmd_publish(argparse.Namespace(target=base + "/upd", out_dir=str(rel), notes="새 기능"))
            info = json.loads((rel / (idu + ".json")).read_text(encoding="utf-8"))
            pub = rel / (idu + "-v" + str(recu["version_code"]) + ".apk")
            check("publish: 같은 폴더 주소·크기·SHA-256·바뀐 점, 옛 APK 정리", pub.is_file()
                  and info["apk"] == uurl.rsplit("/", 1)[0] + "/" + pub.name and info["sha256"] == file_hashes(pub)[1]
                  and info["size"] == pub.stat().st_size and info["version_code"] == recu["version_code"]
                  and info["notes"] == "새 기능" and info["package"] == idu and not (rel / (idu + "-v1.apk")).exists(), info)
            w9 = site / "w9.js"
            w9.write_text("// urlapp:rows 9\nasync function () { return null; }", encoding="utf-8")
            check("위젯 줄 수가 1~8 밖이면 거부", raises(lambda: build(base + "/widget9", "--no-fetch", "--widget", str(w9))))
            build(base + "/widget", "--no-fetch", "--widget", "none")
            rec6 = load_registry(tmp_home)["apps"][id5]
            mf6 = run([tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", rec6["apk"]], tmp_home, "xmltree")
            check("빌드 6: --widget none 이면 위젯이 빠짐", "WidgetProvider" not in mf6 and
                  "assets/urlapp-widget.js" not in zipfile.ZipFile(rec6["apk"]).namelist())
            pjs = site / "p.js"
            pjs.write_text("async function () { return {title: '__PARAM_T__', rows: []}; }", encoding="utf-8")
            check("위젯 값 자리를 안 채우면 거부", raises(lambda: build(base + "/param", "--no-fetch", "--widget", str(pjs))))
            build(base + "/param", "--no-fetch", "--widget", str(pjs), "--widget-param", 'T=a"b')
            id7, rec7 = next((k, v) for k, v in load_registry(tmp_home)["apps"].items() if v["url"].endswith("/param"))
            a7 = zipfile.ZipFile(rec7["apk"]).read("assets/urlapp-widget.js").decode("utf-8")
            check("위젯 값 자리 채움 (따옴표 이스케이프)", "title: 'a\\\"b'" in a7 and "__PARAM_" not in a7, a7[-120:])
            check("잘못된 --widget-param 거부", raises(lambda: build(base + "/param", "--no-fetch", "--widget-param", "bad")))
            cards_json = HERE.parent / "cards" / "smart-ledger.json"
            build(base + "/cards", "--no-fetch", "--cards", str(cards_json), "--cards-via", "intent")
            id8, rec8 = next((k, v) for k, v in load_registry(tmp_home)["apps"].items() if v["url"].endswith("/cards"))
            mf8 = run([tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", rec8["apk"]], tmp_home, "xmltree")
            sc8 = run([tp["aapt2"], "dump", "xmltree", "--file", "res/xml/shortcuts.xml", rec8["apk"]], tmp_home, "xmltree")
            z8 = zipfile.ZipFile(rec8["apk"])
            check("카드(intent, MacroDroid): 키를 확인하는 받는 곳, 알림 읽기 권한 없음",
                  "app.jarvis.urlapp.CardIntentReceiver" in mf8 and "app.jarvis.urlapp.action.CARD_NOTIFICATION" in mf8
                  and "CardListenerService" not in mf8 and "BIND_NOTIFICATION_LISTENER_SERVICE" not in mf8
                  and json.loads(z8.read("assets/urlapp-cards.json"))["via"] == "intent")
            build(base + "/cards-l", "--no-fetch", "--cards", str(cards_json))  # 기본 = listener
            id9, rec9 = next((k, v) for k, v in load_registry(tmp_home)["apps"].items() if v["url"].endswith("/cards-l"))
            mf9 = run([tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", rec9["apk"]], tmp_home, "xmltree")
            check("카드(기본 listener): 알림 듣기 서비스는 시스템만 묶을 수 있게", "app.jarvis.urlapp.CardListenerService" in mf9
                  and "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" in mf9
                  and "android.service.notification.NotificationListenerService" in mf9 and "CardIntentReceiver" not in mf9)
            lf = link_file_json(load_cards_config(cards_json))
            (site / "urlapp.json").write_text(json.dumps(lf, ensure_ascii=False), encoding="utf-8")
            (site / "lc").mkdir()
            (site / "lc" / "index.html").write_text('<script type="module" src="/lc/app.js"></script>', encoding="utf-8")
            (site / "lc" / "app.js").write_text('fetch("/api/monthly-details/add-single");fetch(`/api/monthly-details?month=${m}`);'
                                                'fetch("/api/monthly-details/delete-single");const f={date:1,type:1,category_large:1,'
                                                'category_medium:1,details:1,amount:1,earner_name:1,memo:1};', encoding="utf-8")
            check("연동 점검: 맞는 사이트는 문제 없음", cmd_link_check(parser.parse_args(["link-check", base + "/lc/"])) == 0)
            (site / "lc" / "app.js").write_text('fetch("/api/v2/expenses");', encoding="utf-8")
            check("연동 점검: API 가 바뀐 사이트는 알려 줌", cmd_link_check(parser.parse_args(["link-check", base + "/lc/"])) == 1)
            check("카드: 되돌리기·다시 보내기·설정 화면, 알림 권한", "CardUndoReceiver" in mf8 and "CardRetryJob" in mf8
                  and "android.permission.BIND_JOB_SERVICE" in mf8 and "CardSettingsActivity" in mf8
                  and "android.permission.POST_NOTIFICATIONS" in mf8)
            check("카드: 설정·바로가기·알림 아이콘", json.loads(z8.read("assets/urlapp-cards.json"))["owners"] == ["창수", "지현"]
                  and "app.jarvis.urlapp.action.CARDS" in sc8 and any("urlapp_card_notify" in n for n in z8.namelist()))
            mf_plain = run([tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", rec["apk"]], tmp_home, "xmltree")
            check("카드 기능 없는 앱에는 알림 듣기·알림 권한 없음", "CardListenerService" not in mf_plain
                  and "POST_NOTIFICATIONS" not in mf_plain)
            bad_cards = site / "bad-cards.json"
            bad_cards.write_text(json.dumps({"owners": []}), encoding="utf-8")
            check("카드: 설정이 이상하면 빌드 거부", raises(lambda: build(base + "/cards2", "--no-fetch", "--cards", str(bad_cards))))
            check("위젯 없는 앱에는 위젯 receiver 없음", "WidgetProvider" not in run(
                [tp["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", rec["apk"]], tmp_home, "xmltree"))
        finally:
            srv.shutdown()
            for k, v in old_env.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v
            shutil.rmtree(tmp_home, ignore_errors=True)
            shutil.rmtree(site, ignore_errors=True)

    ok, fail = sum(results), len(results) - sum(results)
    say(f"selftest: {ok}개 통과, {fail}개 실패")
    return 0 if fail == 0 else 1


# ---------------------------------------------------------------- 명령줄

def build_parser():
    ap = argparse.ArgumentParser(prog="url_app.py", description="URL 을 안드로이드 앱(APK)으로 만든다.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("doctor", help="준비 상태 확인")
    sub.add_parser("setup", help="빌드 도구 설치와 서명 키 만들기")
    b = sub.add_parser("build", help="APK 만들기")
    b.add_argument("url")
    b.add_argument("--name", help="앱 이름 (기본: 사이트의 이름·제목)")
    b.add_argument("--icon", help="auto(기본, 사이트 아이콘) | letter(글자 아이콘) | 이미지 파일 경로 | 이미지 주소")
    b.add_argument("--accent", help="강조색 #RRGGBB (진행 막대, 위젯의 수입 같은 강조 숫자). 기본: 아이콘 바탕색 → 테마색")
    b.add_argument("--cards", help="카드 결제 알림 자동 등록을 넣는다: 사이트별 설정(.json) 경로. 'none' 이면 뺀다 (cards/ 의 예시 참고)")
    b.add_argument("--cards-via", dest="cards_via", choices=["intent", "listener"],
                   help="카드 알림을 받는 방법: listener(기본, 앱이 직접 알림 읽기 — 직접 설치를 Play 프로텍트가 막을 수 있어 "
                        "설치할 때 앱 검사를 잠깐 끈다) | intent(MacroDroid 가 넘겨줌 — 설치가 막히지 않음)")
    b.add_argument("--widget", help="홈 화면 위젯을 넣는다: 위젯 스크립트(.js) 경로. 'none' 이면 뺀다 (widgets/ 의 예시 참고)")
    b.add_argument("--update-url", dest="update_url",
                   help="앱 안 업데이트: 새 버전 정보 파일 주소 (https://…/<앱 id>.json, url_app.py publish 로 만든다). "
                        "앱이 열릴 때 6시간에 한 번 확인하고 새 버전이면 묻는다. 'none' 이면 뺀다")
    b.add_argument("--widget-param", dest="widget_param", action="append", default=[],
                   help="위젯 스크립트의 __PARAM_이름__ 자리에 넣을 값: 이름=값 (여러 번). 값은 출력하지 않고 이 컴퓨터에만 기억")
    b.add_argument("--icon-color", dest="icon_color",
                   help="아이콘 바탕색 #RRGGBB (글자 아이콘·투명한 로고에 적용. 기본: 상태 표시줄 색, 흰색이면 자동 색)")
    b.add_argument("--color", help="상태 표시줄 색 #RRGGBB (기본: 사이트의 theme-color, 없으면 흰색)")
    b.add_argument("--bg", help="배경색 #RRGGBB (기본: 흰색)")
    b.add_argument("--orientation", choices=["auto", "portrait", "landscape"])
    b.add_argument("--external", choices=["browser", "app"],
                   help="다른 사이트 링크를 누르면: browser(기본, 브라우저로) | app(앱 안에서)")
    b.add_argument("--allow-host", dest="allow_host", action="append", default=[],
                   help="앱 안에서 열 다른 사이트 (여러 번 줄 수 있음)")
    b.add_argument("--desktop", action=argparse.BooleanOptionalAction, default=None, help="PC 화면으로 보기")
    b.add_argument("--fullscreen", action=argparse.BooleanOptionalAction, default=None, help="상태 표시줄 숨기기")
    b.add_argument("--keep-awake", dest="keep_awake", action=argparse.BooleanOptionalAction, default=None,
                   help="앱을 보는 동안 화면 꺼짐 방지")
    b.add_argument("--widget-style", dest="widget_style", choices=["auto", "dark"],
                   help="위젯 색: auto(기본, 기기의 밝은/어두운 모드를 따름) | dark(늘 어둡게, 앱 바탕색이 어두우면 그 색)")
    b.add_argument("--notify", action=argparse.BooleanOptionalAction, default=None,
                   help="앱 알림: 사이트의 /urlapp-notify.json 을 15분마다 읽어 폰 알림, 웹 알림(new Notification)도 폰 알림으로")
    b.add_argument("--update-encrypt", dest="update_encrypt", action=argparse.BooleanOptionalAction, default=None,
                   help="업데이트 파일을 암호화해서 올린다 (주소를 숨겨야 하는 앱. 열쇠는 이 컴퓨터와 앱 안에만)")
    b.add_argument("--mic", action=argparse.BooleanOptionalAction, default=None,
                   help="마이크·음성 대화: 마이크 권한, 웹 음성 기능을 폰의 음성 인식·읽어 주기로 대신, 위젯·바로가기의 '마이크 모드'")
    b.add_argument("--watch", action=argparse.BooleanOptionalAction, default=None,
                   help="워치(Wear OS) 앱도 만든다: 타일(위젯 요약) + 음성 질문(폰이 사이트의 urlappAsk 로 묻고 답을 워치로). "
                        "폰 앱에 Google Play 서비스 Wearable 이 들어간다. 워치 APK 는 직접 설치(adb)")
    b.add_argument("--voice-icon", dest="voice_icon", action=argparse.BooleanOptionalAction, default=None,
                   help="대화 창을 바로 여는 두 번째 앱 아이콘 (--mic 필요). 빅스비 빠른 명령어·'OO 열어줘'로 부를 때 쓴다")
    b.add_argument("--voice-icon-name", dest="voice_icon_name",
                   help="그 아이콘의 이름 (기본: '<앱 이름> 대화', 20자까지)")
    b.add_argument("--hotword", action=argparse.BooleanOptionalAction, default=None,
                   help="'하이 자비스'처럼 부르면 대화 창이 뜨는 호출어 (--mic 필요). 폰 안에서만 듣는 오프라인 모델(Vosk 한국어, "
                        "약 87MB)은 폰이 설정 화면에서 받는다. 늘 '듣는 중' 알림이 보인다")
    b.add_argument("--hotword-phrase", dest="hotword_phrase", action="append", default=[],
                   help="호출어 문장 (여러 번 또는 쉼표로, 한글 낱말 2~3개. 기본: 앱 이름으로 '하이·헤이·안녕 <이름>')")
    b.add_argument("--widget-sizes", dest="widget_sizes",
                   help="위젯을 크기별로 따로 (예: 4x2,3x2,2x2,4x1 — 최대 4개, 첫 크기가 기본 위젯. 세로 1칸은 가로 한 줄 모양) | none")
    b.add_argument("--live", action=argparse.BooleanOptionalAction, default=None,
                   help="사이트를 자주 고치는 앱: 저장(캐시) 없이 늘 서버에서 새로 받고, 30분 넘게 나가 있다 돌아오면 새로 고침")
    b.add_argument("--dark", choices=["auto", "darken"],
                   help="auto(기본, 사이트가 정함) | darken(기기가 다크 모드면 사이트를 자동으로 어둡게)")
    b.add_argument("--id", help="앱 id (기본: 주소로 자동). 같은 사이트로 앱을 두 개 만들 때만")
    b.add_argument("--out", help="APK 를 둘 폴더 (기본: ~/.jarvis-url-app/apk)")
    b.add_argument("--no-fetch", dest="no_fetch", action="store_true", help="사이트를 읽지 않고 만든다")
    sub.add_parser("list", help="만든 앱 목록")
    lf = sub.add_parser("link-file", help="사이트에 둘 연동 약속 파일(/urlapp.json) 내용 출력")
    lf.add_argument("--cards", required=True, help="카드 설정(.json)")
    lc = sub.add_parser("link-check", help="재배포 뒤 연동 점검 (사이트의 /urlapp.json 과 코드만 본다)")
    lc.add_argument("url")
    lc.add_argument("--cards", help="앱에 넣은 카드 설정(.json) — 사이트에 연동 파일이 없을 때 이것으로 점검")
    pb = sub.add_parser("publish", help="앱 안 업데이트용으로 최신 APK 와 정보 파일(<앱 id>.json)을 폴더에 만든다")
    pb.add_argument("target", help="앱의 URL 또는 id")
    pb.add_argument("out_dir", help="올릴 폴더 (예: app-releases 브랜치를 꺼낸 곳)")
    pb.add_argument("--notes", default="", help="바뀐 점 (앱의 업데이트 창에 보인다, 300자까지)")
    sub.add_parser("selftest", help="자체 시험")
    return ap


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8")
            sys.stderr.reconfigure(encoding="utf-8")
        except (OSError, ValueError):
            pass
    args = build_parser().parse_args(argv)
    handler = {"doctor": cmd_doctor, "setup": cmd_setup, "build": cmd_build, "list": cmd_list,
               "link-file": cmd_link_file, "link-check": cmd_link_check, "publish": cmd_publish,
               "selftest": cmd_selftest}[args.cmd]
    try:
        return handler(args)
    except Fail as e:
        print(f"실패: {e}", file=sys.stderr)
        return 1
    except urllib.error.URLError as e:
        print(f"실패: 네트워크 오류 — {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
