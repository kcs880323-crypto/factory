// 웹: 보는 쪽이 밝게/어둡게를 직접 고르면 <html data-theme="..."> 로 알려 준다 (Claude 아티팩트 화면).
// 속성이 없으면 null을 돌려주고, 그때는 OS 설정(prefers-color-scheme)을 따른다.
import { useSyncExternalStore } from 'react';

function read(): 'light' | 'dark' | null {
  const v = typeof document === 'undefined' ? null : document.documentElement.getAttribute('data-theme');
  return v === 'light' || v === 'dark' ? v : null;
}

function subscribe(onChange: () => void): () => void {
  if (typeof MutationObserver === 'undefined' || typeof document === 'undefined') return () => undefined;
  const mo = new MutationObserver(onChange);
  mo.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  return () => mo.disconnect();
}

export function useDataTheme(): 'light' | 'dark' | null {
  return useSyncExternalStore(subscribe, read, () => null);
}
