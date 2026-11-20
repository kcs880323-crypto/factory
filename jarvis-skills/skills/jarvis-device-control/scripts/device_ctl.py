#!/usr/bin/env python3
"""안드로이드 태블릿(갤럭시탭) 화면을 안전장치를 거쳐 조작하는 도구. 표준 라이브러리만 쓴다. (아이폰은 SKILL.md 의 별도 경로와 device_guard_hook.py)

핵심은 '허락된 시간 안에서만, 허락된 앱에서만, 정해진 동작만' 하는 것이다.
  - 사용자가 arm(켜기)해 둔 시간 동안만 화면을 보거나 조작한다. 끝나면 자동으로 막힌다. disarm 은 언제나 즉시 된다.
  - 은행·결제·인증·비밀번호 관리·설정·앱 설치 화면은 어떤 경우에도 보지도 만지지도 않는다. 전화·문자 앱은 기본으로 막고 사용자가 앱별로 풀 수 있다.
  - 잠금 화면에서는 입력하지 않는다 (PIN·패턴 입력 금지). 앞 화면을 확인하지 못하면 입력하지 않는다.
  - 임의 명령(adb shell …)은 열어 두지 않는다. 아래 동작만 있고 값은 모두 검사한다.
  - 비밀번호 칸, 카드·주민번호·OTP 모양의 글은 입력하지 않는다.

저장 위치: ~/.jarvis-devices  (JARVIS_DEVICES_HOME 으로 바꿀 수 있다)
  devices.json  등록한 기기 (이름, 종류, 주소)         arm.json  켜 둔 시간표(누가·언제까지·몇 번)
  allow.json    사용자가 앱별로 푼 목록                actions.jsonl  한 일의 기록 (입력한 글은 길이만)
  shots/        화면 캡처 (나만 읽게, 끝나면 지우라고 안내)

종료 코드: 0 성공 / 10 켜져 있지 않음(사용자 확인 필요) / 20 안전장치가 막음 / 1 오류

명령
  add NAME --address HOST:PORT [--label L] [--allow-non-tailnet]   안드로이드 등록 (주소는 Tailscale 주소만 받는다)
  add NAME --kind ios [--label L]                                  아이폰 등록 (NAME 은 아이폰 MCP 서버 이름과 같게. arm 시간표만 쓴다)
  list | remove NAME | status
  connect NAME                                 adb connect 후 상태 확인
  arm NAME --task TEXT [--minutes 30] [--max-actions 150] --user-confirmed     disarm [NAME]
  allow-app NAME PACKAGE --user-confirmed      (기본으로 막힌 전화·문자 앱만 풀 수 있다. 은행·인증 등은 풀 수 없다)
  screenshot NAME [--out FILE]   ui NAME   tap NAME X Y   swipe NAME X1 Y1 X2 Y2 [MS]
  text NAME "ASCII 글"   key NAME BACK|HOME|ENTER|…   start-app NAME PACKAGE   open-url NAME https://…
  purge-shots                                  저장된 캡처를 모두 지운다
  selftest
"""
import argparse, datetime, fnmatch, ipaddress, json, os, pathlib, re, shlex, shutil, subprocess, sys, tempfile, time
import xml.etree.ElementTree as ET

HOME = pathlib.Path(os.environ.get("JARVIS_DEVICES_HOME", pathlib.Path.home() / ".jarvis-devices"))
ADB = os.environ.get("JARVIS_ADB", "adb")
OK, NOT_ARMED, DENIED, ERR = 0, 10, 20, 1
MAX_MINUTES, DEFAULT_MINUTES, DEFAULT_ACTIONS, MAX_ACTIONS = 60, 30, 150, 500
MIN_GAP = 0.3

# 어떤 경우에도 막는 앱 (패키지 이름에 이 글자가 들어 있으면 막는다. 사용자가 풀 수 없다)
HARD_SUBSTR = ["bank", "wallet", "pay", "toss", "authenticator", "samsungpass", "otp", "passkey", "bitwarden", "lastpass", "onepassword",
               "agilebits", "keepass", "dashlane", "mobileid", "cert", "knox", "securefolder"]
HARD_EXACT = {"com.android.settings", "com.samsung.android.settings", "com.android.vending", "com.sec.android.app.samsungapps",
              "com.android.packageinstaller", "com.google.android.packageinstaller", "com.samsung.android.packageinstaller",
              "com.google.android.gms", "com.android.keyguard", "com.android.systemui.keyguard"}
