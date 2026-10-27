#!/usr/bin/env python3
"""「꿀벌의 8자 춤」(찰스 오프닝·엔딩 판) HyperFrames 프로젝트 생성기. 사용: python3 -I build_video.py <프로젝트 폴더> [scripts 폴더]
입력: assets/{timing,clauses_timing,parts}.json, duration.txt, narration.wav (build_track.py)  출력: index.html, compositions/*.html
오프닝·엔딩(찰스)은 jarvis-knowledge-video 의 scripts/charles.py 가 만든다.
장면 그림은 reference/bee-dance/build_video.py (30초 판)에서 가져오고, 모든 등장 시각을 낭독 시각표(절 시작·끝)에서 계산한다.
본편 장면은 본편 시각(전체 시각에서 OFF 를 뺀 값)으로 쓰고 호스트의 data-start 에만 OFF 를 더한다."""
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
T = {k: {**v, "start": v["start"] - OFF, "end": v["end"] - OFF} for k, v in raw.items() if v["part"] == "body"}
ES = raw["en1"]["start"] - 0.45 - OFF   # 엔딩 장면이 시작하는 본편 시각
c1a, c1b, c2a, c2b, c2c, c3a, c3b, c3c, c4a, c4b, c5a, c5b = (T[k] for k in ("1a", "1b", "2a", "2b", "2c", "3a", "3b", "3c", "4a", "4b", "5a", "5b"))

# 장면 경계 (본편 시각). 장면은 0.3초씩 겹친다.
D_START = round(c3a["start"] - 0.3, 2)      # 방향 장면 시작
S3_START = round(c4a["start"] - 0.3, 2)     # 거리 장면 시작
O_START = round(c5a["start"] - 0.3, 2)      # 끝 장면(카를 폰 프리슈) 시작
HIVE_DUR = D_START + 0.3
DIR_DUR = S3_START + 0.3 - D_START
DIST_DUR = O_START + 0.3 - S3_START
OUT_DUR = ES + 0.4 - O_START

FONTS = """
    @font-face { font-family: 'Pretendard'; font-weight: 400; src: url('assets/fonts/Pretendard-Regular.woff2') format('woff2'); }
    @font-face { font-family: 'Pretendard'; font-weight: 700; src: url('assets/fonts/Pretendard-Bold.woff2') format('woff2'); }
    @font-face { font-family: 'Pretendard'; font-weight: 800; src: url('assets/fonts/Pretendard-ExtraBold.woff2') format('woff2'); }
"""


def dashed(pid, d, color, width, dash="14 12", mask_w=26, opacity=1):
    """A dashed line that can be drawn in: the dashes are revealed by a mask path whose own dash offset is animated."""
    return (f'<mask id="{pid}-mk" maskUnits="userSpaceOnUse" x="-3000" y="-3000" width="8000" height="8000">'
            f'<path id="{pid}-m" d="{d}" fill="none" stroke="#fff" stroke-width="{mask_w}" pathLength="100" stroke-dasharray="100 120" stroke-dashoffset="100"/></mask>'
            f'<path id="{pid}" d="{d}" fill="none" stroke="{color}" stroke-width="{width}" stroke-dasharray="{dash}" stroke-linecap="round" mask="url(#{pid}-mk)" opacity="{opacity}"/>')


def bee(prefix, scale=1.0):
    """Top-down bee facing +x, centred on its origin. prefix keeps clipPath ids unique across the page."""
    return f"""<svg class="bee" width="130" height="90" viewBox="-65 -45 130 90" style="position:absolute;left:-65px;top:-45px;overflow:visible">
        <g id="{prefix}-wl"><ellipse cx="-4" cy="-27" rx="24" ry="14" fill="#ffffff" opacity="0.62" transform="rotate(-22 -4 -27)"/></g>
        <g id="{prefix}-wr"><ellipse cx="-4" cy="27" rx="24" ry="14" fill="#ffffff" opacity="0.62" transform="rotate(22 -4 27)"/></g>
        <clipPath id="{prefix}-clip"><ellipse cx="0" cy="0" rx="40" ry="26"/></clipPath>
        <ellipse cx="0" cy="0" rx="40" ry="26" fill="#F6B21A"/>
        <g clip-path="url(#{prefix}-clip)"><path d="M-20,-30 Q-11,0 -20,30 L-8,30 Q1,0 -8,-30Z" fill="#2B1A0A"/><path d="M6,-30 Q15,0 6,30 L18,30 Q27,0 18,-30Z" fill="#2B1A0A"/></g>
        <path d="M-40,0 L-54,-5 L-54,5Z" fill="#2B1A0A"/>
        <circle cx="42" cy="0" r="16" fill="#2B1A0A"/>
        <circle cx="49" cy="-6" r="4" fill="#fff"/><circle cx="50" cy="-6" r="1.8" fill="#111"/>
        <circle cx="49" cy="6" r="4" fill="#fff"/><circle cx="50" cy="6" r="1.8" fill="#111"/>
        <path d="M50,-13 Q57,-26 66,-28" stroke="#2B1A0A" stroke-width="2.6" fill="none" stroke-linecap="round"/>
        <path d="M50,13 Q57,26 66,28" stroke="#2B1A0A" stroke-width="2.6" fill="none" stroke-linecap="round"/>
      </svg>"""


# figure-8 dance path (local frame: the straight waggle run is vertical, bottom -> top)
DANCE_D = ("M 0 100 L 0 -100 "
           "C 130 -300 330 -160 300 0 C 280 180 120 210 0 100 "
           "L 0 -100 "
           "C -130 -300 -330 -160 -300 0 C -280 180 -120 210 0 100")
# fraction of the whole path at each segment boundary: run, right lobe, run, left lobe
B = [0.0, 0.0924, 0.5, 0.5924, 1.0]


