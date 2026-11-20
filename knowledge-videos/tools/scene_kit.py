#!/usr/bin/env python3
"""scene_kit.py: 설계표(spec.json) 한 장으로 지식영상의 본편 그림 장면을 만든다. (build_video.py 를 직접 쓰지 않아도 된다)

  python scene_kit.py <프로젝트 폴더> <spec.json> [스킬 scripts 폴더]

입력: <프로젝트>/assets/{timing,clauses_timing,parts}.json, duration.txt, narration.wav (build_track.py 가 만든 것) 와 spec.json
출력: <프로젝트>/index.html, compositions/*.html (배경, 본편 장면, 찰스 오프닝·엔딩, 상단 칩, 자막)

오리(찰스)는 오프닝과 엔딩에만 나온다. 본편은 spec 의 장면(그림, 도표, 숫자)으로만 채운다. 장면이 없으면 만들지 않고 오류를 낸다.

spec.json 형식 (자세한 설명과 예시는 MANUAL.md 6장, examples/*/spec.json):
{
  "chip": "상단 칩에 쓸 주제 이름",
  "theme": {"bg": "#120d08", "bg2": "#2a1c0c", "accent": "#FFB81C", "accent2": "#7FD18B", "text": "#FFF4D6"},
  "scenes": [                                   # 3~5개. clauses 를 이어 붙이면 본편의 절 전부가 순서대로 한 번씩 나와야 한다
    {"type": "question", "clauses": ["1a","1b"], "headline": "…", "sub": "…", "sub_at": "1b", "symbol": "question"},
    {"type": "flow",     "clauses": [...], "nodes": [{"label": "…", "note": "…", "icon": "sun", "at": "2a"}, ...]},
    {"type": "points",   "clauses": [...], "items": [{"text": "…", "icon": "wave", "at": "3a"}, ...]},
    {"type": "compare",  "clauses": [...], "axis": "…", "left": {"title": "…", "value": 0.25, "label": "…", "at": "4a"}, "right": {...}},
    {"type": "stat",     "clauses": [...], "name": "…", "name_at": "5a", "big": "1973", "big_at": "5b", "label": "…", "label_at": "5b",
     "recap": "마지막 한 줄 요약", "recap_at": "5b"}
  ]
}
- 모든 `at`, `*_at` 는 본편 절 id. 요소는 그 절이 시작될 때(+0.15초) 나타난다 (선택 `dt` 로 조정).
- icon: sun moon drop flame leaf heart star bolt cloud mountain magnet atom gear eye clock globe flower fish bee tree question check wave planet
        thermometer arrow-up book scale, 또는 "text:Cu" 처럼 글자(1~4자).
- 마지막 장면의 recap(한 줄 요약)은 엔딩 전환까지 2초 이상 유지되도록 낭독이 끝나기 전에 띄운다.
"""
import html, json, math, pathlib, sys

if len(sys.argv) < 3:
    raise SystemExit(__doc__)
proj = pathlib.Path(sys.argv[1]); spec_path = pathlib.Path(sys.argv[2])
SCRIPTS = sys.argv[3] if len(sys.argv) > 3 else None
A = proj / "assets"; (proj / "compositions").mkdir(exist_ok=True)
sys.path.insert(0, SCRIPTS or str(pathlib.Path(__file__).resolve().parents[2] / "jarvis-skills/skills/jarvis-knowledge-video/scripts"))
import charles


def die(msg):
    raise SystemExit("spec 오류: " + msg)


# ------------------------------------------------------------------ 입력
spec = json.load(open(spec_path, encoding="utf-8"))
timing = json.load(open(A / "timing.json", encoding="utf-8"))
raw = {r["id"]: r for r in json.load(open(A / "clauses_timing.json", encoding="utf-8"))}
parts = json.load(open(A / "parts.json", encoding="utf-8"))
TOTAL = float((A / "duration.txt").read_text().strip())
OFF = parts["body"]["start"] - 0.5
T = {k: {**v, "start": v["start"] - OFF, "end": v["end"] - OFF} for k, v in raw.items() if v["part"] == "body"}
ES = raw["en1"]["start"] - 0.45 - OFF
BODY_IDS = [k for k in raw if raw[k]["part"] == "body"]

th = {"bg": "#0e1420", "bg2": "#1b2740", "accent": "#FFB81C", "accent2": "#6EC8FF", "text": "#F4F7FF", **spec.get("theme", {})}
BG, BG2, AC, AC2, TX = th["bg"], th["bg2"], th["accent"], th["accent2"], th["text"]


def rgba(h, a):
    h = h.lstrip("#"); return f"rgba({int(h[0:2],16)},{int(h[2:4],16)},{int(h[4:6],16)},{a})"


def esc(s): return html.escape(str(s), quote=True).replace("\n", "<br>")


scenes = spec.get("scenes")
if not isinstance(scenes, list) or not 3 <= len(scenes) <= 5:
    die("scenes 는 3~5개여야 합니다 (본편에 그림 장면이 있어야 합니다. 오리는 오프닝·엔딩에만 나옵니다).")
