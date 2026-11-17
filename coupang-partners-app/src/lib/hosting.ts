// 앱이 어디서 돌아가는지: 휴대폰 앱 / 웹사이트(Vercel 등, API 중계 있음) / Claude 아티팩트 화면.
// EXPO_PUBLIC_HOSTING 은 빌드할 때 값이 박힌다 (아티팩트용 빌드만 "artifact").
import { Platform } from 'react-native';

declare const process: { env: Record<string, string | undefined> };

export type Hosting = 'native' | 'web' | 'artifact';

export const HOSTING: Hosting =
  Platform.OS !== 'web' ? 'native' : process.env.EXPO_PUBLIC_HOSTING === 'artifact' ? 'artifact' : 'web';

/** 아티팩트 화면은 바깥 서버로 요청을 보낼 수 없어서 쿠팡 API를 쓸 수 없다. */
export const API_AVAILABLE = HOSTING !== 'artifact';

/** 웹사이트는 같은 주소의 중계(api/coupang.js)를 거쳐 쿠팡에 요청한다. */
export const PROXY_URL = HOSTING === 'web' ? '/api/coupang' : undefined;
