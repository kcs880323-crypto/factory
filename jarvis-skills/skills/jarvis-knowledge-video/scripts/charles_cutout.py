#!/usr/bin/env python3
"""찰스 그림(단색 배경 위의 PNG/JPG)에서 배경을 지워 투명 PNG 와 앵커 정보(charles.json)를 만든다.

  <PY> -I charles_cutout.py <출력 폴더> --pose 이름 그림파일 [--pose ...]
        [--seed 이름 x,y]     (안쪽에 갇힌 배경 덩어리 한 곳을 더 지운다. 여러 번 가능)
        [--feet 이름 x,y]     (발 가운데 · 바닥. 그림 파일의 좌표. 없으면 아래쪽 불투명 영역의 가운데·맨 아래)
        [--mark 이름.키 x,y]  (그림 위의 점을 기록한다. 예: idea.bulb 235,165)
        [--sheet 미리보기.png]

배경색은 그림 테두리의 중앙값으로 정한다. 테두리에서 이어진 배경색만 지우므로 캐릭터 안쪽의 같은 색은 남는다.
(캐릭터 둘레에 흰색 스티커 테두리가 있으면 배경과 확실히 구분되어 잘 지워진다.)
필요: Pillow, numpy.  출력: pose-<이름>.png (캐릭터 영역만 잘라낸 RGBA), charles.json
"""
import argparse, json, pathlib
import numpy as np
from PIL import Image, ImageFilter


def parse_xy(s):
    x, y = s.split(","); return int(x), int(y)


def cutout(path, seeds, tol=34):
    im = Image.open(path).convert("RGB")
    a = np.asarray(im).astype(np.int16)
    edge = np.concatenate([a[0], a[-1], a[:, 0], a[:, -1]])
    bgc = np.median(edge, axis=0)
    d = np.abs(a - bgc)
    cand = (d.sum(axis=2) < tol * 1.6) & (d.max(axis=2) < tol)
    bg = np.zeros_like(cand)
    bg[0, :] = cand[0, :]; bg[-1, :] = cand[-1, :]; bg[:, 0] = cand[:, 0]; bg[:, -1] = cand[:, -1]
    for sx, sy in seeds:
        bg[sy, sx] = True
    while True:                                   # 가장자리(와 seed)에서 이어진 배경색만 번져 나간다
        n = bg.copy()
        n[1:, :] |= bg[:-1, :]; n[:-1, :] |= bg[1:, :]; n[:, 1:] |= bg[:, :-1]; n[:, :-1] |= bg[:, 1:]
        n &= cand
        if (n == bg).all():
            break
        bg = n
    alpha = Image.fromarray(((~bg) * 255).astype(np.uint8)).filter(ImageFilter.MinFilter(3)).filter(ImageFilter.GaussianBlur(0.9))
    rgba = Image.fromarray(np.dstack([a.astype(np.uint8), np.asarray(alpha)]), "RGBA")
    return rgba, [int(v) for v in bgc]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--pose", nargs=2, action="append", metavar=("NAME", "FILE"), required=True)
    ap.add_argument("--seed", nargs=2, action="append", default=[], metavar=("NAME", "X,Y"))
    ap.add_argument("--feet", nargs=2, action="append", default=[], metavar=("NAME", "X,Y"))
    ap.add_argument("--mark", nargs=2, action="append", default=[], metavar=("NAME.KEY", "X,Y"))
    ap.add_argument("--sheet")
    a = ap.parse_args()
    out = pathlib.Path(a.out); out.mkdir(parents=True, exist_ok=True)
    seeds = {}; feet = {}; marks = {}
    for n, xy in a.seed: seeds.setdefault(n, []).append(parse_xy(xy))
    for n, xy in a.feet: feet[n] = parse_xy(xy)
    for nk, xy in a.mark:
        n, k = nk.split("."); marks.setdefault(n, {})[k] = parse_xy(xy)
    info, imgs, bgc = {}, [], None
    for name, f in a.pose:
        rgba, bgc = cutout(f, seeds.get(name, []))
        bb = rgba.getchannel("A").point(lambda v: 255 if v > 16 else 0).getbbox()
        x0, y0, x1, y1 = max(bb[0] - 4, 0), max(bb[1] - 4, 0), min(bb[2] + 4, rgba.width), min(bb[3] + 4, rgba.height)
        crop = rgba.crop((x0, y0, x1, y1))
        if name in feet:
            fx, fy = feet[name]
        else:
            al = np.asarray(crop.getchannel("A")) > 128
            rows = al[-max(8, crop.height // 12):]
            fx = int(np.nonzero(rows.any(axis=0))[0].mean()) + x0; fy = y1 - 16
        crop.save(out / f"pose-{name}.png", optimize=True)
        info[name] = {"file": f"pose-{name}.png", "w": crop.width, "h": crop.height, "fx": fx - x0, "fy": fy - y0,
                      "marks": {k: [x - x0, y - y0] for k, (x, y) in marks.get(name, {}).items()}}
        imgs.append(crop)
        print(f"{name}: {crop.width}x{crop.height}, 발 기준점 ({fx - x0}, {fy - y0})")
    (out / "charles.json").write_text(json.dumps({"bg": bgc, "poses": info}, ensure_ascii=False, indent=1), encoding="utf-8")
    if a.sheet:
        H = 520; tiles = [im.resize((int(im.width * H / im.height), H), Image.LANCZOS) for im in imgs]
        sheet = Image.new("RGB", (sum(t.width for t in tiles) + 40 * (len(tiles) + 1), H + 80), tuple(bgc))
        x = 40
        for t in tiles:
            sheet.paste(t, (x, 40), t); x += t.width + 40
        sheet.save(a.sheet); print("미리보기:", a.sheet)


if __name__ == "__main__":
    main()
