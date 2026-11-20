// 일반 데이터(설정, 링크 보관함, 캐시)는 AsyncStorage, API 키는 기기 보안 저장소(SecureStore).
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

import { HOSTING } from '../lib/hosting';
import { remoteDelete, remoteGet, remoteSet, SYNCED_KEYS } from './artifactDb';

const synced = (key: string) => HOSTING === 'artifact' && SYNCED_KEYS.includes(key);

// 서버 저장이 실패하면(용량 초과 등) 화면에 알릴 수 있게 한다.
let remoteErrorListener: ((key: string) => void) | null = null;
export function onRemoteSaveError(fn: ((key: string) => void) | null) {
  remoteErrorListener = fn;
}

async function readRaw(key: string): Promise<unknown> {
  if (synced(key)) {
    const v = await remoteGet(key);
    if (v !== undefined) return v;
  }
  const raw = await AsyncStorage.getItem(key);
  return raw ? JSON.parse(raw) : undefined;
}

export async function loadJson<T>(key: string, fallback: T): Promise<T> {
  try {
    const v = await readRaw(key);
    return v && typeof v === 'object' ? ({ ...fallbackObj(fallback), ...v } as T) : fallback;
  } catch {
    return fallback;
  }
}

function fallbackObj<T>(v: T) {
  return v && typeof v === 'object' && !Array.isArray(v) ? v : {};
}

export async function loadArray<T>(key: string): Promise<T[]> {
  try {
    const v = await readRaw(key);
    return Array.isArray(v) ? v : [];
  } catch {
    return [];
  }
}

export async function saveJson(key: string, value: unknown): Promise<void> {
  await AsyncStorage.setItem(key, JSON.stringify(value));
  if (synced(key)) {
    try {
      await remoteSet(key, value);
    } catch {
      remoteErrorListener?.(key);
    }
  }
}

export async function removeKeys(keys: string[]): Promise<void> {
  await AsyncStorage.multiRemove(keys);
  if (HOSTING === 'artifact') {
    await Promise.all(SYNCED_KEYS.filter((k) => keys.includes(k)).map((k) => remoteDelete(k).catch(() => undefined)));
  }
}

export async function allKeys(): Promise<readonly string[]> {
  return AsyncStorage.getAllKeys();
}

// ── API 키 ──────────────────────────────────────────────
// 웹 미리보기에는 보안 저장소가 없어서 브라우저 저장소를 쓴다 (실사용은 휴대폰 앱).
const ACCESS = 'cp_access_key';
const SECRET = 'cp_secret_key';

async function secureGet(key: string): Promise<string | null> {
  if (Platform.OS === 'web') return globalThis.localStorage?.getItem(key) ?? null;
  return SecureStore.getItemAsync(key);
}
async function secureSet(key: string, value: string): Promise<void> {
  if (Platform.OS === 'web') return globalThis.localStorage?.setItem(key, value);
  await SecureStore.setItemAsync(key, value);
}
async function secureDelete(key: string): Promise<void> {
  if (Platform.OS === 'web') return globalThis.localStorage?.removeItem(key);
  await SecureStore.deleteItemAsync(key);
}

export interface Keys {
  accessKey: string;
  secretKey: string;
}

export async function loadKeys(): Promise<Keys | null> {
  try {
    const [a, s] = await Promise.all([secureGet(ACCESS), secureGet(SECRET)]);
    return a && s ? { accessKey: a, secretKey: s } : null;
  } catch {
    return null;
  }
}

export async function saveKeys(keys: Keys): Promise<void> {
  await secureSet(ACCESS, keys.accessKey.trim());
  await secureSet(SECRET, keys.secretKey.trim());
}

export async function deleteKeys(): Promise<void> {
  await secureDelete(ACCESS);
  await secureDelete(SECRET);
}

// ── 네이버 API 키 (API HUB 쇼핑인사이트 / 개발자센터 쇼핑 검색) ──────────
const NAVER = {
  hubId: 'cp_naver_hub_id',
  hubSecret: 'cp_naver_hub_secret',
  shopId: 'cp_naver_shop_id',
  shopSecret: 'cp_naver_shop_secret',
} as const;

export interface NaverKeySet {
  hubId?: string;
  hubSecret?: string;
  shopId?: string;
  shopSecret?: string;
}

export async function loadNaverKeys(): Promise<NaverKeySet> {
  try {
    const entries = await Promise.all(Object.entries(NAVER).map(async ([k, store]) => [k, (await secureGet(store)) || undefined] as const));
    return Object.fromEntries(entries) as NaverKeySet;
  } catch {
    return {};
  }
}

/** kind 하나(hub 또는 shop)의 두 값을 저장한다. */
export async function saveNaverKeys(kind: 'hub' | 'shop', id: string, secret: string): Promise<void> {
  await secureSet(kind === 'hub' ? NAVER.hubId : NAVER.shopId, id.trim());
  await secureSet(kind === 'hub' ? NAVER.hubSecret : NAVER.shopSecret, secret.trim());
}

export async function deleteNaverKeys(kind: 'hub' | 'shop' | 'all'): Promise<void> {
  if (kind !== 'shop') {
    await secureDelete(NAVER.hubId);
    await secureDelete(NAVER.hubSecret);
  }
  if (kind !== 'hub') {
    await secureDelete(NAVER.shopId);
    await secureDelete(NAVER.shopSecret);
  }
}
