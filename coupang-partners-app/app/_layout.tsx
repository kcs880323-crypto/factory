import { DarkTheme, DefaultTheme, Stack, ThemeProvider } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { Text, View } from 'react-native';

import { AppProvider, useApp } from '@/src/store/AppContext';
import { useIsDark, useTheme } from '@/src/ui/theme';

export { ErrorBoundary } from 'expo-router';

export const unstable_settings = {
  anchor: '(tabs)',
};

SplashScreen.preventAutoHideAsync().catch(() => undefined);

export default function RootLayout() {
  return (
    <AppProvider>
      <RootNav />
    </AppProvider>
  );
}

function RootNav() {
  const { ready } = useApp();
  const dark = useIsDark();
  const t = useTheme();

  useEffect(() => {
    if (ready) SplashScreen.hideAsync().catch(() => undefined);
  }, [ready]);

  if (!ready) return null;

  const base = dark ? DarkTheme : DefaultTheme;
  return (
    <ThemeProvider
      value={{
        ...base,
        colors: { ...base.colors, primary: t.primary, background: t.bg, card: t.card, text: t.text, border: t.border },
      }}>
      <StatusBar style={dark ? 'light' : 'dark'} />
      <Stack screenOptions={{ headerBackTitle: '뒤로' }}>
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
        <Stack.Screen name="compose" options={{ title: '홍보 글 만들기', presentation: 'modal' }} />
        <Stack.Screen name="guide" options={{ title: '가입·운영 가이드' }} />
      </Stack>
      <Toast />
    </ThemeProvider>
  );
}

function Toast() {
  const { toast } = useApp();
  const t = useTheme();
  if (!toast) return null;
  return (
    <View
      pointerEvents="none"
      style={{ position: 'absolute', left: 16, right: 16, bottom: 96, alignItems: 'center' }}>
      <View style={{ backgroundColor: t.text, borderRadius: 12, paddingVertical: 10, paddingHorizontal: 16, maxWidth: 520 }}>
        <Text style={{ color: t.bg, fontSize: 14, fontWeight: '600', textAlign: 'center' }}>{toast}</Text>
      </View>
    </View>
  );
}
