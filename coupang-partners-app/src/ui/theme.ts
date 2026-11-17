import { useColorScheme } from 'react-native';

const light = {
  bg: '#F4F6F8',
  card: '#FFFFFF',
  cardAlt: '#F0F3F5',
  text: '#15191E',
  sub: '#5A636E',
  faint: '#8B949E',
  border: '#E1E5EA',
  primary: '#0B7A64',
  primaryText: '#FFFFFF',
  primarySoft: '#E2F3EE',
  accent: '#C77700',
  accentSoft: '#FFF3DC',
  danger: '#C62828',
  dangerSoft: '#FDECEC',
  info: '#2457C5',
  infoSoft: '#E8EFFD',
  demo: '#7A4CC2',
  demoSoft: '#F1EAFB',
  bar: '#0B7A64',
  bar2: '#C77700',
};

const dark: typeof light = {
  bg: '#0E1114',
  card: '#171B20',
  cardAlt: '#1E2329',
  text: '#ECEFF2',
  sub: '#A3ACB6',
  faint: '#77818C',
  border: '#2A3037',
  primary: '#35B796',
  primaryText: '#06231C',
  primarySoft: '#123329',
  accent: '#F2A93B',
  accentSoft: '#33260F',
  danger: '#F06A6A',
  dangerSoft: '#3A1818',
  info: '#7CA5FF',
  infoSoft: '#16233F',
  demo: '#B996F2',
  demoSoft: '#2A1F3D',
  bar: '#35B796',
  bar2: '#F2A93B',
};

export type Palette = typeof light;

export function useTheme(): Palette {
  return useColorScheme() === 'dark' ? dark : light;
}

export function useIsDark(): boolean {
  return useColorScheme() === 'dark';
}

export const radius = { sm: 8, md: 12, lg: 16 };
export const space = (n: number) => n * 4;
