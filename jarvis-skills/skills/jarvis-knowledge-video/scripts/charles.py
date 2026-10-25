#!/usr/bin/env python3
"""찰스: 지식을 나누는 채널 캐릭터(오리 박사)와 오프닝·엔딩 장면 생성기 (표준 라이브러리 + numpy).

  import charles   (build_video.py 에서 sys.path 에 이 폴더를 넣고 가져온다)
  charles.mouth_track(wav, t0, t1, fps)  낭독 소리 크기(프레임당 RMS)를 0~1 값 목록으로 만든다 (말할 때 몸이 맞춰 움직이는 데 쓴다)
  charles.write_scenes(proj, clauses, wav, total, off)  compositions/opening.html, ending.html 을 쓰고 호스트 HTML 을 돌려준다

찰스 그림은 사용자가 만든 것이다: assets/charles/pose-*.png (배경 제거본), charles.json (발 기준점), original/ (원본).
그림을 바꿀 때는 scripts/charles_cutout.py 로 다시 만든다.  오프닝과 엔딩의 문구는 고정이다 (reference/charles/opening.json, ending.json).
"""
import json, pathlib, shutil, wave
import numpy as np

# 찰스 색: 남색 베레모, 노란 배경(그림의 배경색), 크림색 몸
NAVY, YELLOW, CREAM, CORAL, GOLD = "#13305B", "#FFD84D", "#FDF7E9", "#FF5A6E", "#FCBD19"
ASSET_DIR = pathlib.Path(__file__).resolve().parent.parent / "assets" / "charles"
S = 0.82                        # 그림 한 장을 화면에 놓는 배율 (발 기준점이 화면 (FEET_X, FEET_Y))
FEET_X, FEET_Y = 520, 852
FONTS = """
    @font-face { font-family: 'Pretendard'; font-weight: 400; src: url('assets/fonts/Pretendard-Regular.woff2') format('woff2'); }
    @font-face { font-family: 'Pretendard'; font-weight: 700; src: url('assets/fonts/Pretendard-Bold.woff2') format('woff2'); }
    @font-face { font-family: 'Pretendard'; font-weight: 800; src: url('assets/fonts/Pretendard-ExtraBold.woff2') format('woff2'); }
"""


def load_poses(proj, names):
    """charles.json 을 읽고 필요한 포즈 PNG 를 프로젝트 assets/charles 로 복사한다. {이름: 정보}"""
    data = json.loads((ASSET_DIR / "charles.json").read_text(encoding="utf-8"))["poses"]
    dst = pathlib.Path(proj) / "assets" / "charles"
    dst.mkdir(parents=True, exist_ok=True)
    for n in names:
        shutil.copy(ASSET_DIR / data[n]["file"], dst / data[n]["file"])
    return {n: data[n] for n in names}


def pose_html(pid, poses):
    """포즈 이미지들을 한 자리에 겹쳐 놓는다. 앵커(0,0)가 발 기준점이다."""
    out = []
    for n, p in poses.items():
        out.append(f'<img id="{pid}-p-{n}" src="assets/charles/{p["file"]}" alt="" '
                   f'style="position:absolute;left:{-p["fx"] * S:.1f}px;top:{-p["fy"] * S:.1f}px;width:{p["w"] * S:.1f}px;height:{p["h"] * S:.1f}px;'
                   f'opacity:0;transform-origin:{p["fx"] * S:.1f}px {p["fy"] * S:.1f}px">')
    return "\n        ".join(out)


def glow_html(pid, p):
    """전구(포즈 idea 의 marks.bulb) 뒤에 켜지는 빛."""
    bx, by = p["marks"]["bulb"]
    return (f'<div id="{pid}-glow" style="position:absolute;left:{(bx - p["fx"]) * S:.1f}px;top:{(by - p["fy"]) * S:.1f}px;width:320px;height:320px;'
            f'margin:-160px 0 0 -160px;border-radius:50%;opacity:0;'
            f'background:radial-gradient(circle, rgba(255,255,255,0.95) 0%, rgba(255,247,170,0.75) 28%, rgba(255,247,170,0) 68%)"></div>')


