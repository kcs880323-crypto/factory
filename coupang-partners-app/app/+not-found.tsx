import { Link, Stack } from 'expo-router';
import { View } from 'react-native';

import { T } from '@/src/ui/kit';
import { useTheme } from '@/src/ui/theme';

export default function NotFoundScreen() {
  const t = useTheme();
  return (
    <>
      <Stack.Screen options={{ title: '없는 화면' }} />
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', padding: 20, gap: 12, backgroundColor: t.bg }}>
        <T v="h2">찾는 화면이 없어요.</T>
        <Link href="/">
          <T color="primary">홈으로 가기</T>
        </Link>
      </View>
    </>
  );
}
