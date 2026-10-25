#!/usr/bin/env python3
"""「문어의 심장은 세 개」 HyperFrames 프로젝트 생성기. 사용: python3 -I build_video.py <프로젝트 폴더>
입력: assets/{timing,clauses_timing,parts}.json, duration.txt, narration.wav (build_track.py), 출력: index.html, compositions/*.html
오프닝·엔딩(찰스)은 jarvis-knowledge-video 의 scripts/charles.py 가 만든다.  사용: python3 -I build_video.py <프로젝트 폴더> [scripts 폴더]"""
import json, math, pathlib, sys

proj = pathlib.Path(sys.argv[1]); (proj / "compositions").mkdir(exist_ok=True)
A = proj / "assets"
timing = json.load(open(A / "timing.json", encoding="utf-8"))               # 자막 단위 (전체 영상 기준 시각)
raw = {r["id"]: r for r in json.load(open(A / "clauses_timing.json", encoding="utf-8"))}
parts = json.load(open(A / "parts.json", encoding="utf-8"))
TOTAL = float((A / "duration.txt").read_text().strip())
OFF = parts["body"]["start"] - 0.5      # 본편의 0초가 전체 영상의 몇 초인가 (본편 첫 절은 본편 시각 0.5초에 있다)
sys.path.insert(0, sys.argv[2] if len(sys.argv) > 2 else str(pathlib.Path(__file__).resolve().parents[2] / "jarvis-skills/skills/jarvis-knowledge-video/scripts"))
import charles
# 본편 장면은 본편 시각(OFF 를 뺀 값)으로 쓴다. 장면 호스트의 data-start 에만 OFF 를 더한다.
T = {k: {**v, "start": v["start"] - OFF, "end": v["end"] - OFF} for k, v in raw.items() if v["part"] == "body"}
ES = raw["en1"]["start"] - 0.45 - OFF   # 엔딩 장면이 시작하는 본편 시각

GROUND, CORAL, HEART, DARK, O2C, TEAL, CREAM = "#07121b", "#F26B5B", "#FF4D6D", "#3B7BE0", "#8FE9FF", "#3DD6C6", "#EAF4F8"
FONTS = """
    @font-face { font-family: 'Pretendard'; font-weight: 400; src: url('assets/fonts/Pretendard-Regular.woff2') format('woff2'); }
    @font-face { font-family: 'Pretendard'; font-weight: 700; src: url('assets/fonts/Pretendard-Bold.woff2') format('woff2'); }
    @font-face { font-family: 'Pretendard'; font-weight: 800; src: url('assets/fonts/Pretendard-ExtraBold.woff2') format('woff2'); }
"""
HEART_D = "M0,38 C-60,-4 -44,-44 -18,-44 C-6,-44 0,-34 0,-30 C0,-34 6,-44 18,-44 C44,-44 60,-4 0,38 Z"
PILL = "padding:2px 18px;border-radius:16px;background:rgba(5,14,22,0.82);"


def scene(host_id, start, body, css, script, wrap):
    return f"""<template>
  <style>{FONTS}
    #root {{ position: absolute; inset: 0; font-family: 'Pretendard', sans-serif; color: {CREAM}; }}
    #root * {{ box-sizing: border-box; }}
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


def dashed(pid, d, color, width, dash="14 12", mask_w=26):
    return (f'<mask id="{pid}-mk" maskUnits="userSpaceOnUse" x="-3000" y="-3000" width="8000" height="8000">'
            f'<path id="{pid}-m" d="{d}" fill="none" stroke="#fff" stroke-width="{mask_w}" pathLength="100" stroke-dasharray="100 120" stroke-dashoffset="100"/></mask>'
            f'<path id="{pid}" d="{d}" fill="none" stroke="{color}" stroke-width="{width}" stroke-dasharray="{dash}" stroke-linecap="round" mask="url(#{pid}-mk)"/>')


def heart(pid, x, y, size, color=HEART):
    s = size / 100
    return (f'<div id="{pid}" class="hrt" style="left:{x}px;top:{y}px"><svg width="{size*1.3:.0f}" height="{size*1.3:.0f}" viewBox="-65 -65 130 130" '
            f'style="position:absolute;left:{-size*0.65:.0f}px;top:{-size*0.65:.0f}px;overflow:visible"><path id="{pid}-p" d="{HEART_D}" fill="{color}" stroke="#fff" stroke-opacity="0.55" stroke-width="3"/></svg></div>')


def particle(pid, d, color):
    return (f'<div id="{pid}" class="pt" style="offset-path:path(\'{d}\')"><span style="background:{color};box-shadow:0 0 14px {color}"></span></div>')


COMMON_JS = """
    // one heartbeat: quick swell, slower release (finite, so every frame is a function of time)
    function beats(sel, start, n, period) {
      for (let k = 0; k < n; k++) {
        const t = start + k * period;
        tl.fromTo(sel, { scale: 1 }, { scale: 1.22, duration: 0.12, ease: "power2.out", immediateRender: false }, t);
        tl.to(sel, { scale: 1, duration: 0.24, ease: "power2.in", immediateRender: false }, t + 0.12);
      }
    }
    // a blood cell travels along its path once
    function flow(sel, start, dur) {
      tl.set(sel, { opacity: 1 }, start);
      tl.fromTo(sel, { offsetDistance: "0%" }, { offsetDistance: "100%", duration: dur, ease: "none", immediateRender: false }, start);
      tl.set(sel, { opacity: 0 }, start + dur);
    }