def mouth_track(wav, t0, t1, fps=30):
    """wav 에서 [t0, t1] 구간의 소리 크기(프레임당 RMS)를 0~1 값으로 바꾼다. [(시각, 값), ...] (말하는 몸짓의 세기)"""
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
THUMB = """<svg width="150" height="150" viewBox="0 0 100 100" style="position:absolute;left:-75px;top:-75px"><circle cx="50" cy="50" r="48" fill="{c}"/>
      <path d="M 24 48 H 14 V 82 H 24 Z" fill="#fff"/><path d="M 30 82 H 64 C 72 82 77 78 79 72 L 84 56 C 86 50 82 46 76 46 H 58 L 62 32 C 63 26 59 22 54 22 L 45 42 L 30 50 Z" fill="#fff"/></svg>"""
BELL = """<svg width="70" height="70" viewBox="0 0 100 100" style="position:absolute;left:0;top:0;overflow:visible"><g id="{p}-bell" style="transform-origin:50px 14px">
      <path d="M 50 8 C 34 8 26 22 26 38 V 52 L 16 64 V 70 H 84 V 64 L 74 52 V 38 C 74 22 66 8 50 8 Z" fill="#fff"/><path d="M 42 76 H 58 C 58 84 54 88 50 88 C 46 88 42 84 42 76 Z" fill="#fff"/></g></svg>"""


def _scene(host, start, css, body, js):
    return f"""<template>
  <style>{FONTS}
    #root {{ position: absolute; inset: 0; font-family: 'Pretendard', sans-serif; color: {NAVY}; }}
    #root * {{ box-sizing: border-box; }}
    .bg {{ position: absolute; inset: 0; background: radial-gradient(ellipse 75% 75% at 35% 55%, #FFEC9C 0%, #FCE06B 58%, #F4CC45 100%); opacity: 0; }}
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
    // 말하는 몸짓: 낭독 소리 크기(0~1)에 맞춰 프레임마다 몸이 늘었다 줄고 살짝 흔들린다 (그림은 입이 고정이라 입 대신 몸이 움직인다)
    function talk(sel, track) {{
      let last = null;
      track.forEach(([t, v]) => {{
        if (v === last) return; last = v;
        tl.set(sel, {{ scaleY: 1 + 0.05 * v, scaleX: 1 - 0.035 * v, rotation: v > 0 ? 1.5 * Math.sin(t * 6.5) * (0.35 + 0.65 * v) : 0 }}, g(t));
      }});
    }}
    function bob(sel, from, to, amp) {{
      const n = Math.max(1, Math.round((to - from) / 0.7) - 1);
      tl.fromTo(sel, {{ y: 0 }}, {{ y: -amp, duration: 0.35, yoyo: true, repeat: n * 2 + 1, ease: "sine.inOut", immediateRender: false }}, g(from));
      tl.set(sel, {{ y: 0 }}, g(from + (n * 2 + 2) * 0.35));
    }}
    // 포즈 바꾸기: 앞 포즈는 사라지고 새 포즈가 톡 나타난다
    function pose(pid, from, to, t) {{
      if (from) tl.to("#" + pid + "-p-" + from, {{ opacity: 0, duration: 0.14, ease: "none", immediateRender: false }}, g(t));
      tl.fromTo("#" + pid + "-p-" + to, {{ opacity: 0, scale: 0.93 }}, {{ opacity: 1, scale: 1, duration: 0.22, ease: "back.out(2)", immediateRender: false }}, g(t));
    }}
    // 전구 빛: 켜지고 tOff 전까지 깜빡이다가 tOff 에 꺼진다 (깜빡임이 tOff 보다 먼저 끝나야 끄는 동작이 덮이지 않는다)
    function glow(pid, t, tOff) {{
      tl.fromTo("#" + pid + "-glow", {{ opacity: 0, scale: 0.4 }}, {{ opacity: 1, scale: 1, duration: 0.4, ease: "back.out(2)", immediateRender: false }}, g(t));
      const n = Math.min(6, Math.floor((tOff - t - 0.45) / 0.2));
      if (n >= 2) tl.to("#" + pid + "-glow", {{ opacity: 0.55, duration: 0.2, yoyo: true, repeat: n - 1, ease: "sine.inOut", immediateRender: false }}, g(t + 0.4));
      tl.to("#" + pid + "-glow", {{ opacity: 0, duration: 0.15, ease: "none", immediateRender: false }}, g(tOff));
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
    poses = load_poses(proj, ["idea", "hug", "like", "wave"])
    op1, op2 = T["op1"], T["op2"]
    d1 = op1["end"] - op1["start"]
    op_end = off + 0.4
    lips_op = mouth_track(wav, op1["start"], op2["end"])
    anchor = f"left: {FEET_X}px; top: {FEET_Y}px; width: 0; height: 0; opacity: 0;"
    # ---------------- 오프닝: 전구 켠 찰스("30초", "지식 하나") → 손 흔드는 찰스("찰스입니다!")
    css = f"""
    #op-ch {{ position: absolute; {anchor} }}
    #op-talk {{ position: absolute; left: 0; top: 0; width: 0; height: 0; transform-origin: 0 0; }}
    #op-30 {{ left: 1010px; top: 120px; font-size: 270px; line-height: 1; color: {NAVY}; }}
    #op-know {{ left: 1020px; top: 405px; font-size: 128px; line-height: 1; color: {NAVY}; }}
    #op-plate {{ position: absolute; left: 1010px; top: 590px; height: 190px; padding: 0 44px; border-radius: 44px; background: {NAVY}; display: flex; align-items: baseline; gap: 18px; opacity: 0; white-space: nowrap; }}
    #op-plate b {{ font-weight: 800; font-size: 150px; line-height: 190px; color: {YELLOW}; letter-spacing: -0.03em; }}
    #op-plate span {{ font-weight: 800; font-size: 70px; color: {CREAM}; }}
    #op-dots {{ position: absolute; inset: 0; opacity: 0; }}