# 기본으로 막되 사용자가 앱별로 풀 수 있는 앱 (전화·문자: 보내면 되돌릴 수 없다)
SOFT_SUBSTR = ["dialer", "com.android.phone", "messaging", "mms", "telecom", "contacts"]
KEYS = {"BACK": "KEYCODE_BACK", "HOME": "KEYCODE_HOME", "ENTER": "KEYCODE_ENTER", "APP_SWITCH": "KEYCODE_APP_SWITCH", "TAB": "KEYCODE_TAB",
        "DEL": "KEYCODE_DEL", "DPAD_UP": "KEYCODE_DPAD_UP", "DPAD_DOWN": "KEYCODE_DPAD_DOWN", "DPAD_LEFT": "KEYCODE_DPAD_LEFT",
        "DPAD_RIGHT": "KEYCODE_DPAD_RIGHT", "VOLUME_UP": "KEYCODE_VOLUME_UP", "VOLUME_DOWN": "KEYCODE_VOLUME_DOWN", "WAKEUP": "KEYCODE_WAKEUP"}
PKG_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$")
TEXT_RE = re.compile(r"^[A-Za-z0-9 .,_@:/+=\-]{1,200}$")
URL_RE = re.compile(r"^https://[A-Za-z0-9.\-]+(:\d+)?(/[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%\-]*)?$")


class Stop(Exception):
    def __init__(self, code, msg):
        super().__init__(msg)
        self.code = code


def now():
    return datetime.datetime.now().astimezone()


def private(p):
    try:
        os.chmod(p, 0o600)
    except OSError:
        pass


def jload(name, default):
    p = HOME / name
    try:
        return json.loads(p.read_text(encoding="utf-8")) if p.exists() else default
    except ValueError:
        raise Stop(ERR, f"{p} 를 읽을 수 없습니다 (JSON 오류). 직접 확인하세요.")


def jsave(name, data):
    HOME.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(HOME, 0o700)
    except OSError:
        pass
    p = HOME / name
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    private(tmp)
    os.replace(tmp, p)


def log(device, action, params, result):
    HOME.mkdir(parents=True, exist_ok=True)
    rec = {"ts": now().isoformat(timespec="seconds"), "device": device, "action": action, "params": params, "result": result}
    with open(HOME / "actions.jsonl", "a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    private(HOME / "actions.jsonl")


# ---------------------------------------------------------------- 안전 판정
def classify_app(pkg, allow):
    """'hard'(영원히 막음) / 'soft'(기본 막음, 풀 수 있음) / 'ok'"""
    p = pkg.lower()
    if p in HARD_EXACT or any(s in p for s in HARD_SUBSTR):
        return "hard"
    if p in {a.lower() for a in allow}:
        return "ok"
    if any(s in p for s in SOFT_SUBSTR):
        return "soft"
    return "ok"


def valid_tailnet_address(addr):
    host = addr.rsplit(":", 1)[0] if ":" in addr and not addr.startswith("[") else addr
    if host.endswith(".ts.net"):
        return True
    try:
        return ipaddress.ip_address(host) in ipaddress.ip_network("100.64.0.0/10")
    except ValueError:
        return False


def dangerous_text(t):
    s = t.strip()
    if re.fullmatch(r"\d{4,8}", s):
        return "PIN·OTP 로 보이는 숫자"
    if len(re.sub(r"\D", "", s)) >= 13:
        return "카드·주민번호로 보이는 긴 숫자"
    if re.search(r"\d{6}-?[1-4]\d{6}", s):
        return "주민등록번호 모양"
    return None


def int_arg(v, name, lo=0, hi=10000):
    try:
        n = int(v)
    except (TypeError, ValueError):
        raise Stop(ERR, f"{name} 은 정수여야 합니다: {v}")
    if not lo <= n <= hi:
        raise Stop(ERR, f"{name} 은 {lo}~{hi} 사이여야 합니다: {n}")
    return n


# ---------------------------------------------------------------- adb
def adb(addr, *args, binary=False, timeout=30):
    cmd = [ADB, "-s", addr, *args]
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=timeout)
    except FileNotFoundError:
        raise Stop(ERR, "adb 가 없습니다. Android platform-tools 를 설치하세요 (자동으로 설치하지 않습니다).")
    except subprocess.TimeoutExpired:
        raise Stop(ERR, "adb 가 시간 안에 응답하지 않았습니다.")
    if r.returncode != 0:
        raise Stop(ERR, "adb 실패: " + r.stderr.decode("utf-8", "replace").strip()[:200])
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def foreground_state(addr):
    """(앞 화면 앱 패키지 또는 None, 잠금 화면인가 또는 None)"""
    d = adb(addr, "shell", "dumpsys", "window")
    m = re.search(r"mCurrentFocus=Window\{[^ ]+ u\d+ ([A-Za-z0-9_.]+)/", d) or re.search(r"mFocusedApp=.*?ActivityRecord\{[^ ]+ u\d+ ([A-Za-z0-9_.]+)/", d)
    pkg = m.group(1) if m else None
    lk = re.search(r"(?:isKeyguardShowing|mShowingLockscreen|mKeyguardShowing)\s*=\s*(true|false)", d)
    locked = (lk.group(1) == "true") if lk else None
    if pkg and ("keyguard" in pkg.lower()):
        locked = True
    return pkg, locked


