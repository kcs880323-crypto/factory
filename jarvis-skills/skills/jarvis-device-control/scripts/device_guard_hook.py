#!/usr/bin/env python3
"""Claude Code PreToolUse 훅: 기기 조작이 '켜 둔 시간(arm)' 밖에서 일어나지 않게 막는다. 표준 라이브러리만 쓴다.

두 가지를 막는다.
  1) Bash 로 adb·scrcpy·ios 도구를 직접 쓰는 것 (device_ctl.py 를 거치지 않으면 안전장치가 빠진다).
     기기를 연결·확인하는 adb 하위 명령(devices, connect, pair …)만 직접 써도 된다.
  2) 아이폰·기기 조작 MCP 도구 (도구 이름이 mcp__<서버>__… 이고 서버 이름이 iphone/ios/wda/android/mobile/phone/tablet/device 로 시작) 를
     device_ctl.py arm 으로 켜 둔 시간·횟수 밖에서 부르는 것. 서버 이름은 등록한 기기 이름과 같아야 한다.

입력: 표준입력으로 훅 JSON (tool_name, tool_input). 출력: 막을 때만 deny JSON (종료 코드 0). 결정이 없으면 아무것도 출력하지 않는다
(훅은 허락을 '줄' 수 없다. 이 훅이 조용하면 평소 권한 확인이 그대로 적용된다).

한계: 이 훅은 실수로 안전장치를 건너뛰는 것을 막는다. 일부러 우회하려는 것(다른 프로그램을 쓰는 것 등)을 막지는 못한다.
"""
import datetime, json, os, pathlib, re, sys, tempfile, time

HOME = pathlib.Path(os.environ.get("JARVIS_DEVICES_HOME", pathlib.Path.home() / ".jarvis-devices"))
MCP_RE = re.compile(r"^mcp__(iphone|ios|wda|android|mobile|phone|tablet|device)[A-Za-z0-9_-]*__")
# 명령 자리(줄 처음, ; & | ( ` $( " ' 바로 뒤, 그리고 sudo/time/env/변수=값 뒤)에 있는 도구만 본다. 다른 명령의 인자(grep adb, git commit -m 'adb')는 건드리지 않는다.
RAW_TOOLS = re.compile(r"""(?:^|[;&|(`"']|\$\()\s*(?:(?:sudo|time|nohup|exec|env)\s+)*(?:[A-Za-z_]+=\S+\s+)*(?:\S*/)?(adb|scrcpy|go-ios|ios|pymobiledevice3|tidevice|idevice[a-z_]*)(?=\s|$|["'])""")
SAFE_ADB = {"version", "devices", "pair", "connect", "disconnect", "kill-server", "start-server", "get-state", "get-serialno", "help", "tcpip"}


def deny(reason):
    print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny", "permissionDecisionReason": reason}}, ensure_ascii=False))


def now():
    return datetime.datetime.now().astimezone()


def check_bash(cmd):
    if "device_ctl.py" in cmd:
        return None
    for m in RAW_TOOLS.finditer(cmd):
        tool = m.group(1)
        rest = cmd[m.end():].split()
        if tool == "adb":
            # adb [-s 주소] 하위명령: -s/-H/-P 같은 옵션을 건너뛰고 첫 하위 명령을 본다
            toks, i = rest, 0
            while i < len(toks) and toks[i].startswith("-"):
                i += 2 if toks[i] in ("-s", "-H", "-P", "-L", "-t") else 1
            sub = toks[i] if i < len(toks) else "help"
            if sub in SAFE_ADB:
                continue
            return f"adb {sub}: 기기 화면 조작·파일·앱 설치는 device_ctl.py 를 거쳐야 합니다 (켜 둔 시간, 앱 차단, 입력 검사가 거기에 있습니다)."
        return f"{tool}: 기기 조작 도구를 직접 쓰지 않습니다. 안드로이드는 device_ctl.py, 아이폰은 켜 둔 시간 안에서 아이폰 MCP 도구로 합니다."
    return None