"""
    dots = "".join(f'<circle cx="{x}" cy="{y}" r="{r}" fill="#FFFFFF" opacity="0.55"/>' for x, y, r in
                   [(120, 160, 5), (300, 90, 4), (1500, 90, 6), (1800, 200, 5), (1700, 960, 6), (200, 960, 4), (900, 80, 4), (1860, 600, 5), (60, 520, 6)])
    body = f"""      <div class="bg" id="op-bg"></div>
      <svg id="op-dots" viewBox="0 0 1920 1080" width="1920" height="1080" style="position:absolute;inset:0">{dots}</svg>
      <div id="op-ch"><div id="op-talk">
        {glow_html('op', poses['idea'])}
        {pose_html('op', {k: poses[k] for k in ('idea', 'wave')})}
      </div></div>
      <div class="tx" id="op-30">30초</div>
      <div class="tx" id="op-know">지식 하나</div>
      <div id="op-plate"><b>찰스</b><span>입니다!</span></div>"""
    s1 = op1["start"]
    js = f"""
    tl.fromTo(["#op-bg", "#op-dots"], {{ opacity: 0.001 }}, {{ opacity: 1, duration: 0.01, ease: "none" }}, 0);
    tl.set("#op-p-idea", {{ opacity: 1 }}, 0);
    tl.fromTo("#op-ch", {{ opacity: 0.6, scale: 0.88 }}, {{ opacity: 1, scale: 1, duration: 0.5, ease: "back.out(1.6)", transformOrigin: "0px 0px" }}, 0);
    bob("#op-ch", 0.6, {op2['end'] + 0.6:.2f}, 8);
    talk("#op-talk", {json.dumps(lips_op)});
    // 30초 / 지식 하나(전구) / 찰스입니다(손 흔들기)
    tl.fromTo("#op-30", {{ opacity: 0, scale: 0.6, transformOrigin: "0% 50%" }}, {{ opacity: 1, scale: 1, duration: 0.45, ease: "back.out(1.8)" }}, g({s1 + 0.05:.2f}));
    tl.fromTo("#op-know", {{ opacity: 0, y: 30, filter: "blur(10px)" }}, {{ opacity: 1, y: 0, filter: "blur(0px)", duration: 0.35, ease: "power3.out" }}, g({s1 + 0.43 * d1:.2f}));
    glow("op", {s1 + 0.43 * d1:.2f}, {op2['start'] - 0.1:.2f});
    pose("op", "idea", "wave", {op2['start'] - 0.1:.2f});
    tl.fromTo("#op-plate", {{ opacity: 0, scale: 0.7, transformOrigin: "0% 50%" }}, {{ opacity: 1, scale: 1, duration: 0.45, ease: "back.out(1.7)" }}, g({op2['start'] + 0.05:.2f}));
    tl.fromTo("#op-ch", {{ y: 0 }}, {{ y: -26, duration: 0.2, yoyo: true, repeat: 1, ease: "power2.out", immediateRender: false }}, g({op2['start'] + 0.05:.2f}));
    // 본편으로 넘어간다
    tl.to("#op-all", {{ opacity: 0, filter: "blur(8px)", duration: 0.35, ease: "power2.in" }}, g({off - 0.05:.2f}));
