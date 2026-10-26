#!/usr/bin/env python3
"""지식영상을 유튜브(구글 API 직접)와 인스타그램(Zernio)에 올리는 도구. 표준 라이브러리만 쓴다.

  python publish_tool.py status                       설정이 어디까지 됐는지 (비밀 값은 출력하지 않는다)
  python publish_tool.py init --youtube-account … --zernio-profile … --instagram-account … --instagram-username …
  python publish_tool.py google-import --file client_secret.json [--delete-source]
  python publish_tool.py youtube-auth                 브라우저로 구글 로그인 (계정이 설정과 같은지 확인)
  python publish_tool.py zernio-key [--from-clipboard | --stdin]
  python publish_tool.py zernio-accounts [--save]     Zernio 에 연결된 계정 목록
  python publish_tool.py vertical <영상.mp4>           인스타 릴스용 9:16 영상 (흐린 배경 위에 원본)
  python publish_tool.py publish --video <영상.mp4> --meta <publish.json> --to youtube,instagram [--confirmed]
  python publish_tool.py history
  python publish_tool.py set-config <키> <값> [--user-confirmed]
  python publish_tool.py selftest

종료 코드: 0 성공 / 10 사용자 확인이 먼저 필요 / 20 거부(중복·검증 실패·설정 부족) / 1 오류.
설정과 토큰은 ~/.jarvis-publish (JARVIS_PUBLISH_HOME) 에 나만 읽게(600) 저장한다. 저장소에는 올리지 않는다.
비밀 값(구글 클라이언트 비밀번호, Zernio API 키, 토큰)은 명령 인자로 받지 않고 화면에 출력하지 않는다.
"""
import argparse, base64, hashlib, http.server, json, os, pathlib, re, secrets, shutil, subprocess, sys, tempfile, threading, time, urllib.error, urllib.parse, urllib.request, uuid, webbrowser

HOME = pathlib.Path(os.environ.get("JARVIS_PUBLISH_HOME", pathlib.Path.home() / ".jarvis-publish"))
GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth"
SCOPES = "openid email https://www.googleapis.com/auth/youtube.upload"
URLS = {"token": "https://oauth2.googleapis.com/token",
        "yt_upload": "https://www.googleapis.com/upload/youtube/v3/videos",
        "zernio": "https://zernio.com/api"}
# 시험용: 이 값이 있으면 모든 주소를 내 컴퓨터(루프백)의 가짜 서버로 돌린다. 다른 호스트는 받지 않는다 (토큰이 엉뚱한 곳으로 가지 않게).
_TEST_BASE = os.environ.get("JARVIS_PUBLISH_TEST_BASE", "")
if _TEST_BASE:
    if not re.match(r"^http://(127\.0\.0\.1|localhost):\d+$", _TEST_BASE):
        sys.exit("JARVIS_PUBLISH_TEST_BASE 는 http://127.0.0.1:포트 형태만 허용합니다.")
    URLS = {"token": _TEST_BASE + "/google/token", "yt_upload": _TEST_BASE + "/yt/upload/youtube/v3/videos", "zernio": _TEST_BASE + "/zernio"}
SLEEP = time.sleep
CHUNK = max(262144, int(os.environ.get("JARVIS_PUBLISH_CHUNK", 8 * 1024 * 1024)) // 262144 * 262144)     # 256KiB 의 배수 (시험에서만 줄인다)
POLL = float(os.environ.get("JARVIS_PUBLISH_POLL", "5"))          # Zernio 처리 상태를 다시 보는 간격(초)
BACKOFF = float(os.environ.get("JARVIS_PUBLISH_BACKOFF", "1"))     # 업로드가 끊겼을 때 기다리는 시간의 배율

DEFAULT_CONFIG = {
    "youtube": {"account_email": "", "gcp_project": "", "visibility": "public", "category_id": "27", "made_for_kids": False,
                "contains_synthetic_media": False, "notify_subscribers": True, "language": "ko"},
    "zernio": {"profile_id": "", "instagram_account_id": "", "instagram_username": "", "youtube_account_id": "", "share_to_feed": True},
    "disclosure": "※ 나레이션은 AI 합성 음성입니다.",
    "confirm_each": True,
}
SETTABLE = {"youtube.visibility": ("public", "unlisted", "private"), "youtube.category_id": None, "youtube.made_for_kids": ("true", "false"),
            "youtube.contains_synthetic_media": ("true", "false"), "youtube.notify_subscribers": ("true", "false"),
            "zernio.share_to_feed": ("true", "false"), "disclosure": None, "confirm_each": ("true", "false")}


# ------------------------------------------------------------------ 공통
def say(*a):
    print(*a, flush=True)


def redact(t):
    t = re.sub(r"ya29\.[A-Za-z0-9_\-\.]+", "ya29.***", str(t))
    t = re.sub(r"sk_[A-Za-z0-9]{8,}", "sk_***", t)
    t = re.sub(r"(refresh_token|access_token|id_token|client_secret|code_verifier)(\"?\s*[:=]\s*\"?)[^\"&,\s}]+", r"\1\2***", t)
    return t


def write_private(path, text):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(path.parent, 0o700)
    except OSError:
        pass
    tmp = path.with_suffix(path.suffix + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def read_json(path, default=None):
    try:
        return json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def deep_merge(base, over):
    out = json.loads(json.dumps(base))
    for k, v in (over or {}).items():
        out[k] = deep_merge(out[k], v) if isinstance(v, dict) and isinstance(out.get(k), dict) else v
    return out


def load_config():
    return deep_merge(DEFAULT_CONFIG, read_json(HOME / "config.json", {}))


def save_config(cfg):
    write_private(HOME / "config.json", json.dumps(cfg, ensure_ascii=False, indent=1))


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


def fetch(method, url, headers=None, body=None, timeout=120):
    """(상태코드, 소문자 헤더, 본문 bytes). 4xx·5xx·308 도 예외 없이 돌려준다. 연결 오류만 OSError."""
    req = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        with _OPENER.open(req, timeout=timeout) as r:
            return r.status, {k.lower(): v for k, v in r.getheaders()}, r.read()
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}, e.read()


def jbody(b):
    try:
        return json.loads(b.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return {}


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""):
            h.update(c)
    return h.hexdigest()


# ------------------------------------------------------------------ 구글(유튜브) 로그인
def google_client():
    d = read_json(HOME / "google_client.json")
    if not d or not d.get("client_id") or not d.get("client_secret"):
        return None
    return d


def b64url_json(seg):
    seg += "=" * (-len(seg) % 4)
    return json.loads(base64.urlsafe_b64decode(seg.encode()).decode("utf-8"))


def cmd_google_import(a):
    p = pathlib.Path(a.file).expanduser()
    d = read_json(p)
    if not isinstance(d, dict):
        say("파일을 읽지 못했습니다 (구글 클라우드에서 내려받은 JSON 이어야 합니다):", p)
        return 20
    inner = d.get("installed") or d.get("web")
    if not inner or "client_id" not in inner or "client_secret" not in inner:
        say("이 JSON 에 client_id / client_secret 이 없습니다.")
        return 20
    if "installed" not in d:
        say("주의: 'web' 형식 클라이언트입니다. 이 도구는 '데스크톱 앱' 형식(installed)을 기대합니다. 다시 만드는 것을 권합니다.")
        return 20
    write_private(HOME / "google_client.json", json.dumps({"client_id": inner["client_id"], "client_secret": inner["client_secret"],
                                                           "project_id": inner.get("project_id", "")}, ensure_ascii=False))
    say(f"저장했습니다: {HOME / 'google_client.json'} (client_id {inner['client_id'][:12]}… 비밀번호는 출력하지 않습니다)")
    cfg = load_config()
    if inner.get("project_id") and not cfg["youtube"]["gcp_project"]:
        cfg["youtube"]["gcp_project"] = inner["project_id"]
        save_config(cfg)
    if a.delete_source:
        try:
            p.unlink()
            say("내려받은 원본 JSON 은 지웠습니다.")
        except OSError as e:
            say("원본을 지우지 못했습니다:", e)
    else:
        say("내려받은 원본 JSON(다운로드 폴더)에도 비밀번호가 들어 있습니다. 확인한 뒤 지우세요 (--delete-source 로 바로 지울 수 있습니다).")
    return 0


class _CB(http.server.BaseHTTPRequestHandler):
    result = {}

    def do_GET(self):
        q = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
        if "code" in q or "error" in q:
            _CB.result = {k: v[0] for k, v in q.items()}
            msg = "로그인이 끝났습니다. 이 창을 닫고 터미널로 돌아가세요." if "code" in q else "로그인이 취소되었습니다."
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(f"<meta charset=utf-8><body style='font-family:sans-serif'><h3>{msg}</h3></body>".encode())
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, *a):
        pass