"""

PT_CSS = """
    .pt { position: absolute; left: 0; top: 0; width: 0; height: 0; opacity: 0; offset-rotate: 0deg; offset-distance: 0%; }
    .pt span { position: absolute; left: -9px; top: -9px; width: 18px; height: 18px; border-radius: 50%; }
    .hrt { position: absolute; width: 0; height: 0; opacity: 0; }
    .lbl { position: absolute; white-space: nowrap; font-weight: 700; font-size: 38px; opacity: 0; __PILL__ }
""".replace("__PILL__", PILL)

# ------------------------------------------------------------------ scene 1: anatomy (0 - 15.2)
c = {k: T[k] for k in T}
MANTLE = "M 730 470 C 730 300 830 230 960 230 C 1090 230 1190 300 1190 470 C 1190 540 1150 585 1100 610 L 820 610 C 770 585 730 540 730 470 Z"
arms = []
for i in range(8):
    x0 = 830 + i * 37.1
    dx = (i - 3.5) * 16
    arms.append(f'<path d="M {x0:.0f} 662 C {x0 + dx*0.4:.0f} 720 {x0 + dx*1.2:.0f} 770 {x0 + dx*2.0:.0f} {820 + (i % 2) * 14}" fill="none" stroke="{CORAL}" stroke-width="24" stroke-linecap="round" opacity="0.92"/>')
ribs = "".join(f'<line x1="{-24}" y1="{y}" x2="{24}" y2="{y}" stroke="{TEAL}" stroke-width="4" stroke-linecap="round" opacity="0.8"/>' for y in range(-70, 80, 26))
def gill(pid, x, y):
    return (f'<div id="{pid}" class="hrt" style="left:{x}px;top:{y}px"><svg width="110" height="230" viewBox="-55 -115 110 230" style="position:absolute;left:-55px;top:-115px;overflow:visible">'
            f'<ellipse cx="0" cy="0" rx="36" ry="98" fill="rgba(61,214,198,0.22)" stroke="{TEAL}" stroke-width="4"/>{ribs}</svg></div>')

an_css = PT_CSS + f"""
    #an-glow {{ position: absolute; inset: 0; background: radial-gradient(ellipse 50% 50% at 50% 45%, rgba(255,120,100,0.22), rgba(255,120,100,0) 70%); opacity: 0; }}
    #an-title {{ position: absolute; left: 0; top: 92px; width: 1920px; text-align: center; font-weight: 800; font-size: 84px; letter-spacing: -0.02em; color: {CREAM}; white-space: nowrap; }}
    #an-body {{ position: absolute; left: 0; top: 0; width: 1920px; height: 1080px; opacity: 0; }}
    #an-q {{ position: absolute; left: 860px; top: 340px; width: 200px; text-align: center; font-weight: 800; font-size: 210px; color: #FFD166; opacity: 0; line-height: 1; }}
    #an-num {{ position: absolute; left: 1330px; top: 330px; width: 420px; font-weight: 800; font-size: 330px; line-height: 1; color: {HEART}; opacity: 0; white-space: nowrap; }}
    #an-num small {{ font-size: 110px; margin-left: 8px; color: {CREAM}; }}
    #an-flows {{ position: absolute; left: 0; top: 0; width: 1920px; height: 1080px; overflow: visible; }}
    #an-o2 {{ position: absolute; left: 0; top: 0; width: 1920px; height: 1080px; overflow: visible; opacity: 0; }}
    #an-body-arrows {{ position: absolute; left: 0; top: 0; width: 1920px; height: 1080px; overflow: visible; }}