"""
    (proj / "compositions" / "opening.html").write_text(_scene("opening", 0, css, body, js), encoding="utf-8")

    # ---------------- 엔딩: 전구 켠 찰스("알아두면 언젠가 쓴다!") → 가슴에 손(유익했다면) → 엄지 척 윙크(좋아요와 구독) → 손 흔들기(찰스였습니다!)
    e1, e2a, e2b, e2c, e3 = T["en1"], T["en2a"], T["en2b"], T["en2c"], T["en3"]
    es = e1["start"] - 0.45
    de1 = e1["end"] - e1["start"]
    lips_en = mouth_track(wav, e1["start"], e3["end"])
    css = f"""
    #en-ch {{ position: absolute; {anchor} }}
    #en-talk {{ position: absolute; left: 0; top: 0; width: 0; height: 0; transform-origin: 0 0; }}
    #en-a {{ left: 1000px; top: 150px; font-size: 118px; line-height: 1.05; color: {NAVY}; }}
    #en-b {{ left: 1000px; top: 280px; font-size: 150px; line-height: 1.05; color: {NAVY}; }}
    #en-name {{ left: 1000px; top: 170px; font-size: 230px; line-height: 1; color: {NAVY}; }}
    #en-name2 {{ left: 1010px; top: 430px; font-size: 80px; color: {NAVY}; }}
    .ico {{ position: absolute; width: 0; height: 0; opacity: 0; }}
    #en-like {{ left: 1090px; top: 640px; }}
    #en-sub {{ position: absolute; left: 1300px; top: 570px; width: 440px; height: 140px; border-radius: 70px; background: {CORAL}; display: flex; align-items: center; justify-content: center; gap: 22px; opacity: 0; font-weight: 800; font-size: 64px; color: #fff; }}
    #en-subicon {{ position: relative; width: 70px; height: 70px; }}
    #en-ghostl, #en-ghosts {{ position: absolute; opacity: 0; border: 6px dashed rgba(19,48,91,0.4); }}
    #en-ghostl {{ left: 1015px; top: 565px; width: 150px; height: 150px; border-radius: 75px; }}
    #en-ghosts {{ left: 1300px; top: 570px; width: 440px; height: 140px; border-radius: 70px; }}
    #en-hearts {{ position: absolute; inset: 0; width: 1920px; height: 1080px; overflow: visible; }}
