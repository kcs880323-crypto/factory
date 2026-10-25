#!/usr/bin/env python3
"""찰스: 지식을 나누는 채널 캐릭터와 오프닝·엔딩 장면 생성기 (표준 라이브러리 + numpy).

  import charles   (build_video.py 에서 sys.path 에 이 폴더를 넣고 가져온다)
  charles.svg(pid)                       찰스 SVG 조각 (id 는 pid- 로 시작)
  charles.mouth_track(wav, t0, t1, fps)  낭독 소리 크기에서 입 벌림 값(0~1) 목록을 만든다 (소리에 맞춘 입 모양)
  charles.write_scenes(proj, clauses, wav, total)  compositions/opening.html, ending.html 을 쓰고 호스트 HTML 을 돌려준다

오프닝과 엔딩의 문구는 고정이다 (reference/charles/opening.json, ending.json).
"""
import json, pathlib, wave
import numpy as np

INDIGO, YELLOW, YELLOW_D, CREAM, CORAL, NAVY = "#1B1F4B", "#FFD84D", "#E39A00", "#FFF4D6", "#FF5A6E", "#2E3A8C"
FONTS = """
    @font-face { font-family: 'Pretendard'; font-weight: 400; src: url('assets/fonts/Pretendard-Regular.woff2') format('woff2'); }
    @font-face { font-family: 'Pretendard'; font-weight: 700; src: url('assets/fonts/Pretendard-Bold.woff2') format('woff2'); }
    @font-face { font-family: 'Pretendard'; font-weight: 800; src: url('assets/fonts/Pretendard-ExtraBold.woff2') format('woff2'); }
"""