"""
O2_BUBBLES = "".join(f'<circle cx="{x}" cy="{y}" r="{r}" fill="rgba(143,233,255,0.25)" stroke="{O2C}" stroke-width="3"/>' for x, y, r in
                     [(790, 330, 12), (812, 292, 9), (770, 278, 7), (1130, 330, 12), (1108, 292, 9), (1150, 278, 7)])
P = {
    "L1": "M 880 690 C 800 660 800 620 850 575", "R1": "M 1040 690 C 1120 660 1120 620 1070 575",
    "L2": "M 850 520 L 850 330", "R2": "M 1070 520 L 1070 330",
    "L3": "M 850 322 C 850 270 930 300 950 385", "R3": "M 1070 322 C 1070 270 990 300 970 385",
    "B1": "M 960 475 L 960 560 C 960 640 940 690 900 770", "B2": "M 960 475 L 960 560 C 960 640 980 690 1020 770",
    "B3": "M 960 475 L 960 560 C 960 640 960 700 960 800",
}
pts = ""
for k in ("L1", "R1"):
    for j in range(3): pts += particle(f"an-{k}{j}", P[k], DARK)
for k in ("L2", "R2"):
    for j in range(3): pts += particle(f"an-{k}{j}", P[k], DARK)
for k in ("L3", "R3"):
    for j in range(2): pts += particle(f"an-{k}{j}", P[k], O2C)
for k in ("B1", "B2", "B3"):
    for j in range(2): pts += particle(f"an-{k}{j}", P[k], O2C)

an_body = f"""    <div id="an-glow"></div>
    <div id="an-title" data-layout-allow-overlap>문어의 심장은 몇 개일까?</div>
    <svg id="an-body" viewBox="0 0 1920 1080">
      {''.join(arms)}
      <ellipse cx="960" cy="640" rx="150" ry="48" fill="{CORAL}" opacity="0.95"/>
      <path d="{MANTLE}" fill="rgba(242,107,91,0.2)" stroke="{CORAL}" stroke-width="9" stroke-linejoin="round"/>
      <circle cx="905" cy="640" r="19" fill="#fff"/><ellipse cx="905" cy="640" rx="12" ry="5.5" fill="#101820"/>
      <circle cx="1015" cy="640" r="19" fill="#fff"/><ellipse cx="1015" cy="640" rx="12" ry="5.5" fill="#101820"/>
    </svg>
    <svg id="an-flows" viewBox="0 0 1920 1080"></svg>
    <svg id="an-o2" viewBox="0 0 1920 1080">{O2_BUBBLES}</svg>
    {gill("an-gl", 850, 412)}{gill("an-gr", 1070, 412)}
    <div id="an-q">?</div>
    <div id="an-num">3<small>개</small></div>
    {heart("an-hl", 850, 548, 62)}{heart("an-hr", 1070, 548, 62)}{heart("an-hs", 960, 432, 108)}
    {pts}
    <div class="lbl" id="an-lgl" style="left:560px;top:392px;color:{TEAL}">아가미</div>
    <div class="lbl" id="an-lgr" style="left:1260px;top:392px;color:{TEAL}">아가미</div>
    <div class="lbl" id="an-lhl" style="left:430px;top:530px;color:{HEART}">아가미 심장</div>
    <div class="lbl" id="an-lhr" style="left:1260px;top:530px;color:{HEART}">아가미 심장</div>
    <div class="lbl" id="an-lhs" style="left:888px;top:150px;color:{HEART}">몸 심장</div>
    <div class="lbl" id="an-lo2" style="left:1262px;top:318px;color:{O2C};font-size:34px">산소</div>
    <div class="lbl" id="an-lo2l" style="left:586px;top:318px;color:{O2C};font-size:34px">산소</div>
    <svg id="an-body-arrows" viewBox="0 0 1920 1080">
      {dashed("an-ll1", "M 700 410 L 800 410", TEAL, 4, "8 8", 20)}{dashed("an-ll2", "M 1220 410 L 1120 410", TEAL, 4, "8 8", 20)}
      {dashed("an-ll3", "M 700 560 L 810 560", HEART, 4, "8 8", 20)}{dashed("an-ll4", "M 1220 560 L 1110 560", HEART, 4, "8 8", 20)}
      {dashed("an-ll5", "M 960 212 L 960 372", HEART, 4, "8 8", 20)}
    </svg>"""

def anim_particles(prefix, start, n, dur, gap):
    return "\n".join(f'    flow("#an-{prefix}{j}", {start + j * gap:.2f}, {dur});' for j in range(n))

an_js = COMMON_JS + f"""
    // the shared drawing of the octopus (mantle, head, eyes, arms) fades in at once, first frame is already readable
    tl.fromTo("#an-title", {{ opacity: 0.4, filter: "blur(14px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.5, ease: "power3.out" }}, 0);
    tl.fromTo("#an-body", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.7, ease: "power2.out" }}, 0.15);
    tl.fromTo("#an-glow", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.8, ease: "power2.out" }}, 0.2);
    // 1a: how many hearts? a question mark in the middle
    tl.fromTo("#an-q", {{ opacity: 0, scale: 0.5, transformOrigin: "50% 50%" }}, {{ opacity: 1, scale: 1, duration: 0.45, ease: "back.out(1.7)" }}, {c['1a']['start'] + 0.4:.2f});
    // 1b: three hearts pop in one by one, and the number lands
    tl.to("#an-q", {{ opacity: 0, scale: 0.6, duration: 0.2, ease: "power3.in" }}, {c['1b']['start'] + 0.05:.2f});
    tl.fromTo("#an-hl", {{ opacity: 0, scale: 0.2 }}, {{ opacity: 1, scale: 1, duration: 0.4, ease: "back.out(2)" }}, {c['1b']['start'] + 0.15:.2f});
    tl.fromTo("#an-hr", {{ opacity: 0, scale: 0.2 }}, {{ opacity: 1, scale: 1, duration: 0.4, ease: "back.out(2)" }}, {c['1b']['start'] + 0.4:.2f});
    tl.fromTo("#an-hs", {{ opacity: 0, scale: 0.2 }}, {{ opacity: 1, scale: 1, duration: 0.45, ease: "back.out(1.8)" }}, {c['1b']['start'] + 0.65:.2f});
    tl.fromTo("#an-num", {{ opacity: 0, scale: 1.3, filter: "blur(14px)", transformOrigin: "0% 50%" }}, {{ opacity: 1, scale: 1, filter: "blur(0px)", duration: 0.5, ease: "expo.out" }}, {c['1b']['start'] + 0.7:.2f});
    tl.to(["#an-title"], {{ opacity: 0, filter: "blur(10px)", duration: 0.35, ease: "power3.in" }}, {c['1b']['end'] + 0.1:.2f});
    tl.to(["#an-num"], {{ opacity: 0, filter: "blur(10px)", duration: 0.35, ease: "power3.in" }}, {c['2a']['start'] - 0.1:.2f});
    // 2a: two hearts beside the gills push blood into them
    tl.fromTo(["#an-gl", "#an-gr"], {{ opacity: 0 }}, {{ opacity: 1, duration: 0.5, ease: "power2.out" }}, {c['2a']['start'] + 0.0:.2f});
    tl.fromTo(["#an-lgl", "#an-lgr"], {{ opacity: 0 }}, {{ opacity: 1, duration: 0.3, ease: "power2.out" }}, {c['2a']['start'] + 0.3:.2f});
    tl.fromTo(["#an-ll1", "#an-ll2"].map(s => s + "-m"), {{ strokeDashoffset: 100 }}, {{ strokeDashoffset: 0, duration: 0.3, ease: "power2.out" }}, {c['2a']['start'] + 0.3:.2f});
    tl.fromTo(["#an-lhl", "#an-lhr"], {{ opacity: 0 }}, {{ opacity: 1, duration: 0.3, ease: "power2.out" }}, {c['2a']['start'] + 0.7:.2f});
    tl.fromTo(["#an-ll3-m", "#an-ll4-m"], {{ strokeDashoffset: 100 }}, {{ strokeDashoffset: 0, duration: 0.3, ease: "power2.out" }}, {c['2a']['start'] + 0.7:.2f});
    beats("#an-hl", {c['2a']['start'] + 1.0:.2f}, 5, 0.62); beats("#an-hr", {c['2a']['start'] + 1.0:.2f}, 5, 0.62);
{anim_particles('L1', c['2a']['start'] + 0.9, 3, 0.9, 0.25)}
{anim_particles('R1', c['2a']['start'] + 0.9, 3, 0.9, 0.25)}
{anim_particles('L2', c['2a']['start'] + 1.9, 3, 1.0, 0.3)}
{anim_particles('R2', c['2a']['start'] + 1.9, 3, 1.0, 0.3)}
    // 2b: in the gills the blood takes up oxygen
    tl.fromTo("#an-o2", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.5, ease: "power2.out" }}, {c['2b']['start'] + 0.1:.2f});
    tl.fromTo(["#an-lo2", "#an-lo2l"], {{ opacity: 0 }}, {{ opacity: 1, duration: 0.3, ease: "power2.out" }}, {c['2b']['start'] + 0.2:.2f});
    tl.fromTo(["#an-gl", "#an-gr"], {{ filter: "drop-shadow(0 0 0px rgba(143,233,255,0))" }}, {{ filter: "drop-shadow(0 0 22px rgba(143,233,255,0.95))", duration: 0.6, ease: "power2.out", immediateRender: false }}, {c['2b']['start'] + 0.2:.2f});
    // 3a: oxygen-rich blood leaves the gills for the middle
{anim_particles('L3', c['3a']['start'] + 0.0, 2, 0.8, 0.25)}
{anim_particles('R3', c['3a']['start'] + 0.0, 2, 0.8, 0.25)}
    tl.to(["#an-o2", "#an-lo2", "#an-lo2l"], {{ opacity: 0, duration: 0.4, ease: "power2.in" }}, {c['3a']['end'] + 0.1:.2f});
    // 3b: they gather in the third heart
    tl.fromTo("#an-lhs", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.3, ease: "power2.out" }}, {c['3b']['start'] + 0.1:.2f});
    tl.fromTo("#an-ll5-m", {{ strokeDashoffset: 100 }}, {{ strokeDashoffset: 0, duration: 0.3, ease: "power2.out" }}, {c['3b']['start'] + 0.1:.2f});
    tl.to(["#an-lhl", "#an-lhr", "#an-ll3-m", "#an-ll4-m"], {{ opacity: 0.35, duration: 0.3, ease: "power2.out" }}, {c['3b']['start']:.2f});
    beats("#an-hs", {c['3b']['start'] + 0.3:.2f}, 6, 0.6);
    // 3c: and it sends the blood to the whole body
{anim_particles('B1', c['3c']['start'] + 0.1, 2, 0.9, 0.3)}
{anim_particles('B2', c['3c']['start'] + 0.1, 2, 0.9, 0.3)}
{anim_particles('B3', c['3c']['start'] + 0.1, 2, 0.9, 0.3)}
    tl.to("#an-all", {{ opacity: 0, filter: "blur(8px)", duration: 0.3, ease: "power2.in" }}, g(14.95));