def scene(host_id, start, duration, body_html, css, script, wrap):
    return f"""<template>
  <style>{FONTS}
    #root {{ position: absolute; inset: 0; font-family: 'Pretendard', sans-serif; color: #FFF4D6; }}
    #root * {{ box-sizing: border-box; }}
{css}
  </style>
  <div id="root" data-composition-id="{host_id}" data-width="1920" data-height="1080">
    <div id="{wrap}" style="position:absolute;inset:0">
{body_html}
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


COMMON_JS = """
    const WIGGLE = 0.06;
    // wing flaps: finite count so every frame is a pure function of time
    function flaps(sel, from, to) {
      const n = Math.max(2, Math.round((to - from) / 0.07));
      tl.fromTo(sel, { scaleY: 1, transformOrigin: "50% 50%" }, { scaleY: 0.35, duration: 0.07, repeat: n, yoyo: true, ease: "sine.inOut", immediateRender: false }, from);
    }
    // waggle: quick side-to-side shake of the body while it is on the straight run
    function wiggle(sel, from, dur) {
      const n = Math.max(1, Math.round(dur / WIGGLE) - 1);
      tl.fromTo(sel, { rotation: -11, transformOrigin: "50% 50%" }, { rotation: 11, duration: WIGGLE, repeat: n, yoyo: true, ease: "sine.inOut", immediateRender: false }, from);
      tl.set(sel, { rotation: 0 }, from + dur);
    }
    function rest(sel, props) { tl.set(sel, props, 0); }
    // one pass over the figure-8: [run, right lobe, run, left lobe] with their own durations
    function dancePass(beeSel, trailSel, start, runD, lobeD, drawTrail) {
      const B = __B__;
      const dur = [runD, lobeD, runD, lobeD];
      let t = start;
      for (let i = 0; i < 4; i++) {
        tl.fromTo(beeSel, { offsetDistance: (B[i] * 100) + "%" }, { offsetDistance: (B[i + 1] * 100) + "%", duration: dur[i], ease: (i % 2 === 0 ? "none" : "sine.inOut"), immediateRender: false }, t);
        if (drawTrail) tl.fromTo(trailSel, { strokeDashoffset: 100 - B[i] * 100 }, { strokeDashoffset: 100 - B[i + 1] * 100, duration: dur[i], ease: (i % 2 === 0 ? "none" : "sine.inOut"), immediateRender: false }, t);
        t += dur[i];
      }
      return t;
    }
""".replace("__B__", json.dumps(B))


def dance_passes(sel_bee, sel_inner, sel_trail, start, length, target, draw_first):
    """Fill [start, start+length] with whole figure-8 passes of about `target` seconds each, so the last pass ends exactly at the end
    (the bee is back at the start pose when the next scene takes over). run : lobe = 2 : 3."""
    n = max(1, round(length / target)); P = length / n; run, lobe = 0.2 * P, 0.3 * P
    out = []
    for k in range(n):
        s = start + k * P
        out.append(f'    dancePass("{sel_bee}", "{sel_trail}", {s:.3f}, {run:.3f}, {lobe:.3f}, {"true" if (k == 0 and draw_first) else "false"});')
        out.append(f'    wiggle("{sel_inner}", {s:.3f}, {run:.3f});')
        out.append(f'    wiggle("{sel_inner}", {s + run + lobe:.3f}, {run:.3f});')
    return "\n".join(out)


# ---------------------------------------------------------------- scene 1: hive (0 - D_START + 0.3)
hive_css = """
    #hv-glow { position: absolute; inset: 0; background: radial-gradient(ellipse 60%% 55%% at 50%% 50%%, rgba(255,184,60,0.55), rgba(255,184,60,0) 70%%); }
    #hv-dim { position: absolute; inset: 0; background: radial-gradient(ellipse 70%% 70%% at 50%% 50%%, rgba(0,0,0,0.25), rgba(0,0,0,0.9) 100%%); opacity: 0; }
    #hv-title { position: absolute; left: 0; top: 380px; width: 1920px; text-align: center; font-weight: 800; font-size: 168px; letter-spacing: -0.03em; color: #FFD36B; white-space: nowrap; }
    #hv-sub { position: absolute; left: 0; top: 590px; width: 1920px; text-align: center; font-weight: 400; font-size: 52px; color: #FFF4D6; opacity: 0; }
    #hv-wall { position: absolute; left: 610px; top: 210px; width: 700px; height: 620px; border-radius: 36px; background: rgba(58,36,10,0.62); border: 3px solid rgba(255,190,70,0.5); opacity: 0; }
    #hv-wall-svg { position: absolute; left: 610px; top: 210px; width: 700px; height: 620px; opacity: 0; }
    #hv-walllbl { position: absolute; left: 650px; top: 232px; font-weight: 700; font-size: 36px; color: rgba(255,244,214,0.85); opacity: 0; white-space: nowrap; }
    #hv-dancelbl { position: absolute; left: 610px; top: 762px; width: 700px; text-align: center; font-weight: 800; font-size: 54px; color: #FFB81C; opacity: 0; }
    #hv-dance { position: absolute; left: 960px; top: 540px; width: 0; height: 0; }
    #hv-trail { position: absolute; left: 0; top: 0; overflow: visible; }
    #hv-bee-d { position: absolute; left: 0; top: 0; width: 0; height: 0; offset-path: path('%(D)s'); offset-rotate: auto; offset-distance: 0%%; opacity: 0; }
    #hv-fly { position: absolute; left: 0; top: 0; width: 0; height: 0; opacity: 0; }
    #hv-flower { position: absolute; left: 1470px; top: 560px; width: 0; height: 0; opacity: 0; }
    #hv-qsvg { position: absolute; left: 0; top: 0; width: 1920px; height: 1080px; overflow: visible; }
    #hv-q { position: absolute; left: 930px; top: 430px; width: 120px; text-align: center; font-weight: 800; font-size: 130px; color: #FFB81C; opacity: 0; }
    #hv-word { position: absolute; left: 0; top: 80px; width: 1920px; text-align: center; font-weight: 800; font-size: 150px; color: #FFB81C; opacity: 0; }