seen = []
for i, s in enumerate(scenes, 1):
    if s.get("type") not in ("question", "flow", "points", "compare", "stat"):
        die(f"{i}번째 장면의 type 은 question/flow/points/compare/stat 중 하나여야 합니다: {s.get('type')!r}")
    cl = s.get("clauses")
    if not cl:
        die(f"{i}번째 장면에 clauses 가 없습니다")
    for c in cl:
        if c not in T: die(f"{i}번째 장면의 절 id {c!r} 는 본편에 없습니다 (있는 것: {', '.join(BODY_IDS)})")
    seen += cl
if seen != BODY_IDS:
    die(f"장면들의 clauses 를 이어 붙이면 본편 절 전부가 순서대로 한 번씩 나와야 합니다.\n  본편: {BODY_IDS}\n  장면: {seen}")


def tt(cid, dt=0.15):
    if cid not in T: die(f"절 id {cid!r} 가 본편에 없습니다 (있는 것: {', '.join(BODY_IDS)})")
    return T[cid]["start"] + dt


# ------------------------------------------------------------------ 장면 경계 (본편 시각, 0.3초씩 겹침)
starts = [0.0] + [round(T[s["clauses"][0]]["start"] - 0.3, 2) for s in scenes[1:]]
ends = [starts[i + 1] + 0.3 for i in range(len(scenes) - 1)] + [ES + 0.4]
exits = starts[1:] + [ES]

FONTS = """
    @font-face { font-family: 'Pretendard'; font-weight: 400; src: url('assets/fonts/Pretendard-Regular.woff2') format('woff2'); }
    @font-face { font-family: 'Pretendard'; font-weight: 700; src: url('assets/fonts/Pretendard-Bold.woff2') format('woff2'); }
    @font-face { font-family: 'Pretendard'; font-weight: 800; src: url('assets/fonts/Pretendard-ExtraBold.woff2') format('woff2'); }
"""