"""

# ------------------------------------------------------------------ scene 2: blood (14.8 - 23.7)
DROP = "M 0 -130 C 40 -60 90 -10 90 50 C 90 100 50 130 0 130 C -50 130 -90 100 -90 50 C -90 -10 -40 -60 0 -130 Z"
bl_css = f"""
    .bl-h {{ position: absolute; top: 210px; width: 600px; text-align: center; font-weight: 800; font-size: 64px; opacity: 0; }}
    .bl-drop {{ position: absolute; width: 0; height: 0; opacity: 0; }}
    .bl-el {{ position: absolute; width: 0; height: 0; opacity: 0; }}
    .bl-el b {{ position: absolute; left: -62px; top: -62px; width: 124px; height: 124px; border-radius: 50%; border: 5px solid; display: flex; align-items: center; justify-content: center; font-weight: 800; font-size: 54px; background: rgba(5,14,22,0.7); }}
    .bl-tag {{ position: absolute; width: 600px; text-align: center; font-weight: 800; font-size: 58px; opacity: 0; }}
    #bl-sea {{ position: absolute; left: 520px; top: 600px; width: 880px; height: 250px; border-radius: 36px; background: rgba(16,60,86,0.55); border: 3px solid rgba(143,233,255,0.4); opacity: 0; overflow: hidden; }}
    #bl-seasvg {{ position: absolute; left: 520px; top: 600px; width: 880px; height: 250px; opacity: 0; }}
    #bl-seatxt {{ position: absolute; left: 520px; top: 640px; width: 880px; text-align: center; font-weight: 800; font-size: 56px; color: {O2C}; opacity: 0; }}
    #bl-ok {{ position: absolute; left: 700px; top: 760px; opacity: 0; font-size: 42px; font-weight: 800; color: {TEAL}; {PILL} white-space: nowrap; }}