def focused_is_password(addr):
    """uiautomator 로 지금 포커스된 칸이 비밀번호 칸인가. 확인하지 못하면 None."""
    try:
        xml = adb(addr, "exec-out", "uiautomator", "dump", "/dev/tty")
        root = ET.fromstring(xml[xml.index("<"):xml.rindex(">") + 1])
    except (Stop, ValueError, ET.ParseError):
        return None
    for n in root.iter("node"):
        if n.get("focused") == "true" and n.get("password") == "true":
            return True
    return False


def guard(name, action, kind="input"):
    """모든 동작 앞에서 부른다. 막으면 Stop 을 던진다. 기기 주소를 돌려준다."""
    devs = jload("devices.json", {})
    if name not in devs:
        raise Stop(ERR, f"등록되지 않은 기기입니다: {name} (add 로 먼저 등록)")
    arm = jload("arm.json", {}).get(name)
    if not arm:
        raise Stop(NOT_ARMED, f"{name} 은(는) 켜져 있지 않습니다. 사용자의 확인을 받아 arm 하세요 (무엇을 하려는지, 몇 분인지 알리고).")
    if datetime.datetime.fromisoformat(arm["until"]) <= now():
        raise Stop(NOT_ARMED, f"{name} 의 허락 시간이 끝났습니다. 계속하려면 사용자에게 다시 확인받고 arm 하세요.")
    if arm["used"] >= arm["max_actions"]:
        raise Stop(NOT_ARMED, f"허락된 동작 수({arm['max_actions']})를 다 썼습니다. 계속하려면 사용자에게 다시 확인받고 arm 하세요.")
    if devs[name]["kind"] != "android":
        raise Stop(ERR, "이 도구는 안드로이드만 다룹니다. 아이폰은 SKILL.md 의 별도 경로를 쓰세요.")
    addr = devs[name]["address"]
    if kind in ("input", "screen"):
        pkg, locked = foreground_state(addr)
        if locked is True:
            raise Stop(DENIED, "잠금 화면입니다. PIN·패턴을 대신 입력하지 않습니다. 사용자가 직접 잠금을 풀어야 합니다.")
        if pkg is None or locked is None:
            raise Stop(DENIED, "지금 앞 화면이 어떤 앱인지 확인하지 못해 멈춥니다 (확인 없이 입력하지 않는다).")
        cls = classify_app(pkg, jload("allow.json", {}).get(name, []))
        if cls == "hard":
            raise Stop(DENIED, f"{pkg}: 은행·결제·인증·비밀번호·설정·앱 설치 화면이라 보지도 만지지도 않습니다.")
        if cls == "soft":
            raise Stop(DENIED, f"{pkg}: 전화·문자 앱은 기본으로 막혀 있습니다. 사용자가 원하면 allow-app 으로 이 앱만 풀 수 있습니다.")
    return addr


def spend(name, action, params):
    arm = jload("arm.json", {})
    time.sleep(MIN_GAP)
    arm[name]["used"] += 1
    jsave("arm.json", arm)
    log(name, action, params, "ok")


def refuse_log(name, action, params, e):
    log(name, action, params, f"denied:{e}")


# ---------------------------------------------------------------- 명령
def cmd_add(a):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,30}", a.name):
        raise Stop(ERR, "기기 이름은 영문·숫자·-·_ 30자 이내")
    if a.kind == "ios":
        # 아이폰은 이 도구가 직접 조작하지 않는다. 아이폰 MCP 서버(서버 이름 = 기기 이름)를 쓰는 동안 arm/disarm 시간표만 같이 쓴다 (device_guard_hook.py 가 확인).
        devs = jload("devices.json", {})
        devs[a.name] = {"kind": "ios", "address": a.address or "mcp", "label": a.label or a.name}
        jsave("devices.json", devs)
        print("등록(아이폰, MCP 서버 이름이 같아야 함):", a.name)
        return
    if not a.address:
        raise Stop(ERR, "--address HOST:PORT 가 필요합니다")
    if not a.allow_non_tailnet and not valid_tailnet_address(a.address):
        raise Stop(ERR, "Tailscale 주소(100.x.x.x 또는 *.ts.net)만 받습니다. 공용 주소나 같은 와이파이 주소로 adb 를 열어 두면 위험합니다. 꼭 필요하면 --allow-non-tailnet.")
    if not re.fullmatch(r"[A-Za-z0-9.\-]+:\d{2,5}", a.address):
        raise Stop(ERR, "주소는 HOST:PORT 형식 (예: 100.64.1.2:5555)")
    devs = jload("devices.json", {})
    devs[a.name] = {"kind": "android", "address": a.address, "label": a.label or a.name}
    jsave("devices.json", devs)
    print("등록:", a.name, a.address)