def cmd_youtube_auth(a):
    cl = google_client()
    cfg = load_config()
    want = cfg["youtube"]["account_email"].strip().lower()
    if not cl:
        say("먼저 구글 클라이언트가 필요합니다: google-import --file <내려받은 client_secret JSON>")
        return 20
    if not want:
        say("먼저 init --youtube-account <이메일> 로 올릴 계정을 정해 두세요.")
        return 20
    srv = http.server.HTTPServer(("127.0.0.1", 0), _CB)
    port = srv.server_address[1]
    redirect = f"http://127.0.0.1:{port}"
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    state = secrets.token_urlsafe(16)
    url = GOOGLE_AUTH + "?" + urllib.parse.urlencode({
        "client_id": cl["client_id"], "redirect_uri": redirect, "response_type": "code", "scope": SCOPES,
        "code_challenge": challenge, "code_challenge_method": "S256", "state": state, "access_type": "offline",
        "prompt": "consent", "login_hint": want})
    say(f"브라우저에서 {want} 계정으로 로그인하고 허용하세요 (유튜브에 영상 올리기 권한 하나만 요청합니다).")
    say("주소:", url)
    if not a.no_browser:
        try:
            webbrowser.open(url)
        except Exception:
            pass
    _CB.result = {}
    srv.timeout = 1
    t0 = time.time()
    while not _CB.result and time.time() - t0 < a.timeout:
        srv.handle_request()
    srv.server_close()
    r = _CB.result
    if not r or "code" not in r:
        say("로그인을 받지 못했습니다:", r.get("error", "시간 초과"))
        return 1
    if r.get("state") != state:
        say("state 가 맞지 않아 중단했습니다 (다른 곳에서 온 요청일 수 있습니다).")
        return 1
    return _finish_auth(cl, r["code"], verifier, redirect, want)


def _finish_auth(cl, code, verifier, redirect, want):
    body = urllib.parse.urlencode({"client_id": cl["client_id"], "client_secret": cl["client_secret"], "code": code, "code_verifier": verifier,
                                   "redirect_uri": redirect, "grant_type": "authorization_code"}).encode()
    st, _, b = fetch("POST", URLS["token"], {"Content-Type": "application/x-www-form-urlencoded"}, body)
    d = jbody(b)
    if st != 200 or "refresh_token" not in d:
        say("토큰을 받지 못했습니다:", st, redact(b[:300].decode("utf-8", "replace")))
        if st == 200:
            say("refresh_token 이 없습니다. 이전에 이미 허용한 계정이면 구글 계정의 '타사 앱 액세스'에서 이 앱을 삭제하고 다시 시도하세요.")
        return 1
    email = ""
    try:
        email = b64url_json(d.get("id_token", "..").split(".")[1]).get("email", "").lower()
    except Exception:
        pass
    if email != want:
        say(f"로그인한 계정이 설정과 다릅니다: 로그인 {email or '(확인 불가)'} / 설정 {want}. 저장하지 않았습니다. 올바른 계정으로 다시 로그인하세요.")
        return 20
    write_private(HOME / "youtube_token.json", json.dumps({"refresh_token": d["refresh_token"], "email": email, "scope": d.get("scope", ""),
                                                           "obtained_at": time.strftime("%Y-%m-%dT%H:%M:%S%z")}, ensure_ascii=False))
    say(f"연결됐습니다: {email} (토큰은 {HOME / 'youtube_token.json'} 에 나만 읽게 저장, 화면에는 출력하지 않음)")
    return 0


def yt_access_token():
    cl = google_client()
    tk = read_json(HOME / "youtube_token.json")
    if not cl or not tk:
        raise SystemExit("유튜브 연결이 안 되어 있습니다: google-import 와 youtube-auth 를 먼저 하세요.")
    body = urllib.parse.urlencode({"client_id": cl["client_id"], "client_secret": cl["client_secret"], "refresh_token": tk["refresh_token"],
                                   "grant_type": "refresh_token"}).encode()
    st, _, b = fetch("POST", URLS["token"], {"Content-Type": "application/x-www-form-urlencoded"}, body)
    d = jbody(b)
    if st != 200 or "access_token" not in d:
        hint = ""
        if d.get("error") == "invalid_grant":
            hint = " (토큰이 만료·취소됐습니다. 동의 화면이 '테스트' 상태면 7일마다 만료됩니다. youtube-auth 를 다시 하고, 동의 화면을 '프로덕션'으로 게시하는 것을 권합니다)"
        raise SystemExit(f"액세스 토큰을 받지 못했습니다: {st} {redact(b[:200].decode('utf-8', 'replace'))}{hint}")
    return d["access_token"]


def yt_upload(video, meta_body, notify=True):
    """구글 재개형 업로드. 반환: 응답 JSON. 끊기면 이어서 올린다."""
    token = yt_access_token()
    size = os.path.getsize(video)
    q = urllib.parse.urlencode({"uploadType": "resumable", "part": "snippet,status", "notifySubscribers": "true" if notify else "false"})
    st, h, b = fetch("POST", f"{URLS['yt_upload']}?{q}", {"Authorization": f"Bearer {token}", "Content-Type": "application/json; charset=UTF-8",
                                                          "X-Upload-Content-Length": str(size), "X-Upload-Content-Type": "video/mp4"},
                    json.dumps(meta_body, ensure_ascii=False).encode("utf-8"))
    if st != 200 or "location" not in h:
        raise SystemExit(f"업로드를 시작하지 못했습니다: {st} {redact(b[:400].decode('utf-8', 'replace'))}" + _yt_hint(st, b))
    session = h["location"]
    sent = 0
    fails = 0
    with open(video, "rb") as f:
        while True:
            f.seek(sent)
            chunk = f.read(CHUNK)
            end = sent + len(chunk) - 1
            hdr = {"Content-Type": "video/mp4", "Content-Length": str(len(chunk)), "Content-Range": f"bytes {sent}-{end}/{size}" if chunk else f"bytes */{size}"}
            try:
                st, h, b = fetch("PUT", session, hdr, chunk, timeout=300)
            except OSError as e:
                st, h, b = 0, {}, str(e).encode()
            if st in (200, 201):
                return jbody(b)
            if st == 308:
                fails = 0
                rng = h.get("range", "")
                m = re.match(r"bytes=0-(\d+)", rng)
                sent = int(m.group(1)) + 1 if m else 0
                continue
            fails += 1
            if st in (0, 500, 502, 503, 504) and fails <= 5:      # 끊김: 어디까지 받았는지 물어보고 이어서
                SLEEP(min(2 ** fails, 30) * BACKOFF)
                try:
                    st2, h2, b2 = fetch("PUT", session, {"Content-Type": "video/mp4", "Content-Length": "0", "Content-Range": f"bytes */{size}"}, b"", timeout=60)
                except OSError:
                    continue
                if st2 in (200, 201):
                    return jbody(b2)
                m = re.match(r"bytes=0-(\d+)", h2.get("range", "")) if st2 == 308 else None
                sent = int(m.group(1)) + 1 if m else (0 if st2 == 308 else sent)
                continue
            raise SystemExit(f"업로드가 실패했습니다: {st} {redact(b[:400].decode('utf-8', 'replace'))}" + _yt_hint(st, b))