"""
snow = "".join(f'<line x1="0" y1="-26" x2="0" y2="26" stroke="{O2C}" stroke-width="5" stroke-linecap="round" transform="rotate({a})"/>' for a in (0, 60, 120))
bl_body = f"""    <div class="bl-h" id="bl-hl" style="left:300px;color:#FF8C8C">사람</div>
    <div class="bl-h" id="bl-hr" style="left:1020px;color:{DARK}">문어</div>
    <div class="bl-drop" id="bl-dl" style="left:600px;top:470px"><svg width="220" height="290" viewBox="-110 -145 220 290" style="position:absolute;left:-110px;top:-145px;overflow:visible"><path d="{DROP}" fill="#E5484D" stroke="#fff" stroke-opacity="0.5" stroke-width="4"/></svg></div>
    <div class="bl-drop" id="bl-dr" style="left:1320px;top:470px"><svg width="220" height="290" viewBox="-110 -145 220 290" style="position:absolute;left:-110px;top:-145px;overflow:visible"><path id="bl-drp" d="{DROP}" fill="rgba(80,100,130,0.35)" stroke="#fff" stroke-opacity="0.5" stroke-width="4"/></svg></div>
    <div class="bl-el" id="bl-fe" style="left:600px;top:480px"><b style="border-color:#FF8C8C;color:#FF8C8C">Fe</b></div>
    <div class="bl-el" id="bl-cu" style="left:1320px;top:480px"><b style="border-color:{O2C};color:{O2C}">Cu</b></div>
    <div class="bl-tag" id="bl-tl" style="left:300px;top:660px;color:#FF8C8C">철 → 붉은 피</div>
    <div class="bl-tag" id="bl-tr" style="left:1020px;top:660px;color:{O2C}">구리 → 푸른 피</div>
    <div id="bl-sea"></div>
    <svg id="bl-seasvg" viewBox="0 0 880 250">
      <path d="M 0 200 Q 55 170 110 200 T 220 200 T 330 200 T 440 200 T 550 200 T 660 200 T 770 200 T 880 200 V 250 H 0 Z" fill="rgba(143,233,255,0.12)"/>
      <g transform="translate(90 170)">{snow}</g>
      <circle cx="740" cy="190" r="10" fill="none" stroke="{O2C}" stroke-width="3"/><circle cx="780" cy="150" r="7" fill="none" stroke="{O2C}" stroke-width="3"/><circle cx="715" cy="140" r="5" fill="none" stroke="{O2C}" stroke-width="3"/>
    </svg>
    <div id="bl-seatxt">차갑고 산소가 적은 바다</div>
    <div id="bl-ok">구리 방식이 더 유리</div>"""
bl_js = f"""
    const S4 = {c['4a']['start']}, S4b = {c['4b']['start']}, S4c = {c['4c']['start']};
    tl.fromTo(["#bl-hl"], {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.35, ease: "power3.out" }}, g(S4 - 0.05));
    tl.fromTo("#bl-dl", {{ opacity: 0, scale: 0.6 }}, {{ opacity: 1, scale: 1, duration: 0.5, ease: "back.out(1.5)" }}, g(S4 + 0.1));
    tl.fromTo("#bl-fe", {{ opacity: 0, scale: 0.4 }}, {{ opacity: 1, scale: 1, duration: 0.4, ease: "back.out(1.7)" }}, g(S4 + 0.6));
    tl.fromTo("#bl-tl", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.35, ease: "power3.out" }}, g(S4 + 0.9));
    tl.fromTo(["#bl-hr"], {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.35, ease: "power3.out" }}, g(S4 + 1.3));
    tl.fromTo("#bl-dr", {{ opacity: 0, scale: 0.6 }}, {{ opacity: 1, scale: 1, duration: 0.5, ease: "back.out(1.5)" }}, g(S4 + 1.5));
    tl.fromTo("#bl-cu", {{ opacity: 0, scale: 0.4 }}, {{ opacity: 1, scale: 1, duration: 0.4, ease: "back.out(1.7)" }}, g(S4 + 1.9));
    // 4b: so it looks blue
    tl.fromTo("#bl-drp", {{ fill: "rgba(80,100,130,0.35)" }}, {{ fill: "{DARK}", duration: 0.6, ease: "power2.inOut" }}, g(S4b + 0.05));
    tl.fromTo("#bl-dr", {{ filter: "drop-shadow(0 0 0px rgba(59,123,224,0))" }}, {{ filter: "drop-shadow(0 0 26px rgba(59,123,224,0.95))", duration: 0.6, ease: "power2.out", immediateRender: false }}, g(S4b + 0.05));
    tl.fromTo("#bl-tr", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.35, ease: "power3.out" }}, g(S4b + 0.35));
    // 4c: in cold, low-oxygen water this works better: both drops step up, the sea appears
    tl.to(["#bl-dl", "#bl-dr", "#bl-fe", "#bl-cu"], {{ y: -150, duration: 0.6, ease: "power2.inOut" }}, g(S4c - 0.05));
    tl.to(["#bl-tl", "#bl-tr"], {{ y: -150, duration: 0.6, ease: "power2.inOut" }}, g(S4c - 0.05));
    tl.to(["#bl-hl", "#bl-hr"], {{ opacity: 0, duration: 0.3, ease: "power2.in" }}, g(S4c - 0.05));
    tl.fromTo(["#bl-sea", "#bl-seasvg"], {{ opacity: 0, y: 40 }}, {{ opacity: 1, y: 0, duration: 0.6, ease: "power3.out" }}, g(S4c + 0.2));
    tl.fromTo("#bl-seatxt", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.4, ease: "power3.out" }}, g(S4c + 0.7));
    tl.fromTo("#bl-ok", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.4, ease: "power3.out" }}, g(S4c + 1.9));
    tl.to("#bl-all", {{ opacity: 0, filter: "blur(8px)", duration: 0.3, ease: "power2.in" }}, g(23.4));
