// 앱 전체 상태: 설정, API 키 유무, 링크 보관함, 호출 기록(시간당 한도), 응답 캐시, 알림 토스트.
import * as Clipboard from 'expo-clipboard';
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Alert, Platform, Share } from 'react-native';

import { CoupangApiError, createClient, type ApiClient } from '../api/client';
import { createDemoClient } from '../demo/demoData';
import { DEFAULT_DISCLOSURE } from '../lib/compose';
import { budget, clampLimit, DEFAULT_HOURLY_LIMIT, type Budget } from '../lib/rateGuard';
import { API_AVAILABLE, PROXY_URL } from '../lib/hosting';
import { SYNCED_KEYS } from './artifactDb';
import { CallBudget } from './callBudget';
import { kstYmd } from '../lib/dates';
import {
  allKeys,
  deleteKeys,
  loadArray,
  loadJson,
  loadKeys,
  onRemoteSaveError,
  removeKeys,
  saveJson,
  saveKeys,
  type Keys,
} from './storage';

export interface Settings {
  subId: string; // 기본 채널 ID
  channels: string[]; // 등록해 둔 채널 ID 목록
  hourlyLimit: number;
  disclosure: string;
  demoMode: boolean;
}

export interface SavedLink {
  id: string;
  title: string;
  url: string; // 올릴 링크 (파트너스 링크)
  originalUrl?: string;
  productId?: string;
  price?: number;
  image?: string;
  isRocket?: boolean;
  subId?: string;
  memo?: string;
  createdAt: number;
  source: 'api' | 'manual' | 'demo';
}

export type Mode = 'api' | 'demo' | 'manual';

export interface Cached<T> {
  data: T;
  fetchedAt: number;
  fromCache: boolean;
}

const K = {
  settings: 'cp.settings',
  links: 'cp.links',
  calls: 'cp.calls',
  checklist: 'cp.checklist',
  cachePrefix: 'cp.cache.',
};

export const DEFAULT_SETTINGS: Settings = {
  subId: '',
  channels: [],
  hourlyLimit: DEFAULT_HOURLY_LIMIT,
  disclosure: DEFAULT_DISCLOSURE,
  demoMode: false,
};

interface Checklist {
  date: string;
  done: Record<string, boolean>;
}

export interface AskSpec {
  title: string;
  message: string;
  ok: string;
  onOk: () => void;
}

interface AppState {
  ready: boolean;
  settings: Settings;
  updateSettings: (patch: Partial<Settings>) => Promise<void>;
  hasKeys: boolean;
  setApiKeys: (keys: Keys) => Promise<void>;
  clearApiKeys: () => Promise<void>;
  mode: Mode;
  client: ApiClient | null;
  links: SavedLink[];
  addLink: (link: Omit<SavedLink, 'id' | 'createdAt'>) => Promise<SavedLink>;
  updateLink: (id: string, patch: Partial<SavedLink>) => Promise<void>;
  removeLink: (id: string) => Promise<void>;
  usage: Budget;
  /** 30초마다 바뀌는 현재 시각 (화면 표시용) */
  now: number;
  cached: <T>(key: string, ttlMs: number, fetcher: (c: ApiClient) => Promise<T>, force?: boolean) => Promise<Cached<T>>;
  peekCache: <T>(key: string) => Promise<Cached<T> | null>;
  clearCache: () => Promise<void>;
  resetAll: () => Promise<void>;
  checklist: Checklist;
  toggleChecklist: (id: string) => Promise<void>;
  toast: string | null;
  showToast: (message: string) => void;
  /** 되돌릴 수 없는 일 전에 묻는다. 웹은 confirm()이 막힌 곳이 있어 앱 안 대화상자를 쓴다. */
  ask: (spec: AskSpec) => void;
  dialog: AskSpec | null;
  closeDialog: () => void;
  copy: (text: string, message?: string) => Promise<void>;
  share: (text: string) => Promise<void>;
}

const Ctx = createContext<AppState | null>(null);

export function useApp(): AppState {
  const v = useContext(Ctx);
  if (!v) throw new Error('AppProvider가 없어요');
  return v;
}