def _yt_hint(st, b):
    t = b.decode("utf-8", "replace")
    if "quotaExceeded" in t:
        return " (하루 사용량 한도를 넘었습니다. 구글 클라우드 콘솔의 할당량을 확인하고 내일 다시 하세요)"
    if "youtubeSignupRequired" in t or "channelNotFound" in t:
        return " (이 구글 계정에 유튜브 채널이 없습니다. 유튜브에서 채널을 먼저 만드세요)"
    if st in (401, 403):
        return " (권한 문제: youtube-auth 를 다시 하거나 YouTube Data API v3 를 이 프로젝트에서 사용 설정했는지 확인하세요)"
    return ""


# ------------------------------------------------------------------ Zernio
def zernio_key():
    k = os.environ.get("ZERNIO_API_KEY", "").strip()
    if not k:
        try:
            k = (HOME / "zernio_key").read_text(encoding="utf-8").strip()
        except OSError:
            k = ""
    return k


def z_req(method, path, body=None, headers=None):
    key = zernio_key()
    if not key:
        raise SystemExit("Zernio API 키가 없습니다: zernio-key 를 먼저 하세요.")
    h = {"Authorization": f"Bearer {key}", "Content-Type": "application/json", "Accept": "application/json"}
    h.update(headers or {})
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    st, rh, b = fetch(method, URLS["zernio"] + path, h, data)
    return st, rh, jbody(b), b


def clipboard_text():
    cmds = {"win32": ["powershell", "-NoProfile", "-Command", "Get-Clipboard"], "darwin": ["pbpaste"]}.get(sys.platform, ["xclip", "-selection", "clipboard", "-o"])
    try:
        return subprocess.run(cmds, capture_output=True, text=True, timeout=10).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def clear_clipboard():
    try:
        if sys.platform == "win32":
            subprocess.run(["powershell", "-NoProfile", "-Command", "Set-Clipboard -Value ' '"], timeout=10)
        elif sys.platform == "darwin":
            subprocess.run(["pbcopy"], input="", text=True, timeout=10)
        else:
            subprocess.run(["xclip", "-selection", "clipboard"], input="", text=True, timeout=10)
    except (OSError, subprocess.SubprocessError):
        pass


def cmd_zernio_key(a):
    if a.from_clipboard:
        k = clipboard_text()
    elif a.stdin:
        k = sys.stdin.readline().strip()
    else:
        import getpass
        k = getpass.getpass("Zernio API 키를 붙여 넣으세요 (화면에 보이지 않습니다): ").strip()
    if not k:
        say("키를 받지 못했습니다.")
        return 20
    if not re.match(r"^sk_[A-Za-z0-9]{40,}$", k):
        say("주의: 키 모양이 문서의 'sk_' + 64자리 16진수와 다릅니다. 그대로 저장하지 않았습니다. (다른 값을 복사했을 수 있습니다)")
        return 20
    write_private(HOME / "zernio_key", k + "\n")
    say(f"저장했습니다: {HOME / 'zernio_key'} (길이 {len(k)}, 값은 출력하지 않음)")
    if a.from_clipboard and a.clear_clipboard:
        clear_clipboard()
        say("클립보드를 비웠습니다.")
    return 0


def cmd_zernio_accounts(a):
    cfg = load_config()
    q = "?" + urllib.parse.urlencode({"profileId": cfg["zernio"]["profile_id"]}) if cfg["zernio"]["profile_id"] else ""
    st, _, d, b = z_req("GET", "/v1/accounts" + q)
    if st != 200:
        say("계정 목록을 받지 못했습니다:", st, redact(b[:300].decode("utf-8", "replace")))
        return 1
    accs = d.get("accounts", [])
    say(f"Zernio 연결 계정 {len(accs)}개")
    for x in accs:
        flag = "" if x.get("isActive", True) and not x.get("needsReconnection") else "  ← 다시 연결 필요"
        say(f"  {x.get('platform'):10s} @{x.get('username', '')}  id={x.get('_id')}{flag}")
    if a.save:
        z = cfg["zernio"]
        for x in accs:
            if not x.get("isActive", True) or x.get("needsReconnection"):
                continue
            if x.get("platform") == "instagram" and (x.get("username") == z["instagram_username"] or not z["instagram_account_id"]):
                z["instagram_account_id"], z["instagram_username"] = x["_id"], x.get("username", "")
            if x.get("platform") == "youtube" and not z["youtube_account_id"]:
                z["youtube_account_id"] = x["_id"]
        save_config(cfg)
        say("설정에 저장했습니다 (instagram:", z["instagram_account_id"] or "없음", "/ youtube:", z["youtube_account_id"] or "없음", ")")
    return 0


def z_upload_media(path, content_type="video/mp4"):
    size = os.path.getsize(path)
    st, _, d, b = z_req("POST", "/v1/media/presign", {"filename": pathlib.Path(path).name, "contentType": content_type, "size": size})
    if st != 200 or not d.get("uploadUrl") or not d.get("publicUrl"):
        raise SystemExit(f"Zernio 업로드 주소를 받지 못했습니다: {st} {redact(b[:300].decode('utf-8', 'replace'))}")
    data = pathlib.Path(path).read_bytes()
    st2, _, b2 = fetch("PUT", d["uploadUrl"], {"Content-Type": content_type, "Content-Length": str(len(data))}, data, timeout=600)
    if st2 not in (200, 201, 204):
        raise SystemExit(f"Zernio 저장소에 올리지 못했습니다: {st2} {redact(b2[:200].decode('utf-8', 'replace'))}")
    return d["publicUrl"], size


def z_post(platform, account_id, content, media_url, size, psd, idem, tags=None, publish_now=True):
    body = {"content": content, "mediaItems": [{"type": "video", "url": media_url, "mimeType": "video/mp4", "size": size}],
            "platforms": [{"platform": platform, "accountId": account_id, "platformSpecificData": psd}], "publishNow": publish_now}
    if tags:
        body["tags"] = tags
    st, _, d, b = z_req("POST", "/v1/posts", body, {"Idempotency-Key": idem})
    if st not in (200, 201, 207, 202):
        raise SystemExit(f"Zernio 게시에 실패했습니다: {st} {redact(b[:500].decode('utf-8', 'replace'))}")
    post = d.get("post") or {}
    pid = post.get("_id")
    for _ in range(30):                                   # 처리 중이면 잠깐 기다리며 상태를 본다
        plats = post.get("platforms") or []
        ent = next((p for p in plats if p.get("platform") == platform), {})
        status = ent.get("status", post.get("status", ""))
        if status in ("published", "failed", "cancelled"):
            return {"post_id": pid, "status": status, "url": ent.get("platformPostUrl") or "", "error": ent.get("errorMessage") or d.get("error") or ""}
        if not pid:
            break
        SLEEP(POLL)
        st, _, d2, b2 = z_req("GET", f"/v1/posts/{pid}")
        post = (d2.get("post") if isinstance(d2, dict) and "post" in d2 else d2) or {}
    return {"post_id": pid, "status": "processing", "url": "", "error": "아직 처리 중입니다 (history 로 나중에 다시 확인하세요)"}