"""

# ------------------------------------------------------------------ scene 3: swim (23.3 - 30)
def octo(pid, x, y, crawl=False, inner=""):
    if crawl:
        arms_svg = ""
        for i in range(3):
            for sgn in (1, -1):
                x0 = sgn * (30 + i * 22)
                arms_svg += (f'<path d="M {x0} 62 C {x0 + sgn*70} {96 + i*4} {x0 + sgn*(130 + i*26)} {118} {x0 + sgn*(190 + i*30)} {100 - i*6}" '
                             f'fill="none" stroke="{CORAL}" stroke-width="22" stroke-linecap="round"/>')
        floor = '<path d="M -300 128 Q -150 112 0 128 T 300 128" fill="none" stroke="rgba(234,244,248,0.35)" stroke-width="6" stroke-linecap="round"/>'
        tail = ""
        mantle = f'<ellipse cx="0" cy="0" rx="112" ry="84" fill="rgba(242,107,91,0.35)" stroke="{CORAL}" stroke-width="8"/>'
        eyes = '<circle cx="-34" cy="50" r="14" fill="#fff"/><ellipse cx="-34" cy="50" rx="8" ry="4" fill="#101820"/><circle cx="34" cy="50" r="14" fill="#fff"/><ellipse cx="34" cy="50" rx="8" ry="4" fill="#101820"/>'
    else:
        floor = ""
        arms_svg = ""
        # arms trail together behind the mantle, tapering to a point (jet swimming)
        tail = (f'<path d="M -88 -40 C -170 -32 -230 -10 -300 0 C -230 10 -170 32 -88 40 Z" fill="{CORAL}"/>'
                f'<path d="M -110 -16 C -170 -10 -220 -4 -270 0 M -110 16 C -170 10 -220 4 -270 0" fill="none" stroke="rgba(7,18,27,0.45)" stroke-width="4" stroke-linecap="round"/>')
        mantle = f'<ellipse cx="0" cy="0" rx="120" ry="82" fill="rgba(242,107,91,0.35)" stroke="{CORAL}" stroke-width="8"/>'
        eyes = '<circle cx="70" cy="30" r="15" fill="#fff"/><ellipse cx="70" cy="30" rx="9" ry="4.5" fill="#101820"/>'
    return (f'<div id="{pid}" class="oct" style="left:{x}px;top:{y}px"><svg width="640" height="360" viewBox="-320 -180 640 360" style="position:absolute;left:-320px;top:-180px;overflow:visible">'
            f'{floor}{tail}{arms_svg}{mantle}{eyes}</svg>{inner}</div>')

sw_css = PT_CSS + f"""
    .sw-card {{ position: absolute; top: 190px; width: 760px; height: 500px; border-radius: 36px; background: rgba(16,60,86,0.45); border: 3px solid rgba(143,233,255,0.35); opacity: 0; }}
    .sw-h {{ position: absolute; top: 214px; width: 760px; text-align: center; font-weight: 800; font-size: 54px; opacity: 0; }}
    .oct {{ position: absolute; width: 0; height: 0; opacity: 0; }}
    .sw-lbl {{ position: absolute; width: 760px; text-align: center; top: 616px; font-weight: 800; font-size: 44px; opacity: 0; }}
    #sw-pause {{ position: absolute; width: 0; height: 0; opacity: 0; }}
    #sw-recap {{ position: absolute; left: 0; top: 742px; width: 1920px; text-align: center; font-weight: 800; font-size: 62px; color: #FFD166; opacity: 0; white-space: nowrap; }}
    #sw-jet {{ position: absolute; left: 0; top: 0; width: 1920px; height: 1080px; overflow: visible; }}
