// 웹 아이콘: Material Symbols 글꼴 파일을 직접 받아(fetch) FontFace로 등록하고 글자로 그린다.
// Claude 아티팩트 화면은 CSS로 글꼴 파일을 불러오는 것을 막을 수 있어서, 주소 대신 파일 내용을 넘긴다.
import { MaterialSymbols_400Regular } from '@expo-google-fonts/material-symbols/400Regular';
import { Asset } from 'expo-asset';
import { useSyncExternalStore } from 'react';
import { Text, View, type ColorValue, type StyleProp, type ViewStyle } from 'react-native';

import { ICONS, type IconName } from './icons';
import { useTheme } from './theme';

export type { IconName };

const FAMILY = 'CPMaterialSymbols';
let status: 'idle' | 'loading' | 'ready' | 'failed' = 'idle';
const listeners = new Set<() => void>();

function load() {
  if (status !== 'idle' || typeof document === 'undefined') return;
  status = 'loading';
  (async () => {
    const uri = Asset.fromModule(MaterialSymbols_400Regular).uri;
    const res = await fetch(uri);
    if (!res.ok) throw new Error(`font ${res.status}`);
    const face = new FontFace(FAMILY, await res.arrayBuffer());
    await face.load();
    document.fonts.add(face);
    status = 'ready';
  })()
    .catch(() => {
      status = 'failed';
    })
    .finally(() => listeners.forEach((l) => l()));
}

function subscribe(l: () => void) {
  listeners.add(l);
  load();
  return () => {
    listeners.delete(l);
  };
}

export function Icon({ name, size = 20, color, style }: { name: IconName; size?: number; color?: ColorValue; style?: StyleProp<ViewStyle> }) {
  const t = useTheme();
  const ready = useSyncExternalStore(subscribe, () => status === 'ready', () => false);
  return (
    <View style={[{ width: size, height: size, alignItems: 'center', justifyContent: 'center' }, style]}>
      {ready ? (
        <Text aria-hidden style={{ fontFamily: FAMILY, fontSize: size, lineHeight: size, color: color ?? t.text }}>
          {String.fromCharCode(ICONS[name].cp)}
        </Text>
      ) : null}
    </View>
  );
}
