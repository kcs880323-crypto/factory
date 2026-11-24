#!/usr/bin/env python3
"""자비스 영상일꾼 & 영상 퀄리티 R&D 모니터 (로컬 데스크톱 앱 서버).

표준 라이브러리만 사용합니다 (추가 패키지 설치 불필요).
실행:
  python app.py                # 로컬 서버 실행 + 크롬/엣지 앱 창 자동 열기
  python app.py --no-browser   # 서버만 실행 (테스트/디버그용)

보안 원칙 (DEC-002):
  - 로컬 일꾼 폴더는 읽기 전용으로 output/videos/*.mp4 메타데이터(파일명·크기·시각)만 확인합니다.
  - worker_config.json, .deevid_config.json, .env, collect-chrome/ 쿠키는 절대 읽지 않습니다.
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import threading
import time
import webbrowser
from datetime import datetime
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

APP_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = APP_DIR.parents[1]


def _read_text(path: pathlib.Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return ""


def parse_improvements() -> list[dict[str, Any]]:
    """coupang-partners-app/src/data/improvements.ts 에서 개선 이력 항목을 파싱."""
    ts_path = REPO_ROOT / "coupang-partners-app" / "src" / "data" / "improvements.ts"
    raw = _read_text(ts_path)
    if not raw:
        return []
    m = re.search(r"export const IMPROVEMENTS[^=]*=\s*\[(.*)\];", raw, re.S)
    if not m:
        return []
    body = m.group(1)
    entries: list[dict[str, Any]] = []
    for block in re.findall(r"\{([^{}]+)\}", body):
        item: dict[str, Any] = {}
        for key in ("id", "date", "category", "target", "title", "summary", "status", "owner"):
            km = re.search(rf"\b{key}\s*:\s*\n?\s*'([^']*)'", block)
            if not km:
                km = re.search(rf'\b{key}\s*:\s*\n?\s*"([^"]*)"', block)
            if km:
                item[key] = km.group(1).strip()
        if item.get("id") and item.get("title"):
            entries.append(item)
    entries.sort(key=lambda x: x.get("date", ""), reverse=True)
    return entries


def parse_markdown_table(text: str, heading_keyword: str) -> list[dict[str, str]]:
    """특정 제목 아래의 첫 번째 마크다운 표를 dict 목록으로 파싱."""
    lines = text.splitlines()
    in_section = False
    table_lines: list[str] = []
    for line in lines:
        if line.startswith("#") and heading_keyword in line:
            in_section = True
            continue
        if in_section:
            if line.startswith("#") and table_lines:
                break
            stripped = line.strip()
            if stripped.startswith("|") and stripped.endswith("|"):
                table_lines.append(stripped)
            elif table_lines:
                break
    if len(table_lines) < 3:
        return []
    headers = [c.strip().strip("*") for c in table_lines[0].strip("|").split("|")]
    rows: list[dict[str, str]] = []
    for row_line in table_lines[2:]:
        cols = [c.strip() for c in row_line.strip("|").split("|")]
        if len(cols) < len(headers):
            cols += [""] * (len(headers) - len(cols))
        rows.append(dict(zip(headers, cols)))
    return rows


def parse_products_audit() -> dict[str, Any]:
    """docs/products.json 상품 목록 + 누락 이미지·중복 이미지 자동 무결성 점검."""
    p_path = REPO_ROOT / "docs" / "products.json"
    raw = _read_text(p_path)
    if not raw:
        return {"total": 0, "issues": [], "recent": []}
    data = json.loads(raw)
    products = data.get("products", [])
    issues: list[dict[str, Any]] = []
    seen_hashes: dict[str, int] = {}

    for p in products:
        no = p.get("no")
        title = p.get("title", "")
        img = p.get("image")
        if not img:
            issues.append({"no": no, "type": "이미지 누락", "detail": f"{no}번 '{title}'에 image 필드가 없음"})
        else:
            img_file = REPO_ROOT / "docs" / img
            if not img_file.exists():
                issues.append({"no": no, "type": "이미지 파일 없음", "detail": f"{no}번 '{img}' 파일이 저장소에 없음"})
            hm = re.search(r"-([0-9a-f]{6,})\.", img)
            if hm:
                h = hm.group(1)
                if h in seen_hashes:
                    issues.append(
                        {
                            "no": no,
                            "type": "중복 상품 의심",
                            "detail": f"{no}번과 {seen_hashes[h]}번이 동일한 이미지 해시({h})를 사용",
                        }
                    )
                else:
                    seen_hashes[h] = no

    recent = sorted(products, key=lambda x: x.get("no", 0), reverse=True)[:8]
    return {"total": len(products), "issues": issues, "recent": recent}


def scan_local_worker() -> dict[str, Any]:
    """로컬 PC 일꾼 폴더의 최근 렌더 MP4 메타데이터만 읽기 전용으로 확인 (키/쿠키 접근 금지)."""
    desktop = pathlib.Path.home() / "Desktop"
    worker_dir = pathlib.Path(
        os.environ.get(
            "JARVIS_WORKER_DIR",
            str(desktop / "영상일꾼_DESKTOP-4CMADIK" / "jarvis_video_worker_desktop-4cmadik"),
        )
    )
    rnd_copy_dir = pathlib.Path(
        os.environ.get("JARVIS_RND_COPY_DIR", str(desktop / "영상일꾼_RND복사본"))
    )

    videos_dir = worker_dir / "Affiliate_HotItems_Generator" / "output" / "videos"
    recent_videos: list[dict[str, Any]] = []
    if videos_dir.is_dir():
        mp4s = sorted(videos_dir.glob("*.mp4"), key=lambda f: f.stat().st_mtime, reverse=True)[:12]
        for f in mp4s:
            st = f.stat()
            recent_videos.append(
                {
                    "name": f.name,
                    "size_mb": round(st.st_size / (1024 * 1024), 2),
                    "updated": datetime.fromtimestamp(st.st_mtime).strftime("%Y-%m-%d %H:%M"),
                }
            )

    return {
        "worker_dir": str(worker_dir),
        "worker_connected": worker_dir.is_dir(),
        "rnd_copy_dir": str(rnd_copy_dir),
        "rnd_copy_connected": rnd_copy_dir.is_dir(),
        "recent_videos": recent_videos,
    }


def build_overview() -> dict[str, Any]:
    sync_md = _read_text(REPO_ROOT / "SYNC_BOARD.md")
    exp_md = _read_text(REPO_ROOT / "video-rnd" / "EXPERIMENT_LOG.md")

    exp_docs: list[dict[str, str]] = []
    exp_dir = REPO_ROOT / "video-rnd" / "experiments"
    if exp_dir.is_dir():
        for f in sorted(exp_dir.glob("*.md")):
            if f.name == "TEMPLATE.md":
                continue
            first_line = _read_text(f).splitlines()[0].lstrip("# ").strip() if _read_text(f) else f.name
            exp_docs.append({"file": f.name, "title": first_line})

    return {
        "generated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "repo_root": str(REPO_ROOT),
        "improvements": parse_improvements(),
        "status_board": parse_markdown_table(sync_md, "현재 R&D 현황판"),
        "decisions": parse_markdown_table(sync_md, "결정 사항 로그"),
        "r_line_experiments": parse_markdown_table(exp_md, "R 라인 (제품 릴스"),
        "k_line_experiments": parse_markdown_table(exp_md, "K 라인 (찰스 지식영상"),
        "experiment_docs": exp_docs,
        "products": parse_products_audit(),
        "worker": scan_local_worker(),
    }


class MonitorHandler(BaseHTTPRequestHandler):
    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        return

    def _send(self, code: int, content_type: str, body: bytes) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if self.path in ("/", "/index.html"):
            html_bytes = (APP_DIR / "index.html").read_bytes()
            self._send(HTTPStatus.OK, "text/html; charset=utf-8", html_bytes)
        elif self.path == "/api/overview":
            payload = json.dumps(build_overview(), ensure_ascii=False).encode("utf-8")
            self._send(HTTPStatus.OK, "application/json; charset=utf-8", payload)
        else:
            self._send(HTTPStatus.NOT_FOUND, "text/plain; charset=utf-8", b"Not Found")


def open_app_window(url: str) -> None:
    """크롬/엣지가 있으면 네이티브 앱 창(--app)으로 열고, 없으면 기본 브라우저로 엽니다."""
    candidates = [
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        shutil.which("google-chrome") or "",
        shutil.which("msedge") or "",
    ]
    for exe in candidates:
        if exe and pathlib.Path(exe).exists():
            try:
                subprocess.Popen([exe, f"--app={url}", "--window-size=1180,820"])
                return
            except OSError:
                continue
    webbrowser.open(url)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--no-browser", action="store_true")
    args = ap.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), MonitorHandler)
    url = f"http://{args.host}:{args.port}"
    print(f"자비스 영상일꾼 & R&D 모니터 실행 중: {url} (종료: Ctrl+C)")
    if not args.no_browser:
        threading.Timer(0.4, open_app_window, args=(url,)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n종료합니다.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