"""
speed = "".join(f'<line id="sw-sp{i}" x1="{x1}" y1="{y}" x2="{x1 + 90}" y2="{y}" stroke="{O2C}" stroke-width="6" stroke-linecap="round" opacity="0"/>' for i, (x1, y) in enumerate([(190, 330), (170, 420), (200, 500), (180, 380)]))
sw_body = f"""    <div class="sw-card" id="sw-cl" style="left:130px"></div>
    <div class="sw-card" id="sw-cr" style="left:1030px"></div>
    <div class="sw-h" id="sw-hl" style="left:130px;color:{O2C}">헤엄칠 때</div>
    <div class="sw-h" id="sw-hr" style="left:1030px;color:{TEAL}">기어 다닐 때</div>
    <svg id="sw-jet" viewBox="0 0 1920 1080">{speed}</svg>
    {octo("sw-ol", 560, 450, False, heart("sw-hearl", -10, -4, 70, "#7A8B99") + '<div id="sw-pause" style="left:-10px;top:-4px"><svg width="120" height="120" viewBox="-60 -60 120 120" style="position:absolute;left:-60px;top:-60px"><rect x="-22" y="-26" width="16" height="52" rx="4" fill="#fff"/><rect x="6" y="-26" width="16" height="52" rx="4" fill="#fff"/></svg></div>')}
    {octo("sw-or", 1410, 440, True, heart("sw-hearr", 0, -2, 70, HEART))}
    <div class="sw-lbl" id="sw-ll" style="left:130px;color:#B6C4CE">몸 심장이 멈춘다고 알려져 있어요</div>
    <div class="sw-lbl" id="sw-lr" style="left:1030px;color:{HEART}">몸 심장이 계속 뛰어요</div>
    <div id="sw-recap">심장 셋 = 아가미 심장 2 + 몸 심장 1</div>"""
sw_js = COMMON_JS + f"""
    const S5 = {c['5a']['start']}, S5b = {c['5b']['start']};
    tl.fromTo(["#sw-cl", "#sw-hl"], {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.4, ease: "power3.out" }}, g(S5 - 0.1));
    tl.fromTo("#sw-ol", {{ opacity: 0, x: -80 }}, {{ opacity: 1, x: 0, duration: 0.5, ease: "power3.out" }}, g(S5 + 0.1));
    // jet: the octopus shoots forward, speed lines streak past
    tl.fromTo("#sw-ol", {{ x: 0 }}, {{ x: 70, duration: 1.6, ease: "power1.inOut", immediateRender: false }}, g(S5 + 0.7));
    [0, 1, 2, 3].forEach(i => {{
      tl.fromTo("#sw-sp" + i, {{ opacity: 0, x: 0 }}, {{ opacity: 0.9, x: 260, duration: 0.5, ease: "power1.in", immediateRender: false }}, g(S5 + 0.8 + i * 0.22));
      tl.to("#sw-sp" + i, {{ opacity: 0, duration: 0.15, ease: "power1.out" }}, g(S5 + 1.25 + i * 0.22));
    }});
    tl.fromTo("#sw-hearl", {{ opacity: 0, scale: 0.4 }}, {{ opacity: 1, scale: 1, duration: 0.4, ease: "back.out(1.6)" }}, g(S5 + 0.5));
    beats("#sw-hearl", {c['5a']['start'] + 0.6:.2f}, 2, 0.62);
    tl.fromTo("#sw-pause", {{ opacity: 0, scale: 0.4 }}, {{ opacity: 1, scale: 1, duration: 0.3, ease: "back.out(1.8)" }}, g(S5 + 1.9));
    tl.to("#sw-hearl", {{ filter: "grayscale(1)", opacity: 0.7, duration: 0.3, ease: "power2.out" }}, g(S5 + 1.9));
    tl.fromTo("#sw-ll", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.35, ease: "power3.out" }}, g(S5 + 1.7));
    // 5b: crawling along the bottom
    tl.fromTo(["#sw-cr", "#sw-hr"], {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.4, ease: "power3.out" }}, g(S5b - 0.1));
    tl.fromTo("#sw-or", {{ opacity: 0, x: 60 }}, {{ opacity: 1, x: 0, duration: 0.5, ease: "power3.out" }}, g(S5b + 0.05));
    tl.fromTo("#sw-hearr", {{ opacity: 0, scale: 0.4 }}, {{ opacity: 1, scale: 1, duration: 0.4, ease: "back.out(1.6)" }}, g(S5b + 0.3));
    beats("#sw-hearr", {c['5b']['start'] + 0.6:.2f}, 5, 0.58);
    tl.fromTo("#sw-lr", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.35, ease: "power3.out" }}, g(S5b + 0.7));
    // the one idea in one line, held to the end (2+ s)
    tl.fromTo("#sw-recap", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.45, ease: "power3.out" }}, g({c['5b']['start'] + 0.0:.2f}));
    tl.to("#sw-all", {{ opacity: 0, filter: "blur(8px)", duration: 0.3, ease: "power2.in" }}, g({ES:.2f}));
"""

for name, start, body, css, js, wrap in (
    ("anatomy", 0.0, an_body, an_css, an_js, "an-all"),
    ("blood", 14.8, bl_body, bl_css, bl_js, "bl-all"),
    ("swim", 23.3, sw_body, sw_css, sw_js, "sw-all"),
):
    (proj / "compositions" / f"{name}.html").write_text(scene(name, start, body, css, js, wrap), encoding="utf-8")
    print(name, "ok")

# ------------------------------------------------------------------ captions
cap_css = f"""
    .cap {{ position: absolute; left: 0; top: 884px; width: 1920px; height: 110px; text-align: center; }}
    .cap-pill {{ display: inline-block; padding: 8px 34px 12px; border-radius: 20px; background: rgba(4,10,16,0.66); font-weight: 700; font-size: 56px; line-height: 1.25; color: {CREAM}; opacity: 0; white-space: nowrap; }}