# ------------------------------------------------------------------ 아이콘 (코드로 그린 단순한 그림, 100x100 칸 중심 0,0)
ICONS = {
    "sun": '<circle r="17" fill="__C__"/>' + "".join(f'<line x1="0" y1="-27" x2="0" y2="-39" stroke="__C__" stroke-width="7" stroke-linecap="round" transform="rotate({a})"/>' for a in range(0, 360, 45)),
    "moon": '<path d="M 14 -38 A 38 38 0 1 0 14 38 A 29 29 0 1 1 14 -38 Z" fill="__C__"/>',
    "drop": '<path d="M0 -40 C 22 -12 31 2 31 16 C31 32 17 41 0 41 C-17 41 -31 32 -31 16 C-31 2 -22 -12 0 -40Z" fill="__C__"/>',
    "flame": '<path d="M0 -42 C 10 -22 28 -10 25 14 C 23 30 11 41 0 41 C -12 41 -26 30 -24 12 C -22 0 -12 -6 -10 -22 C -6 -16 -2 -12 0 -4 C 7 -15 5 -30 0 -42Z" fill="__C__"/>',
    "leaf": '<path d="M -32 32 C -32 -10 0 -38 36 -36 C 38 0 10 34 -32 32 Z" fill="__C__"/><path d="M -30 30 L 12 -12" stroke="rgba(0,0,0,0.35)" stroke-width="5" stroke-linecap="round"/>',
    "heart": '<path d="M0 36 C-46 4 -32 -32 -13 -32 C-4 -32 0 -24 0 -20 C0 -24 4 -32 13 -32 C32 -32 46 4 0 36Z" fill="__C__"/>',
    "star": '<polygon points="0,-40 11,-14 40,-12 18,7 25,36 0,21 -25,36 -18,7 -40,-12 -11,-14" fill="__C__"/>',
    "bolt": '<path d="M 10 -42 L -24 4 L -3 4 L -12 42 L 26 -8 L 5 -8 Z" fill="__C__"/>',
    "cloud": '<path d="M -26 22 A 16 16 0 0 1 -24 -10 A 22 22 0 0 1 18 -18 A 18 18 0 0 1 28 22 Z" fill="__C__"/>',
    "mountain": '<path d="M -44 34 L -12 -22 L 6 6 L 18 -12 L 44 34Z" fill="__C__"/>',
    "magnet": '<path d="M -26 -34 L -26 6 A 26 26 0 0 0 26 6 L 26 -34" fill="none" stroke="__C__" stroke-width="17"/><rect x="-35" y="-40" width="18" height="12" fill="rgba(255,255,255,0.85)"/><rect x="17" y="-40" width="18" height="12" fill="rgba(255,255,255,0.85)"/>',
    "atom": '<circle r="7" fill="__C__"/>' + "".join(f'<ellipse rx="40" ry="14" fill="none" stroke="__C__" stroke-width="4.5" transform="rotate({a})"/>' for a in (0, 60, 120)),
    "gear": '<circle r="22" fill="none" stroke="__C__" stroke-width="12"/>' + "".join(f'<rect x="-7" y="-41" width="14" height="14" rx="3" fill="__C__" transform="rotate({a})"/>' for a in range(0, 360, 45)),
    "eye": '<path d="M -44 0 Q 0 -36 44 0 Q 0 36 -44 0Z" fill="none" stroke="__C__" stroke-width="7" stroke-linejoin="round"/><circle r="13" fill="__C__"/>',
    "clock": '<circle r="38" fill="none" stroke="__C__" stroke-width="8"/><path d="M 0 -22 L 0 0 L 16 10" fill="none" stroke="__C__" stroke-width="7" stroke-linecap="round" stroke-linejoin="round"/>',
    "globe": '<circle r="38" fill="none" stroke="__C__" stroke-width="6"/><ellipse rx="16" ry="38" fill="none" stroke="__C__" stroke-width="5"/><line x1="-38" y1="0" x2="38" y2="0" stroke="__C__" stroke-width="5"/>',
    "flower": "".join(f'<circle cx="0" cy="-22" r="15" fill="__C__" transform="rotate({a})"/>' for a in range(0, 360, 72)) + '<circle r="11" fill="rgba(0,0,0,0.35)"/>',
    "fish": '<ellipse cx="-4" rx="30" ry="19" fill="__C__"/><path d="M 22 0 L 44 -18 L 44 18 Z" fill="__C__"/><circle cx="-17" cy="-4" r="4" fill="rgba(0,0,0,0.55)"/>',
    "bee": '<ellipse rx="30" ry="21" fill="__C__"/><path d="M -12 -21 Q -4 0 -12 21 L -3 21 Q 5 0 -3 -21Z M 8 -21 Q 16 0 8 21 L 17 21 Q 25 0 17 -21Z" fill="rgba(0,0,0,0.55)"/><ellipse cx="-6" cy="-26" rx="15" ry="9" fill="rgba(255,255,255,0.7)" transform="rotate(-20 -6 -26)"/>',
    "tree": '<rect x="-6" y="8" width="12" height="32" fill="rgba(0,0,0,0.35)"/><circle cy="-8" r="26" fill="__C__"/><circle cx="-16" cy="6" r="17" fill="__C__"/><circle cx="16" cy="6" r="17" fill="__C__"/>',
    "question": '<text x="0" y="24" text-anchor="middle" font-family="Pretendard" font-weight="800" font-size="76" fill="__C__">?</text>',
    "check": '<path d="M -28 2 L -8 22 L 30 -22" fill="none" stroke="__C__" stroke-width="12" stroke-linecap="round" stroke-linejoin="round"/>',
    "wave": '<path d="M -44 0 Q -33 -26 -22 0 T 0 0 T 22 0 T 44 0" fill="none" stroke="__C__" stroke-width="9" stroke-linecap="round"/>',
    "planet": '<circle r="22" fill="__C__"/><ellipse rx="42" ry="11" fill="none" stroke="__C__" stroke-width="5" transform="rotate(-20)"/>',
    "thermometer": '<rect x="-8" y="-40" width="16" height="56" rx="8" fill="none" stroke="__C__" stroke-width="6"/><circle cy="26" r="15" fill="__C__"/><rect x="-3" y="-14" width="6" height="34" fill="__C__"/>',
    "arrow-up": '<path d="M 0 40 L 0 -30 M -22 -8 L 0 -34 L 22 -8" fill="none" stroke="__C__" stroke-width="11" stroke-linecap="round" stroke-linejoin="round"/>',
    "book": '<path d="M -40 -26 L -4 -20 L -4 32 L -40 26 Z M 40 -26 L 4 -20 L 4 32 L 40 26 Z" fill="__C__"/>',
    "scale": '<line x1="0" y1="-36" x2="0" y2="30" stroke="__C__" stroke-width="6"/><line x1="-36" y1="-24" x2="36" y2="-24" stroke="__C__" stroke-width="6"/><path d="M -46 -2 L -26 -2 L -36 -24 Z M 26 -2 L 46 -2 L 36 -24Z" fill="__C__"/><rect x="-18" y="30" width="36" height="8" rx="3" fill="__C__"/>',
}


def icon(name, color, size=120):
    if str(name).startswith("text:"):
        t = esc(str(name)[5:]); fs = 66 if len(str(name)[5:]) <= 1 else 54 if len(str(name)[5:]) == 2 else 38 if len(str(name)[5:]) == 3 else 30
        inner = f'<text x="0" y="{fs * 0.35:.0f}" text-anchor="middle" font-family="Pretendard" font-weight="800" font-size="{fs}" fill="{color}">{t}</text>'
    elif name in ICONS:
        inner = ICONS[name].replace("__C__", color)
    else:
        die(f"icon {name!r} 은 없습니다. 쓸 수 있는 것: {' '.join(ICONS)} 또는 text:글자")
    return f'<svg width="{size}" height="{size}" viewBox="-50 -50 100 100" style="overflow:visible">{inner}</svg>'


def badge(pid, ic, color, size=220, cls="badge"):
    return (f'<div id="{pid}" class="{cls}" style="width:{size}px;height:{size}px;left:{-size // 2}px;top:{-size // 2}px;border-color:{color};background:{rgba(color, 0.14)}">'
            f'{icon(ic, color, int(size * 0.55))}</div>')


