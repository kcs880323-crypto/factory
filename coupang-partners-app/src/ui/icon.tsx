// 휴대폰 앱 아이콘: iOS는 SF Symbols, 안드로이드는 Material Symbols (expo-symbols)
import { SymbolView } from 'expo-symbols';
import type { ColorValue, StyleProp, ViewStyle } from 'react-native';

import { ICONS, type IconName } from './icons';
import { useTheme } from './theme';

export type { IconName };

export function Icon({ name, size = 20, color, style }: { name: IconName; size?: number; color?: ColorValue; style?: StyleProp<ViewStyle> }) {
  const t = useTheme();
  const n = ICONS[name];
  return <SymbolView name={{ ios: n.ios, android: n.android, web: n.android }} size={size} tintColor={color ?? t.text} style={style} />;
}