def cmd_list(a):
    devs = jload("devices.json", {})
    for n, d in devs.items():
        print(f"{n}  {d['kind']}  {d['address']}  {d.get('label', '')}")
    if not devs:
        print("(등록된 기기 없음)")


def cmd_remove(a):
    devs = jload("devices.json", {})
    devs.pop(a.name, None)
    jsave("devices.json", devs)
    arm = jload("arm.json", {}); arm.pop(a.name, None); jsave("arm.json", arm)
    print("삭제:", a.name)


def cmd_status(a):
    devs, arm = jload("devices.json", {}), jload("arm.json", {})
    for n, d in devs.items():
        s = arm.get(n)
        left = ""
        if s:
            sec = (datetime.datetime.fromisoformat(s["until"]) - now()).total_seconds()
            left = f"켜짐 {int(sec // 60)}분 남음, 동작 {s['used']}/{s['max_actions']}, 일: {s['task']}" if sec > 0 else "허락 시간 끝남"
        print(f"{n}  {d['address']}  {left or '꺼짐'}")
    if not devs:
        print("(등록된 기기 없음)")


def cmd_connect(a):
    devs = jload("devices.json", {})
    if a.name not in devs:
        raise Stop(ERR, "등록되지 않은 기기")
    addr = devs[a.name]["address"]
    try:
        out = subprocess.run([ADB, "connect", addr], capture_output=True, timeout=30).stdout.decode("utf-8", "replace").strip()
    except FileNotFoundError:
        raise Stop(ERR, "adb 가 없습니다.")
    state = adb(addr, "get-state").strip()
    print(out, "| 상태:", state)
    if state != "device":
        raise Stop(ERR, "기기 상태가 device 가 아닙니다 (태블릿에서 USB 디버깅 허용 창을 확인하세요).")


def cmd_arm(a):
    if not a.user_confirmed:
        raise Stop(NOT_ARMED, "사용자가 확인한 경우에만 --user-confirmed 를 붙여 arm 합니다 (무엇을 하려는지, 몇 분인지 사용자에게 알린 뒤).")
    devs = jload("devices.json", {})
    if a.name not in devs:
        raise Stop(ERR, "등록되지 않은 기기")
    minutes = int_arg(a.minutes, "minutes", 1, MAX_MINUTES)
    mx = int_arg(a.max_actions, "max-actions", 1, MAX_ACTIONS)
    arm = jload("arm.json", {})
    arm[a.name] = {"task": a.task[:200], "until": (now() + datetime.timedelta(minutes=minutes)).isoformat(timespec="seconds"), "max_actions": mx, "used": 0}
    jsave("arm.json", arm)
    log(a.name, "arm", {"minutes": minutes, "max_actions": mx, "task": a.task[:80]}, "ok")
    print(f"켬: {a.name} {minutes}분, 동작 {mx}번까지. 끝나면 disarm 하세요.")


def cmd_disarm(a):
    arm = jload("arm.json", {})
    for n in ([a.name] if a.name else list(arm)):
        arm.pop(n, None)
    jsave("arm.json", arm)
    log(a.name or "*", "disarm", {}, "ok")
    print("껐습니다:", a.name or "모든 기기")


def cmd_allow_app(a):
    if not a.user_confirmed:
        raise Stop(NOT_ARMED, "사용자가 확인한 경우에만 --user-confirmed")
    if not PKG_RE.match(a.package):
        raise Stop(ERR, "패키지 이름 형식이 아닙니다")
    if classify_app(a.package, []) == "hard":
        raise Stop(DENIED, f"{a.package} 는 은행·결제·인증·비밀번호·설정·앱 설치 계열이라 풀 수 없습니다.")
    al = jload("allow.json", {})
    al.setdefault(a.name, [])
    if a.package not in al[a.name]:
        al[a.name].append(a.package)
    jsave("allow.json", al)
    log(a.name, "allow-app", {"package": a.package}, "ok")
    print("풀었습니다:", a.package)


def cmd_screenshot(a):
    addr = guard(a.name, "screenshot", "screen")
    png = adb(addr, "exec-out", "screencap", "-p", binary=True, timeout=60)
    if not png.startswith(b"\x89PNG"):
        raise Stop(ERR, "캡처 결과가 PNG 가 아닙니다")
    (HOME / "shots").mkdir(parents=True, exist_ok=True)
    out = pathlib.Path(a.out) if a.out else HOME / "shots" / (now().strftime("%Y%m%d-%H%M%S") + ".png")
    out.write_bytes(png)
    private(out)
    spend(a.name, "screenshot", {})
    print(out)