""" % {"D": DANCE_D}

hive_body = f"""    <div id="hv-glow"></div>
    <div id="hv-dim"></div>
    <div id="hv-title">꿀벌의 8자 춤</div>
    <div id="hv-sub">길을 알려 주는 춤</div>
    <svg id="hv-qsvg" viewBox="0 0 1920 1080">{dashed("hv-qpath", "M 560 620 C 760 470 1180 470 1380 590", "#FFB81C", 7, "3 14")}</svg>
    <div id="hv-q">?</div>
    <div id="hv-flower">
      <svg width="260" height="300" viewBox="-130 -190 260 300" style="position:absolute;left:-130px;top:-190px;overflow:visible">
        <path d="M0,0 L0,110" stroke="#5CBF6E" stroke-width="12" stroke-linecap="round"/>
        <path d="M0,70 Q-50,60 -64,26 Q-20,28 0,70Z" fill="#5CBF6E"/>
        <g fill="#FF7A8A"><circle cx="0" cy="-62" r="40"/><circle cx="59" cy="-19" r="40"/><circle cx="36" cy="50" r="40"/><circle cx="-36" cy="50" r="40"/><circle cx="-59" cy="-19" r="40"/></g>
        <circle cx="0" cy="0" r="34" fill="#FFD84A"/>
      </svg>
    </div>
    <div id="hv-wall"></div>
    <svg id="hv-wall-svg" viewBox="0 0 700 620" aria-hidden="true">
      <defs><pattern id="hv-hex" width="97" height="168" patternUnits="userSpaceOnUse">
        <g fill="none" stroke="rgba(255,190,70,0.22)" stroke-width="2">
          <path d="M48.5,0 L97,28 L97,84 L48.5,112 L0,84 L0,28Z" transform="translate(0,0)"/>
          <path d="M0,112 L48.5,140 L48.5,196 L0,224 L-48.5,196 L-48.5,140Z" transform="translate(0,-28)"/>
          <path d="M97,112 L145.5,140 L145.5,196 L97,224 L48.5,196 L48.5,140Z" transform="translate(0,-28)"/>
        </g></pattern></defs>
      <rect x="0" y="0" width="700" height="620" rx="36" fill="url(#hv-hex)"/>
    </svg>
    <div id="hv-walllbl" data-layout-allow-overlap>벌집 안, 세로 벽</div>
    <div id="hv-dancelbl">8자 춤</div>
    <div id="hv-word">춤!</div>
    <div id="hv-dance">
      <svg id="hv-trail" width="1" height="1" viewBox="0 0 1 1"><path id="hv-trail-p" d="{DANCE_D}" fill="none" stroke="#FFD36B" stroke-width="7" stroke-linecap="round" stroke-linejoin="round" pathLength="100" stroke-dasharray="100 120" stroke-dashoffset="100" opacity="0.9"/></svg>
      <div id="hv-bee-d"><div id="hv-bee-d-in">{bee("hvd")}</div></div>
    </div>
    <div id="hv-fly"><div id="hv-fly-in">{bee("hvf")}</div></div>"""

DS = 1.05  # dance figure scale
TITLE_OUT = round(c1b["start"] - 0.1, 2)    # the title leaves just before the question is asked
FLY = c1b["start"]                          # the forager bee flies in with the question
D0 = round(c2c["start"] - 0.05, 2)          # hand over from the flying bee to the dancing bee
hive_js = COMMON_JS + f"""
    const S = {DS}, BS = 1.35;
    // opening: first frame is already readable (title is on screen from t=0)
    tl.fromTo("#hv-title", {{ opacity: 0.35, filter: "blur(14px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.5, ease: "power3.out" }}, 0);
    // "깜깜합니다": the warm glow drains out and the hive goes dark
    tl.fromTo("#hv-dim", {{ opacity: 0 }}, {{ opacity: 0.85, duration: {c1a['end'] - c1a['start']:.2f}, ease: "power2.inOut" }}, {c1a['start']:.2f});
    tl.fromTo("#hv-glow", {{ opacity: 1 }}, {{ opacity: 0.0, duration: {c1a['end'] - c1a['start']:.2f}, ease: "power2.inOut" }}, {c1a['start']:.2f});
    tl.fromTo("#hv-sub", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 0.9, filter: "blur(0px)", duration: 0.4, ease: "power3.out" }}, 0.9);
    // the title leaves, the hive stays dark
    tl.to(["#hv-title", "#hv-sub"], {{ opacity: 0, filter: "blur(10px)", duration: 0.35, ease: "power3.in" }}, {TITLE_OUT});

    // 1b: the forager bee flies in, a flower far away, a question between them
    tl.set("#hv-fly", {{ opacity: 1 }}, {FLY:.2f});
    tl.fromTo("#hv-fly", {{ x: -160, y: 640 }}, {{ x: 560, y: 640, duration: 1.1, ease: "power3.out" }}, {FLY:.2f});
    gsap.set("#hv-fly-in", {{ scale: S * BS }});
    flaps("#hvf-wl", {FLY:.2f}, {D0}); flaps("#hvf-wr", {FLY:.2f}, {D0});
    tl.fromTo("#hv-flower", {{ opacity: 0, scale: 0.2, transformOrigin: "50% 100%" }}, {{ opacity: 1, scale: 1, duration: 0.55, ease: "back.out(1.4)" }}, {FLY + 0.4:.2f});
    tl.fromTo("#hv-qpath-m", {{ strokeDashoffset: 100 }}, {{ strokeDashoffset: 0, duration: 1.0, ease: "power2.out" }}, {FLY + 0.7:.2f});
    tl.fromTo("#hv-q", {{ opacity: 0, scale: 0.5, transformOrigin: "50% 50%" }}, {{ opacity: 1, scale: 1, duration: 0.4, ease: "back.out(1.7)" }}, {FLY + 1.3:.2f});
    tl.to(["#hv-flower", "#hv-q", "#hv-qpath"], {{ opacity: 0, duration: 0.3, ease: "power3.in" }}, {c1b['end'] + 0.15:.2f});

    // 2a: the answer, a dance. The bee turns to face "up" at the start of the dance path.
    tl.fromTo("#hv-fly", {{ x: 560, y: 640 }}, {{ x: 960, y: {540 + 100 * DS:.0f}, duration: 0.75, ease: "power2.inOut" }}, {c2a['start'] - 0.35:.2f});
    tl.fromTo("#hv-fly-in", {{ rotation: 0 }}, {{ rotation: -90, duration: 0.75, ease: "power2.inOut" }}, {c2a['start'] - 0.35:.2f});
    tl.fromTo("#hv-word", {{ opacity: 0, scale: 1.3, filter: "blur(10px)", transformOrigin: "50% 50%" }}, {{ opacity: 1, scale: 1, filter: "blur(0px)", duration: 0.35, ease: "expo.out" }}, {c2a['start']:.2f});
    tl.to("#hv-word", {{ opacity: 0, filter: "blur(8px)", duration: 0.25, ease: "power3.in" }}, {c2a['end'] + 0.1:.2f});

    // 2b: back in the hive: the comb wall fades in around the bee
    tl.fromTo(["#hv-wall", "#hv-wall-svg"], {{ opacity: 0 }}, {{ opacity: 1, duration: 0.6, ease: "power2.out" }}, {c2b['start'] + 0.1:.2f});
    tl.fromTo("#hv-walllbl", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.4, ease: "power2.out" }}, {c2b['start'] + 0.3:.2f});
    gsap.set("#hv-dance", {{ scale: S }});
    gsap.set("#hv-bee-d-in", {{ scale: BS }});

    // 2c: the 8-shaped dance. Hand over from the flying bee to the dancing bee at the same pose.
    tl.set("#hv-fly", {{ opacity: 0 }}, {D0});
    tl.set("#hv-bee-d", {{ opacity: 1 }}, {D0});
    flaps("#hvd-wl", {D0}, {HIVE_DUR:.2f}); flaps("#hvd-wr", {D0}, {HIVE_DUR:.2f});
    tl.fromTo("#hv-dancelbl", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.35, ease: "power3.out" }}, {c2c['start'] + 0.4:.2f});
    rest("#hv-bee-d", {{ offsetDistance: "0%" }}); rest("#hv-bee-d-in", {{ rotation: 0 }});
{dance_passes("#hv-bee-d", "#hv-bee-d-in", "#hv-trail-p", c2c['start'], D_START - c2c['start'], 2.2, True)}
    // exit: last 0.3 s fades out while the next scene takes over the same pose
    tl.to("#hv-all", {{ opacity: 0, duration: 0.3, ease: "power2.in" }}, {D_START:.2f});
