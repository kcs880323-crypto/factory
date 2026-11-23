// 웹 화면의 영상 만들기: 상품 사진 + 자막(자비스 배치)을 캔버스에 그리고 그대로 녹화해 mp4/webm 파일을 만든다.
// 사진은 직접 올린 것만 확실히 쓸 수 있다 (바깥 주소 사진은 브라우저 보안 때문에 녹화가 막힐 수 있어 CORS가 허락될 때만 쓴다).
import { useEffect, useRef, useState } from 'react';
import { Platform, View } from 'react-native';

import { HOSTING } from '../lib/hosting';
import { useApp } from '../store/AppContext';
import { Banner, Button, Card, Row, T } from '../ui/kit';
import { useTheme } from '../ui/theme';
import { canRecord, pickVideoType, recordCanvas, type Recorded } from './record';
import { drawReelFrame, H, planDuration, W, type Photo } from './reelDraw';
import type { ReelStudioProps } from './ReelStudio';

const MAX_PHOTOS = 6;
const MAX_SIDE = 1600;

function shrink(img: HTMLImageElement): Photo {
  const k = Math.min(1, MAX_SIDE / Math.max(img.naturalWidth, img.naturalHeight));
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(img.naturalWidth * k));
  c.height = Math.max(1, Math.round(img.naturalHeight * k));
  c.getContext('2d')?.drawImage(img, 0, 0, c.width, c.height);
  return c;
}

function loadImage(src: string, cors: boolean): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    if (cors) img.crossOrigin = 'anonymous';
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('사진을 읽지 못했어요.'));
    img.src = src;
  });
}

/** 바깥 주소 사진은 CORS가 허락될 때만 (안 그러면 녹화가 막힌다). 시험 그리기로 확인한다. */
async function tryRemote(url: string): Promise<Photo | null> {
  if (!/^https:\/\//.test(url)) return null;
  try {
    const img = await loadImage(url, true);
    const p = shrink(img) as HTMLCanvasElement;
    p.getContext('2d')?.getImageData(0, 0, 1, 1); // 막히면 여기서 던진다
    return p;
  } catch {
    return null;
  }
}

function pickFiles(): Promise<File[]> {
  return new Promise((resolve) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.multiple = true;
    input.style.display = 'none';
    input.onchange = () => {
      resolve(Array.from(input.files ?? []));
      input.remove();
    };
    document.body.appendChild(input);
    input.click();
  });
}

interface DownloadsNs {
  save: (r: { filename: string; data: Blob }) => Promise<unknown>;
}