def dashed(pid, d, color, width=7, dash="14 12", mask_w=26):
    return (f'<mask id="{pid}-mk" maskUnits="userSpaceOnUse" x="-3000" y="-3000" width="8000" height="8000">'
            f'<path id="{pid}-m" d="{d}" fill="none" stroke="#fff" stroke-width="{mask_w}" pathLength="100" stroke-dasharray="100 120" stroke-dashoffset="100"/></mask>'
            f'<path id="{pid}" d="{d}" fill="none" stroke="{color}" stroke-width="{width}" stroke-dasharray="{dash}" stroke-linecap="round" mask="url(#{pid}-mk)"/>')


def scene_html(host_id, start, body, css, script, wrap):
    return f"""<template>
  <style>{FONTS}
    #root {{ position: absolute; inset: 0; font-family: 'Pretendard', sans-serif; color: {TX}; }}
    #root * {{ box-sizing: border-box; }}
    .badge {{ position: absolute; border-radius: 50%; border: 6px solid; display: flex; align-items: center; justify-content: center; }}
    .anc {{ position: absolute; width: 0; height: 0; opacity: 0; }}
    .tx {{ position: absolute; text-align: center; opacity: 0; }}
{css}
  </style>
  <div id="root" data-composition-id="{host_id}" data-width="1920" data-height="1080">
    <div id="{wrap}" style="position:absolute;inset:0">
{body}
    </div>
  </div>
  <script>
    const T0 = {start};
    const g = (t) => +(t - T0).toFixed(3);
    const tl = gsap.timeline({{ paused: true }});
{script}
    window.__timelines["{host_id}"] = tl;
  </script>
</template>
"""


def fade_in(sel, t, dur=0.4, y=14):
    return f'    tl.fromTo("{sel}", {{ opacity: 0, y: {y}, filter: "blur(8px)" }}, {{ opacity: 1, y: 0, filter: "blur(0px)", duration: {dur}, ease: "power3.out" }}, g({t:.2f}));'


def pop(sel, t, dur=0.45):
    return f'    tl.fromTo("{sel}", {{ opacity: 0, scale: 0.45 }}, {{ opacity: 1, scale: 1, duration: {dur}, ease: "back.out(1.7)" }}, g({t:.2f}));'


def draw(sel, t, dur=0.35):
    return f'    tl.fromTo("{sel}-m", {{ strokeDashoffset: 100 }}, {{ strokeDashoffset: 0, duration: {dur}, ease: "power2.out" }}, g({t:.2f}));'


def recap_block(p, s, last):
    if not s.get("recap"):
        return "", "", ""
    if not last:
        die("recap(마지막 한 줄 요약)은 마지막 장면에만 쓸 수 있습니다")
    css = f"    #{p}-recap {{ left: 0; top: 770px; width: 1920px; font-weight: 800; font-size: 62px; color: {AC}; white-space: nowrap; }}"
    body = f'    <div class="tx" id="{p}-recap">{esc(s["recap"])}</div>'
    t = tt(s.get("recap_at", s["clauses"][-1]), s.get("recap_dt", 0.9))
    if ES - t < 2.0:
        print(f"주의: 요약 한 줄이 엔딩 전환까지 {ES - t:.1f}초만 보입니다 (2초 이상 권장). recap_at 을 앞으로 옮기세요.")
    return css, body, fade_in(f"#{p}-recap", t, 0.45)


# ------------------------------------------------------------------ 장면 종류별 그리기
def build_question(i, s, last):
    p = f"s{i}"
    cl = s["clauses"]
    sub_t = tt(s.get("sub_at", cl[-1]), s.get("sub_dt", 0.3))
    sym_t = tt(s.get("symbol_at", cl[min(1, len(cl) - 1)]), s.get("symbol_dt", 0.1))
    rc, rb, rj = recap_block(p, s, last)
    css = f"""
    #{p}-glow {{ position: absolute; inset: 0; background: radial-gradient(ellipse 55% 50% at 50% 48%, {rgba(AC, 0.20)}, {rgba(AC, 0)} 70%); }}
    #{p}-head {{ left: 0; top: 330px; width: 1920px; opacity: 1; font-weight: 800; font-size: 128px; letter-spacing: -0.02em; line-height: 1.22; color: {TX}; }}
    #{p}-sub {{ left: 0; top: 700px; width: 1920px; font-weight: 700; font-size: 58px; color: {AC2}; }}
{rc}"""
    body = f"""    <div id="{p}-glow"></div>
    <div class="anc" id="{p}-sym" style="left:960px;top:215px">{badge(p + "-symb", s.get("symbol", "question"), AC, 200)}</div>
    <div class="tx" id="{p}-head">{esc(s["headline"])}</div>
    <div class="tx" id="{p}-sub">{esc(s.get("sub", ""))}</div>
{rb}"""
    js = [f'    tl.fromTo("#{p}-head", {{ opacity: 0.35, filter: "blur(14px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.5, ease: "power3.out" }}, g({(0 if i == 1 else starts[i - 1] + 0.05):.2f}));',
          pop(f"#{p}-sym", sym_t)]
    if s.get("sub"): js.append(fade_in(f"#{p}-sub", sub_t))
    if rj: js.append(rj)
    return css, body, js