"""
(proj / "compositions" / "hive.html").write_text(scene("hive", 0, HIVE_DUR, hive_body, hive_css, hive_js, "hv-all"), encoding="utf-8")
print("hive ok")


# ---------------------------------------------------------------- shared svg pieces
def sun(prefix, r=26):
    rays = "".join(f'<line x1="0" y1="{-(r+10)}" x2="0" y2="{-(r+24)}" stroke="#FFD84A" stroke-width="5" stroke-linecap="round" transform="rotate({a})"/>' for a in range(0, 360, 45))
    return f'<svg width="120" height="120" viewBox="-60 -60 120 120" style="position:absolute;left:-60px;top:-60px;overflow:visible"><circle cx="0" cy="0" r="{r}" fill="#FFD84A"/>{rays}</svg>'


def flower(scale=1.0):
    return f"""<svg width="260" height="300" viewBox="-130 -190 260 300" style="position:absolute;left:-130px;top:-190px;overflow:visible;transform:scale({scale});transform-origin:130px 190px">
        <path d="M0,0 L0,110" stroke="#5CBF6E" stroke-width="12" stroke-linecap="round"/>
        <g fill="#FF7A8A"><circle cx="0" cy="-62" r="40"/><circle cx="59" cy="-19" r="40"/><circle cx="36" cy="50" r="40"/><circle cx="-36" cy="50" r="40"/><circle cx="-59" cy="-19" r="40"/></g>
        <circle cx="0" cy="0" r="34" fill="#FFD84A"/></svg>"""


HEXTILE = """<defs><pattern id="%s" width="97" height="168" patternUnits="userSpaceOnUse">
        <g fill="none" stroke="rgba(255,190,70,0.22)" stroke-width="2">
          <path d="M48.5,0 L97,28 L97,84 L48.5,112 L0,84 L0,28Z"/>
          <path d="M0,112 L48.5,140 L48.5,196 L0,224 L-48.5,196 L-48.5,140Z" transform="translate(0,-28)"/>
          <path d="M97,112 L145.5,140 L145.5,196 L97,224 L48.5,196 L48.5,140Z" transform="translate(0,-28)"/>
        </g></pattern></defs>"""

PILL = "padding:2px 16px;border-radius:14px;background:rgba(18,13,8,0.82);"

# ---------------------------------------------------------------- scene 2: direction (D_START - S3_START + 0.3)
TH = 40          # angle between the waggle run and "up", in degrees
FS = 0.92        # figure scale once it sits in the left panel
sn, cs_ = math.sin(math.radians(TH)), math.cos(math.radians(TH))
dir_css = """
    #dr-wall { position: absolute; left: 610px; top: 210px; width: 700px; height: 620px; border-radius: 36px; background: rgba(58,36,10,0.62); border: 3px solid rgba(255,190,70,0.5); }
    #dr-wall-svg { position: absolute; left: 610px; top: 210px; width: 700px; height: 620px; }
    #dr-walllbl { position: absolute; left: 650px; top: 232px; font-weight: 700; font-size: 36px; color: rgba(255,244,214,0.85); white-space: nowrap; }
    #dr-call { position: absolute; left: 130px; top: 756px; width: 700px; text-align: center; font-weight: 700; font-size: 40px; color: #FFE08A; opacity: 0; }
    #dr-dance { position: absolute; left: 960px; top: 540px; width: 0; height: 0; }
    #dr-ref, #dr-trail { position: absolute; left: 0; top: 0; overflow: visible; }
    #dr-fig { position: absolute; left: 0; top: 0; width: 0; height: 0; }
    #dr-bee { position: absolute; left: 0; top: 0; width: 0; height: 0; offset-path: path('%(D)s'); offset-rotate: auto; offset-distance: 0%%; }
    #dr-angle { position: absolute; left: 0; top: 0; width: 0; height: 0; }
    .dr-pill { position: absolute; white-space: nowrap; font-weight: 700; font-size: 38px; color: #FFB81C; %(PILL)s }
    #dr-rp { position: absolute; left: 1020px; top: 210px; width: 760px; height: 620px; border-radius: 36px; background: rgba(14,44,30,0.6); border: 3px solid rgba(127,209,139,0.42); opacity: 0; }
    #dr-rplbl { position: absolute; left: 1052px; top: 232px; font-weight: 700; font-size: 36px; color: rgba(255,244,214,0.85); white-space: nowrap; opacity: 0; }
    #dr-rsvg { position: absolute; left: 0; top: 0; width: 1920px; height: 1080px; overflow: visible; }
    #dr-hive { position: absolute; left: 1400px; top: 690px; width: 0; height: 0; opacity: 0; }
    #dr-hivelbl { position: absolute; left: 1300px; top: 722px; width: 200px; text-align: center; font-weight: 700; font-size: 34px; color: #FFF4D6; opacity: 0; }
    #dr-rsun { position: absolute; left: 1400px; top: 264px; width: 0; height: 0; opacity: 0; }
    #dr-lsun { position: absolute; width: 0; height: 0; opacity: 0; }
    #dr-flower { position: absolute; left: %(FX).0fpx; top: %(FY).0fpx; width: 0; height: 0; opacity: 0; }
    #dr-flbl { left: 1500px; top: 326px; opacity: 0; }
    #dr-eq { position: absolute; left: 830px; top: 440px; width: 190px; text-align: center; font-weight: 800; font-size: 120px; color: #FFB81C; opacity: 0; }
    #dr-uplbl { opacity: 0; }
""" % {"D": DANCE_D, "PILL": PILL, "FX": 1400 + 330 * sn, "FY": 690 - 330 * cs_}

dir_body = f"""    <div id="dr-wall"></div>
    <svg id="dr-wall-svg" viewBox="0 0 700 620" aria-hidden="true">{HEXTILE % 'dr-hex'}<rect x="0" y="0" width="700" height="620" rx="36" fill="url(#dr-hex)"/></svg>
    <div id="dr-walllbl" data-layout-allow-overlap>벌집 안, 세로 벽</div>
    <div id="dr-call">직선 구간 = 몸을 흔드는 곳</div>
    <div id="dr-dance">
      <svg id="dr-ref" width="1" height="1" viewBox="0 0 1 1">
        {dashed("dr-refline", "M 0 235 L 0 -270", "#FFF4D6", 5)}
        <path id="dr-refarrow" d="M -12 -258 L 0 -284 L 12 -258Z" fill="#FFF4D6" opacity="0"/>
        <path id="dr-arc" d="M 0 -150 A 150 150 0 0 1 {150*sn:.1f} {-150*cs_:.1f}" fill="none" stroke="#FFB81C" stroke-width="8" stroke-linecap="round" pathLength="100" stroke-dasharray="100 120" stroke-dashoffset="100" opacity="0"/>
      </svg>
      <div id="dr-lsun" style="left:0;top:-300px">{sun('dl')}</div>
      <div id="dr-fig">
        <svg id="dr-trail" width="1" height="1" viewBox="0 0 1 1">
          <path d="{DANCE_D}" fill="none" stroke="#FFD36B" stroke-width="7" stroke-linecap="round" stroke-linejoin="round" opacity="0.9"/>
          <path id="dr-runline" d="M 0 100 L 0 -100" fill="none" stroke="#FFFFFF" stroke-width="15" stroke-linecap="round" opacity="0" style="filter: drop-shadow(0 0 10px rgba(255,200,80,0.95))"/>
        </svg>
        <div id="dr-bee"><div id="dr-bee-in">{bee('drb')}</div></div>
      </div>
      <div id="dr-angle"><div id="dr-arclbl" class="dr-pill" style="left:{150*math.sin(math.radians(TH/2))*1.38 - 10:.0f}px;top:{-150*math.cos(math.radians(TH/2))*1.38 - 24:.0f}px;opacity:0">각도</div></div>
    </div>
    <div id="dr-rp"></div>
    <div id="dr-rplbl">바깥 풍경</div>
    <svg id="dr-rsvg" viewBox="0 0 1920 1080">
      {dashed("dr-rup", "M 1400 645 L 1400 300", "#FFF4D6", 5)}
      {dashed("dr-rto", f"M 1400 690 L {1400 + 330 * sn:.0f} {690 - 330 * cs_:.0f}", "#FFB81C", 7)}
      <path id="dr-rarc" d="M 1400 {690 - 140:.0f} A 140 140 0 0 1 {1400 + 140 * sn:.1f} {690 - 140 * cs_:.1f}" fill="none" stroke="#FFB81C" stroke-width="8" stroke-linecap="round" pathLength="100" stroke-dasharray="100 120" stroke-dashoffset="100" opacity="0"/>
      {dashed("dr-link", "M 480 206 C 600 96 1280 96 1400 206", "#FFD84A", 4, "4 12")}
    </svg>
    <div id="dr-hive"><svg width="100" height="100" viewBox="-50 -50 100 100" style="position:absolute;left:-50px;top:-50px"><path d="M0,-44 L38,-22 L38,22 L0,44 L-38,22 L-38,-22Z" fill="#F6B21A" stroke="#2B1A0A" stroke-width="5"/></svg></div>
    <div id="dr-hivelbl">벌집</div>
    <div id="dr-rsun">{sun('dr')}</div>
    <div id="dr-flower">{flower(0.55)}</div>
    <div id="dr-flbl" class="dr-pill">꿀이 있는 곳</div>
    <div id="dr-rarclbl" class="dr-pill" style="left:{1400 + 140*math.sin(math.radians(TH/2))*1.55 - 10:.0f}px;top:{690 - 140*math.cos(math.radians(TH/2))*1.55 - 24:.0f}px;opacity:0">같은 각도</div>
    <div id="dr-eq">=</div>"""

dir_js = COMMON_JS + f"""
    const S0 = 1.05, FS = {FS}, TH = {TH}, SLIDE = -480;
    // state carried over from the hive scene: wall, label and figure sit where the hive scene left them
    gsap.set("#dr-dance", {{ scale: S0 }});
    gsap.set("#dr-trail path:first-child", {{ strokeDashoffset: 0 }});
    gsap.set("#dr-bee-in", {{ scale: 1.35 }});
    flaps("#drb-wl", 0, {DIR_DUR:.2f}); flaps("#drb-wr", 0, {DIR_DUR:.2f});
    rest("#dr-bee", {{ offsetDistance: "0%" }}); rest("#dr-bee-in", {{ rotation: 0 }}); rest("#dr-fig", {{ rotation: 0 }});
    // the dance goes on: the first pass picks up exactly where the hive scene stopped (offset 0 at {D_START:.2f})
{dance_passes("#dr-bee", "#dr-bee-in", "", 0, DIR_DUR, 2.6, False)}
    // 0. slide the wall + figure to the left panel
    tl.to(["#dr-wall", "#dr-wall-svg", "#dr-walllbl", "#dr-dance"], {{ x: SLIDE, duration: 0.8, ease: "power2.inOut" }}, 0.15);
    tl.to("#dr-dance", {{ scale: FS, duration: 0.8, ease: "power2.inOut" }}, 0.15);
    // 3a: the straight part of the 8 lights up
    tl.fromTo("#dr-runline", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.35, ease: "power2.out" }}, g({c3a['start'] + 0.1:.2f}));
    tl.fromTo("#dr-call", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.35, ease: "power3.out" }}, g({c3a['start'] + 0.35:.2f}));
    // 3b: the angle between the run and "up" is the direction of the flowers relative to the sun
    tl.fromTo("#dr-refline-m", {{ strokeDashoffset: 100 }}, {{ strokeDashoffset: 0, duration: 0.6, ease: "power2.out" }}, g({c3b['start'] - 0.05:.2f}));
    tl.fromTo("#dr-fig", {{ rotation: 0 }}, {{ rotation: TH, duration: 0.8, ease: "power2.inOut" }}, g({c3b['start'] + 0.05:.2f}));
    tl.to("#dr-call", {{ opacity: 0, filter: "blur(8px)", duration: 0.25, ease: "power3.in" }}, g({c3b['start'] + 0.05:.2f}));
    tl.fromTo("#dr-arc", {{ opacity: 0, strokeDashoffset: 100 }}, {{ opacity: 1, strokeDashoffset: 0, duration: 0.55, ease: "power2.out" }}, g({c3b['start'] + 0.85:.2f}));
    tl.fromTo("#dr-arclbl", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.3, ease: "power2.out" }}, g({c3b['start'] + 1.2:.2f}));
    // right panel: the same angle, seen from outside
    const R0 = {c3b['start'] + 0.55:.2f};
    tl.fromTo(["#dr-rp", "#dr-rplbl"], {{ opacity: 0 }}, {{ opacity: 1, duration: 0.5, ease: "power2.out" }}, g(R0));
    tl.fromTo(["#dr-hive", "#dr-hivelbl", "#dr-rsun"], {{ opacity: 0 }}, {{ opacity: 1, duration: 0.4, ease: "power2.out" }}, g(R0 + 0.25));
    tl.fromTo("#dr-rup-m", {{ strokeDashoffset: 100 }}, {{ strokeDashoffset: 0, duration: 0.5, ease: "power2.out" }}, g(R0 + 0.45));
    tl.fromTo("#dr-flower", {{ opacity: 0, scale: 0.3, transformOrigin: "50% 100%" }}, {{ opacity: 1, scale: 1, duration: 0.5, ease: "back.out(1.4)" }}, g(R0 + 0.75));
    tl.fromTo("#dr-rto-m", {{ strokeDashoffset: 100 }}, {{ strokeDashoffset: 0, duration: 0.5, ease: "power2.out" }}, g(R0 + 0.8));
    tl.fromTo("#dr-flbl", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.3, ease: "power2.out" }}, g(R0 + 1.2));
    tl.fromTo("#dr-rarc", {{ opacity: 0, strokeDashoffset: 100 }}, {{ opacity: 1, strokeDashoffset: 0, duration: 0.45, ease: "power2.out" }}, g(R0 + 1.35));
    tl.fromTo("#dr-rarclbl", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.3, ease: "power2.out" }}, g(R0 + 1.6));
    tl.fromTo("#dr-eq", {{ opacity: 0, scale: 0.6, transformOrigin: "50% 50%" }}, {{ opacity: 1, scale: 1, duration: 0.35, ease: "back.out(1.7)" }}, g(R0 + 1.75));
    // 3c: "up on the comb wall means the sun"
    const U0 = {c3c['start'] + 0.05:.2f};
    tl.fromTo("#dr-refarrow", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.25, ease: "power2.out" }}, g(U0));
    tl.fromTo("#dr-lsun", {{ opacity: 0, scale: 0.4, transformOrigin: "50% 50%" }}, {{ opacity: 1, scale: 1, duration: 0.5, ease: "back.out(1.5)" }}, g(U0 + 0.1));
    tl.fromTo("#dr-link-m", {{ strokeDashoffset: 100 }}, {{ strokeDashoffset: 0, duration: 0.8, ease: "power2.inOut" }}, g(U0 + 0.55));
    tl.fromTo("#dr-rsun", {{ scale: 1 }}, {{ scale: 1.25, duration: 0.3, yoyo: true, repeat: 1, ease: "power2.inOut", transformOrigin: "50% 50%" }}, g(U0 + 1.2));
    // exit (the next scene starts under the last 0.3 s)
    tl.to("#dr-all", {{ opacity: 0, filter: "blur(8px)", duration: 0.3, ease: "power2.in" }}, g({S3_START:.2f}));