def svg(p, badge=True):
    """찰스. 노란 콩 모양 몸통, 둥근 뿔테 안경, 머리 위 '아이디어 안테나' 3가닥, 남색 나비넥타이. viewBox -30 -70 460 560."""
    return f"""<svg id="{p}-svg" width="460" height="560" viewBox="-30 -70 460 560" style="position:absolute;left:-230px;top:-280px;overflow:visible">
      <defs>
        <linearGradient id="{p}-body" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFE36B"/><stop offset="1" stop-color="#FFC21A"/></linearGradient>
      </defs>
      <ellipse id="{p}-shadow" cx="200" cy="452" rx="130" ry="16" fill="rgba(0,0,0,0.28)"/>
      <g id="{p}-feet"><ellipse cx="148" cy="438" rx="40" ry="19" fill="#FF9F1C" stroke="{YELLOW_D}" stroke-width="5"/><ellipse cx="252" cy="438" rx="40" ry="19" fill="#FF9F1C" stroke="{YELLOW_D}" stroke-width="5"/></g>
      <g id="{p}-armL"><path d="M 66 285 C 30 296 14 328 20 360" fill="none" stroke="{YELLOW_D}" stroke-width="42" stroke-linecap="round"/><path d="M 66 285 C 30 296 14 328 20 360" fill="none" stroke="#FFD23F" stroke-width="30" stroke-linecap="round"/></g>
      <g id="{p}-antenna" stroke="{YELLOW_D}" stroke-width="9" stroke-linecap="round" fill="none">
        <path d="M 178 50 C 168 22 160 10 148 2"/><path d="M 200 44 C 200 20 200 8 200 -8"/><path d="M 222 50 C 232 22 240 10 252 2"/>
        <g id="{p}-tips" stroke="none"><circle cx="146" cy="0" r="11" fill="#FFF3B0"/><circle cx="200" cy="-12" r="12" fill="#FFF3B0"/><circle cx="254" cy="0" r="11" fill="#FFF3B0"/></g>
      </g>
      <path id="{p}-torso" d="M 200 40 C 302 40 342 142 342 252 C 342 372 292 438 200 438 C 108 438 58 372 58 252 C 58 142 98 40 200 40 Z" fill="url(#{p}-body)" stroke="{YELLOW_D}" stroke-width="7"/>
      <ellipse cx="200" cy="352" rx="92" ry="66" fill="#FFF0A8" opacity="0.7"/>
      <g id="{p}-armR"><path d="M 334 285 C 370 296 386 328 380 360" fill="none" stroke="{YELLOW_D}" stroke-width="42" stroke-linecap="round"/><path d="M 334 285 C 370 296 386 328 380 360" fill="none" stroke="#FFD23F" stroke-width="30" stroke-linecap="round"/></g>
      <g id="{p}-cheeks" fill="#FF8FA3" opacity="0.65"><ellipse cx="106" cy="238" rx="22" ry="13"/><ellipse cx="294" cy="238" rx="22" ry="13"/></g>
      <g id="{p}-glasses" fill="rgba(255,255,255,0.55)" stroke="#2B2D42" stroke-width="9">
        <circle cx="146" cy="172" r="48"/><circle cx="254" cy="172" r="48"/>
        <path d="M 192 168 Q 200 156 208 168" fill="none"/><path d="M 100 164 L 74 156" fill="none" stroke-linecap="round"/><path d="M 300 164 L 326 156" fill="none" stroke-linecap="round"/>
      </g>
      <g id="{p}-eyes"><circle cx="152" cy="176" r="15" fill="#1B1B2F"/><circle cx="248" cy="176" r="15" fill="#1B1B2F"/><circle cx="147" cy="170" r="5" fill="#fff"/><circle cx="243" cy="170" r="5" fill="#fff"/></g>
      <path id="{p}-smile" d="M 166 252 Q 200 284 234 252" fill="none" stroke="#7A3E00" stroke-width="8" stroke-linecap="round"/>
      <g id="{p}-mouth" opacity="0"><path d="M 168 250 Q 200 312 232 250 Q 200 258 168 250 Z" fill="#7A3E00"/><ellipse cx="200" cy="284" rx="17" ry="10" fill="#FF7B8B"/></g>
      <g id="{p}-bow"><path d="M 200 330 L 154 306 L 154 354 Z" fill="{NAVY}"/><path d="M 200 330 L 246 306 L 246 354 Z" fill="{NAVY}"/><circle cx="200" cy="330" r="12" fill="#1C2466"/><circle cx="171" cy="322" r="3.5" fill="#fff"/><circle cx="171" cy="338" r="3.5" fill="#fff"/><circle cx="229" cy="322" r="3.5" fill="#fff"/><circle cx="229" cy="338" r="3.5" fill="#fff"/></g>
      {f'<g id="{p}-badge"><rect x="158" y="372" width="84" height="38" rx="12" fill="#fff" stroke="{NAVY}" stroke-width="4"/><text x="200" y="400" text-anchor="middle" font-family="Pretendard, sans-serif" font-weight="800" font-size="26" fill="{NAVY}">찰스</text></g>' if badge else ''}
    </svg>"""


def mouth_track(wav, t0, t1, fps=30):
    """wav 에서 [t0, t1] 구간의 소리 크기(프레임당 RMS)를 입 벌림 값 0~1 로 바꾼다. [(시각, 값), ...]"""
    with wave.open(str(wav)) as w:
        sr = w.getframerate()
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768
    out, step = [], 1.0 / fps
    n = int(round((t1 - t0) / step))
    rms = []
    for i in range(n + 1):
        a = int((t0 + i * step) * sr); b = int((t0 + (i + 1) * step) * sr)
        seg = x[a:b]
        rms.append(float(np.sqrt(np.mean(seg ** 2))) if len(seg) else 0.0)
    ref = max(float(np.percentile(rms, 90)), 1e-4)
    prev = 0.0
    for i, r in enumerate(rms):
        v = min(1.0, r / ref)
        v = 0.0 if v < 0.12 else v
        v = 0.55 * v + 0.45 * prev      # 살짝 부드럽게
        prev = v
        out.append((round(t0 + i * step, 3), round(v, 2)))
    return out