export function AppProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false);
  const [settings, setSettings] = useState<Settings>(DEFAULT_SETTINGS);
  const [keys, setKeys] = useState<Keys | null>(null);
  const [links, setLinks] = useState<SavedLink[]>([]);
  const [calls, setCalls] = useState<number[]>([]);
  const [callBudget] = useState(() => new CallBudget((c) => saveJson(K.calls, c)));
  const [now, setNow] = useState(() => Date.now());
  const [checklist, setChecklist] = useState<Checklist>(() => ({ date: kstYmd(new Date()), done: {} }));
  // 콜백 안에서 최신 값을 읽기 위한 ref. 상태를 바꾸는 곳에서 함께 갱신한다.
  const settingsRef = useRef<Settings>(DEFAULT_SETTINGS);
  const linksRef = useRef<SavedLink[]>([]);
  const checklistRef = useRef<Checklist>({ date: '', done: {} });
  const [toast, setToast] = useState<string | null>(null);
  const [dialog, setDialog] = useState<AskSpec | null>(null);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    (async () => {
      const [s, k, l, c, cl] = await Promise.all([
        loadJson<Settings>(K.settings, DEFAULT_SETTINGS),
        loadKeys(),
        loadArray<SavedLink>(K.links),
        loadArray<number>(K.calls),
        loadJson<Checklist>(K.checklist, { date: '', done: {} }),
      ]);
      settingsRef.current = { ...DEFAULT_SETTINGS, ...s, hourlyLimit: clampLimit(s.hourlyLimit) };
      setSettings(settingsRef.current);
      setKeys(k);
      linksRef.current = l;
      setLinks(l);
      callBudget.load(c);
      const today = kstYmd(new Date());
      checklistRef.current = cl.date === today ? cl : { date: today, done: {} };
      setChecklist(checklistRef.current);
      setReady(true);
    })();
  }, [callBudget]);

  useEffect(() => callBudget.subscribe(setCalls), [callBudget]);

  // 호출 사용량 표시가 시간이 지나면 줄어들도록 30초마다 갱신
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30000);
    return () => clearInterval(t);
  }, []);

  const showToast = useCallback((message: string) => {
    setToast(message);
    if (toastTimer.current) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(null), 2200);
  }, []);

  // 아티팩트 화면에서 서버 저장이 실패하면 알린다 (이 브라우저에는 남아 있음).
  useEffect(() => {
    onRemoteSaveError(() =>
      showToast('서버에 저장하지 못했어요 (이 브라우저에는 저장됨). 보관함이 너무 크면 오래된 링크를 지우거나 CSV로 내보내 주세요'),
    );
    return () => onRemoteSaveError(null);
  }, [showToast]);

  const updateSettings = useCallback(async (patch: Partial<Settings>) => {
    const next: Settings = { ...settingsRef.current, ...patch };
    settingsRef.current = next;
    setSettings(next);
    await saveJson(K.settings, next);
  }, []);

  const setApiKeys = useCallback(async (k: Keys) => {
    await saveKeys(k);
    setKeys({ accessKey: k.accessKey.trim(), secretKey: k.secretKey.trim() });
  }, []);

  const clearApiKeys = useCallback(async () => {
    await deleteKeys();
    setKeys(null);
  }, []);

  const mode: Mode = settings.demoMode ? 'demo' : keys && API_AVAILABLE ? 'api' : 'manual';

  // 실제 요청 직전마다 최근 1시간 호출 수를 확인하고, 한도에 닿았으면 요청을 막는다.
  const limit = settings.hourlyLimit;
  const client = useMemo<ApiClient | null>(() => {
    if (mode === 'demo') return createDemoClient();
    if (mode === 'manual' || !keys) return null;
    return createClient({
      accessKey: keys.accessKey,
      secretKey: keys.secretKey,
      proxyUrl: PROXY_URL,
      beforeCall: () => callBudget.take(limit),
    });
  }, [mode, keys, callBudget, limit]);

  const usage = useMemo(() => budget(calls, settings.hourlyLimit, now), [calls, settings.hourlyLimit, now]);

  const persistLinks = useCallback(async (next: SavedLink[]) => {
    linksRef.current = next;
    setLinks(next);
    await saveJson(K.links, next);
  }, []);

  const addLink = useCallback(
    async (link: Omit<SavedLink, 'id' | 'createdAt'>) => {
      const item: SavedLink = { ...link, id: `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`, createdAt: Date.now() };
      // 같은 링크가 이미 있으면 위로 올리기만 한다
      const rest = linksRef.current.filter((l) => l.url !== item.url);
      await persistLinks([item, ...rest]);
      return item;
    },
    [persistLinks],
  );

  const updateLink = useCallback(
    async (id: string, patch: Partial<SavedLink>) => {
      await persistLinks(linksRef.current.map((l) => (l.id === id ? { ...l, ...patch } : l)));
    },
    [persistLinks],
  );

  const removeLink = useCallback(
    async (id: string) => {
      await persistLinks(linksRef.current.filter((l) => l.id !== id));
    },
    [persistLinks],
  );

  const cacheKey = useCallback((key: string) => `${K.cachePrefix}${mode}.${key}`, [mode]);

  const peekCache = useCallback(
    async <T,>(key: string): Promise<Cached<T> | null> => {
      const hit = await loadJson<{ t?: number; data?: T }>(cacheKey(key), {});
      return hit.t && hit.data !== undefined ? { data: hit.data, fetchedAt: hit.t, fromCache: true } : null;
    },
    [cacheKey],
  );

  const cached = useCallback(
    async <T,>(key: string, ttlMs: number, fetcher: (c: ApiClient) => Promise<T>, force = false): Promise<Cached<T>> => {
      if (!client) throw new CoupangApiError('auth', 'API 키를 넣거나 예시 데이터 모드를 켜야 쓸 수 있어요.');
      if (!force) {
        const hit = await peekCache<T>(key);
        if (hit && Date.now() - hit.fetchedAt < ttlMs) return hit;
      }
      const data = await fetcher(client);
      const t = Date.now();
      await saveJson(cacheKey(key), { t, data });
      return { data, fetchedAt: t, fromCache: false };
    },
    [client, cacheKey, peekCache],
  );

  const clearCache = useCallback(async () => {
    const ks = (await allKeys()).filter((k) => k.startsWith(K.cachePrefix));
    await removeKeys([...ks]);
  }, []);

  const resetAll = useCallback(async () => {
    const ks = (await allKeys()).filter((k) => k.startsWith('cp.'));
    await removeKeys([...new Set([...ks, ...SYNCED_KEYS])]);
    await deleteKeys();
    setKeys(null);
    settingsRef.current = DEFAULT_SETTINGS;
    setSettings(DEFAULT_SETTINGS);
    linksRef.current = [];
    setLinks([]);
    callBudget.reset();
    checklistRef.current = { date: kstYmd(new Date()), done: {} };
    setChecklist(checklistRef.current);
  }, [callBudget]);

  const toggleChecklist = useCallback(async (id: string) => {
    const today = kstYmd(new Date());
    const prev = checklistRef.current;
    const base = prev.date === today ? prev : { date: today, done: {} };
    const next: Checklist = { date: today, done: { ...base.done, [id]: !base.done[id] } };
    checklistRef.current = next;
    setChecklist(next);
    await saveJson(K.checklist, next);
  }, []);

  const ask = useCallback((spec: AskSpec) => {
    if (Platform.OS === 'web') {
      setDialog(spec);
      return;
    }
    Alert.alert(spec.title, spec.message, [
      { text: '취소', style: 'cancel' },
      { text: spec.ok, style: 'destructive', onPress: spec.onOk },
    ]);
  }, []);
  const closeDialog = useCallback(() => setDialog(null), []);

  const copy = useCallback(
    async (text: string, message = '복사했어요') => {
      let ok = false;
      try {
        ok = (await Clipboard.setStringAsync(text)) !== false;
      } catch {
        ok = false;
      }
      showToast(ok ? message : '복사가 막혀 있어요. 글을 길게 눌러(또는 드래그해) 직접 복사해 주세요');
    },
    [showToast],
  );

  const share = useCallback(
    async (text: string) => {
      // 웹 브라우저 대부분(특히 PC)과 아티팩트 화면에는 공유 창이 없어 복사로 대신한다.
      const nav = Platform.OS === 'web' ? (globalThis.navigator as Navigator | undefined) : undefined;
      if (Platform.OS === 'web' && typeof nav?.share !== 'function') {
        await copy(text, '이 브라우저는 공유 창이 없어 복사했어요. 붙여 넣어 올리세요');
        return;
      }
      try {
        await Share.share({ message: text });
      } catch {
        if (Platform.OS === 'web') await copy(text, '공유 창을 열 수 없어 복사했어요');
      }
    },
    [copy],
  );

  const value: AppState = {
    ready,
    settings,
    updateSettings,
    hasKeys: !!keys,
    setApiKeys,
    clearApiKeys,
    mode,
    client,
    links,
    addLink,
    updateLink,
    removeLink,
    usage,
    now,
    cached,
    peekCache,
    clearCache,
    resetAll,
    checklist,
    toggleChecklist,
    toast,
    showToast,
    ask,
    dialog,
    closeDialog,
    copy,
    share,
  };

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function errorText(e: unknown): string {
  if (e instanceof CoupangApiError) return e.message;
  if (e instanceof Error) return e.message;
  return '알 수 없는 오류가 났어요.';
}
