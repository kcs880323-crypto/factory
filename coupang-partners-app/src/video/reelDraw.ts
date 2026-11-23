// 홍보 영상 한 장면을 캔버스(1080×1920)에 그린다 — 자비스 shop_reel.overlay_png와 같은 배치.
// - 모든 장면 왼쪽 위 '광고' + 짧은 수수료 고지 (인스타 위 표시줄 아래, y=150)
// - 흰 박스 자막 y=300부터, 노란 강조 y=1150 (인스타가 아래 약 35%를 덮으므로 그 위)
// - 끝 장면: 안내 문구 + 노란 가격 기준일 줄
// 시간 t(초)를 주면 같은 그림이 나온다 (미리보기와 녹화가 같은 함수를 쓴다).
import type { ReelScene } from '../lib/productReel';

export const W = 1080;
export const H = 1920;
const TOP_Y = 150;
const TEXT_Y = 300;
const BADGE_Y = 1150;
const FONT = '"Apple SD Gothic Neo","Malgun Gothic","Noto Sans KR","Noto Sans CJK KR","Nanum Gothic",system-ui,sans-serif';

export interface ReelPlan {
  scenes: ReelScene[];
  /** 짧은 수수료 고지 (광고 표시 옆) */
  notice: string;
  /** 끝 장면 가격 기준일 줄 */
  priceLine: string;
  /** 사진이 없을 때 가운데에 크게 */
  productName: string;
}

/** 그릴 수 있는 사진 (캔버스·이미지·비트맵) */
export type Photo = { width: number; height: number } & CanvasImageSource;

export function planDuration(plan: ReelPlan): number {
  return plan.scenes.reduce((a, s) => a + s.dur, 0);
}

export function sceneAt(plan: ReelPlan, t: number): { index: number; local: number } {
  let acc = 0;
  for (let i = 0; i < plan.scenes.length; i++) {
    const d = plan.scenes[i].dur;
    if (t < acc + d || i === plan.scenes.length - 1) return { index: i, local: Math.max(0, t - acc) };
    acc += d;
  }
  return { index: 0, local: 0 };
}

const ease = (x: number) => 1 - Math.pow(1 - Math.max(0, Math.min(1, x)), 3);

function font(size: number, bold = true) {
  return `${bold ? '800' : '500'} ${size}px ${FONT}`;
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

/** 화면 폭을 넘지 않게: 긴 줄은 가운데 가까운 띄어쓰기에서 한 번 나누고, 그래도 넘치면 글자를 줄인다. (자비스 _fit) */
export function fitLines(ctx: CanvasRenderingContext2D, text: string, size: number, maxw = W - 140, min = 40): { size: number; lines: string[] } {
  const lines: string[] = [];
  ctx.font = font(size);
  for (const ln of text
    .split('\n')
    .map((x) => x.trim())
    .filter(Boolean)
    .slice(0, 3)) {
    if (ctx.measureText(ln).width > maxw && ln.includes(' ')) {
      const mid = ln.length / 2;
      let cut = -1;
      for (let i = 0; i < ln.length; i++) if (ln[i] === ' ' && (cut < 0 || Math.abs(i - mid) < Math.abs(cut - mid))) cut = i;
      lines.push(ln.slice(0, cut).trim(), ln.slice(cut + 1).trim());
    } else lines.push(ln);
  }
  const out = lines.filter(Boolean).slice(0, 4);
  let s = size;
  const wide = () => {
    ctx.font = font(s);
    return out.some((l) => ctx.measureText(l).width > maxw);
  };
  while (s > min && wide()) s -= 4;
  return { size: s, lines: out };
}

/** 흰 박스 자막 (줄마다 박스). 다음 y를 돌려준다. */
function boxText(ctx: CanvasRenderingContext2D, text: string, y: number, size: number, fill = '#FFFFFF', ink = '#111111', alpha = 1, dy = 0): number {
  const { size: s, lines } = fitLines(ctx, text, size);
  ctx.font = font(s);
  ctx.textBaseline = 'alphabetic';
  ctx.textAlign = 'left';
  const padX = 30;
  const padY = 16;
  for (const ln of lines) {
    const m = ctx.measureText(ln);
    const asc = m.actualBoundingBoxAscent || s * 0.82;
    const desc = m.actualBoundingBoxDescent || s * 0.2;
    const w = m.width;
    const h = asc + desc;
    const bx = (W - w) / 2 - padX;
    ctx.globalAlpha = alpha;
    ctx.fillStyle = fill;
    ctx.fillRect(bx, y + dy, w + padX * 2, h + padY * 2);
    ctx.fillStyle = ink;
    ctx.fillText(ln, bx + padX, y + dy + padY + asc);
    ctx.globalAlpha = 1;
    y += h + padY * 2 + 6;
  }
  return y;
}

function drawCover(ctx: CanvasRenderingContext2D, img: Photo, zoom: number, panX: number, panY: number) {
  const s = Math.max(W / img.width, H / img.height) * zoom;
  const w = img.width * s;
  const h = img.height * s;
  const x = (W - w) / 2 + panX * (w - W) * 0.5;
  const y = (H - h) / 2 + panY * (h - H) * 0.5;
  ctx.drawImage(img, x, y, w, h);
}

function background(ctx: CanvasRenderingContext2D, plan: ReelPlan, photos: Photo[], index: number, p: number) {
  const scene = plan.scenes[index];
  if (photos.length) {
    const img = photos[index % photos.length];
    const dir = index % 2 === 0 ? 1 : -1;
    drawCover(ctx, img, 1.04 + 0.08 * p, dir * (p - 0.5) * 0.6, (index % 3 === 1 ? 1 : -1) * (p - 0.5) * 0.3);
    if (scene.kind === 'cta') {
      ctx.fillStyle = 'rgba(0,0,0,0.45)';
      ctx.fillRect(0, 0, W, H);
    }
  } else {
    const g = ctx.createLinearGradient(0, 0, W, H);
    g.addColorStop(0, '#0F766E');
    g.addColorStop(1, '#1E293B');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, W, H);
    if (scene.kind !== 'cta') {
      const { size, lines } = fitLines(ctx, plan.productName.slice(0, 40), 72, W - 160, 44);
      ctx.font = font(size);
      ctx.fillStyle = 'rgba(255,255,255,0.92)';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      lines.forEach((l, i) => ctx.fillText(l, W / 2, 820 + (i - (lines.length - 1) / 2) * size * 1.25));
      ctx.textAlign = 'left';
    }
  }
  // 위쪽 글자가 잘 보이게 살짝 어둡게
  const top = ctx.createLinearGradient(0, 0, 0, 520);
  top.addColorStop(0, 'rgba(0,0,0,0.35)');
  top.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = top;
  ctx.fillRect(0, 0, W, 520);
}