def build_flow(i, s, last):
    p = f"s{i}"; nodes = s.get("nodes") or []
    n = len(nodes)
    if not 2 <= n <= 5: die(f"{i}번째 장면(flow)의 nodes 는 2~5개여야 합니다")
    gap = min(520, 1500 // (n - 1)); x0 = 960 - gap * (n - 1) / 2; Y = 430
    rc, rb, rj = recap_block(p, s, last)
    css = f"""
    #{p}-lay {{ position: absolute; left: 0; top: 0; width: 1920px; height: 1080px; overflow: visible; }}
    .{p}-lbl {{ left: -270px; top: 140px; width: 540px; font-weight: 800; font-size: 56px; color: {TX}; line-height: 1.2; }}
    .{p}-note {{ left: -270px; top: 218px; width: 540px; font-weight: 400; font-size: 36px; color: {rgba(TX, 0.82)}; line-height: 1.3; }}
{rc}"""
    body, js = [], []
    arrows = []
    for k, nd in enumerate(nodes):
        x = x0 + gap * k
        t = tt(nd["at"], nd.get("dt", 0.15))
        col = AC if k == n - 1 else AC2
        body.append(f'    <div class="anc" id="{p}-n{k}" style="left:{x:.0f}px;top:{Y}px">{badge(f"{p}-b{k}", nd.get("icon", "star"), col, 220)}'
                    f'<div class="tx {p}-lbl" id="{p}-l{k}">{esc(nd["label"])}</div>' + (f'<div class="tx {p}-note" id="{p}-t{k}">{esc(nd["note"])}</div>' if nd.get("note") else "") + '</div>')
        if k > 0:
            xa, xb = x0 + gap * (k - 1) + 135, x - 135
            arrows.append(dashed(f"{p}-a{k}", f"M {xa:.0f} {Y} L {xb - 14:.0f} {Y}", AC, 7, "12 10") +
                          f'<path id="{p}-h{k}" d="M {xb - 22:.0f} {Y - 17} L {xb + 4:.0f} {Y} L {xb - 22:.0f} {Y + 17} Z" fill="{AC}" opacity="0"/>')
            js.append(draw(f"#{p}-a{k}", t - 0.05, 0.3))
            js.append(f'    tl.fromTo("#{p}-h{k}", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.15, ease: "power2.out" }}, g({t + 0.2:.2f}));')
        js.append(pop(f"#{p}-n{k}", t + (0.15 if k else 0)))
        js.append(f'    tl.fromTo("#{p}-l{k}", {{ opacity: 0, y: 10 }}, {{ opacity: 1, y: 0, duration: 0.35, ease: "power3.out" }}, g({t + (0.3 if k else 0.15):.2f}));')
        if nd.get("note"):
            js.append(f'    tl.fromTo("#{p}-t{k}", {{ opacity: 0, y: 10 }}, {{ opacity: 1, y: 0, duration: 0.35, ease: "power3.out" }}, g({t + (0.5 if k else 0.35):.2f}));')
    body = f'    <svg id="{p}-lay" viewBox="0 0 1920 1080">{"".join(arrows)}</svg>\n' + "\n".join(body) + "\n" + rb
    # 마지막 노드는 빛난다
    js.append(f'    tl.fromTo("#{p}-b{n - 1}", {{ boxShadow: "0 0 0px {rgba(AC, 0)}" }}, {{ boxShadow: "0 0 46px {rgba(AC, 0.85)}", duration: 0.6, ease: "power2.out", immediateRender: false }}, g({tt(nodes[-1]["at"], 0.7):.2f}));')
    if rj: js.append(rj)
    return css, body, js


def build_points(i, s, last):
    p = f"s{i}"; items = s.get("items") or []
    n = len(items)
    if not 2 <= n <= 4: die(f"{i}번째 장면(points)의 items 는 2~4개여야 합니다")
    cw = 520 if n <= 3 else 420; gap = 60 if n <= 3 else 40
    x0 = 960 - (cw * n + gap * (n - 1)) / 2
    rc, rb, rj = recap_block(p, s, last)
    css = f"""
    .{p}-card {{ position: absolute; top: 230px; height: 480px; border-radius: 36px; background: {rgba(AC, 0.09)}; border: 3px solid {rgba(AC, 0.35)}; opacity: 0; }}
    .{p}-ic {{ position: absolute; top: 270px; width: {cw}px; height: 190px; opacity: 0; }}
    .{p}-tx {{ top: 500px; width: {cw - 70}px; font-weight: 800; font-size: {52 if n <= 3 else 44}px; line-height: 1.3; color: {TX}; }}
{rc}"""
    body, js = [], []
    for k, it in enumerate(items):
        x = x0 + (cw + gap) * k; t = tt(it["at"], it.get("dt", 0.15))
        body.append(f'    <div class="{p}-card" id="{p}-c{k}" style="left:{x:.0f}px;width:{cw}px"></div>'
                    f'<div class="{p}-ic" id="{p}-i{k}" style="left:{x:.0f}px"><div class="anc" style="left:{cw // 2}px;top:95px;opacity:1">{badge(f"{p}-b{k}", it.get("icon", "star"), AC if k == n - 1 else AC2, 170)}</div></div>'
                    f'<div class="tx {p}-tx" id="{p}-x{k}" style="left:{x + 35:.0f}px">{esc(it["text"])}</div>')
        js.append(f'    tl.fromTo(["#{p}-c{k}", "#{p}-i{k}"], {{ opacity: 0, y: 24, filter: "blur(8px)" }}, {{ opacity: 1, y: 0, filter: "blur(0px)", duration: 0.45, ease: "power3.out" }}, g({t:.2f}));')
        js.append(fade_in(f"#{p}-x{k}", t + 0.2, 0.4))
    body = "\n".join(body) + "\n" + rb
    if rj: js.append(rj)
    return css, body, js


def build_compare(i, s, last):
    p = f"s{i}"; L, R = s.get("left"), s.get("right")
    if not (L and R): die(f"{i}번째 장면(compare)에는 left 와 right 가 필요합니다")
    rc, rb, rj = recap_block(p, s, last)
    css = f"""
    .{p}-card {{ position: absolute; top: 215px; width: 750px; height: 450px; border-radius: 36px; background: {rgba(AC, 0.09)}; border: 3px solid {rgba(AC, 0.4)}; opacity: 0; }}
    .{p}-head {{ top: 250px; width: 750px; font-weight: 800; font-size: 64px; color: {AC}; }}
    .{p}-axis {{ top: 368px; width: 750px; font-weight: 700; font-size: 38px; color: {rgba(TX, 0.85)}; }}
    .{p}-track {{ position: absolute; top: 450px; width: 560px; height: 38px; border-radius: 19px; background: {rgba(TX, 0.16)}; opacity: 0; }}
    .{p}-fill {{ position: absolute; top: 450px; width: 560px; height: 38px; border-radius: 19px; background: {AC}; transform-origin: 0 50%; opacity: 0; }}
    .{p}-val {{ top: 520px; width: 750px; font-weight: 800; font-size: 88px; color: {TX}; }}
    .{p}-ico {{ position: absolute; top: 560px; width: 750px; height: 120px; opacity: 0; }}
{rc}"""
    body, js = [], []
    for k, (nm, c, x) in enumerate((("l", L, 140), ("r", R, 1030))):
        t = tt(c["at"], c.get("dt", 0.15)); bt = tt(c.get("bar_at", c["at"]), c.get("bar_dt", 0.45))
        dur = max(0.5, (T[c.get("bar_at", c["at"])]["end"] - bt) - 0.1)
        v = float(c.get("value", 0.5))
        if not 0 <= v <= 1: die("compare 의 value 는 0~1 사이여야 합니다")
        body.append(f'    <div class="{p}-card" id="{p}-{nm}c" style="left:{x}px"></div>'
                    f'<div class="tx {p}-head" id="{p}-{nm}h" style="left:{x}px">{esc(c["title"])}</div>'
                    f'<div class="tx {p}-axis" id="{p}-{nm}a" style="left:{x}px">{esc(s.get("axis", ""))}</div>'
                    f'<div class="{p}-track" id="{p}-{nm}t" style="left:{x + 95}px"></div><div class="{p}-fill" id="{p}-{nm}f" style="left:{x + 95}px"></div>'
                    f'<div class="tx {p}-val" id="{p}-{nm}v" style="left:{x}px">{esc(c.get("label", ""))}</div>'
                    + (f'<div class="{p}-ico" id="{p}-{nm}i" style="left:{x}px"><div class="anc" style="left:375px;top:60px;opacity:1">{badge(f"{p}-{nm}b", c["icon"], AC2, 110)}</div></div>' if c.get("icon") else ""))
        js.append(f'    tl.fromTo(["#{p}-{nm}c", "#{p}-{nm}h", "#{p}-{nm}a", "#{p}-{nm}t"], {{ opacity: 0, y: 20, filter: "blur(8px)" }}, {{ opacity: 1, y: 0, filter: "blur(0px)", duration: 0.45, ease: "power3.out" }}, g({t:.2f}));')
        js.append(f'    tl.set("#{p}-{nm}f", {{ opacity: 1 }}, g({bt:.2f}));')
        js.append(f'    tl.fromTo("#{p}-{nm}f", {{ scaleX: 0 }}, {{ scaleX: {v}, duration: {dur:.2f}, ease: "none", immediateRender: false }}, g({bt:.2f}));')
        lt = tt(c["label_at"], c.get("label_dt", 0.3)) if c.get("label_at") else bt + dur - 0.1   # 값 글자: 지정한 절에, 없으면 막대가 다 찰 때
        js.append(fade_in(f"#{p}-{nm}v", lt, 0.4))
        if c.get("icon"): js.append(f'    tl.fromTo("#{p}-{nm}i", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.4, ease: "power2.out" }}, g({t + 0.3:.2f}));')
    body = "\n".join(body) + "\n" + rb
    if rj: js.append(rj)
    return css, body, js


def build_stat(i, s, last):
    p = f"s{i}"
    big = str(s["big"]); fs = 300 if len(big) <= 6 else 220 if len(big) <= 9 else 150
    if last and s.get("recap") and s.get("note"): die("마지막 stat 장면에서 note 와 recap 을 같이 쓸 수 없습니다 (겹칩니다)")
    rc, rb, rj = recap_block(p, s, last)
    css = f"""
    #{p}-glow {{ position: absolute; inset: 0; background: radial-gradient(ellipse 50% 45% at 50% 50%, {rgba(AC, 0.16)}, {rgba(AC, 0)} 70%); }}
    #{p}-name {{ left: 0; top: 230px; width: 1920px; font-weight: 800; font-size: 76px; color: {TX}; white-space: nowrap; }}
    #{p}-big {{ left: 0; top: 330px; width: 1920px; font-weight: 800; font-size: {fs}px; line-height: 1; letter-spacing: -0.03em; color: {AC}; white-space: nowrap; }}
    #{p}-label {{ left: 0; top: 660px; width: 1920px; font-weight: 700; font-size: 76px; color: {TX}; white-space: nowrap; }}
    #{p}-note {{ left: 0; top: 760px; width: 1920px; font-weight: 400; font-size: 44px; color: {rgba(TX, 0.85)}; }}
{rc}"""
    body = (f'    <div id="{p}-glow"></div>\n' + (f'    <div class="tx" id="{p}-name">{esc(s["name"])}</div>\n' if s.get("name") else "")
            + f'    <div class="tx" id="{p}-big">{esc(big)}</div>\n'
            + (f'    <div class="tx" id="{p}-label">{esc(s["label"])}</div>\n' if s.get("label") else "")
            + (f'    <div class="tx" id="{p}-note">{esc(s["note"])}</div>\n' if s.get("note") else "") + rb)
    js = []
    if s.get("name"): js.append(fade_in(f"#{p}-name", tt(s.get("name_at", s["clauses"][0]), s.get("name_dt", 0.0)), 0.4))
    js.append(f'    tl.fromTo("#{p}-big", {{ opacity: 0, scale: 1.18, filter: "blur(14px)", transformOrigin: "50% 50%" }}, {{ opacity: 1, scale: 1, filter: "blur(0px)", duration: 0.45, ease: "expo.out" }}, g({tt(s.get("big_at", s["clauses"][-1]), s.get("big_dt", 0.1)):.2f}));')
    if s.get("label"): js.append(fade_in(f"#{p}-label", tt(s.get("label_at", s["clauses"][-1]), s.get("label_dt", 0.8)), 0.4))
    if s.get("note"): js.append(fade_in(f"#{p}-note", tt(s.get("note_at", s["clauses"][-1]), s.get("note_dt", 1.3)), 0.4))
    if rj: js.append(rj)
    return css, body, js


BUILD = {"question": build_question, "flow": build_flow, "points": build_points, "compare": build_compare, "stat": build_stat}

HOST = []
for i, s in enumerate(scenes, 1):
    last = i == len(scenes)
    css, body, js = BUILD[s["type"]](i, s, last)
    js = list(js) + [f'    tl.to("#s{i}-all", {{ opacity: 0, filter: "blur(8px)", duration: 0.3, ease: "power2.in" }}, g({exits[i - 1]:.2f}));']
    (proj / "compositions" / f"s{i}.html").write_text(scene_html(f"s{i}", starts[i - 1], body, css, "\n".join(js), f"s{i}-all"), encoding="utf-8")
    HOST.append(f'<div id="s{i}" data-composition-id="s{i}" data-composition-src="compositions/s{i}.html" data-start="{OFF + starts[i - 1]:.2f}" '
                f'data-duration="{ends[i - 1] - starts[i - 1]:.2f}" data-track-index="1" data-track-kind="graphics" data-width="1920" data-height="1080"></div>')
    print(f"장면 {i} ({s['type']}): 본편 {starts[i - 1]:.1f}~{ends[i - 1]:.1f}초, 절 {', '.join(s['clauses'])}")

# ------------------------------------------------------------------ 자막, 배경, 칩, 오프닝·엔딩, index
def cs(r): return max(r["start"] - 0.04, 0.05)


cap_body = "\n".join(f'    <div class="cap clip" id="cap-{r["id"]}" data-start="{cs(r):.2f}" data-duration="{(r["end"] + 0.10) - cs(r):.2f}" data-track-index="{k % 6}"><span class="cap-pill" id="capp-{r["id"]}">{esc(r["caption"])}</span></div>' for k, r in enumerate(timing))
cap_js = "\n".join(f'    tl.fromTo("#capp-{r["id"]}", {{ opacity: 0, y: 10 }}, {{ opacity: 1, y: 0, duration: 0.12, ease: "power3.out" }}, {cs(r):.2f});\n'
                   f'    tl.to("#capp-{r["id"]}", {{ opacity: 0, duration: 0.07, ease: "power2.in" }}, {r["end"]:.2f});\n'
                   f'    tl.set("#capp-{r["id"]}", {{ opacity: 0 }}, {r["end"] + 0.1:.2f});' for r in timing)
(proj / "compositions" / "captions.html").write_text(f"""<template>
  <style>{FONTS}
    #root {{ position: absolute; inset: 0; font-family: 'Pretendard', sans-serif; }}
    .cap {{ position: absolute; left: 0; top: 884px; width: 1920px; height: 110px; text-align: center; }}
    .cap-pill {{ display: inline-block; padding: 8px 34px 12px; border-radius: 20px; background: rgba(4,8,14,0.66); font-weight: 700; font-size: 56px; line-height: 1.25; color: {TX}; opacity: 0; white-space: nowrap; }}
  </style>
  <div id="root" data-composition-id="captions" data-width="1920" data-height="1080">
{cap_body}
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
{cap_js}
    window.__timelines["captions"] = tl;
  </script>
</template>
""", encoding="utf-8")

dots = [(160, 900, 11), (420, 980, 8), (760, 1010, 14), (1010, 940, 7), (1290, 1000, 10), (1560, 930, 13), (1790, 990, 9), (300, 760, 6), (1650, 700, 8), (60, 520, 9), (1860, 420, 11), (520, 300, 7), (1420, 250, 8), (900, 160, 6)]
dots_svg = "".join(f'<circle id="bb{k}" cx="{x}" cy="{y}" r="{r}" fill="{rgba(AC, 0.16)}"/>' for k, (x, y, r) in enumerate(dots))
dots_js = "\n".join(f'    tl.fromTo("#bb{k}", {{ y: 0 }}, {{ y: -{120 + (k % 5) * 45}, duration: {TOTAL:g}, ease: "none" }}, 0);' for k in range(len(dots)))
(proj / "compositions" / "bg.html").write_text(f"""<template>
  <style>
    #root {{ position: absolute; inset: 0; background: radial-gradient(ellipse 80% 80% at 50% 40%, {BG2} 0%, {BG} 72%, #04070c 100%); overflow: hidden; }}
    #bg-b {{ position: absolute; inset: 0; width: 1920px; height: 1080px; }}
  </style>
  <div id="root" data-composition-id="bg" data-width="1920" data-height="1080">
    <svg id="bg-b" viewBox="0 0 1920 1080" data-layout-allow-overflow>{dots_svg}</svg>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
{dots_js}
    window.__timelines["bg"] = tl;
  </script>
</template>
""", encoding="utf-8")

HOSTS, _ = charles.write_scenes(proj, raw, A / "narration.wav", TOTAL, OFF)
(proj / "compositions" / "chip.html").write_text(f"""<template>
  <style>{FONTS}
    #root {{ position: absolute; inset: 0; font-family: 'Pretendard', sans-serif; }}
    #chip-t {{ position: absolute; left: 120px; top: 60px; font-weight: 800; font-size: 40px; letter-spacing: -0.01em; color: {AC}; white-space: nowrap; opacity: 0; }}
    #chip-t i {{ display: inline-block; width: 22px; height: 22px; margin-right: 14px; border-radius: 50%; background: {AC}; vertical-align: -1px; }}
  </style>
  <div id="root" data-composition-id="chip" data-width="1920" data-height="1080">
    <div id="chip-t"><i></i>{esc(spec.get("chip", ""))}</div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    tl.fromTo("#chip-t", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.5, ease: "power2.out" }}, 0);
    window.__timelines["chip"] = tl;
  </script>
</template>
""", encoding="utf-8")

hosts = "\n      ".join(HOST)
(proj / "index.html").write_text(f"""<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=1920, height=1080" />
    <script src="assets/gsap.min.js"></script>
    <style>
      * {{ margin: 0; padding: 0; box-sizing: border-box; }}
      html, body {{ margin: 0; width: 1920px; height: 1080px; overflow: hidden; background: {BG}; }}
    </style>
  </head>
  <body>
    <div id="root" data-composition-id="main" data-start="0" data-duration="{TOTAL:g}" data-width="1920" data-height="1080">
      <div id="bg" data-composition-id="bg" data-composition-src="compositions/bg.html" data-start="0" data-duration="{TOTAL:g}" data-track-index="0" data-track-kind="graphics" data-width="1920" data-height="1080"></div>
      {hosts}
      {HOSTS}
      <div id="chip" data-composition-id="chip" data-composition-src="compositions/chip.html" data-start="{OFF + 2.3:.2f}" data-duration="{ES - 2.3:.2f}" data-track-index="2" data-track-kind="graphics" data-width="1920" data-height="1080" style="z-index: 80"></div>
      <div id="captions" data-composition-id="captions" data-composition-src="compositions/captions.html" data-start="0" data-duration="{TOTAL:g}" data-track-index="4" data-track-kind="captions" data-width="1920" data-height="1080" style="z-index: 90"></div>
      <audio id="vo" src="assets/narration.wav" data-start="0" data-duration="{TOTAL:g}" data-track-index="9" data-volume="0.85"></audio>
    </div>
    <script>
      window.__timelines = window.__timelines || {{}};
      window.__timelines["main"] = gsap.timeline({{ paused: true }});
    </script>
  </body>
</html>
""", encoding="utf-8")
print("index ok: 본편 장면", len(scenes), "개 + 찰스 오프닝·엔딩 (오리는 오프닝·엔딩에만 나옵니다)")