"""
(proj / "compositions" / "direction.html").write_text(scene("direction", D_START, DIR_DUR, dir_body, dir_css, dir_js, "dr-all"), encoding="utf-8")
print("direction ok")

# ---------------------------------------------------------------- scene 3: distance
dist_css = """
    .ds-card { position: absolute; top: 230px; width: 750px; height: 470px; border-radius: 36px; background: rgba(58,36,10,0.58); border: 3px solid rgba(255,190,70,0.45); opacity: 0; }
    .ds-head { position: absolute; top: 254px; width: 750px; text-align: center; font-weight: 800; font-size: 60px; color: #FFD36B; opacity: 0; }
    .ds-lbl { position: absolute; font-weight: 700; font-size: 36px; color: rgba(255,244,214,0.9); white-space: nowrap; opacity: 0; }
    .ds-track { position: absolute; height: 30px; border-radius: 15px; background: rgba(255,244,214,0.16); opacity: 0; }
    .ds-fill { position: absolute; height: 30px; border-radius: 15px; background: #FFB81C; opacity: 1; transform-origin: 0 50%; }
    .ds-bee { position: absolute; width: 0; height: 0; opacity: 0; }
    .ds-ground { position: absolute; height: 5px; border-radius: 3px; background: rgba(255,244,214,0.35); opacity: 0; }
    .ds-obj { position: absolute; width: 0; height: 0; opacity: 0; }
    .ds-arrow { position: absolute; left: 0; top: 0; width: 1920px; height: 1080px; overflow: visible; }
    .ds-tag { position: absolute; font-weight: 800; font-size: 46px; color: #FFB81C; white-space: nowrap; opacity: 0; }
"""


def card(prefix, left, head, bar_len_frac, hive_x, flower_x):
    return f"""
    <div class="ds-card" id="{prefix}-card" style="left:{left}px"></div>
    <div class="ds-head" id="{prefix}-head" style="left:{left}px">{head}</div>
    <div class="ds-lbl" id="{prefix}-tl" style="left:{left+170}px;top:360px">흔드는 시간</div>
    <div class="ds-track" id="{prefix}-track" style="left:{left+170}px;top:425px;width:540px"></div>
    <div class="ds-fill" id="{prefix}-fill" style="left:{left+170}px;top:425px;width:540px;opacity:0"></div>
    <div class="ds-bee" id="{prefix}-bee" style="left:{left+88}px;top:440px"><div id="{prefix}-bee-s">{bee(prefix + 'b')}</div></div>
    <div class="ds-ground" id="{prefix}-ground" style="left:{left+50}px;top:640px;width:650px"></div>
    <div class="ds-obj" id="{prefix}-hive" style="left:{hive_x}px;top:603px"><svg width="90" height="90" viewBox="-45 -45 90 90" style="position:absolute;left:-45px;top:-45px"><path d="M0,-40 L35,-20 L35,20 L0,40 L-35,20 L-35,-20Z" fill="#F6B21A" stroke="#2B1A0A" stroke-width="5"/></svg></div>
    <div class="ds-obj" id="{prefix}-flower" style="left:{flower_x}px;top:585px">{flower(0.5)}</div>"""


dist_body = card("dn", 150, "짧게 흔들면", 0.25, 235, 405) + card("df", 1020, "길게 흔들면", 1.0, 1105, 1650) + f"""
    <svg class="ds-arrow" viewBox="0 0 1920 1080">
      {dashed("dn-arrow", "M 270 603 L 360 603", "#FFB81C", 7, "12 10")}
      {dashed("df-arrow", "M 1140 603 L 1590 603", "#FFB81C", 7, "12 10")}
    </svg>
    <div class="ds-tag" id="dn-tag" style="left:150px;width:750px;text-align:center;top:476px">가까워요</div>
    <div class="ds-tag" id="df-tag" style="left:1020px;width:750px;text-align:center;top:476px">멀어요</div>"""

dist_js = COMMON_JS + f"""
    const F0 = {c4a['start'] + 0.2:.2f}, NEAR = 0.6, FAR = {c4a['end'] - c4a['start'] - 0.25:.2f};
    tl.fromTo(["#dn-card", "#df-card"], {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.4, ease: "power3.out" }}, g({S3_START:.2f}));
    tl.fromTo(["#dn-head", "#df-head"], {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.4, ease: "power3.out" }}, g({S3_START + 0.05:.2f}));
    tl.fromTo(["#dn-tl", "#df-tl", "#dn-track", "#df-track", "#dn-bee", "#df-bee"], {{ opacity: 0 }}, {{ opacity: 1, duration: 0.35, ease: "power2.out" }}, g({S3_START + 0.2:.2f}));
    gsap.set(["#dn-bee-s", "#df-bee-s"], {{ scale: 0.7 }});
    flaps("#dnb-wl", g({S3_START + 0.2:.2f}), g({O_START:.2f})); flaps("#dnb-wr", g({S3_START + 0.2:.2f}), g({O_START:.2f}));
    flaps("#dfb-wl", g({S3_START + 0.2:.2f}), g({O_START:.2f})); flaps("#dfb-wr", g({S3_START + 0.2:.2f}), g({O_START:.2f}));
    // 4a: the longer the body shakes, the longer the bar
    tl.set(["#dn-fill", "#df-fill"], {{ opacity: 1 }}, g(F0));
    tl.fromTo("#dn-fill", {{ scaleX: 0 }}, {{ scaleX: 0.25, duration: NEAR, ease: "none" }}, g(F0));
    tl.fromTo("#df-fill", {{ scaleX: 0 }}, {{ scaleX: 1, duration: FAR, ease: "none" }}, g(F0));
    rest("#dn-bee-s", {{ rotation: 0 }}); rest("#df-bee-s", {{ rotation: 0 }});
    wiggle("#dn-bee-s", g(F0), NEAR);
    wiggle("#df-bee-s", g(F0), FAR);
    // 4b: and the farther the flowers are
    const M0 = {c4b['start']:.2f};
    tl.fromTo(["#dn-ground", "#df-ground", "#dn-hive", "#df-hive"], {{ opacity: 0 }}, {{ opacity: 1, duration: 0.3, ease: "power2.out" }}, g(M0 - 0.05));
    tl.fromTo("#dn-flower", {{ opacity: 0, scale: 0.3, transformOrigin: "50% 100%" }}, {{ opacity: 1, scale: 1, duration: 0.45, ease: "back.out(1.4)" }}, g(M0 + 0.15));
    tl.fromTo("#df-flower", {{ opacity: 0, scale: 0.3, transformOrigin: "50% 100%" }}, {{ opacity: 1, scale: 1, duration: 0.45, ease: "back.out(1.4)" }}, g(M0 + 0.3));
    tl.fromTo("#dn-arrow-m", {{ strokeDashoffset: 100 }}, {{ strokeDashoffset: 0, duration: 0.35, ease: "power2.out" }}, g(M0 + 0.35));
    tl.fromTo("#df-arrow-m", {{ strokeDashoffset: 100 }}, {{ strokeDashoffset: 0, duration: 0.7, ease: "power2.out" }}, g(M0 + 0.4));
    tl.fromTo("#dn-tag", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.3, ease: "power3.out" }}, g(M0 + 0.6));
    tl.fromTo("#df-tag", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.3, ease: "power3.out" }}, g(M0 + 0.75));
    tl.to("#ds-all", {{ opacity: 0, filter: "blur(8px)", duration: 0.3, ease: "power2.in" }}, g({O_START:.2f}));