"""
    hearts = "".join(f'<path id="en-h{i}" d="M0,10 C-14,-2 -10,-12 -4,-12 C-1,-12 0,-9 0,-8 C0,-9 1,-12 4,-12 C10,-12 14,-2 0,10 Z" fill="{CORAL}" opacity="0" transform="translate({1090 + (i - 2) * 26} 620) scale(2)"/>' for i in range(5))
    body = f"""      <div class="bg" id="en-bg"></div>
      <div id="en-ch"><div id="en-talk">
        {glow_html('en', poses['idea'])}
        {pose_html('en', poses)}
      </div></div>
      <div class="tx" id="en-a">알아두면</div>
      <div class="tx" id="en-b">언젠가 쓴다!</div>
      <div class="tx" id="en-name">찰스</div>
      <div class="tx" id="en-name2">였습니다!</div>
      <div id="en-ghostl"></div><div id="en-ghosts"></div>
      <div class="ico" id="en-like">{THUMB.format(c=CORAL)}</div>
      <div id="en-sub"><span id="en-subicon">{BELL.format(p='ens')}</span><span>구독</span></div>
      <svg id="en-hearts" viewBox="0 0 1920 1080">{hearts}</svg>"""
    js = f"""
    tl.fromTo("#en-bg", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.35, ease: "power2.out" }}, 0);
    tl.set("#en-p-idea", {{ opacity: 1 }}, 0);
    tl.fromTo("#en-ch", {{ opacity: 0, scale: 0.85 }}, {{ opacity: 1, scale: 1, duration: 0.5, ease: "back.out(1.6)", transformOrigin: "0px 0px" }}, g({es + 0.15:.2f}));
    bob("#en-ch", {es + 0.7:.2f}, {total - 0.3:.2f}, 8);
    talk("#en-talk", {json.dumps(lips_en)});
    // 알아두면 / 언젠가 쓴다 + 전구
    tl.fromTo("#en-a", {{ opacity: 0, y: 30, filter: "blur(10px)" }}, {{ opacity: 1, y: 0, filter: "blur(0px)", duration: 0.35, ease: "power3.out" }}, g({e1['start'] + 0.05:.2f}));
    tl.fromTo("#en-b", {{ opacity: 0, scale: 0.7, transformOrigin: "0% 50%" }}, {{ opacity: 1, scale: 1, duration: 0.45, ease: "back.out(1.8)" }}, g({e1['start'] + 0.5 * de1:.2f}));
    glow("en", {e1['start'] + 0.5 * de1:.2f}, {e2a['start'] - 0.1:.2f});
    // 좋아요와 구독: 점선 자리가 먼저 나오고 말할 때 채워진다
    tl.to(["#en-a", "#en-b"], {{ opacity: 0.0, filter: "blur(8px)", duration: 0.3, ease: "power2.in", immediateRender: false }}, g({e1['end'] + 0.1:.2f}));
    tl.fromTo(["#en-ghostl", "#en-ghosts"], {{ opacity: 0 }}, {{ opacity: 1, duration: 0.35, ease: "power2.out" }}, g({e1['end'] + 0.2:.2f}));
    pose("en", "idea", "hug", {e2a['start'] - 0.1:.2f});
    pose("en", "hug", "like", {e2b['start'] - 0.1:.2f});
    tl.fromTo("#en-like", {{ opacity: 0, scale: 0.4 }}, {{ opacity: 1, scale: 1, duration: 0.4, ease: "back.out(2.2)" }}, g({e2b['start'] + 0.05:.2f}));
    tl.fromTo("#en-ghostl", {{ opacity: 1 }}, {{ opacity: 0, duration: 0.2, immediateRender: false }}, g({e2b['start'] + 0.05:.2f}));
    [0, 1, 2, 3, 4].forEach(i => {{
      tl.fromTo("#en-h" + i, {{ opacity: 0, y: 0, x: 0 }}, {{ opacity: 1, y: -90 - i * 14, x: (i - 2) * 22, duration: 0.5, ease: "power2.out", immediateRender: false }}, g({e2b['start'] + 0.15:.2f} + i * 0.06));
      tl.to("#en-h" + i, {{ opacity: 0, duration: 0.2, ease: "power1.in", immediateRender: false }}, g({e2b['start'] + 0.6:.2f} + i * 0.06));
    }});
    tl.fromTo("#en-sub", {{ opacity: 0, scale: 0.6, transformOrigin: "50% 50%" }}, {{ opacity: 1, scale: 1, duration: 0.4, ease: "back.out(2)" }}, g({e2c['start'] + 0.0:.2f}));
    tl.fromTo("#en-ghosts", {{ opacity: 1 }}, {{ opacity: 0, duration: 0.2, immediateRender: false }}, g({e2c['start'] + 0.0:.2f}));
    tl.fromTo("#ens-bell", {{ rotation: 0 }}, {{ rotation: 18, duration: 0.09, yoyo: true, repeat: 5, ease: "sine.inOut", immediateRender: false }}, g({e2c['start'] + 0.35:.2f}));
    // 찰스였습니다 (손 흔드는 찰스)
    pose("en", "like", "wave", {e3['start'] - 0.1:.2f});
    tl.fromTo("#en-name", {{ opacity: 0, scale: 0.6, transformOrigin: "0% 50%" }}, {{ opacity: 1, scale: 1, duration: 0.45, ease: "back.out(1.7)" }}, g({e3['start'] + 0.05:.2f}));
    tl.fromTo("#en-name2", {{ opacity: 0, y: 20 }}, {{ opacity: 1, y: 0, duration: 0.35, ease: "power3.out" }}, g({e3['start'] + 0.35:.2f}));
"""
    (proj / "compositions" / "ending.html").write_text(_scene("ending", es, css, body, js), encoding="utf-8")
    hosts = (f'<div id="opening" data-composition-id="opening" data-composition-src="compositions/opening.html" data-start="0" data-duration="{op_end:.2f}" '
             f'data-track-index="5" data-track-kind="graphics" data-width="1920" data-height="1080" style="z-index: 70"></div>\n      '
             f'<div id="ending" data-composition-id="ending" data-composition-src="compositions/ending.html" data-start="{es:.2f}" data-duration="{total - es:.2f}" '
             f'data-track-index="5" data-track-kind="graphics" data-width="1920" data-height="1080" style="z-index: 70"></div>')
    return hosts, es