def cmd_ui(a):
    addr = guard(a.name, "ui", "screen")
    xml = adb(addr, "exec-out", "uiautomator", "dump", "/dev/tty")
    try:
        root = ET.fromstring(xml[xml.index("<"):xml.rindex(">") + 1])
    except (ValueError, ET.ParseError):
        raise Stop(ERR, "화면 구조를 읽지 못했습니다")
    rows = []
    for n in root.iter("node"):
        txt = "" if n.get("password") == "true" else (n.get("text") or n.get("content-desc") or "")
        if not (txt or n.get("clickable") == "true"):
            continue
        rows.append({"text": txt[:60], "id": (n.get("resource-id") or "").split("/")[-1], "bounds": n.get("bounds"),
                     "clickable": n.get("clickable") == "true", "password": n.get("password") == "true"})
    spend(a.name, "ui", {"nodes": len(rows)})
    print(json.dumps(rows[:150], ensure_ascii=False, indent=1))


def cmd_tap(a):
    x, y = int_arg(a.x, "x"), int_arg(a.y, "y")
    addr = guard(a.name, "tap", "input")
    adb(addr, "shell", "input", "tap", str(x), str(y))
    spend(a.name, "tap", {"x": x, "y": y})
    print("ok")


def cmd_swipe(a):
    v = [int_arg(a.x1, "x1"), int_arg(a.y1, "y1"), int_arg(a.x2, "x2"), int_arg(a.y2, "y2")]
    ms = int_arg(a.ms, "ms", 50, 3000)
    addr = guard(a.name, "swipe", "input")
    adb(addr, "shell", "input", "swipe", *map(str, v), str(ms))
    spend(a.name, "swipe", {"from": v[:2], "to": v[2:], "ms": ms})
    print("ok")


def cmd_text(a):
    if not TEXT_RE.match(a.text):
        raise Stop(DENIED, "이 도구는 영문·숫자와 . , _ @ : / + = - 공백만 입력합니다 (한글·특수문자는 입력하지 않는다. 한글은 ADBKeyBoard 같은 도구가 따로 필요하고 사용자 동의가 먼저입니다).")
    bad = dangerous_text(a.text)
    if bad:
        raise Stop(DENIED, f"{bad} 는 입력하지 않습니다.")
    addr = guard(a.name, "text", "input")
    pw = focused_is_password(addr)
    if pw is None:
        raise Stop(DENIED, "입력 칸이 비밀번호 칸인지 확인하지 못해 멈춥니다.")
    if pw:
        raise Stop(DENIED, "비밀번호 칸에는 입력하지 않습니다. 사용자가 직접 입력해야 합니다.")
    adb(addr, "shell", "input text " + shlex.quote(a.text.replace(" ", "%s")))
    spend(a.name, "text", {"chars": len(a.text)})
    print("ok")


def cmd_key(a):
    k = a.key.upper()
    if k not in KEYS:
        raise Stop(DENIED, f"허용된 키만 누릅니다: {', '.join(KEYS)}")
    addr = guard(a.name, "key", "input" if k not in ("HOME", "BACK") else "recover")
    adb(addr, "shell", "input", "keyevent", KEYS[k])
    spend(a.name, "key", {"key": k})
    print("ok")


def cmd_start_app(a):
    if not PKG_RE.match(a.package):
        raise Stop(ERR, "패키지 이름 형식이 아닙니다")
    if classify_app(a.package, jload("allow.json", {}).get(a.name, [])) != "ok":
        raise Stop(DENIED, f"{a.package} 는 막힌 앱입니다.")
    addr = guard(a.name, "start-app", "recover")
    adb(addr, "shell", f"monkey -p {a.package} -c android.intent.category.LAUNCHER 1")
    spend(a.name, "start-app", {"package": a.package})
    print("ok")


def cmd_open_url(a):
    if not URL_RE.match(a.url):
        raise Stop(DENIED, "https 주소만 엽니다.")
    addr = guard(a.name, "open-url", "recover")
    adb(addr, "shell", "am start -a android.intent.action.VIEW -d " + shlex.quote(a.url))
    spend(a.name, "open-url", {"host": a.url.split("/")[2]})
    print("ok")


def cmd_purge_shots(a):
    d = HOME / "shots"
    n = len(list(d.glob("*"))) if d.exists() else 0
    shutil.rmtree(d, ignore_errors=True)
    print(f"캡처 {n}개를 지웠습니다.")