"""
(proj / "compositions" / "distance.html").write_text(scene("distance", S3_START, DIST_DUR, dist_body, dist_css, dist_js, "ds-all"), encoding="utf-8")
print("distance ok")

# ---------------------------------------------------------------- scene 4: outro (Karl von Frisch)
out_css = """
    .ot-word { display: inline-block; opacity: 0; }
    #ot-name { position: absolute; left: 0; top: 300px; width: 1920px; text-align: center; font-weight: 800; font-size: 124px; letter-spacing: -0.02em; color: #FFF4D6; white-space: nowrap; }
    #ot-role { position: absolute; left: 0; top: 460px; width: 1920px; text-align: center; font-weight: 700; font-size: 56px; color: #FFB81C; opacity: 0; }
    #ot-year { position: absolute; left: 0; top: 330px; width: 1920px; text-align: center; font-weight: 800; font-size: 300px; letter-spacing: -0.03em; color: #FFB81C; opacity: 0; line-height: 1; }
    #ot-prize { position: absolute; left: 0; top: 650px; width: 1920px; text-align: center; font-weight: 700; font-size: 76px; color: #FFF4D6; opacity: 0; white-space: nowrap; }
    #ot-prize small { font-size: 44px; font-weight: 400; color: rgba(255,244,214,0.8); margin-left: 18px; }
    #ot-recap { position: absolute; left: 0; top: 776px; width: 1920px; text-align: center; font-weight: 800; font-size: 62px; color: #FFD36B; opacity: 0; }