# ------------------------------------------------------------------ 메타데이터
def validate_meta(m):
    errs = []
    if not isinstance(m, dict):
        return ["publish.json 이 객체가 아닙니다"]
    t = m.get("title", "")
    if not t.strip():
        errs.append("title 이 비어 있습니다")
    if len(t) > 100:
        errs.append(f"title 이 {len(t)}자입니다 (유튜브 100자 제한)")
    for k in ("title", "description"):
        if re.search(r"[<>]", m.get(k, "")):
            errs.append(f"{k} 에 < > 가 있습니다 (유튜브가 거부합니다)")
    tags = m.get("tags", [])
    if sum(len(x) + 1 for x in tags) > 500 or any(len(x) > 100 for x in tags):
        errs.append("tags 가 너무 깁니다 (한 개 100자, 전체 500자)")
    ht = m.get("hashtags", [])
    if len(ht) > 30:
        errs.append("해시태그가 30개를 넘습니다 (인스타그램 제한)")
    if any(not x.startswith("#") or " " in x for x in ht):
        errs.append("hashtags 는 공백 없이 # 로 시작해야 합니다")
    return errs


def youtube_description(m, cfg):
    parts = [m.get("description", "").strip()]
    src = m.get("sources") or []
    if src:
        parts.append("출처:\n" + "\n".join(f"- {s}" for s in src))
    if cfg.get("disclosure") and cfg["disclosure"] not in parts[0]:
        parts.append(cfg["disclosure"])
    if m.get("hashtags"):
        parts.append(" ".join(m["hashtags"]))
    return "\n\n".join(p for p in parts if p)[:4900]


def instagram_caption(m, cfg):
    base = (m.get("instagram_caption") or m.get("description", "")).strip()
    parts = [base]
    if cfg.get("disclosure") and cfg["disclosure"] not in base:
        parts.append(cfg["disclosure"])
    if m.get("hashtags"):
        parts.append(" ".join(m["hashtags"]))
    return "\n\n".join(p for p in parts if p)[:2200]


# ------------------------------------------------------------------ 세로 영상
def make_vertical(src, out):
    if not shutil.which("ffmpeg"):
        raise SystemExit("ffmpeg 가 없습니다 (자동 설치하지 않습니다).")
    fc = ("[0:v]split=2[bg][fg];[bg]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,boxblur=40:5,eq=brightness=-0.06[b];"
          "[fg]scale=1080:-2[f];[b][f]overlay=(W-w)/2:(H-h)/2,format=yuv420p[v]")
    r = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(src), "-filter_complex", fc, "-map", "[v]", "-map", "0:a?",
                        "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-r", "30", "-c:a", "aac", "-b:a", "160k", "-ar", "48000",
                        "-movflags", "+faststart", str(out)], capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit("세로 영상을 만들지 못했습니다: " + r.stderr[-400:])
    return out


def cmd_vertical(a):
    src = pathlib.Path(a.video)
    out = pathlib.Path(a.out) if a.out else src.with_name(src.stem + "-vertical.mp4")
    if out.exists() and not a.force:
        say("이미 있습니다:", out)
        return 0
    make_vertical(src, out)
    say("만들었습니다:", out)
    return 0


# ------------------------------------------------------------------ 게시
def ledger_rows():
    p = HOME / "published.jsonl"
    rows = []
    try:
        for line in p.read_text(encoding="utf-8").splitlines():
            try:
                rows.append(json.loads(line))
            except ValueError:
                pass
    except OSError:
        pass
    return rows


def ledger_add(row):
    HOME.mkdir(parents=True, exist_ok=True)
    with open(HOME / "published.jsonl", "a", encoding="utf-8") as f:
        f.write(json.dumps(row, ensure_ascii=False) + "\n")
    try:
        os.chmod(HOME / "published.jsonl", 0o600)
    except OSError:
        pass


def prerequisites(cfg, platforms):
    miss = []
    if "youtube" in platforms:
        if not google_client():
            miss.append("유튜브: 구글 클라이언트 없음 → google-import")
        if not read_json(HOME / "youtube_token.json"):
            miss.append("유튜브: 로그인 안 됨 → youtube-auth")
    z = cfg["zernio"]
    if "instagram" in platforms or "youtube_via_zernio" in platforms:
        if not zernio_key():
            miss.append("Zernio: API 키 없음 → zernio-key")
    if "instagram" in platforms and not z["instagram_account_id"]:
        miss.append("인스타그램: Zernio 계정 id 없음 → init --instagram-account 또는 zernio-accounts --save")
    if "youtube_via_zernio" in platforms and not z["youtube_account_id"]:
        miss.append("Zernio 유튜브: 계정 id 없음 (Zernio 대시보드에서 유튜브 채널을 연결한 뒤 zernio-accounts --save)")
    return miss