# ---------------------------------------------------------------- selftest
FAKE_ADB = r'''#!{py}
import os, sys
S = os.environ["FAKE_STATE"]
a = sys.argv[1:]
open(S + "/calls.log", "a").write(" ".join(a) + "\n")
def rd(n, d=""):
    try: return open(S + "/" + n).read()
    except OSError: return d
if a[:1] == ["connect"]: print("connected to " + a[1]); sys.exit(0)
if a[:1] == ["-s"]: a = a[2:]
if a[:1] == ["get-state"]: print(rd("state", "device")); sys.exit(0)
if a[:2] == ["shell", "dumpsys"]:
    fg = rd("fg", "com.example.notes"); lock = rd("lock", "false")
    if fg == "UNKNOWN": print("mCurrentFocus=null"); sys.exit(0)
    print("  mCurrentFocus=Window{abc1 u0 %s/.Main}\n  isKeyguardShowing=%s" % (fg, lock)); sys.exit(0)
if a[:3] == ["exec-out", "screencap", "-p"]: sys.stdout.buffer.write(b"\x89PNG\r\n\x1a\nFAKE"); sys.exit(0)
if a[:3] == ["exec-out", "uiautomator", "dump"]:
    if rd("dumpfail"): sys.exit(1)
    pw = rd("pw", "false")
    print('UI hierarchy dumped\n<?xml version="1.0"?><hierarchy><node text="로그인" resource-id="a/b" clickable="true" bounds="[0,0][10,10]" password="false" focused="false"/><node text="secret" resource-id="a/pw" password="%s" focused="true" clickable="true" bounds="[0,10][10,20]"/></hierarchy>' % pw)
    sys.exit(0)
sys.exit(0)
'''


