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
WIDGET_RUNNER = HERE / "shell" / "widget-runner.js"  # 위젯 스크립트를 감싸 페이지에서 돌리는 실행기
WIDGET_SLOT = "(/*__WIDGET_FN__*/ null)"
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

def manifest_xml(app_id, cleartext, orientation, widget=False):
    orient = {"portrait": "portrait", "landscape": "sensorLandscape"}.get(orientation)
    orient_attr = f'\n            android:screenOrientation="{orient}"' if orient else ""
    # 안드로이드 16 은 큰 화면(태블릿)에서 방향 고정을 무시한다. 고정을 원하면 임시 예외 속성을 넣는다.
    prop = ('\n            <property android:name="android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY"'
            ' android:value="true" />') if orient else ""
    receiver = f"""
        <receiver
            android:name="app.jarvis.urlapp.WidgetProvider"
            android:exported="false"
            android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/urlapp_widget_info" />
        </receiver>""" if widget else ""
    return f"""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{app_id}">
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher"
        android:theme="@style/AppTheme"
        android:allowBackup="false"
        android:supportsRtl="true"
        android:usesCleartextTraffic="{'true' if cleartext else 'false'}"
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
            </intent-filter>
            <meta-data android:name="android.app.shortcuts" android:resource="@xml/shortcuts" />{prop}
        </activity>{receiver}
    </application>
</manifest>
"""