# ---------------------------------------------------------------- 장면 생성
BULB = """<svg width="150" height="190" viewBox="-75 -95 150 190" style="position:absolute;left:-75px;top:-95px;overflow:visible">
      <g id="{p}-rays" stroke="#FFF3B0" stroke-width="7" stroke-linecap="round">{rays}</g>
      <circle cx="0" cy="-10" r="46" fill="#FFF3B0" stroke="#E39A00" stroke-width="6"/>
      <path d="M -16 34 H 16 V 50 Q 0 58 -16 50 Z" fill="#FFC21A" stroke="#E39A00" stroke-width="5" stroke-linejoin="round"/>
      <path d="M -10 -16 Q 0 4 10 -16" fill="none" stroke="#E39A00" stroke-width="5" stroke-linecap="round"/>
    </svg>"""
RAYS = "".join(f'<line x1="0" y1="-66" x2="0" y2="-86" transform="rotate({a} 0 -10)"/>' for a in (-80, -50, -20, 20, 50, 80))
THUMB = """<svg width="150" height="150" viewBox="0 0 100 100" style="position:absolute;left:-75px;top:-75px"><circle cx="50" cy="50" r="48" fill="{c}"/>
      <path d="M 24 48 H 14 V 82 H 24 Z" fill="#fff"/><path d="M 30 82 H 64 C 72 82 77 78 79 72 L 84 56 C 86 50 82 46 76 46 H 58 L 62 32 C 63 26 59 22 54 22 L 45 42 L 30 50 Z" fill="#fff"/></svg>"""
BELL = """<svg width="70" height="70" viewBox="0 0 100 100" style="position:absolute;left:0;top:0;overflow:visible"><g id="{p}-bell" style="transform-origin:50px 14px">
      <path d="M 50 8 C 34 8 26 22 26 38 V 52 L 16 64 V 70 H 84 V 64 L 74 52 V 38 C 74 22 66 8 50 8 Z" fill="#fff"/><path d="M 42 76 H 58 C 58 84 54 88 50 88 C 46 88 42 84 42 76 Z" fill="#fff"/></g></svg>"""


def _scene(host, start, css, body, js):
    return f"""<template>
  <style>{FONTS}
    #root {{ position: absolute; inset: 0; font-family: 'Pretendard', sans-serif; color: {CREAM}; }}
    #root * {{ box-sizing: border-box; }}
    .bg {{ position: absolute; inset: 0; background: radial-gradient(ellipse 70% 70% at 35% 55%, #2A2F6E 0%, {INDIGO} 65%, #10133A 100%); opacity: 0; }}
    .abs {{ position: absolute; width: 0; height: 0; }}
    .tx {{ position: absolute; white-space: nowrap; font-weight: 800; opacity: 0; letter-spacing: -0.02em; }}
{css}
  </style>
  <div id="root" data-composition-id="{host}" data-width="1920" data-height="1080">
    <div id="{host[:2]}-all" style="position:absolute;inset:0">
{body}
    </div>
  </div>
  <script>
    const T0 = {start};
    const g = (t) => +(t - T0).toFixed(3);
    const tl = gsap.timeline({{ paused: true }});
    const O = (sel, o, t) => tl.set(sel, o, g(t));
    // 입 모양: 소리 크기에 맞춰 프레임마다 입을 벌린다 (낭독 구간에서만)
    function lips(pid, track) {{
      let last = -1;
      track.forEach(([t, v]) => {{
        if (v === last) return; last = v;
        tl.set("#" + pid + "-mouth", {{ opacity: v > 0 ? 1 : 0, scaleY: Math.max(v, 0.18), svgOrigin: "200 250" }}, g(t));
        tl.set("#" + pid + "-smile", {{ opacity: v > 0 ? 0 : 1 }}, g(t));
      }});
    }}
    function blink(pid, times) {{
      times.forEach(t => tl.fromTo("#" + pid + "-eyes", {{ scaleY: 1, svgOrigin: "200 176" }}, {{ scaleY: 0.1, svgOrigin: "200 176", duration: 0.07, yoyo: true, repeat: 1, ease: "none", immediateRender: false }}, g(t)));
    }}
    function bob(sel, from, to, amp) {{
      const n = Math.max(1, Math.round((to - from) / 0.7) - 1);
      tl.fromTo(sel, {{ y: 0 }}, {{ y: -amp, duration: 0.35, yoyo: true, repeat: n * 2 + 1, ease: "sine.inOut", immediateRender: false }}, g(from));
      tl.set(sel, {{ y: 0 }}, g(from + (n * 2 + 2) * 0.35));
    }}
    function wave(pid, from, to) {{
      tl.fromTo("#" + pid + "-armR", {{ rotation: 0, svgOrigin: "334 285" }}, {{ rotation: -125, svgOrigin: "334 285", duration: 0.35, ease: "power2.out", immediateRender: false }}, g(from));
      const n = Math.max(2, Math.round((to - from - 0.4) / 0.24));
      tl.fromTo("#" + pid + "-armR", {{ rotation: -125, svgOrigin: "334 285" }}, {{ rotation: -98, svgOrigin: "334 285", duration: 0.24, yoyo: true, repeat: n, ease: "sine.inOut", immediateRender: false }}, g(from + 0.35));
      tl.to("#" + pid + "-armR", {{ rotation: 0, svgOrigin: "334 285", duration: 0.35, ease: "power2.in", immediateRender: false }}, g(from + 0.35 + (n + 1) * 0.24));
    }}
{js}
    window.__timelines["{host}"] = tl;
  </script>
</template>
"""