def cmd_publish(a):
    cfg = load_config()
    video = pathlib.Path(a.video)
    if not video.is_file():
        say("영상 파일이 없습니다:", video)
        return 20
    meta = read_json(a.meta)
    errs = validate_meta(meta)
    if errs:
        say("publish.json 검사 실패:\n  - " + "\n  - ".join(errs))
        return 20
    platforms = [p.strip() for p in a.to.split(",") if p.strip()]
    bad = [p for p in platforms if p not in ("youtube", "instagram", "youtube_via_zernio")]
    if bad or not platforms:
        say("--to 는 youtube, instagram, youtube_via_zernio 중에서 쉼표로:", bad)
        return 20
    miss = prerequisites(cfg, platforms)
    digest = sha256_file(video)
    done = {(r["sha256"], r["platform"]): r for r in ledger_rows() if r.get("ok")}
    dup = [p for p in platforms if (digest, p) in done]
    if dup and not a.force_repost:
        for p in dup:
            r = done[(digest, p)]
            say(f"이미 올린 영상입니다 ({p}): {r.get('url') or r.get('id')} ({r.get('ts')}). 다시 올리려면 사용자 확인 후 --force-repost")
        return 20
    yt = cfg["youtube"]
    vertical = video.with_name(video.stem + "-vertical.mp4")
    plan = [f"파일: {video.name} ({os.path.getsize(video) / 1e6:.1f} MB, {digest[:12]}…)", f"제목: {meta['title']}"]
    if "youtube" in platforms:
        plan.append(f"유튜브(직접): {yt['account_email']} / 공개 범위 요청: {yt['visibility']} / 카테고리 {yt['category_id']} / 아동용 {'예' if yt['made_for_kids'] else '아니오'}"
                    " ※ 구글 감사를 받지 않은 프로젝트는 결과가 '비공개'로 고정될 수 있습니다")
    if "youtube_via_zernio" in platforms:
        plan.append(f"유튜브(Zernio 경유): 계정 {cfg['zernio']['youtube_account_id']} / 공개 범위 요청: {yt['visibility']}")
    if "instagram" in platforms:
        plan.append(f"인스타그램 릴스(Zernio): @{cfg['zernio']['instagram_username']} / 세로(9:16) 영상 {vertical.name} {'사용' if vertical.exists() else '새로 만듦'} / 피드에도 표시 {'예' if cfg['zernio']['share_to_feed'] else '아니오'}")
    if any(p in platforms for p in ("youtube", "youtube_via_zernio")):
        plan.append("유튜브 설명:\n    " + youtube_description(meta, cfg).replace("\n", "\n    "))
    if "instagram" in platforms:
        plan.append("인스타 캡션:\n    " + instagram_caption(meta, cfg).replace("\n", "\n    "))
    say("=== 올릴 내용 ===\n" + "\n".join(plan))
    if miss:
        say("\n설정이 부족합니다:\n  - " + "\n  - ".join(miss))
        return 0 if a.dry_run else 20
    if a.dry_run:
        say("(--dry-run: 아무것도 올리지 않았습니다)")
        return 0
    if cfg.get("confirm_each", True) and not a.confirmed:
        say("\n사용자에게 위 내용을 보여 주고 '올려도 되는지' 확인받은 뒤, 같은 명령에 --confirmed 를 붙여 다시 실행하세요.")
        return 10
    rc = 0
    for p in platforms:
        try:
            row = _publish_one(p, cfg, video, vertical, meta, digest)
        except SystemExit as e:
            row = {"ok": False, "error": str(e)}
        row.update({"ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "sha256": digest, "platform": p, "title": meta["title"], "file": video.name})
        ledger_add(row)
        if row["ok"]:
            say(f"[{p}] 완료: {row.get('url') or row.get('id')}" + (f"  ({row['note']})" if row.get("note") else ""))
        else:
            say(f"[{p}] 실패: {row.get('error')}")
            rc = 1
    return rc


def _publish_one(p, cfg, video, vertical, meta, digest):
    yt = cfg["youtube"]
    z = cfg["zernio"]
    if p == "youtube":
        body = {"snippet": {"title": meta["title"], "description": youtube_description(meta, cfg), "tags": meta.get("tags", []),
                            "categoryId": yt["category_id"], "defaultLanguage": yt["language"], "defaultAudioLanguage": yt["language"]},
                "status": {"privacyStatus": yt["visibility"], "selfDeclaredMadeForKids": bool(yt["made_for_kids"]),
                           "containsSyntheticMedia": bool(yt["contains_synthetic_media"]), "embeddable": True}}
        d = yt_upload(str(video), body, notify=bool(yt["notify_subscribers"]))
        vid = d.get("id", "")
        actual = (d.get("status") or {}).get("privacyStatus", "")
        note = ""
        if actual and actual != yt["visibility"]:
            note = f"요청 {yt['visibility']} → 실제 {actual}: 구글 감사를 받지 않은 프로젝트의 제한입니다. 유튜브 스튜디오에서 직접 공개로 바꾸거나 감사를 신청하세요"
        return {"ok": bool(vid), "id": vid, "url": f"https://youtu.be/{vid}" if vid else "", "privacy": actual, "note": note, "error": "" if vid else "응답에 영상 id 가 없습니다"}
    if p == "instagram":
        if not vertical.exists():
            say("세로 영상 만드는 중…")
            make_vertical(video, vertical)
        url, size = z_upload_media(vertical)
        idem = _idem(digest, p)
        r = z_post("instagram", z["instagram_account_id"], instagram_caption(meta, cfg), url, size,
                   {"shareToFeed": bool(z["share_to_feed"])}, idem)
        return {"ok": r["status"] == "published", "id": r["post_id"], "url": r["url"], "error": r["error"], "note": "" if r["status"] == "published" else r["status"]}
    if p == "youtube_via_zernio":
        url, size = z_upload_media(video)
        idem = _idem(digest, p)
        r = z_post("youtube", z["youtube_account_id"], youtube_description(meta, cfg), url, size,
                   {"title": meta["title"], "visibility": yt["visibility"], "madeForKids": bool(yt["made_for_kids"]), "categoryId": yt["category_id"],
                    "containsSyntheticMedia": bool(yt["contains_synthetic_media"]), "notifySubscribers": bool(yt["notify_subscribers"])}, idem, tags=meta.get("tags"))
        return {"ok": r["status"] == "published", "id": r["post_id"], "url": r["url"], "error": r["error"], "note": "" if r["status"] == "published" else r["status"]}
    raise SystemExit("알 수 없는 플랫폼")


def _idem(digest, platform):
    """같은 영상·플랫폼은 24시간 안에 다시 시도해도 같은 키를 써서, 끊겼다 다시 해도 두 번 올라가지 않게 한다."""
    p = HOME / "idempotency.json"
    d = read_json(p, {})
    k = f"{digest}:{platform}"
    now = time.time()
    d = {kk: vv for kk, vv in d.items() if now - vv["t"] < 20 * 3600}
    if k not in d:
        d[k] = {"key": str(uuid.uuid4()), "t": now}
    write_private(p, json.dumps(d))
    return d[k]["key"]


# ------------------------------------------------------------------ 기타 명령
def cmd_init(a):
    cfg = load_config()
    for attr, sec, key in (("youtube_account", "youtube", "account_email"), ("gcp_project", "youtube", "gcp_project"), ("zernio_profile", "zernio", "profile_id"),
                           ("instagram_account", "zernio", "instagram_account_id"), ("instagram_username", "zernio", "instagram_username"),
                           ("zernio_youtube_account", "zernio", "youtube_account_id")):
        v = getattr(a, attr)
        if v:
            cfg[sec][key] = v.strip().lstrip("@") if key == "instagram_username" else v.strip()
    save_config(cfg)
    say("저장했습니다:", HOME / "config.json")
    return cmd_status(a)


def cmd_status(a):
    cfg = load_config()
    z, y = cfg["zernio"], cfg["youtube"]
    tk = read_json(HOME / "youtube_token.json") or {}
    cl = google_client()
    say(f"설정 위치: {HOME}")
    say(f"유튜브  계정 {y['account_email'] or '(미설정)'} / 프로젝트 {y['gcp_project'] or '(미설정)'} / 클라이언트 {'있음' if cl else '없음'} / 로그인 {tk.get('email') or '안 됨'}")
    say(f"인스타  Zernio 프로필 {z['profile_id'] or '(미설정)'} / 계정 id {z['instagram_account_id'] or '(미설정)'} / @{z['instagram_username'] or '(미설정)'} / API 키 {'있음' if zernio_key() else '없음'}")
    say(f"규칙    올리기 전 확인 {'매번' if cfg['confirm_each'] else '끔(사용자가 허락함)'} / 유튜브 공개 범위 요청 {y['visibility']} / 아동용 {'예' if y['made_for_kids'] else '아니오'}")
    todo = []
    if not y["account_email"]:
        todo.append("init --youtube-account …")
    if not cl:
        todo.append("google-import --file …")
    if not tk:
        todo.append("youtube-auth")
    if not zernio_key():
        todo.append("zernio-key --from-clipboard")
    if not z["instagram_account_id"]:
        todo.append("zernio-accounts --save")
    say("남은 일: " + (", ".join(todo) if todo else "없음 (올릴 준비 끝)"))
    return 0


def cmd_set_config(a):
    if a.key not in SETTABLE:
        say("바꿀 수 있는 키:", ", ".join(SETTABLE))
        return 20
    opts = SETTABLE[a.key]
    if opts and a.value not in opts:
        say(f"{a.key} 는 {'/'.join(opts)} 중 하나:", a.value)
        return 20
    if a.key == "confirm_each" and a.value == "false" and not a.user_confirmed:
        say("올리기 전 확인을 끄는 것은 사용자가 직접 허락했을 때만 합니다. 허락을 받은 뒤 --user-confirmed 를 붙이세요.")
        return 20
    cfg = load_config()
    val = (a.value == "true") if opts == ("true", "false") else a.value
    if "." in a.key:
        s, k = a.key.split(".")
        cfg[s][k] = val
    else:
        cfg[a.key] = val
    save_config(cfg)
    say("바꿨습니다:", a.key, "=", val)
    return 0


def cmd_history(a):
    rows = ledger_rows()
    if not rows:
        say("올린 기록이 없습니다.")
        return 0
    for r in rows[-20:]:
        say(f"{r.get('ts', '')[:19]} {r.get('platform'):18s} {'성공' if r.get('ok') else '실패'}  {r.get('title', '')[:30]}  {r.get('url') or r.get('error', '')}")
    return 0


# ------------------------------------------------------------------ 시험 (가짜 구글·Zernio 서버)
def selftest():
    import io, contextlib
    ok = fail = 0

    def check(name, cond):
        nonlocal ok, fail
        if cond:
            ok += 1
        else:
            fail += 1
            say("실패:", name)

    state = {"yt": {}, "z": {"posts": {}, "calls": []}, "uploads": 0, "fail_once": True, "email": "me@example.com", "zkey": "", "challenge": ""}
    VID = os.urandom(600 * 1024)

    class H(http.server.BaseHTTPRequestHandler):
        def _base(self):
            return f"http://127.0.0.1:{self.server.server_address[1]}"

        def _send(self, code, obj=None, headers=None):
            body = json.dumps(obj).encode() if obj is not None else b""
            self.send_response(code)
            for k, v in (headers or {}).items():
                self.send_header(k, v)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _body(self):
            n = int(self.headers.get("Content-Length") or 0)
            return self.rfile.read(n) if n else b""

        def do_POST(self):
            b = self._body()
            u = urllib.parse.urlsplit(self.path)
            if u.path == "/google/token":
                f = urllib.parse.parse_qs(b.decode())
                if f.get("grant_type") == ["authorization_code"]:
                    v = (f.get("code_verifier") or [""])[0]
                    chal = base64.urlsafe_b64encode(hashlib.sha256(v.encode()).digest()).decode().rstrip("=")
                    if f.get("code") != ["GOODCODE"] or not v or (state["challenge"] and chal != state["challenge"]):
                        return self._send(400, {"error": "invalid_grant"})
                    seg = base64.urlsafe_b64encode(json.dumps({"email": state["email"]}).encode()).decode().rstrip("=")
                    return self._send(200, {"access_token": "ya29.TEST", "refresh_token": "1//REFRESH", "id_token": f"x.{seg}.y", "scope": SCOPES})
                if f.get("refresh_token") == ["1//REFRESH"]:
                    return self._send(200, {"access_token": "ya29.TEST", "expires_in": 3599})
                return self._send(400, {"error": "invalid_grant"})
            if u.path == "/yt/upload/youtube/v3/videos":
                if self.headers.get("Authorization") != "Bearer ya29.TEST":
                    return self._send(401, {"error": {"message": "bad token"}})
                state["yt"].update(meta=json.loads(b.decode("utf-8")), query=urllib.parse.parse_qs(u.query),
                                   hdr={k.lower(): v for k, v in self.headers.items()}, got=bytearray())
                return self._send(200, headers={"Location": self._base() + "/yt/session/1"})
            if u.path == "/zernio/v1/media/presign":
                if self.headers.get("Authorization") != "Bearer " + state["zkey"]:
                    return self._send(401, {"error": "unauthorized"})
                j = json.loads(b)
                state["z"]["presign"] = j
                return self._send(200, {"uploadUrl": f"{self._base()}/zstore/{j['filename']}", "publicUrl": f"https://cdn.example/{j['filename']}", "expiresIn": 3600})
            if u.path == "/zernio/v1/posts":
                if self.headers.get("Authorization") != "Bearer " + state["zkey"]:
                    return self._send(401, {"error": "unauthorized"})
                j = json.loads(b)
                state["z"]["calls"].append({"body": j, "idem": self.headers.get("Idempotency-Key")})
                ent = j["platforms"][0]
                pid = "post" + str(len(state["z"]["posts"]) + 1)
                state["z"]["posts"][pid] = {"polls": 0, "platform": ent["platform"], "mode": state.get("zmode", "ok")}
                return self._send(201, {"post": {"_id": pid, "status": "publishing", "platforms": [{"platform": ent["platform"], "status": "processing"}]}})
            self._send(404, {"error": "nope"})

        def do_PUT(self):
            b = self._body()
            u = urllib.parse.urlsplit(self.path)
            if u.path.startswith("/zstore/"):
                state["uploads"] += 1
                state["z"]["stored"] = len(b)
                return self._send(200)
            if u.path == "/yt/session/1":
                cr = self.headers.get("Content-Range", "")
                got = state["yt"]["got"]
                if cr.startswith("bytes */"):                                   # 어디까지 받았는지 묻는 요청
                    state["yt"]["status_queries"] = state["yt"].get("status_queries", 0) + 1
                    return self._send(308, headers={"Range": f"bytes=0-{len(got) - 1}"} if got else None)
                m = re.match(r"bytes (\d+)-(\d+)/(\d+)", cr)
                s0, e0, t0 = int(m.group(1)), int(m.group(2)), int(m.group(3))
                if state["fail_once"] and s0 > 0:                               # 두 번째 조각에서 한 번 실패
                    state["fail_once"] = False
                    return self._send(503, {"error": "backend"})
                if s0 != len(got):
                    return self._send(400, {"error": "range mismatch"})
                got.extend(b)
                if e0 + 1 < t0:
                    return self._send(308, headers={"Range": f"bytes=0-{e0}"})
                st = "private" if state.get("force_private") else state["yt"]["meta"]["status"]["privacyStatus"]
                return self._send(200, {"id": "VID123", "status": {"privacyStatus": st, "uploadStatus": "uploaded"}})
            self._send(404, {"error": "nope"})

        def do_GET(self):
            u = urllib.parse.urlsplit(self.path)
            if u.path == "/zernio/v1/accounts":
                return self._send(200, {"accounts": [{"_id": "ACC_IG", "platform": "instagram", "username": "charles_test", "isActive": True},
                                                      {"_id": "ACC_YT", "platform": "youtube", "username": "ch", "isActive": True, "needsReconnection": True}]})
            m = re.match(r"/zernio/v1/posts/(\w+)", u.path)
            if m and m.group(1) in state["z"]["posts"]:
                p = state["z"]["posts"][m.group(1)]
                p["polls"] += 1
                if p["polls"] < 2:
                    return self._send(202, {"post": {"_id": m.group(1), "status": "publishing", "platforms": [{"platform": p["platform"], "status": "processing"}]}})
                if p["mode"] == "fail":
                    return self._send(200, {"post": {"_id": m.group(1), "status": "failed", "platforms": [{"platform": p["platform"], "status": "failed", "errorMessage": "video too long"}]}})
                return self._send(200, {"post": {"_id": m.group(1), "status": "published", "platforms": [{"platform": p["platform"], "status": "published", "platformPostUrl": "https://instagram.com/reel/XYZ"}]}})
            self._send(404, {"error": "nope"})

        def log_message(self, *a):
            pass

    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{port}"
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="publish-selftest-"))
    env = dict(os.environ, JARVIS_PUBLISH_HOME=str(tmp / "home"), JARVIS_PUBLISH_TEST_BASE=base, JARVIS_PUBLISH_CHUNK="262144",
               JARVIS_PUBLISH_POLL="0.05", JARVIS_PUBLISH_BACKOFF="0.01")
    env.pop("ZERNIO_API_KEY", None)
    global HOME
    saved_home, saved_urls = HOME, dict(URLS)
    HOME = tmp / "home"                                       # 이 프로세스 안에서 부르는 함수도 같은 임시 폴더를 쓴다
    URLS.update({"token": base + "/google/token", "yt_upload": base + "/yt/upload/youtube/v3/videos", "zernio": base + "/zernio"})

    def run(*args, stdin=None):
        r = subprocess.run([sys.executable, "-I", os.path.abspath(__file__), *args], capture_output=True, text=True, env=env, input=stdin, timeout=180)
        return r.returncode, r.stdout + r.stderr

    try:
        # --- 순수 함수
        check("redact: ya29 토큰 가림", "abcDEF" not in redact("Bearer ya29.abcDEF-123"))
        check("redact: sk_ 키 가림", "sk_" + "a" * 64 not in redact("key sk_" + "a" * 64))
        check("redact: refresh_token 가림", "SECRET" not in redact('{"refresh_token": "SECRET"}'))
        m_ok = {"title": "제목", "description": "설명", "tags": ["a", "b"], "hashtags": ["#a", "#b"]}
        check("메타 검사 통과", validate_meta(m_ok) == [])
        check("제목 100자 초과 거부", any("100자" in e for e in validate_meta({**m_ok, "title": "가" * 101})))
        check("< > 거부", any("< >" in e for e in validate_meta({**m_ok, "description": "a<b"})))
        check("해시태그 형식 거부", any("hashtags" in e for e in validate_meta({**m_ok, "hashtags": ["abc"]})))
        check("해시태그 31개 거부", any("30개" in e for e in validate_meta({**m_ok, "hashtags": ["#a"] * 31})))
        cfg = deep_merge(DEFAULT_CONFIG, {})
        d = youtube_description({**m_ok, "sources": ["https://x.example/a"]}, cfg)
        check("설명에 출처·고지·해시태그", "https://x.example/a" in d and "AI 합성 음성" in d and d.rstrip().endswith("#a #b"))
        check("캡션 2200자 제한", len(instagram_caption({**m_ok, "description": "가" * 3000}, cfg)) <= 2200)

        # --- 설정 단계
        video = tmp / "v.mp4"
        video.write_bytes(VID)
        meta = tmp / "publish.json"
        meta.write_text(json.dumps({"title": "문어의 심장은 세 개", "description": "설명 줄", "tags": ["문어"], "hashtags": ["#지식공유", "#문어"],
                                    "sources": ["https://example.org/s"]}, ensure_ascii=False), encoding="utf-8")
        rc, out = run("status")
        check("처음엔 남은 일이 보인다", rc == 0 and "google-import" in out and "youtube-auth" in out)
        rc, out = run("publish", "--video", str(video), "--meta", str(meta), "--to", "youtube", "--confirmed")
        check("설정 없이는 거부(20)", rc == 20 and "설정이 부족" in out)
        rc, out = run("init", "--youtube-account", "me@example.com", "--zernio-profile", "PROF", "--instagram-account", "ACC_IG", "--instagram-username", "@charles_test")
        c0 = read_json(tmp / "home" / "config.json", {})
        check("init 저장(@ 제거)", rc == 0 and c0["youtube"]["account_email"] == "me@example.com" and c0["zernio"]["instagram_username"] == "charles_test")
        cj = tmp / "client.json"
        cj.write_text(json.dumps({"installed": {"client_id": "CID.apps.googleusercontent.com", "client_secret": "SECRETVALUE", "project_id": "proj-1"}}), encoding="utf-8")
        rc, out = run("google-import", "--file", str(cj), "--delete-source")
        check("클라이언트 가져오기, 비밀번호 출력 안 함, 원본 삭제", rc == 0 and "SECRETVALUE" not in out and not cj.exists())
        wj = tmp / "web.json"
        wj.write_text(json.dumps({"web": {"client_id": "a", "client_secret": "b"}}), encoding="utf-8")
        check("web 형식 거부", run("google-import", "--file", str(wj))[0] == 20)
        if os.name != "nt":
            check("클라이언트 파일 권한 600", ((tmp / "home" / "google_client.json").stat().st_mode & 0o777) == 0o600)

        # --- 구글 로그인
        cl = google_client()
        check("클라이언트 읽기", bool(cl) and cl["client_id"].startswith("CID"))
        state["email"] = "someone.else@example.com"
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = _finish_auth(cl, "GOODCODE", "verifier", "http://127.0.0.1:1", "me@example.com")
        check("다른 계정 로그인은 거부(20), 토큰 저장 안 함", rc == 20 and "설정과 다릅니다" in buf.getvalue() and not (HOME / "youtube_token.json").exists())
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = _finish_auth(cl, "BADCODE", "verifier", "http://127.0.0.1:1", "me@example.com")
        check("잘못된 코드는 실패(1)", rc == 1)

        def auth_flow(code, bad_state=False):
            pr = subprocess.Popen([sys.executable, "-I", os.path.abspath(__file__), "youtube-auth", "--no-browser", "--timeout", "40"],
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env)
            url = ""
            for line in pr.stdout:
                m = re.search(r"주소: (\S+)", line)
                if m:
                    url = m.group(1)
                    break
            q = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)
            state["challenge"] = q.get("code_challenge", [""])[0]
            red = q["redirect_uri"][0]
            st_val = "WRONG" if bad_state else q["state"][0]
            urllib.request.urlopen(f"{red}/?code={code}&state={st_val}", timeout=10).read()
            rest = pr.stdout.read()
            pr.wait(timeout=30)
            return pr.returncode, rest, q
        state["email"] = "me@example.com"
        rc, rest, q = auth_flow("GOODCODE", bad_state=True)
        check("state 가 다르면 거부(1)", rc == 1 and "state" in rest and not (HOME / "youtube_token.json").exists())
        rc, rest, q = auth_flow("GOODCODE")
        check("로그인 흐름 성공(PKCE S256 확인), 토큰 저장", rc == 0 and (HOME / "youtube_token.json").exists() and "REFRESH" not in rest)
        check("요청 범위가 업로드 하나(+이메일)", q["scope"][0] == SCOPES and q["code_challenge_method"] == ["S256"] and q["login_hint"] == ["me@example.com"]
              and q["access_type"] == ["offline"])
        state["challenge"] = ""

        # --- Zernio 키
        rc, out = run("zernio-key", "--stdin", stdin="not-a-key\n")
        check("키 모양이 다르면 저장 안 함", rc == 20 and not (tmp / "home" / "zernio_key").exists())
        key = "sk_" + "ab12" * 16
        state["zkey"] = key
        rc, out = run("zernio-key", "--stdin", stdin=key + "\n")
        check("키 저장, 값은 출력 안 함", rc == 0 and key not in out and (tmp / "home" / "zernio_key").exists())
        rc, out = run("zernio-accounts", "--save")
        c1 = read_json(tmp / "home" / "config.json")["zernio"]
        check("계정 목록, 다시 연결 필요 표시", rc == 0 and "charles_test" in out and "다시 연결 필요" in out)
        check("설정 저장: 인스타만, 다시 연결 필요한 유튜브는 저장 안 함", c1["instagram_account_id"] == "ACC_IG" and c1["youtube_account_id"] == "")

        # --- 확인 단계와 올리기
        rc, out = run("publish", "--video", str(video), "--meta", str(meta), "--to", "youtube,instagram", "--dry-run")
        check("dry-run: 계획만 출력", rc == 0 and "올릴 내용" in out and not ledger_rows() and state["yt"].get("meta") is None)
        rc, out = run("publish", "--video", str(video), "--meta", str(meta), "--to", "youtube")
        check("--confirmed 없으면 10, 아무것도 안 올림", rc == 10 and "--confirmed" in out and state["yt"].get("meta") is None)
        rc, out = run("publish", "--video", str(video), "--meta", str(meta), "--to", "youtube", "--confirmed")
        check("유튜브 업로드 성공(조각 나눔·실패 후 이어받기)", rc == 0 and "https://youtu.be/VID123" in out)
        check("실패 뒤 상태를 물어보고 이어서 올림", state["yt"].get("status_queries", 0) >= 1)
        check("서버가 받은 바이트가 원본과 같다", bytes(state["yt"].get("got", b"")) == VID)
        mt = state["yt"].get("meta", {})
        check("메타데이터 전달", mt.get("snippet", {}).get("title") == "문어의 심장은 세 개" and mt["status"]["privacyStatus"] == "public"
              and mt["status"]["selfDeclaredMadeForKids"] is False and "AI 합성 음성" in mt["snippet"]["description"] and mt["snippet"]["categoryId"] == "27")
        check("업로드 시작 헤더(길이·형식)와 part", state["yt"]["hdr"].get("x-upload-content-length") == str(len(VID))
              and state["yt"]["hdr"].get("x-upload-content-type") == "video/mp4" and state["yt"]["query"]["part"] == ["snippet,status"]
              and state["yt"]["query"]["uploadType"] == ["resumable"])
        check("기록에 남음", any(r["platform"] == "youtube" and r["ok"] for r in ledger_rows()))
        rc, out = run("publish", "--video", str(video), "--meta", str(meta), "--to", "youtube", "--confirmed")
        check("같은 영상 중복 거부(20)", rc == 20 and "이미 올린" in out)
        state["force_private"], state["fail_once"] = True, False
        rc, out = run("publish", "--video", str(video), "--meta", str(meta), "--to", "youtube", "--confirmed", "--force-repost")
        check("비공개로 고정되면 알려 줌", rc == 0 and "실제 private" in out)
        state["force_private"] = False
        rc, out = run("set-config", "confirm_each", "false")
        check("확인 끄기는 허락 없이 거부", rc == 20 and read_json(tmp / "home" / "config.json").get("confirm_each", True) is True)

        have_ff = bool(shutil.which("ffmpeg"))
        vf = tmp / "real.mp4"
        if have_ff:
            subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=1920x1080:rate=30:duration=2",
                            "-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", str(vf)], check=True)
            rc, out = run("vertical", str(vf))
            vv = tmp / "real-vertical.mp4"
            pr = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,width,height", "-of", "csv=p=0", str(vv)], capture_output=True, text=True).stdout if vv.exists() else ""
            check("세로 영상 1080x1920, 소리 유지", rc == 0 and "video,1080,1920" in pr and "audio" in pr)
        else:
            vf.write_bytes(VID)
            (tmp / "real-vertical.mp4").write_bytes(VID)
        rc, out = run("publish", "--video", str(vf), "--meta", str(meta), "--to", "instagram", "--confirmed")
        check("인스타 게시 성공(처리 중이면 기다림)", rc == 0 and "https://instagram.com/reel/XYZ" in out)
        c = state["z"]["calls"][-1]
        b = c["body"]
        check("Zernio 요청 형식", b["publishNow"] is True and b["platforms"][0]["platform"] == "instagram" and b["platforms"][0]["accountId"] == "ACC_IG"
              and b["mediaItems"][0]["type"] == "video" and b["mediaItems"][0]["url"].startswith("https://cdn.example/")
              and b["platforms"][0]["platformSpecificData"]["shareToFeed"] is True)
        check("캡션에 해시태그·고지", "#지식공유 #문어" in b["content"] and "AI 합성 음성" in b["content"])
        check("Idempotency-Key 전달", bool(c["idem"]) and len(c["idem"]) >= 30)
        check("presign: mp4, 세로 파일 이름", state["z"]["presign"]["contentType"] == "video/mp4" and state["z"]["presign"]["filename"].endswith("-vertical.mp4"))
        check("Zernio 저장소에 올림", state["uploads"] >= 1 and state["z"]["stored"] > 0)
        rc, out = run("publish", "--video", str(vf), "--meta", str(meta), "--to", "instagram", "--confirmed")
        check("인스타 중복 거부(20)", rc == 20)
        state["zmode"] = "fail"
        rc, out = run("publish", "--video", str(vf), "--meta", str(meta), "--to", "instagram", "--confirmed", "--force-repost")
        check("플랫폼이 거절하면 실패(1)와 이유", rc == 1 and "video too long" in out)
        state["zmode"] = "ok"
        rc, out = run("publish", "--video", str(video), "--meta", str(meta), "--to", "youtube_via_zernio", "--confirmed")
        check("Zernio 유튜브 계정 없으면 거부(20)", rc == 20 and "유튜브" in out)
        rc, out = run("history")
        check("기록 보기", rc == 0 and "instagram" in out and "youtube" in out and "실패" in out)
        rc, out = run("status")
        check("status 에 비밀 값이 없다", rc == 0 and key not in out and "SECRETVALUE" not in out and "REFRESH" not in out and "올릴 준비 끝" in out)
        rc, out = run("set-config", "confirm_each", "false", "--user-confirmed")
        rc2, out2 = run("publish", "--video", str(video), "--meta", str(meta), "--to", "youtube", "--force-repost")
        check("허락받아 확인을 끄면 --confirmed 없이 진행", rc == 0 and rc2 in (0, 1) and "올릴 내용" in out2 and "--confirmed 를 붙여" not in out2)
        check("루프백 외 시험 주소는 환경변수로도 거부", subprocess.run([sys.executable, "-I", os.path.abspath(__file__), "status"], capture_output=True, text=True,
                                                                  env=dict(env, JARVIS_PUBLISH_TEST_BASE="http://evil.example:80")).returncode != 0)
    finally:
        srv.shutdown()
        HOME = saved_home
        URLS.update(saved_urls)
        shutil.rmtree(tmp, ignore_errors=True)
    say(f"selftest: {ok}개 통과, {fail}개 실패")
    return 1 if fail else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status")
    p = sub.add_parser("init")
    for n in ("youtube-account", "gcp-project", "zernio-profile", "instagram-account", "instagram-username", "zernio-youtube-account"):
        p.add_argument("--" + n)
    p = sub.add_parser("google-import")
    p.add_argument("--file", required=True)
    p.add_argument("--delete-source", action="store_true")
    p = sub.add_parser("youtube-auth")
    p.add_argument("--no-browser", action="store_true")
    p.add_argument("--timeout", type=int, default=300)
    p = sub.add_parser("zernio-key")
    p.add_argument("--from-clipboard", action="store_true")
    p.add_argument("--stdin", action="store_true")
    p.add_argument("--clear-clipboard", action="store_true")
    p = sub.add_parser("zernio-accounts")
    p.add_argument("--save", action="store_true")
    p = sub.add_parser("vertical")
    p.add_argument("video")
    p.add_argument("--out")
    p.add_argument("--force", action="store_true")
    p = sub.add_parser("publish")
    p.add_argument("--video", required=True)
    p.add_argument("--meta", required=True)
    p.add_argument("--to", required=True)
    p.add_argument("--confirmed", action="store_true")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--force-repost", action="store_true")
    sub.add_parser("history")
    p = sub.add_parser("set-config")
    p.add_argument("key")
    p.add_argument("value")
    p.add_argument("--user-confirmed", action="store_true")
    sub.add_parser("selftest")
    a = ap.parse_args()
    if a.cmd == "selftest":
        sys.exit(selftest())
    fn = {"status": cmd_status, "init": cmd_init, "google-import": cmd_google_import, "youtube-auth": cmd_youtube_auth, "zernio-key": cmd_zernio_key,
          "zernio-accounts": cmd_zernio_accounts, "vertical": cmd_vertical, "publish": cmd_publish, "history": cmd_history, "set-config": cmd_set_config}[a.cmd]
    sys.exit(fn(a))


if __name__ == "__main__":
    main()