function adLabel(ctx: CanvasRenderingContext2D, plan: ReelPlan) {
  // 자비스와 같이 모든 장면에 '광고' (수수료 없는 상품이어도 홍보 영상이라 그대로 둔다)
  const label = '광고';
  ctx.fillStyle = 'rgba(0,0,0,0.69)';
  roundRect(ctx, 48, TOP_Y, 118, 60, 14);
  ctx.fill();
  ctx.font = font(34);
  ctx.fillStyle = '#FFFFFF';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(label, 48 + 59, TOP_Y + 31);
  if (plan.notice) {
    let s = 26;
    ctx.font = font(s, false);
    while (s > 18 && ctx.measureText(plan.notice).width > W - 48 - 118 - 40) {
      s -= 2;
      ctx.font = font(s, false);
    }
    const tw = ctx.measureText(plan.notice).width;
    ctx.fillStyle = 'rgba(0,0,0,0.55)';
    roundRect(ctx, 180, TOP_Y + 8, tw + 28, 44, 12);
    ctx.fill();
    ctx.fillStyle = 'rgba(255,255,255,0.94)';
    ctx.textAlign = 'left';
    ctx.fillText(plan.notice, 194, TOP_Y + 31);
  }
  ctx.textAlign = 'left';
  ctx.textBaseline = 'alphabetic';
}

function badge(ctx: CanvasRenderingContext2D, text: string, local: number) {
  const k = ease((local - 0.3) / 0.3);
  if (k <= 0) return;
  const { size, lines } = fitLines(ctx, text, 76, W - 220);
  const ln = lines.join(' ');
  ctx.font = font(size);
  const m = ctx.measureText(ln);
  const asc = m.actualBoundingBoxAscent || size * 0.82;
  const desc = m.actualBoundingBoxDescent || size * 0.2;
  const w = m.width + 80;
  const h = asc + desc + 44;
  const scale = 0.7 + 0.3 * k;
  ctx.save();
  ctx.globalAlpha = k;
  ctx.translate(W / 2, BADGE_Y + h / 2);
  ctx.scale(scale, scale);
  ctx.fillStyle = '#FFD84D';
  roundRect(ctx, -w / 2, -h / 2, w, h, 28);
  ctx.fill();
  ctx.fillStyle = '#111111';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'alphabetic';
  ctx.fillText(ln, 0, -h / 2 + 22 + asc);
  ctx.restore();
}

/** 시간 t(초)의 한 장면을 그린다. */
export function drawReelFrame(ctx: CanvasRenderingContext2D, plan: ReelPlan, photos: Photo[], t: number) {
  const { index, local } = sceneAt(plan, t);
  const scene = plan.scenes[index];
  const p = scene.dur > 0 ? local / scene.dur : 0;
  ctx.save();
  ctx.clearRect(0, 0, W, H);
  background(ctx, plan, photos, index, p);
  adLabel(ctx, plan);
  const k = ease(local / 0.35);
  const dy = (1 - k) * 40;
  if (scene.kind === 'hook') boxText(ctx, scene.text, TEXT_Y, 84, '#FFFFFF', '#111111', k, dy);
  else if (scene.kind === 'cta') {
    const y = boxText(ctx, scene.text, TEXT_Y, 68, '#FFFFFF', '#111111', k, dy);
    if (plan.priceLine) boxText(ctx, plan.priceLine, y + 24, 36, '#FFD84D', '#111111', k, dy);
  } else if (scene.text) boxText(ctx, scene.text, TEXT_Y, 64, '#FFFFFF', '#111111', k, dy);
  if (scene.badge) badge(ctx, scene.badge, local);
  ctx.restore();
}