"""
out_body = """    <div id="ot-name"><span class="ot-word">카를</span> <span class="ot-word">폰</span> <span class="ot-word">프리슈</span></div>
    <div id="ot-role">동물학자</div>
    <div id="ot-year">1973</div>
    <div id="ot-prize">노벨 생리의학상<small>공동 수상</small></div>
    <div id="ot-recap">방향은 각도로, 거리는 시간으로</div>"""
out_js = COMMON_JS + f"""
    // 5a: the name, word by word with the voice
    tl.fromTo("#ot-name .ot-word", {{ opacity: 0, filter: "blur(10px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.2, stagger: 0.28, ease: "power3.out" }}, g({c5a['start']:.2f}));
    tl.fromTo("#ot-role", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.35, ease: "power3.out" }}, g({c5a['start'] + 1.0:.2f}));
    // 5b: the name steps back, the year lands
    tl.to("#ot-name", {{ y: -190, scale: 0.55, transformOrigin: "50% 50%", duration: 0.5, ease: "power2.inOut" }}, g({c5b['start'] - 0.1:.2f}));
    tl.to("#ot-role", {{ opacity: 0, duration: 0.2, ease: "power3.in" }}, g({c5b['start'] - 0.1:.2f}));
    tl.fromTo("#ot-year", {{ opacity: 0, scale: 1.18, filter: "blur(14px)", transformOrigin: "50% 50%" }}, {{ opacity: 1, scale: 1, filter: "blur(0px)", duration: 0.45, ease: "expo.out" }}, g({c5b['start'] + 0.05:.2f}));
    tl.fromTo("#ot-prize", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.35, ease: "power3.out" }}, g({c5b['start'] + 0.85:.2f}));
    // the one idea, in one line: held until the ending takes over (2+ s)
    tl.fromTo("#ot-recap", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.4, ease: "power3.out" }}, g({c5b['start'] + 1.6:.2f}));
    tl.to("#ot-all", {{ opacity: 0, filter: "blur(8px)", duration: 0.3, ease: "power2.in" }}, g({ES:.2f}));