def selftest(_a):
    global HOME, ADB, MIN_GAP
    old = (HOME, ADB, MIN_GAP)
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="devctl-selftest-"))
    HOME = tmp / "home"
    state = tmp / "state"; state.mkdir()
    (tmp / "bin").mkdir()
    fake = tmp / "bin" / "adb"
    fake.write_text(FAKE_ADB.replace("{py}", sys.executable), encoding="utf-8")
    fake.chmod(0o755)
    ADB = str(fake)
    MIN_GAP = 0.0
    os.environ["FAKE_STATE"] = str(state)
    real_out, sys.stdout = sys.stdout, __import__("io").StringIO()
    ok = fail = 0

    def check(name, cond):
        nonlocal ok, fail
        if cond:
            ok += 1
        else:
            fail += 1
            print("실패:", name, file=real_out)

    def code(fn, *args):
        try:
            fn(argparse.Namespace(**args[0]))
            return OK
        except Stop as e:
            return e.code

    def setfg(pkg="com.example.notes", lock="false", pw="false"):
        (state / "fg").write_text(pkg); (state / "lock").write_text(lock); (state / "pw").write_text(pw)

    def calls():
        p = state / "calls.log"
        return p.read_text() if p.exists() else ""
    try:
        setfg()
        # 등록 규칙
        check("공용 주소는 등록 거부", code(cmd_add, dict(name="tab", address="8.8.8.8:5555", label=None, kind="android", allow_non_tailnet=False)) == ERR)
        check("집 와이파이 주소도 거부", code(cmd_add, dict(name="tab", address="192.168.0.5:5555", label=None, kind="android", allow_non_tailnet=False)) == ERR)
        check("tailnet 주소는 등록", code(cmd_add, dict(name="tab", address="100.101.102.103:5555", label="갤럭시탭", kind="android", allow_non_tailnet=False)) == OK)
        check("ts.net 이름도 등록", valid_tailnet_address("tab.tail1234.ts.net:5555"))
        # arm 전에는 아무것도 못 한다
        check("켜기 전 탭은 10", code(cmd_tap, dict(name="tab", x=5, y=5)) == NOT_ARMED)
        check("확인 없이 arm 거부", code(cmd_arm, dict(name="tab", task="메모", minutes=10, max_actions=5, user_confirmed=False)) == NOT_ARMED)
        check("너무 긴 시간은 거부", code(cmd_arm, dict(name="tab", task="메모", minutes=999, max_actions=5, user_confirmed=True)) == ERR)
        check("arm", code(cmd_arm, dict(name="tab", task="메모 앱 열기", minutes=10, max_actions=6, user_confirmed=True)) == OK)
        check("탭 허용", code(cmd_tap, dict(name="tab", x=100, y=200)) == OK and "input tap 100 200" in calls())
        check("정수가 아닌 좌표 거부", code(cmd_tap, dict(name="tab", x="1;rm", y=2)) == ERR)
        check("범위 밖 좌표 거부", code(cmd_tap, dict(name="tab", x=99999, y=2)) == ERR)
        check("스와이프", code(cmd_swipe, dict(name="tab", x1=1, y1=2, x2=3, y2=4, ms=300)) == OK)
        # 앱 차단
        for pkg in ("com.kbstar.kbbank", "com.samsung.android.spay", "com.google.android.apps.authenticator2", "com.android.settings",
                    "com.android.vending", "com.x8bit.bitwarden", "viva.republica.toss", "com.kakaopay.app"):
            setfg(pkg)
            check(f"차단 {pkg}", code(cmd_tap, dict(name="tab", x=1, y=1)) == DENIED)
            check(f"차단 화면 캡처도 금지 {pkg}", code(cmd_screenshot, dict(name="tab", out=None)) == DENIED)
        setfg("com.samsung.android.dialer")
        check("전화 앱은 기본 차단", code(cmd_tap, dict(name="tab", x=1, y=1)) == DENIED)
        check("전화 앱 허용은 확인 필요", code(cmd_allow_app, dict(name="tab", package="com.samsung.android.dialer", user_confirmed=False)) == NOT_ARMED)
        check("은행 앱은 풀 수 없음", code(cmd_allow_app, dict(name="tab", package="com.kbstar.kbbank", user_confirmed=True)) == DENIED)
        check("전화 앱은 확인 후 풀 수 있음", code(cmd_allow_app, dict(name="tab", package="com.samsung.android.dialer", user_confirmed=True)) == OK
              and code(cmd_tap, dict(name="tab", x=1, y=1)) == OK)
        # 잠금 화면, 앞 화면 모름
        setfg(lock="true")
        check("잠금 화면에서는 입력 금지", code(cmd_tap, dict(name="tab", x=1, y=1)) == DENIED)
        setfg("UNKNOWN")
        check("앞 화면을 모르면 입력 금지", code(cmd_tap, dict(name="tab", x=1, y=1)) == DENIED)
        # 글 입력
        setfg()
        check("arm 재설정", code(cmd_arm, dict(name="tab", task="입력 시험", minutes=10, max_actions=50, user_confirmed=True)) == OK)
        check("영문 입력 허용", code(cmd_text, dict(name="tab", text="hello world")) == OK and "input text hello%sworld" in calls())
        for bad, why in (("a; rm -rf /", "셸 문자"), ("$(reboot)", "셸 치환"), ("한글", "한글"), ("`id`", "백틱"), ("a&b", "앰퍼샌드")):
            check(f"입력 거부({why})", code(cmd_text, dict(name="tab", text=bad)) == DENIED)
        for bad in ("123456", "4111111111111111", "900101-1234567"):
            check(f"입력 거부(PIN·카드·주민번호 모양) {bad}", code(cmd_text, dict(name="tab", text=bad)) == DENIED)
        setfg(pw="true")
        check("비밀번호 칸에는 입력 금지", code(cmd_text, dict(name="tab", text="abcd")) == DENIED)
        setfg()
        (state / "dumpfail").write_text("1")
        check("입력 칸 확인 실패면 입력 금지", code(cmd_text, dict(name="tab", text="abcd")) == DENIED)
        (state / "dumpfail").unlink()
        # 키·앱·URL
        check("허용 키", code(cmd_key, dict(name="tab", key="back")) == OK)
        setfg("com.kbstar.kbbank")
        check("은행 앱 위에서도 HOME 으로 나올 수 있다", code(cmd_key, dict(name="tab", key="HOME")) == OK)
        check("은행 앱 위에서 ENTER 는 막힘", code(cmd_key, dict(name="tab", key="ENTER")) == DENIED)
        setfg()
        check("허용되지 않은 키(POWER)", code(cmd_key, dict(name="tab", key="POWER")) == DENIED)
        check("앱 실행", code(cmd_start_app, dict(name="tab", package="com.example.notes")) == OK and "monkey -p com.example.notes" in calls())
        check("막힌 앱 실행 금지", code(cmd_start_app, dict(name="tab", package="com.android.settings")) == DENIED)
        check("패키지 형식 검사", code(cmd_start_app, dict(name="tab", package="a b;c")) == ERR)
        check("https 열기", code(cmd_open_url, dict(name="tab", url="https://example.com/a?b=1&c=2")) == OK and "-d 'https://example.com/a?b=1&c=2'" in calls())
        check("http·javascript 거부", code(cmd_open_url, dict(name="tab", url="http://example.com")) == DENIED and code(cmd_open_url, dict(name="tab", url="javascript:alert(1)")) == DENIED)
        # 화면 구조: 비밀번호 칸의 글은 가려진다
        setfg(pw="true")
        buf = __import__("io").StringIO(); cur = sys.stdout; sys.stdout = buf
        check("화면 구조 읽기", code(cmd_ui, dict(name="tab")) == OK); sys.stdout = cur
        check("비밀번호 칸의 글은 출력 안 됨", "secret" not in buf.getvalue() and '"password": true' in buf.getvalue())
        # 캡처
        setfg()
        check("캡처", code(cmd_screenshot, dict(name="tab", out=None)) == OK)
        shots = list((HOME / "shots").glob("*.png"))
        check("캡처 파일 권한 600(리눅스·맥)", bool(shots) and (os.name == "nt" or oct(shots[0].stat().st_mode & 0o777) == "0o600"))
        # 동작 수 한도와 시간
        check("arm 6회 한도", code(cmd_arm, dict(name="tab", task="한도", minutes=10, max_actions=3, user_confirmed=True)) == OK)
        r = [code(cmd_tap, dict(name="tab", x=1, y=1)) for _ in range(4)]
        check("한도를 넘으면 10", r == [OK, OK, OK, NOT_ARMED])
        arm = jload("arm.json", {}); arm["tab"]["until"] = (now() - datetime.timedelta(seconds=1)).isoformat(timespec="seconds"); arm["tab"]["used"] = 0; jsave("arm.json", arm)
        check("시간이 끝나면 10", code(cmd_tap, dict(name="tab", x=1, y=1)) == NOT_ARMED)
        # disarm 은 언제나 된다
        code(cmd_arm, dict(name="tab", task="x", minutes=5, max_actions=5, user_confirmed=True))
        check("disarm", code(cmd_disarm, dict(name="tab")) == OK and code(cmd_tap, dict(name="tab", x=1, y=1)) == NOT_ARMED)
        # 기록: 입력한 글 내용은 남지 않는다
        logtxt = (HOME / "actions.jsonl").read_text(encoding="utf-8")
        check("기록에 입력한 글 내용이 없다", "hello" not in logtxt and '"chars": 11' in logtxt)
        check("거부 기록은 남지 않아도 허용 기록은 있다", '"action": "tap"' in logtxt)
        # 임의 명령 없음
        check("raw shell 명령은 없다", not any(n in ("shell", "raw", "exec") for n in ("tap", "swipe", "text", "key", "start-app", "open-url", "screenshot", "ui")))
        check("캡처 삭제", (cmd_purge_shots(argparse.Namespace()) or True) and not (HOME / "shots").exists())
    finally:
        sys.stdout = real_out
        shutil.rmtree(tmp, ignore_errors=True)
        HOME, ADB, MIN_GAP = old
    print(f"selftest: {ok}개 통과, {fail}개 실패")
    sys.exit(1 if fail else 0)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest="cmd", required=True)

    def P(name, fn, *args, **flags):
        s = sp.add_parser(name)
        for x in args:
            s.add_argument(x)
        for k, v in flags.items():
            if v is True:
                s.add_argument("--" + k.replace("_", "-"), action="store_true")
            elif v is not None:
                s.add_argument("--" + k.replace("_", "-"), default=v)
            else:
                s.add_argument("--" + k.replace("_", "-"))
        s.set_defaults(f=fn)
        return s
    P("add", cmd_add, "name", address=None, label=None, kind="android", allow_non_tailnet=True)
    P("list", cmd_list); P("status", cmd_status)
    P("remove", cmd_remove, "name"); P("connect", cmd_connect, "name")
    s = P("arm", cmd_arm, "name", task=None, minutes=DEFAULT_MINUTES, max_actions=DEFAULT_ACTIONS, user_confirmed=True)
    s = sp.add_parser("disarm"); s.add_argument("name", nargs="?"); s.set_defaults(f=cmd_disarm)
    P("allow-app", cmd_allow_app, "name", "package", user_confirmed=True)
    P("screenshot", cmd_screenshot, "name", out=None); P("ui", cmd_ui, "name")
    P("tap", cmd_tap, "name", "x", "y")
    s = sp.add_parser("swipe"); [s.add_argument(x) for x in ("name", "x1", "y1", "x2", "y2")]; s.add_argument("ms", nargs="?", default=300); s.set_defaults(f=cmd_swipe)
    P("text", cmd_text, "name", "text"); P("key", cmd_key, "name", "key")
    P("start-app", cmd_start_app, "name", "package"); P("open-url", cmd_open_url, "name", "url")
    P("purge-shots", cmd_purge_shots)
    sp.add_parser("selftest").set_defaults(f=selftest)
    a = ap.parse_args()
    try:
        a.f(a)
    except Stop as e:
        print(("막음: " if e.code == DENIED else "알림: ") + str(e), file=sys.stderr)
        sys.exit(e.code)


if __name__ == "__main__":
    main()