def write_resources(res, app_id, name, theme, bg, icon_bg):
    def w(rel, text):
        p = res / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")

    w("values/strings.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">{android_string(name)}</string>
    <string name="sc_home">처음 화면</string>
    <string name="sc_browser">브라우저에서 열기</string>
</resources>
""")
    w("values-en/strings.xml", """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="sc_home">Home</string>
    <string name="sc_browser">Open in browser</string>
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
    w("values/styles.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="AppTheme" parent="@android:style/Theme.DeviceDefault.Light.NoActionBar">
{items}    </style>
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
    w("xml/shortcuts.xml", '<?xml version="1.0" encoding="utf-8"?>\n'
      '<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">\n'
      + shortcut.format(sid="home", label="sc_home", action="HOME", pkg=app_id, cls=SHELL_CLASS)
      + shortcut.format(sid="browser", label="sc_browser", action="BROWSER", pkg=app_id, cls=SHELL_CLASS)
      + "</shortcuts>\n")


# ---------------------------------------------------------------- 빌드

def mix(hex_color, other, t):
    a = [int(hex_color[i:i + 2], 16) for i in (1, 3, 5)]
    b = [int(other[i:i + 2], 16) for i in (1, 3, 5)]
    return "#%02X%02X%02X" % tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def write_widget_resources(res, accent):
    """홈 화면 위젯의 모양 (RemoteViews 로 그릴 수 있는 LinearLayout·TextView·ImageView 만 쓴다)."""
    def w(rel, text):
        f = res / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(text, encoding="utf-8")

    def colors(vals):
        return ('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                + "".join(f'    <color name="{k}">{v}</color>\n' for k, v in vals.items()) + "</resources>\n")

    w("values/widget_colors.xml", colors({
        "widget_bg": "#FFFFFF", "widget_title": "#0F172A", "widget_label": "#64748B", "widget_value": "#0F172A",
        "widget_note": "#94A3B8", "widget_accent": accent, "widget_negative": "#E11D48"}))
    w("values-night/widget_colors.xml", colors({
        "widget_bg": "#1E293B", "widget_title": "#F1F5F9", "widget_label": "#94A3B8", "widget_value": "#F8FAFC",
        "widget_note": "#64748B", "widget_accent": mix(accent, "#FFFFFF", 0.35), "widget_negative": "#FB7185"}))
    w("values/widget_strings.xml", """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="widget_desc">앱의 요약을 홈 화면에 보여 줍니다</string>
    <string name="widget_hint">앱을 한 번 열면 여기에 요약이 보여요</string>
</resources>
""")
    w("values-en/widget_strings.xml", """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="widget_desc">Shows the app\\'s summary on the home screen</string>
    <string name="widget_hint">Open the app once to fill this widget</string>
</resources>
""")
    w("drawable/urlapp_widget_bg.xml", """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/widget_bg" />
    <corners android:radius="22dp" />
</shape>
""")
    rows = "".join(f"""
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
            android:textSize="13sp" />
        <TextView
            android:id="@+id/w_value{i}"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:maxLines="1"
            android:textColor="@color/widget_value"
            android:textSize="16sp"
            android:textStyle="bold" />
    </LinearLayout>""" for i in range(1, 5))
    w("layout/urlapp_widget.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/w_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/urlapp_widget_bg"
    android:orientation="vertical"
    android:paddingStart="16dp"
    android:paddingTop="12dp"
    android:paddingEnd="16dp"
    android:paddingBottom="10dp">
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginBottom="4dp"
        android:gravity="center_vertical"
        android:orientation="horizontal">
        <ImageView
            android:layout_width="22dp"
            android:layout_height="22dp"
            android:importantForAccessibility="no"
            android:src="@mipmap/ic_launcher" />
        <TextView
            android:id="@+id/w_title"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:layout_weight="1"
            android:ellipsize="end"
            android:maxLines="1"
            android:text="@string/app_name"
            android:textColor="@color/widget_title"
            android:textSize="14sp"
            android:textStyle="bold" />
    </LinearLayout>{rows}
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
""")
    w("xml/urlapp_widget_info.xml", """<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/widget_desc"
    android:initialLayout="@layout/urlapp_widget"
    android:minWidth="180dp"
    android:minHeight="110dp"
    android:minResizeWidth="140dp"
    android:minResizeHeight="90dp"
    android:previewLayout="@layout/urlapp_widget"
    android:resizeMode="horizontal|vertical"
    android:targetCellWidth="3"
    android:targetCellHeight="2"
    android:updatePeriodMillis="0"
    android:widgetCategory="home_screen" />
""")


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
    if runner.count(WIDGET_SLOT) != 1:
        raise Fail("widget-runner.js 의 자리 표시가 없습니다.")
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


EXPLICIT_KEYS = ("name", "icon", "icon_color", "accent", "widget", "widget_param", "color", "bg", "orientation", "external", "allow_host", "desktop",
                 "fullscreen", "keep_awake", "dark", "id")
DEFAULTS = {"orientation": "auto", "external": "browser", "allow_host": [], "desktop": False,
            "fullscreen": False, "keep_awake": False, "dark": "auto"}


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
        if opt["widget"] and opt["widget"] != "none":
            ws = Path(opt["widget"]).expanduser()
            if not ws.is_file():
                raise Fail(f"위젯 스크립트 파일이 없습니다: {ws}")
            keep_w = home / "widgets" / (app_id + ".js")
            if ws.resolve() != keep_w.resolve():
                keep_w.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ws, keep_w)
            explicit["widget"] = str(keep_w)
            widget_js = widget_asset(keep_w.read_text(encoding="utf-8"), opt["widget_param"] or [])

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
        write_resources(res, app_id, name, theme, bg, icon_info["icon_bg"])
        widget_accent = accent or icon_color or (theme if not is_near_white(theme) else "#1A73E8")
        if widget_js:
            write_widget_resources(res, widget_accent)

        # 설정과 매니페스트
        version_code = int(prev.get("version_code", 0)) + 1
        version_name = f"1.{version_code}"
        cfg = {"url": url, "hosts": own_hosts(url, opt["allow_host"]), "external": opt["external"],
               "theme_color": theme, "background_color": bg,
               "theme_color_dark": None if opt["color"] else site["theme_dark"],
               "desktop": bool(opt["desktop"]),
               "fullscreen": bool(opt["fullscreen"]), "keep_awake": bool(opt["keep_awake"]), "dark": opt["dark"],
               "accent": accent}
        assets = workdir / "assets"
        assets.mkdir()
        (assets / "urlapp.json").write_text(json.dumps(cfg, ensure_ascii=False, indent=1), encoding="utf-8")
        shutil.copyfile(DOWNLOAD_JS, assets / "urlapp-download.js")
        if widget_js:
            (assets / "urlapp-widget.js").write_text(widget_js, encoding="utf-8")
        cleartext = urllib.parse.urlsplit(url).scheme == "http"
        (workdir / "AndroidManifest.xml").write_text(manifest_xml(app_id, cleartext, opt["orientation"], bool(widget_js)),
                                                     encoding="utf-8")

        say("APK 만드는 중")
        run([p["aapt2"], "compile", "--dir", res, "-o", workdir / "res.zip"], workdir, "리소스 컴파일(aapt2 compile)")
        unsigned = workdir / "unsigned.apk"
        run([p["aapt2"], "link", "-o", unsigned, "-I", p["android_jar"], "--manifest", workdir / "AndroidManifest.xml",
             "-A", assets, "--min-sdk-version", str(MIN_SDK), "--target-sdk-version", str(TARGET_SDK),
             "--version-code", str(version_code), "--version-name", version_name, workdir / "res.zip"],
            workdir, "앱 묶기(aapt2 link)")
        dex = shell_dex(home, tc, javac, java, workdir)
        with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as z:
            z.write(dex, "classes.dex")

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

        reg["apps"][app_id] = {
            "url": url, "name": name, "version_code": version_code, "version_name": version_name,
            "theme_color": theme, "background_color": bg, "icon_from": icon_from, "explicit": explicit,
            "apk": str(apk), "apk_sha256": file_hashes(apk)[1], "cert_sha256": digest, "widget": bool(widget_js),
            "built_at": time.strftime("%Y-%m-%d %H:%M:%S")}
        save_registry(home, reg)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)

    say("")
    say(f"완성: {apk} ({apk.stat().st_size / 1024:.0f}KB)")
    say(f"  이름 {name} | id {app_id} | 버전 {version_name} (코드 {version_code}) | 안드로이드 7.0 이상")
    say(f"  색 {theme} / 배경 {bg} | 아이콘: {icon_from}")
    if widget_js:
        say("  홈 화면 위젯: 있음 (설치 후 홈 화면을 길게 눌러 '위젯'에서 이 앱을 고른다. 앱을 열 때마다 새로 읽는다)")
    say(f"  다른 사이트 링크: {'브라우저로' if opt['external'] == 'browser' else '앱 안에서'} | 앱 안에서 여는 사이트: "
        + ", ".join(cfg["hosts"]))
    say("설치: 이 APK 를 태블릿·폰으로 옮겨 파일을 누르고 '출처를 알 수 없는 앱 설치'를 그 앱(파일 관리자 등)에만 허용한다."
        + (" 같은 키로 서명했으므로 기존 앱 위에 업데이트된다 (로그인 유지)." if version_code > 1 else ""))
    return 0


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
            check("빌드 1: 인터넷 권한만", b.get("permissions") == ["android.permission.INTERNET"], str(b))
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
    b.add_argument("--widget", help="홈 화면 위젯을 넣는다: 위젯 스크립트(.js) 경로. 'none' 이면 뺀다 (widgets/ 의 예시 참고)")
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
    b.add_argument("--dark", choices=["auto", "darken"],
                   help="auto(기본, 사이트가 정함) | darken(기기가 다크 모드면 사이트를 자동으로 어둡게)")
    b.add_argument("--id", help="앱 id (기본: 주소로 자동). 같은 사이트로 앱을 두 개 만들 때만")
    b.add_argument("--out", help="APK 를 둘 폴더 (기본: ~/.jarvis-url-app/apk)")
    b.add_argument("--no-fetch", dest="no_fetch", action="store_true", help="사이트를 읽지 않고 만든다")
    sub.add_parser("list", help="만든 앱 목록")
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