def lock_file():
    p = HOME / ".arm.lock"
    t0 = time.time()
    while time.time() - t0 < 2:
        try:
            fd = os.open(p, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            os.close(fd)
            return p
        except FileExistsError:
            try:
                if time.time() - p.stat().st_mtime > 10:   # 죽은 잠금
                    p.unlink()
            except OSError:
                pass
            time.sleep(0.05)
    return None


def check_mcp(tool):
    server = tool.split("__")[1]
    p = HOME / "arm.json"
    try:
        arm = json.loads(p.read_text(encoding="utf-8")).get(server)
    except (OSError, ValueError):
        arm = None
    if not arm:
        return f"기기 '{server}' 은(는) 켜져 있지 않습니다. 사용자에게 무엇을 할지·몇 분인지 알리고 확인받은 뒤 `device_ctl.py arm {server} --task … --user-confirmed` 를 하세요."
    if datetime.datetime.fromisoformat(arm["until"]) <= now():
        return f"기기 '{server}' 의 허락 시간이 끝났습니다. 계속하려면 사용자에게 다시 확인받고 arm 하세요."
    if arm["used"] >= arm["max_actions"]:
        return f"기기 '{server}' 의 허락된 동작 수({arm['max_actions']})를 다 썼습니다. 계속하려면 사용자에게 다시 확인받고 arm 하세요."
    lk = lock_file()
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
        data[server]["used"] += 1
        tmp = p.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
        try:
            os.chmod(tmp, 0o600)
        except OSError:
            pass
        os.replace(tmp, p)
        with open(HOME / "actions.jsonl", "a", encoding="utf-8") as f:
            f.write(json.dumps({"ts": now().isoformat(timespec="seconds"), "device": server, "action": "mcp:" + tool.split("__", 2)[2], "params": {}, "result": "ok"}, ensure_ascii=False) + "\n")
    finally:
        if lk:
            try:
                lk.unlink()
            except OSError:
                pass
    return None


def decide(event):
    tool = event.get("tool_name", "")
    if tool == "Bash":
        return check_bash((event.get("tool_input") or {}).get("command", ""))
    if MCP_RE.match(tool):
        return check_mcp(tool)
    return None


def selftest():
    import subprocess, shutil
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="guardhook-"))
    env = dict(os.environ, JARVIS_DEVICES_HOME=str(tmp))
    ok = fail = 0

    def run(event):
        r = subprocess.run([sys.executable, "-I", __file__], input=json.dumps(event), capture_output=True, text=True, env=env)
        return r.returncode, (json.loads(r.stdout)["hookSpecificOutput"]["permissionDecision"] if r.stdout.strip() else None)

    def check(name, cond):
        nonlocal ok, fail
        if cond:
            ok += 1
        else:
            fail += 1
            print("실패:", name)
    try:
        bash = lambda c: {"tool_name": "Bash", "tool_input": {"command": c}}
        for c, want in (("adb devices", None), ("adb connect 100.1.2.3:5555", None), ("adb -s 100.1.2.3:5555 get-state", None),
                        ("adb shell input tap 1 2", "deny"), ("adb -s 100.1.2.3:5555 exec-out screencap -p > a.png", "deny"), ("adb install a.apk", "deny"),
                        ("echo hi; adb push a /sdcard", "deny"), ("scrcpy --serial x", "deny"), ("pymobiledevice3 developer screenshot a.png", "deny"),
                        ("python device_ctl.py tap tab 1 2", None), ("ls -la", None), ("grep adb notes.txt", None), ("git commit -m 'fix adb docs'", None)):
            code, dec = run(bash(c))
            check(f"Bash [{c}] → {want}", code == 0 and dec == want)
        mcp = lambda t: {"tool_name": t, "tool_input": {}}
        check("다른 MCP 도구는 건드리지 않는다", run(mcp("mcp__github__get_me")) == (0, None))
        check("켜기 전 아이폰 도구는 막힘", run(mcp("mcp__iphone12__pua_tap")) == (0, "deny"))
        (tmp / "arm.json").write_text(json.dumps({"iphone12": {"task": "t", "until": (now() + datetime.timedelta(minutes=5)).isoformat(), "max_actions": 2, "used": 0}}))
        check("켜져 있으면 조용히 통과(1)", run(mcp("mcp__iphone12__pua_tap")) == (0, None))
        check("켜져 있으면 조용히 통과(2)", run(mcp("mcp__iphone12__pua_observe")) == (0, None))
        check("횟수를 넘으면 막힘", run(mcp("mcp__iphone12__pua_tap")) == (0, "deny"))
        check("다른 기기 이름은 켜지지 않음", run(mcp("mcp__iphone99__pua_tap")) == (0, "deny"))
        (tmp / "arm.json").write_text(json.dumps({"iphone12": {"task": "t", "until": (now() - datetime.timedelta(seconds=1)).isoformat(), "max_actions": 9, "used": 0}}))
        check("시간이 끝나면 막힘", run(mcp("mcp__iphone12__pua_tap")) == (0, "deny"))
        (tmp / "arm.json").write_text("{깨짐")
        check("arm 파일이 깨지면 막힘", run(mcp("mcp__iphone12__pua_tap")) == (0, "deny"))
        check("사용 기록이 남는다", "mcp:pua_tap" in (tmp / "actions.jsonl").read_text(encoding="utf-8"))
        r = subprocess.run([sys.executable, "-I", __file__], input="깨진 입력", capture_output=True, text=True, env=env)
        check("입력이 깨져도 오류 없이 통과", r.returncode == 0 and not r.stdout.strip())
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print(f"selftest: {ok}개 통과, {fail}개 실패")
    sys.exit(1 if fail else 0)


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "selftest":
        selftest()
    try:
        event = json.loads(sys.stdin.read())
    except ValueError:
        sys.exit(0)                      # 읽을 수 없으면 결정하지 않는다
    reason = decide(event)
    if reason:
        deny(reason)


if __name__ == "__main__":
    main()