"""
(proj / "compositions" / "outro.html").write_text(scene("outro", O_START, OUT_DUR, out_body, out_css, out_js, "ot-all"), encoding="utf-8")
print("outro ok")

# ---------------------------------------------------------------- captions
cap_css = """
    .cap { position: absolute; left: 0; top: 884px; width: 1920px; height: 110px; text-align: center; }
    .cap-pill { display: inline-block; padding: 8px 34px 12px; border-radius: 20px; background: rgba(12,8,4,0.62); font-weight: 700; font-size: 56px; line-height: 1.25; color: #FFF4D6; opacity: 0; white-space: nowrap; }
"""


def cs(r): return max(r["start"] - 0.04, 0.05)


cap_body = "\n".join(
    f'    <div class="cap clip" id="cap-{r["id"]}" data-start="{cs(r):.2f}" data-duration="{(r["end"] + 0.10) - cs(r):.2f}" data-track-index="{i % 6}"><span class="cap-pill" id="capp-{r["id"]}">{r["caption"]}</span></div>'
    for i, r in enumerate(timing))
cap_js = "\n".join(
    f'    tl.fromTo("#capp-{r["id"]}", {{ opacity: 0, y: 10 }}, {{ opacity: 1, y: 0, duration: 0.12, ease: "power3.out" }}, {cs(r):.2f});\n'
    f'    tl.to("#capp-{r["id"]}", {{ opacity: 0, duration: 0.07, ease: "power2.in" }}, {r["end"]:.2f});\n'
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
print("captions ok")


# ---------------------------------------------------------------- background + chip
def hexcells():
    cells = []
    r = 56.0; w = math.sqrt(3) * r
    picks = [(2, 1), (5, 3), (9, 2), (12, 5), (3, 6), (14, 1), (7, 7), (16, 4), (1, 4), (10, 8), (13, 7), (6, 0)]
    for (i, k) in picks:
        cx = w * i + (w / 2 if k % 2 else 0) + w / 2
        cy = 1.5 * r * k + r
        pts = " ".join(f"{cx + r * math.sin(math.radians(60 * j)):.1f},{cy - r * math.cos(math.radians(60 * j)):.1f}" for j in range(6))
        cells.append(f'<polygon points="{pts}" fill="rgba(246,178,26,0.07)"/>')
    return "".join(cells)


(proj / "compositions" / "bg.html").write_text(f"""<template>
  <style>
    #root {{ position: absolute; inset: 0; background: #120d08; overflow: hidden; }}
    #bg-grid {{ position: absolute; left: -120px; top: -120px; width: 2200px; height: 1300px; }}
    #bg-vig {{ position: absolute; inset: 0; background: radial-gradient(ellipse 75% 75% at 50% 50%, rgba(18,13,8,0) 35%, rgba(8,5,2,0.8) 100%); }}
  </style>
  <div id="root" data-composition-id="bg" data-width="1920" data-height="1080">
    <svg id="bg-grid" viewBox="0 0 2200 1300" width="2200" height="1300" data-layout-allow-overflow>
      <defs><pattern id="bg-hex" width="97" height="168" patternUnits="userSpaceOnUse">
        <g fill="none" stroke="rgba(255,190,70,0.13)" stroke-width="2">
          <path d="M48.5,0 L97,28 L97,84 L48.5,112 L0,84 L0,28Z"/>
          <path d="M0,112 L48.5,140 L48.5,196 L0,224 L-48.5,196 L-48.5,140Z" transform="translate(0,-28)"/>
          <path d="M97,112 L145.5,140 L145.5,196 L97,224 L48.5,196 L48.5,140Z" transform="translate(0,-28)"/>
        </g></pattern></defs>
      <rect width="2200" height="1300" fill="url(#bg-hex)"/>
      {hexcells()}
    </svg>
    <div id="bg-vig"></div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    // the comb drifts slowly behind everything
    tl.fromTo("#bg-grid", {{ x: 0, y: 0 }}, {{ x: -97, y: -84, duration: {TOTAL:g}, ease: "none" }}, 0);
    window.__timelines["bg"] = tl;
  </script>
</template>
""", encoding="utf-8")

HOSTS, _ = charles.write_scenes(proj, raw, A / "narration.wav", TOTAL, OFF)
(proj / "compositions" / "chip.html").write_text(f"""<template>
  <style>{FONTS}
    #root {{ position: absolute; inset: 0; font-family: 'Pretendard', sans-serif; }}
    #chip-t {{ position: absolute; left: 120px; top: 96px; font-weight: 800; font-size: 40px; letter-spacing: -0.01em; color: #FFD36B; white-space: nowrap; opacity: 0; }}
    #chip-t i {{ display: inline-block; width: 22px; height: 22px; margin-right: 14px; background: #F6B21A; clip-path: polygon(50% 0, 100% 25%, 100% 75%, 50% 100%, 0 75%, 0 25%); vertical-align: -1px; }}
  </style>
  <div id="root" data-composition-id="chip" data-width="1920" data-height="1080">
    <div id="chip-t"><i></i>꿀벌의 8자 춤</div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    tl.fromTo("#chip-t", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.5, ease: "power2.out" }}, 0);
    window.__timelines["chip"] = tl;
  </script>
</template>
""", encoding="utf-8")

H = lambda hid, start, dur, track: (f'<div id="{hid}" data-composition-id="{hid}" data-composition-src="compositions/{hid}.html" data-start="{start:.2f}" data-duration="{dur:.2f}" '
                                    f'data-track-index="{track}" data-track-kind="graphics" data-width="1920" data-height="1080"></div>')
(proj / "index.html").write_text(f"""<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=1920, height=1080" />
    <script src="assets/gsap.min.js"></script>
    <style>
      * {{ margin: 0; padding: 0; box-sizing: border-box; }}
      html, body {{ margin: 0; width: 1920px; height: 1080px; overflow: hidden; background: #120d08; }}
    </style>
  </head>
  <body>
    <div id="root" data-composition-id="main" data-start="0" data-duration="{TOTAL:g}" data-width="1920" data-height="1080">
      {H("bg", 0, TOTAL, 0)}
      {H("hive", OFF, HIVE_DUR, 1)}
      {H("direction", OFF + D_START, DIR_DUR, 1)}
      {H("distance", OFF + S3_START, DIST_DUR, 1)}
      {H("outro", OFF + O_START, OUT_DUR, 1)}
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
