import { Tabs } from 'expo-router';

import { Icon, type IconName } from '@/src/ui/kit';
import { useTheme } from '@/src/ui/theme';

const TABS: { name: string; title: string; header: string; icon: IconName }[] = [
  { name: 'index', title: '홈', header: '파트너스 매니저', icon: 'home' },
  { name: 'products', title: '상품', header: '상품 찾기', icon: 'search' },
  { name: 'links', title: '링크', header: '링크 보관함', icon: 'link' },
  { name: 'reports', title: '실적', header: '실적', icon: 'chart' },
  { name: 'settings', title: '설정', header: '설정', icon: 'settings' },
];

export default function TabLayout() {
  const t = useTheme();
  return (
    <Tabs
      screenOptions={{
        tabBarActiveTintColor: t.primary,
        tabBarInactiveTintColor: t.faint,
        tabBarStyle: { backgroundColor: t.card, borderTopColor: t.border },
        headerStyle: { backgroundColor: t.card },
        headerTitleStyle: { color: t.text, fontWeight: '800' },
        headerShadowVisible: false,
      }}>
      {TABS.map((tab) => (
        <Tabs.Screen
          key={tab.name}
          name={tab.name}
          options={{
            title: tab.title,
            headerTitle: tab.header,
            tabBarIcon: ({ color }) => <Icon name={tab.icon} size={24} color={color} />,
          }}
        />
      ))}
    </Tabs>
  );
}