def write_scenes(proj, T, wav, total, off):
    """T: 절 id → {start,end}. off: 본편이 시작하는 시각. 반환: index.html 에 넣을 호스트 HTML 두 줄과 엔딩 시작 시각."""
    proj = pathlib.Path(proj)
    (proj / "compositions").mkdir(exist_ok=True)
    op1, op2 = T["op1"], T["op2"]
    d1 = op1["end"] - op1["start"]
    op_end = off + 0.4
    lips_op = mouth_track(wav, op1["start"], op2["end"])
    # ---------------- 오프닝
    css = f"""
    #op-ch {{ position: absolute; left: 560px; top: 610px; width: 0; height: 0; opacity: 0; }}
    #op-bulb {{ position: absolute; left: 560px; top: 250px; width: 0; height: 0; opacity: 0; }}
    #op-30 {{ left: 1010px; top: 120px; font-size: 270px; line-height: 1; color: {YELLOW}; }}
    #op-know {{ left: 1020px; top: 405px; font-size: 128px; line-height: 1; color: {CREAM}; }}
    #op-plate {{ position: absolute; left: 1010px; top: 590px; height: 190px; padding: 0 44px; border-radius: 44px; background: {YELLOW}; display: flex; align-items: baseline; gap: 18px; opacity: 0; white-space: nowrap; }}
    #op-plate b {{ font-weight: 800; font-size: 150px; line-height: 190px; color: {INDIGO}; letter-spacing: -0.03em; }}
    #op-plate span {{ font-weight: 800; font-size: 70px; color: {NAVY}; }}
    #op-dots {{ position: absolute; inset: 0; opacity: 0; }}
"""
    dots = "".join(f'<circle cx="{x}" cy="{y}" r="{r}" fill="#FFD84D" opacity="0.35"/>' for x, y, r in
                   [(120, 160, 5), (300, 90, 4), (1500, 90, 6), (1800, 200, 5), (1700, 960, 6), (200, 940, 4), (900, 80, 4), (1860, 600, 5), (60, 520, 6)])
    body = f"""      <div class="bg" id="op-bg"></div>
      <svg id="op-dots" viewBox="0 0 1920 1080" width="1920" height="1080" style="position:absolute;inset:0">{dots}</svg>
      <div id="op-ch">{svg('opc')}</div>
      <div id="op-bulb">{BULB.format(p='opb', rays=RAYS)}</div>
      <div class="tx" id="op-30">30초</div>
      <div class="tx" id="op-know">지식 하나</div>
      <div id="op-plate"><b>찰스</b><span>입니다!</span></div>"""
    s1 = op1["start"]
    js = f"""
    const SC = 1.15;
    tl.fromTo(["#op-bg", "#op-dots"], {{ opacity: 0.001 }}, {{ opacity: 1, duration: 0.01, ease: "none" }}, 0);
    gsap.set("#op-ch", {{ scale: SC * 0.85 }});
    tl.fromTo("#op-ch", {{ opacity: 0.6, scale: SC * 0.85 }}, {{ opacity: 1, scale: SC, duration: 0.5, ease: "back.out(1.6)" }}, 0);
    bob("#op-ch", 0.6, {op2['end'] + 0.6:.2f}, 8);
    blink("opc", [{s1 + 1.3:.2f}, {s1 + 3.2:.2f}]);
    wave("opc", {s1 - 0.1:.2f}, {op2['end'] + 0.3:.2f});
    lips("opc", {json.dumps(lips_op)});
    // 30초 / 지식 하나 / 드릴게요(전구) / 찰스입니다
    tl.fromTo("#op-30", {{ opacity: 0, scale: 0.6, transformOrigin: "0% 50%" }}, {{ opacity: 1, scale: 1, duration: 0.45, ease: "back.out(1.8)" }}, g({s1 + 0.05:.2f}));
    tl.fromTo("#op-know", {{ opacity: 0, y: 30, filter: "blur(10px)" }}, {{ opacity: 1, y: 0, filter: "blur(0px)", duration: 0.35, ease: "power3.out" }}, g({s1 + 0.43 * d1:.2f}));
    tl.fromTo("#op-bulb", {{ opacity: 0, scale: 0.3 }}, {{ opacity: 1, scale: 1.05, duration: 0.45, ease: "back.out(2)" }}, g({s1 + 0.71 * d1:.2f}));
    tl.fromTo("#opb-rays", {{ opacity: 0.2 }}, {{ opacity: 1, duration: 0.15, yoyo: true, repeat: 5, ease: "none", immediateRender: false }}, g({s1 + 0.71 * d1 + 0.3:.2f}));
    tl.fromTo("#op-plate", {{ opacity: 0, scale: 0.7, transformOrigin: "0% 50%" }}, {{ opacity: 1, scale: 1, duration: 0.45, ease: "back.out(1.7)" }}, g({op2['start'] + 0.05:.2f}));
    tl.fromTo("#op-ch", {{ y: 0 }}, {{ y: -26, duration: 0.2, yoyo: true, repeat: 1, ease: "power2.out", immediateRender: false }}, g({op2['start'] + 0.05:.2f}));
    // 본편으로 넘어간다
    tl.to("#op-all", {{ opacity: 0, filter: "blur(8px)", duration: 0.35, ease: "power2.in" }}, g({off - 0.05:.2f}));
"""
    (proj / "compositions" / "opening.html").write_text(_scene("opening", 0, css, body, js), encoding="utf-8")

    # ---------------- 엔딩
    e1, e2a, e2b, e2c, e3 = T["en1"], T["en2a"], T["en2b"], T["en2c"], T["en3"]
    es = e1["start"] - 0.45
    de1 = e1["end"] - e1["start"]
    lips_en = mouth_track(wav, e1["start"], e3["end"])
    css = f"""
    #en-ch {{ position: absolute; left: 560px; top: 610px; width: 0; height: 0; opacity: 0; }}
    #en-bulb {{ position: absolute; left: 560px; top: 250px; width: 0; height: 0; opacity: 0; }}
    #en-a {{ left: 1000px; top: 150px; font-size: 118px; line-height: 1.05; color: {CREAM}; }}
    #en-b {{ left: 1000px; top: 280px; font-size: 150px; line-height: 1.05; color: {YELLOW}; }}
    #en-name {{ left: 1000px; top: 170px; font-size: 230px; line-height: 1; color: {YELLOW}; }}
    #en-name2 {{ left: 1010px; top: 430px; font-size: 80px; color: {CREAM}; }}
    .ico {{ position: absolute; width: 0; height: 0; opacity: 0; }}
    #en-like {{ left: 1090px; top: 640px; }}
    #en-sub {{ position: absolute; left: 1300px; top: 570px; width: 440px; height: 140px; border-radius: 70px; background: {CORAL}; display: flex; align-items: center; justify-content: center; gap: 22px; opacity: 0; font-weight: 800; font-size: 64px; color: #fff; }}
    #en-subicon {{ position: relative; width: 70px; height: 70px; }}
    #en-ghostl, #en-ghosts {{ position: absolute; opacity: 0; border: 6px dashed rgba(255,244,214,0.45); }}
    #en-ghostl {{ left: 1015px; top: 565px; width: 150px; height: 150px; border-radius: 75px; }}
    #en-ghosts {{ left: 1300px; top: 570px; width: 440px; height: 140px; border-radius: 70px; }}
    #en-hearts {{ position: absolute; inset: 0; width: 1920px; height: 1080px; overflow: visible; }}
"""
    hearts = "".join(f'<path id="en-h{i}" d="M0,10 C-14,-2 -10,-12 -4,-12 C-1,-12 0,-9 0,-8 C0,-9 1,-12 4,-12 C10,-12 14,-2 0,10 Z" fill="{CORAL}" opacity="0" transform="translate({1090 + (i - 2) * 26} 620) scale(2)"/>' for i in range(5))
    body = f"""      <div class="bg" id="en-bg"></div>
      <div id="en-ch">{svg('enc')}</div>
      <div id="en-bulb">{BULB.format(p='enb', rays=RAYS)}</div>
      <div class="tx" id="en-a">알아두면</div>
      <div class="tx" id="en-b">언젠가 쓴다!</div>
      <div class="tx" id="en-name">찰스</div>
      <div class="tx" id="en-name2">였습니다!</div>
      <div id="en-ghostl"></div><div id="en-ghosts"></div>
      <div class="ico" id="en-like">{THUMB.format(c=CORAL)}</div>
      <div id="en-sub"><span id="en-subicon">{BELL.format(p='ens')}</span><span>구독</span></div>
      <svg id="en-hearts" viewBox="0 0 1920 1080">{hearts}</svg>"""
    js = f"""
    const SC = 1.15;
    tl.fromTo("#en-bg", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.35, ease: "power2.out" }}, 0);
    gsap.set("#en-ch", {{ scale: SC * 0.8 }});
    tl.fromTo("#en-ch", {{ opacity: 0, scale: SC * 0.8 }}, {{ opacity: 1, scale: SC, duration: 0.5, ease: "back.out(1.6)" }}, g({es + 0.15:.2f}));
    bob("#en-ch", {es + 0.7:.2f}, {total - 0.3:.2f}, 8);
    blink("enc", [{e1['start'] + 1.4:.2f}, {e2b['start'] + 0.6:.2f}, {e3['start'] + 1.5:.2f}]);
    lips("enc", {json.dumps(lips_en)});
    // 알아두면 / 언젠가 쓴다 + 전구
    tl.fromTo("#en-a", {{ opacity: 0, y: 30, filter: "blur(10px)" }}, {{ opacity: 1, y: 0, filter: "blur(0px)", duration: 0.35, ease: "power3.out" }}, g({e1['start'] + 0.05:.2f}));
    tl.fromTo("#en-b", {{ opacity: 0, scale: 0.7, transformOrigin: "0% 50%" }}, {{ opacity: 1, scale: 1, duration: 0.45, ease: "back.out(1.8)" }}, g({e1['start'] + 0.5 * de1:.2f}));
    tl.fromTo("#en-bulb", {{ opacity: 0, scale: 0.3 }}, {{ opacity: 1, scale: 1.05, duration: 0.45, ease: "back.out(2)" }}, g({e1['start'] + 0.5 * de1:.2f}));
    tl.fromTo("#enb-rays", {{ opacity: 0.2 }}, {{ opacity: 1, duration: 0.15, yoyo: true, repeat: 5, ease: "none", immediateRender: false }}, g({e1['start'] + 0.5 * de1 + 0.3:.2f}));
    // 좋아요와 구독: 점선 자리가 먼저 나오고 말할 때 채워진다
    tl.to(["#en-a", "#en-b"], {{ opacity: 0.0, filter: "blur(8px)", duration: 0.3, ease: "power2.in", immediateRender: false }}, g({e1['end'] + 0.1:.2f}));
    tl.fromTo(["#en-ghostl", "#en-ghosts"], {{ opacity: 0 }}, {{ opacity: 1, duration: 0.35, ease: "power2.out" }}, g({e1['end'] + 0.2:.2f}));
    wave("enc", {e2a['start'] + 0.3:.2f}, {e2b['start'] - 0.1:.2f});
    tl.fromTo("#en-like", {{ opacity: 0, scale: 0.4 }}, {{ opacity: 1, scale: 1, duration: 0.4, ease: "back.out(2.2)" }}, g({e2b['start'] + 0.05:.2f}));
    tl.fromTo("#en-ghostl", {{ opacity: 1 }}, {{ opacity: 0, duration: 0.2, immediateRender: false }}, g({e2b['start'] + 0.05:.2f}));
    [0, 1, 2, 3, 4].forEach(i => {{
      tl.fromTo("#en-h" + i, {{ opacity: 0, y: 0, x: 0 }}, {{ opacity: 1, y: -90 - i * 14, x: (i - 2) * 22, duration: 0.5, ease: "power2.out", immediateRender: false }}, g({e2b['start'] + 0.15:.2f} + i * 0.06));
      tl.to("#en-h" + i, {{ opacity: 0, duration: 0.2, ease: "power1.in", immediateRender: false }}, g({e2b['start'] + 0.6:.2f} + i * 0.06));
    }});
    tl.fromTo("#en-sub", {{ opacity: 0, scale: 0.6, transformOrigin: "50% 50%" }}, {{ opacity: 1, scale: 1, duration: 0.4, ease: "back.out(2)" }}, g({e2c['start'] + 0.0:.2f}));
    tl.fromTo("#en-ghosts", {{ opacity: 1 }}, {{ opacity: 0, duration: 0.2, immediateRender: false }}, g({e2c['start'] + 0.0:.2f}));
    tl.fromTo("#ens-bell", {{ rotation: 0 }}, {{ rotation: 18, duration: 0.09, yoyo: true, repeat: 5, ease: "sine.inOut", immediateRender: false }}, g({e2c['start'] + 0.35:.2f}));
    // 찰스였습니다
    tl.fromTo("#en-name", {{ opacity: 0, scale: 0.6, transformOrigin: "0% 50%" }}, {{ opacity: 1, scale: 1, duration: 0.45, ease: "back.out(1.7)" }}, g({e3['start'] + 0.05:.2f}));
    tl.fromTo("#en-name2", {{ opacity: 0, y: 20 }}, {{ opacity: 1, y: 0, duration: 0.35, ease: "power3.out" }}, g({e3['start'] + 0.35:.2f}));
    wave("enc", {e3['start'] - 0.05:.2f}, {total - 0.5:.2f});
"""
    (proj / "compositions" / "ending.html").write_text(_scene("ending", es, css, body, js), encoding="utf-8")
    hosts = (f'<div id="opening" data-composition-id="opening" data-composition-src="compositions/opening.html" data-start="0" data-duration="{op_end:.2f}" '
             f'data-track-index="5" data-track-kind="graphics" data-width="1920" data-height="1080" style="z-index: 70"></div>\n      '
             f'<div id="ending" data-composition-id="ending" data-composition-src="compositions/ending.html" data-start="{es:.2f}" data-duration="{total - es:.2f}" '
             f'data-track-index="5" data-track-kind="graphics" data-width="1920" data-height="1080" style="z-index: 70"></div>')
    return hosts, es
