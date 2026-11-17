// 브라우저에서 캔버스를 영상 파일로 녹화한다 (MediaRecorder). 소리는 없다 — 음악·목소리는 인스타·유튜브 편집기에서 넣는다.
// mp4를 먼저 고르고(인스타 업로드에 편함), 안 되는 브라우저는 webm.

const TYPES = [
  'video/mp4;codecs=avc1.42E01E',
  'video/mp4;codecs=avc1',
  'video/mp4',
  'video/webm;codecs=vp9',
  'video/webm;codecs=vp8',
  'video/webm',
];

export function pickVideoType(): { mime: string; ext: 'mp4' | 'webm' } | null {
  const MR = (globalThis as { MediaRecorder?: typeof MediaRecorder }).MediaRecorder;
  if (!MR || typeof MR.isTypeSupported !== 'function') return null;
  const mime = TYPES.find((m) => MR.isTypeSupported(m));
  return mime ? { mime, ext: mime.startsWith('video/mp4') ? 'mp4' : 'webm' } : null;
}

export function canRecord(): boolean {
  return typeof document !== 'undefined' && !!pickVideoType() && typeof HTMLCanvasElement !== 'undefined' && 'captureStream' in HTMLCanvasElement.prototype;
}

export interface Recorded {
  blob: Blob;
  mime: string;
  ext: 'mp4' | 'webm';
  seconds: number;
}

/** draw(t)를 실제 시간에 맞춰 부르며 duration초 동안 녹화한다. */
export function recordCanvas(
  canvas: HTMLCanvasElement,
  draw: (t: number) => void,
  duration: number,
  opts: { onProgress?: (t: number) => void; signal?: AbortSignal } = {},
): Promise<Recorded> {
  const type = pickVideoType();
  if (!type) return Promise.reject(new Error('이 브라우저는 영상 녹화(MediaRecorder)를 지원하지 않아요. 크롬·엣지·사파리 최신 버전에서 해 주세요.'));
  return new Promise((resolve, reject) => {
    let stream: MediaStream;
    try {
      stream = canvas.captureStream(30);
    } catch {
      reject(new Error('화면을 영상으로 담지 못했어요. 바깥 주소의 사진이 섞이면 막힐 수 있어요 — 사진을 직접 올려 주세요.'));
      return;
    }
    const rec = new MediaRecorder(stream, { mimeType: type.mime, videoBitsPerSecond: 8_000_000 });
    const chunks: Blob[] = [];
    let stopped = false;
    let aborted = false;
    rec.ondataavailable = (e) => {
      if (e.data && e.data.size) chunks.push(e.data);
    };
    rec.onerror = () => {
      if (!stopped) reject(new Error('녹화 중 오류가 났어요. 다시 해 주세요.'));
    };
    rec.onstop = () => {
      stream.getTracks().forEach((tr) => tr.stop());
      if (aborted) return reject(Object.assign(new Error('멈췄어요.'), { code: 'cancelled' }));
      const blob = new Blob(chunks, { type: type.mime.split(';')[0] });
      if (!blob.size) return reject(new Error('영상이 비어 있어요. 화면을 켜 둔 채로 다시 해 주세요.'));
      resolve({ blob, mime: type.mime, ext: type.ext, seconds: duration });
    };
    const stop = () => {
      if (stopped) return;
      stopped = true;
      // 마지막 장면이 잘리지 않게 조금 더 담고 멈춘다
      setTimeout(() => rec.state !== 'inactive' && rec.stop(), 200);
    };
    opts.signal?.addEventListener('abort', () => {
      aborted = true;
      stop();
    });
    draw(0);
    rec.start(500);
    const t0 = performance.now();
    const tick = () => {
      if (stopped) return;
      const t = (performance.now() - t0) / 1000;
      if (t >= duration) {
        draw(duration - 0.001);
        opts.onProgress?.(duration);
        stop();
        return;
      }
      draw(t);
      opts.onProgress?.(t);
      // 화면이 뒤로 가면 requestAnimationFrame이 멈추므로 타이머로 이어 간다
      if (typeof document !== 'undefined' && document.hidden) setTimeout(tick, 33);
      else requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });
}
