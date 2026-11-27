export function won(n: number): string {
  return `${Math.round(n).toLocaleString('ko-KR')}원`;
}

export function num(n: number): string {
  return Math.round(n).toLocaleString('ko-KR');
}

export function pct(ratio: number, digits = 1): string {
  return `${(ratio * 100).toFixed(digits)}%`;
}

export function minutesText(ms: number): string {
  const m = Math.ceil(ms / 60000);
  return m <= 1 ? '1분' : `${m}분`;
}

export function dateTimeText(ms: number): string {
  const d = new Date(ms);
  const p = (x: number) => String(x).padStart(2, '0');
  return `${d.getMonth() + 1}.${d.getDate()} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

export function shortUrl(url: string, max = 42): string {
  const s = url.replace(/^https?:\/\//, '');
  return s.length > max ? `${s.slice(0, max - 1)}…` : s;
}