"""
def cs(r): return max(r["start"] - 0.04, 0.05)
cap_body = "\n".join(
    f'    <div class="cap clip" id="cap-{r["id"]}" data-start="{cs(r):.2f}" data-duration="{(r["end"] + 0.10) - cs(r):.2f}" data-track-index="{i % 6}"><span class="cap-pill" id="capp-{r["id"]}">{r["caption"]}</span></div>'
    for i, r in enumerate(timing))
cap_js = "\n".join(
    f'    tl.fromTo("#capp-{r["id"]}", {{ opacity: 0, y: 10 }}, {{ opacity: 1, y: 0, duration: 0.12, ease: "power3.out" }}, {cs(r):.2f});\n'
    f'    tl.to("#capp-{r["id"]}", {{ opacity: 0, duration: 0.1, ease: "power2.in" }}, {r["end"]:.2f});\n'
    f'    tl.set("#capp-{r["id"]}", {{ opacity: 0 }}, {r["end"] + 0.1:.2f});'
    for r in timing)
(proj / "compositions" / "captions.html").write_text(f"""<template>
  <style>{FONTS}
    #root {{ position: absolute; inset: 0; font-family: 'Pretendard', sans-serif; }}
{cap_css}
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

# ------------------------------------------------------------------ background + chip
bub = [(160, 900, 14), (420, 980, 9), (760, 1010, 18), (1010, 940, 8), (1290, 1000, 12), (1560, 930, 16), (1790, 990, 10), (300, 760, 7), (1650, 700, 9), (60, 520, 11), (1860, 420, 13), (520, 300, 8), (1420, 250, 10), (900, 160, 7)]
bub_svg = "".join(f'<circle id="bb{i}" cx="{x}" cy="{y}" r="{r}" fill="none" stroke="rgba(143,233,255,0.22)" stroke-width="2.5"/>' for i, (x, y, r) in enumerate(bub))
bub_js = "\n".join(f'    tl.fromTo("#bb{i}", {{ y: 0 }}, {{ y: -{140 + (i % 5) * 50}, duration: {TOTAL}, ease: "none" }}, 0);' for i in range(len(bub)))
(proj / "compositions" / "bg.html").write_text(f"""<template>
  <style>
    #root {{ position: absolute; inset: 0; background: radial-gradient(ellipse 80% 80% at 50% 40%, #0e2b3d 0%, #07121b 70%, #040a10 100%); overflow: hidden; }}
    #bg-b {{ position: absolute; inset: 0; width: 1920px; height: 1080px; }}
  </style>
  <div id="root" data-composition-id="bg" data-width="1920" data-height="1080">
    <svg id="bg-b" viewBox="0 0 1920 1080" data-layout-allow-overflow>{bub_svg}</svg>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
{bub_js}
    window.__timelines["bg"] = tl;
  </script>
</template>
""", encoding="utf-8")
HOSTS, _ = charles.write_scenes(proj, raw, A / "narration.wav", TOTAL, OFF)
(proj / "compositions" / "chip.html").write_text(f"""<template>
  <style>{FONTS}
    #root {{ position: absolute; inset: 0; font-family: 'Pretendard', sans-serif; }}
    #chip-t {{ position: absolute; left: 120px; top: 40px; font-weight: 800; font-size: 40px; letter-spacing: -0.01em; color: {HEART}; white-space: nowrap; opacity: 0; }}
    #chip-t i {{ display: inline-block; width: 26px; height: 24px; margin-right: 14px; background: {HEART}; clip-path: polygon(50% 100%, 0 40%, 0 20%, 20% 0, 50% 18%, 80% 0, 100% 20%, 100% 40%); vertical-align: -2px; }}
    #chip-t span {{ color: {CREAM}; }}
  </style>
  <div id="root" data-composition-id="chip" data-width="1920" data-height="1080">
    <div id="chip-t"><i></i><span>문어의 심장은 세 개</span></div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    tl.fromTo("#chip-t", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.5, ease: "power2.out" }}, 0);
    window.__timelines["chip"] = tl;
  </script>
</template>
""", encoding="utf-8")

(proj / "index.html").write_text(f"""<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=1920, height=1080" />
    <script src="assets/gsap.min.js"></script>
    <style>
      * {{ margin: 0; padding: 0; box-sizing: border-box; }}
      html, body {{ margin: 0; width: 1920px; height: 1080px; overflow: hidden; background: {GROUND}; }}
    </style>
  </head>
  <body>
    <div id="root" data-composition-id="main" data-start="0" data-duration="{TOTAL:g}" data-width="1920" data-height="1080">
      <div id="bg" data-composition-id="bg" data-composition-src="compositions/bg.html" data-start="0" data-duration="{TOTAL:g}" data-track-index="0" data-track-kind="graphics" data-width="1920" data-height="1080"></div>
      <div id="anatomy" data-composition-id="anatomy" data-composition-src="compositions/anatomy.html" data-start="{OFF:.2f}" data-duration="15.25" data-track-index="1" data-track-kind="graphics" data-width="1920" data-height="1080"></div>
      <div id="blood" data-composition-id="blood" data-composition-src="compositions/blood.html" data-start="{OFF + 14.8:.2f}" data-duration="8.9" data-track-index="1" data-track-kind="graphics" data-width="1920" data-height="1080"></div>
      <div id="swim" data-composition-id="swim" data-composition-src="compositions/swim.html" data-start="{OFF + 23.3:.2f}" data-duration="{ES + 0.4 - 23.3:.2f}" data-track-index="1" data-track-kind="graphics" data-width="1920" data-height="1080"></div>
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
print("index ok")