async function saveFile(rec: Recorded, filename: string): Promise<string> {
  if (HOSTING === 'artifact') {
    const c = (globalThis as { claude?: { use: (n: string) => Promise<unknown> } }).claude;
    const dl = c?.use ? ((await c.use('downloads').catch(() => null)) as DownloadsNs | null) : null;
    if (!dl) throw new Error('이 화면에서는 파일 저장을 쓸 수 없어요. 위 영상을 길게 눌러(또는 오른쪽 클릭) 저장해 주세요.');
    try {
      await dl.save({ filename, data: rec.blob });
      return '저장했어요';
    } catch (e) {
      const code = (e as { code?: string })?.code;
      if (code === 'declined') return '저장을 취소했어요';
      throw new Error(code === 'rejected_extension' ? '이 형식은 저장할 수 없어요.' : '저장하지 못했어요. 위 영상을 길게 눌러(또는 오른쪽 클릭) 저장해 주세요.');
    }
  }
  const url = URL.createObjectURL(rec.blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
  return '내려받기를 시작했어요';
}

export function ReelStudio({ plan, fileBase, imageUrl, disabled }: ReelStudioProps) {
  const { showToast } = useApp();
  const t = useTheme();
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [remote, setRemote] = useState<{ url: string; photo: Photo | null } | null>(null);
  const [busy, setBusy] = useState<'preview' | 'record' | null>(null);
  const [progress, setProgress] = useState(0);
  const [rec, setRec] = useState<(Recorded & { url: string }) | null>(null);
  const [err, setErr] = useState<string | null>(null);
  // 일부 화면(Claude 아티팩트 등)은 보안 정책상 만든 영상을 페이지 안에서 재생하지 못한다 → 저장해서 보게 안내
  const [noPlayer, setNoPlayer] = useState<string | null>(null);
  const ctl = useRef<AbortController | null>(null);
  const [supported] = useState(() => Platform.OS === 'web' && canRecord());

  const remotePhoto = remote && remote.url === imageUrl ? remote.photo : null;
  const usePhotos = photos.length ? photos : remotePhoto ? [remotePhoto] : [];
  const duration = planDuration(plan);

  // 보관함에 사진 주소가 있으면 한 번 시도 (안 되면 조용히 넘어간다)
  useEffect(() => {
    if (!imageUrl) return;
    let live = true;
    tryRemote(imageUrl).then((p) => live && setRemote({ url: imageUrl, photo: p }));
    return () => {
      live = false;
    };
  }, [imageUrl]);

  // 대본·사진이 바뀌면 첫 장면을 다시 그린다
  useEffect(() => {
    const c = canvasRef.current;
    const ctx = c?.getContext('2d');
    if (ctx && !busy) drawReelFrame(ctx, plan, usePhotos, 1.2);
  });

  useEffect(
    () => () => {
      ctl.current?.abort();
      if (rec) URL.revokeObjectURL(rec.url);
    },
    [rec],
  );

  const addPhotos = async () => {
    const files = (await pickFiles()).slice(0, MAX_PHOTOS);
    if (!files.length) return;
    const loaded: Photo[] = [];
    for (const f of files) {
      const url = URL.createObjectURL(f);
      try {
        loaded.push(shrink(await loadImage(url, false)));
      } catch {
        showToast(`${f.name}을(를) 읽지 못했어요`);
      } finally {
        URL.revokeObjectURL(url);
      }
    }
    setPhotos((p) => [...p, ...loaded].slice(0, MAX_PHOTOS));
  };

  const play = async (record: boolean) => {
    const c = canvasRef.current;
    const ctx = c?.getContext('2d');
    if (!c || !ctx || busy) return;
    setErr(null);
    setBusy(record ? 'record' : 'preview');
    setProgress(0);
    ctl.current = new AbortController();
    const draw = (sec: number) => drawReelFrame(ctx, plan, usePhotos, sec);
    try {
      if (record) {
        const r = await recordCanvas(c, draw, duration, { onProgress: setProgress, signal: ctl.current.signal });
        setRec((old) => {
          if (old) URL.revokeObjectURL(old.url);
          return { ...r, url: URL.createObjectURL(r.blob) };
        });
      } else {
        const signal = ctl.current.signal;
        await new Promise<void>((resolve) => {
          const t0 = performance.now();
          const tick = () => {
            const sec = (performance.now() - t0) / 1000;
            if (signal.aborted || sec >= duration) return resolve();
            draw(sec);
            setProgress(sec);
            requestAnimationFrame(tick);
          };
          requestAnimationFrame(tick);
        });
      }
    } catch (e) {
      if ((e as { code?: string })?.code !== 'cancelled') setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
      draw(1.2);
    }
  };

  const save = async () => {
    if (!rec) return;
    try {
      showToast(await saveFile(rec, `${fileBase}.${rec.ext}`));
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };

  const nav = globalThis.navigator as Navigator | undefined;
  const shareFile = async () => {
    if (!rec || !nav?.share) return;
    const file = new File([rec.blob], `${fileBase}.${rec.ext}`, { type: rec.blob.type });
    try {
      await nav.share({ files: [file] });
    } catch {
      /* 사용자가 닫음 */
    }
  };
  const canShareFile = !!rec && HOSTING !== 'artifact' && typeof nav?.canShare === 'function' && nav.canShare({ files: [new File([rec.blob], `x.${rec.ext}`, { type: rec.blob.type })] });

  if (!supported) {
    return (
      <Banner tone="info" icon="movie" title="이 브라우저에서는 영상 파일을 만들 수 없어요">
        크롬·엣지·사파리 최신 버전에서 열어 주세요. 아래 대본·캡션은 복사해서 편집 앱에서 써도 돼요.
      </Banner>
    );
  }

  const type = pickVideoType();
  return (
    <Card style={{ gap: 10 }}>
      <Row gap={14} style={{ alignItems: 'flex-start', flexWrap: 'wrap' }}>
        <View style={{ width: 216, borderRadius: 14, overflow: 'hidden', backgroundColor: '#000', borderWidth: 1, borderColor: t.border }}>
          <canvas ref={canvasRef} width={W} height={H} style={{ width: 216, height: 384, display: 'block' }} aria-label="영상 미리보기" />
        </View>
        <View style={{ flex: 1, minWidth: 200, gap: 8 }}>
          <T v="h3">{`세로 영상 ${Math.round(duration)}초 · ${type?.ext ?? ''}`}</T>
          <T v="sub">
            {usePhotos.length
              ? `사진 ${usePhotos.length}장을 장면마다 돌려 써요.${photos.length ? '' : ' (보관함 사진)'}`
              : '사진이 없으면 상품 이름 배경으로 만들어요. 상품 사진을 올리면 훨씬 좋아요 (0초부터 제품이 보이게).'}
          </T>
          <Row gap={8} style={{ flexWrap: 'wrap' }}>
            <Button small kind="secondary" icon="photo" label="사진 올리기" onPress={addPhotos} disabled={!!busy} />
            {photos.length ? <Button small kind="ghost" icon="trash" label="사진 지우기" onPress={() => setPhotos([])} disabled={!!busy} /> : null}
          </Row>
          <Row gap={8} style={{ flexWrap: 'wrap' }}>
            <Button small kind="ghost" icon="play" label="미리보기" onPress={() => play(false)} disabled={!!busy || disabled} />
            {busy ? <Button small kind="ghost" icon="stop" label="멈추기" onPress={() => ctl.current?.abort()} /> : null}
          </Row>
          <Button icon="movie" label={busy === 'record' ? `녹화 중… ${Math.floor(progress)}/${Math.round(duration)}초` : '영상 파일 만들기'} onPress={() => play(true)} disabled={!!busy || disabled} />
          <T v="caption">실제 시간만큼(약 {Math.round(duration)}초) 걸려요. 녹화하는 동안 이 화면을 켜 두세요. 소리는 없어요 — 음악·목소리는 인스타·유튜브 편집에서 넣어요.</T>
        </View>
      </Row>
      {err ? (
        <Banner tone="danger" icon="warn" title="영상을 만들지 못했어요">
          {err}
        </Banner>
      ) : null}
      {rec ? (
        <View style={{ gap: 8 }}>
          <T v="h3">{`완성 — ${(rec.blob.size / 1024 / 1024).toFixed(1)}MB ${rec.ext}`}</T>
          {noPlayer === rec.url ? (
            <T v="caption">이 화면은 보안 정책상 영상을 바로 재생할 수 없어요. 저장한 뒤 휴대폰·PC에서 열어 확인해 주세요.</T>
          ) : (
            <video src={rec.url} controls playsInline onError={() => setNoPlayer(rec.url)} style={{ width: 216, borderRadius: 12, background: '#000' }} />
          )}
          <Row gap={8} style={{ flexWrap: 'wrap' }}>
            <Button icon="download" label="영상 저장" onPress={save} />
            {canShareFile ? <Button kind="secondary" icon="share" label="공유 (인스타·유튜브 앱으로)" onPress={shareFile} /> : null}
          </Row>
          {rec.ext === 'webm' ? <T v="caption">webm 파일이에요. 유튜브는 그대로 올라가고, 인스타는 편집 앱(CapCut 등)에서 mp4로 바꿔 올려 주세요.</T> : null}
        </View>
      ) : null}
    </Card>
  );
}
