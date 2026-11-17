// 일반 데이터(설정, 링크 보관함, 캐시)는 AsyncStorage, API 키는 기기 보안 저장소(SecureStore).
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

export async function loadJson<T>(key: string, fallback: T): Promise<T> {
  try {
    const raw = await AsyncStorage.getItem(key);
    return raw ? ({ ...fallbackObj(fallback), ...JSON.parse(raw) } as T) : fallback;
  } catch {
    return fallback;
  }
}

function fallbackObj<T>(v: T) {
  return v && typeof v === 'object' && !Array.isArray(v) ? v : {};
}

export async function loadArray<T>(key: string): Promise<T[]> {
  try {
    const raw = await AsyncStorage.getItem(key);
    const v = raw ? JSON.parse(raw) : [];
    return Array.isArray(v) ? v : [];
  } catch {
    return [];
  }
}

export async function saveJson(key: string, value: unknown): Promise<void> {
  await AsyncStorage.setItem(key, JSON.stringify(value));
}

export async function removeKeys(keys: string[]): Promise<void> {
  await AsyncStorage.multiRemove(keys);
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
