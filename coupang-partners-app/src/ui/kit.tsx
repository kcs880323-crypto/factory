// 화면에서 공통으로 쓰는 작은 부품들.
import type { ReactNode } from 'react';
import {
  ActivityIndicator,
  Image,
  Linking,
  Platform,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  type StyleProp,
  type TextInputProps,
  type TextStyle,
  type ViewStyle,
} from 'react-native';

import { Icon, type IconName } from './icon';
import { radius, useTheme, type Palette } from './theme';

export { Icon, type IconName } from './icon';

// ── 글자 ──────────────────────────────────────────────
type Variant = 'title' | 'h2' | 'h3' | 'body' | 'sub' | 'caption' | 'mono';

export function T({
  children,
  v = 'body',
  color,
  style,
  numberOfLines,
  selectable,
}: {
  children: ReactNode;
  v?: Variant;
  color?: keyof Palette | string;
  style?: StyleProp<TextStyle>;
  numberOfLines?: number;
  selectable?: boolean;
}) {
  const t = useTheme();
  const c = color ? ((t as Record<string, string>)[color] ?? color) : v === 'sub' || v === 'caption' ? t.sub : t.text;
  return (
    <Text style={[textStyles[v], { color: c }, style]} numberOfLines={numberOfLines} selectable={selectable}>
      {children}
    </Text>
  );
}

const textStyles = StyleSheet.create({
  title: { fontSize: 24, fontWeight: '800', letterSpacing: -0.4 },
  h2: { fontSize: 18, fontWeight: '700', letterSpacing: -0.2 },
  h3: { fontSize: 15, fontWeight: '700' },
  body: { fontSize: 15, lineHeight: 22 },
  sub: { fontSize: 13, lineHeight: 19 },
  caption: { fontSize: 12, lineHeight: 17 },
  mono: { fontSize: 13, fontFamily: 'monospace' },
});

// ── 레이아웃 ──────────────────────────────────────────
export function Screen({
  children,
  refreshing,
  onRefresh,
}: {
  children: ReactNode;
  refreshing?: boolean;
  onRefresh?: () => void;
}) {
  const t = useTheme();
  return (
    <ScrollView
      style={{ flex: 1, backgroundColor: t.bg }}
      contentContainerStyle={{ padding: 16, paddingBottom: 48, gap: 14, width: '100%', maxWidth: 720, alignSelf: 'center' }}
      keyboardShouldPersistTaps="handled"
      refreshControl={onRefresh ? <RefreshControl refreshing={!!refreshing} onRefresh={onRefresh} tintColor={t.primary} /> : undefined}>
      {children}
    </ScrollView>
  );
}

export function Card({ children, style, onPress }: { children: ReactNode; style?: StyleProp<ViewStyle>; onPress?: () => void }) {
  const t = useTheme();
  const base = [{ backgroundColor: t.card, borderColor: t.border, borderWidth: StyleSheet.hairlineWidth, borderRadius: radius.lg, padding: 16, gap: 10 }, style];
  if (!onPress) return <View style={base}>{children}</View>;
  return (
    <Pressable onPress={onPress} style={({ pressed }) => [base, pressed && { opacity: 0.7 }]}>
      {children}
    </Pressable>
  );
}

export function Row({ children, style, gap = 8 }: { children: ReactNode; style?: StyleProp<ViewStyle>; gap?: number }) {
  return <View style={[{ flexDirection: 'row', alignItems: 'center', gap }, style]}>{children}</View>;
}

export function Section({ title, right, children }: { title: string; right?: ReactNode; children: ReactNode }) {
  return (
    <View style={{ gap: 10 }}>
      <Row style={{ justifyContent: 'space-between' }}>
        <T v="h2">{title}</T>
        {right}
      </Row>
      {children}
    </View>
  );
}

// ── 버튼·칩 ──────────────────────────────────────────
/** 바깥 주소로 가는 버튼. 웹에서는 진짜 <a> 링크로 그린다 (아티팩트 화면은 스크립트로 연 창을 막음). */
function pressProps(href: string | undefined, onPress: (() => void) | undefined): object {
  if (!href) return { onPress };
  if (Platform.OS === 'web') return { href, hrefAttrs: { target: '_blank', rel: 'noopener noreferrer' } };
  return { onPress: () => Linking.openURL(href).catch(() => undefined) };
}

type ButtonKind = 'primary' | 'secondary' | 'ghost' | 'danger';

export function Button({
  label,
  onPress,
  href,
  kind = 'primary',
  icon,
  loading,
  disabled,
  small,
  style,
}: {
  label: string;
  onPress?: () => void;
  href?: string;
  kind?: ButtonKind;
  icon?: IconName;
  loading?: boolean;
  disabled?: boolean;
  small?: boolean;
  style?: StyleProp<ViewStyle>;
}) {
  const t = useTheme();
  const bg = { primary: t.primary, secondary: t.primarySoft, ghost: 'transparent', danger: t.dangerSoft }[kind];
  const fg = { primary: t.primaryText, secondary: t.primary, ghost: t.primary, danger: t.danger }[kind];
  const off = disabled || loading;
  return (
    <Pressable
      accessibilityRole={href ? 'link' : 'button'}
      accessibilityLabel={label}
      {...(off ? {} : pressProps(href, onPress))}
      style={({ pressed }) => [
        {
          backgroundColor: bg,
          borderRadius: radius.md,
          paddingVertical: small ? 8 : 12,
          paddingHorizontal: small ? 12 : 16,
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 6,
          opacity: off ? 0.5 : pressed ? 0.75 : 1,
          borderWidth: kind === 'ghost' ? 1 : 0,
          borderColor: t.border,
          minHeight: small ? 36 : 46,
        },
        style,
      ]}>
      {loading ? <ActivityIndicator size="small" color={fg} /> : icon ? <Icon name={icon} size={small ? 16 : 18} color={fg} /> : null}
      <Text style={{ color: fg, fontSize: small ? 13 : 15, fontWeight: '700' }}>{label}</Text>
    </Pressable>
  );
}

export function IconButton({
  icon,
  label,
  onPress,
  href,
  color,
}: {
  icon: IconName;
  label: string;
  onPress?: () => void;
  href?: string;
  color?: string;
}) {
  const t = useTheme();
  return (
    <Pressable
      accessibilityRole={href ? 'link' : 'button'}
      accessibilityLabel={label}
      {...pressProps(href, onPress)}
      hitSlop={6}
      style={({ pressed }) => ({ alignItems: 'center', gap: 2, paddingVertical: 4, paddingHorizontal: 6, opacity: pressed ? 0.5 : 1 })}>
      <Icon name={icon} size={20} color={color ?? t.primary} />
      <Text style={{ fontSize: 11, color: color ?? t.sub }}>{label}</Text>
    </Pressable>
  );
}

export function Chip({ label, selected, onPress }: { label: string; selected?: boolean; onPress?: () => void }) {
  const t = useTheme();
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityState={{ selected }}
      style={({ pressed }) => ({
        paddingVertical: 7,
        paddingHorizontal: 12,
        borderRadius: 999,
        backgroundColor: selected ? t.primary : t.card,
        borderWidth: 1,
        borderColor: selected ? t.primary : t.border,
        opacity: pressed ? 0.7 : 1,
      })}>
      <Text style={{ color: selected ? t.primaryText : t.text, fontSize: 13, fontWeight: selected ? '700' : '500' }}>{label}</Text>
    </Pressable>
  );
}

export function Badge({ label, tone = 'primary' }: { label: string; tone?: 'primary' | 'accent' | 'info' | 'demo' | 'danger' }) {
  const t = useTheme();
  const map = {
    primary: [t.primarySoft, t.primary],
    accent: [t.accentSoft, t.accent],
    info: [t.infoSoft, t.info],
    demo: [t.demoSoft, t.demo],
    danger: [t.dangerSoft, t.danger],
  } as const;
  const [bg, fg] = map[tone];
  return (
    <View style={{ backgroundColor: bg, borderRadius: 6, paddingHorizontal: 6, paddingVertical: 2, alignSelf: 'flex-start' }}>
      <Text style={{ color: fg, fontSize: 11, fontWeight: '700' }}>{label}</Text>
    </View>
  );
}

export function Banner({
  tone = 'info',
  icon,
  title,
  children,
  action,
}: {
  tone?: 'info' | 'warn' | 'demo' | 'success' | 'danger';
  icon?: IconName;
  title?: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  const t = useTheme();
  const [bg, fg] = {
    info: [t.infoSoft, t.info],
    warn: [t.accentSoft, t.accent],
    demo: [t.demoSoft, t.demo],
    success: [t.primarySoft, t.primary],
    danger: [t.dangerSoft, t.danger],
  }[tone];
  return (
    <View style={{ backgroundColor: bg, borderRadius: radius.md, padding: 14, gap: 8 }}>
      <Row gap={8} style={{ alignItems: 'flex-start' }}>
        {icon ? <Icon name={icon} size={18} color={fg} style={{ marginTop: 2 }} /> : null}
        <View style={{ flex: 1, gap: 4 }}>
          {title ? <Text style={{ color: fg, fontWeight: '800', fontSize: 14 }}>{title}</Text> : null}
          {typeof children === 'string' ? <Text style={{ color: t.text, fontSize: 13, lineHeight: 19 }}>{children}</Text> : children}
        </View>
      </Row>
      {action}
    </View>
  );
}

// ── 입력 ──────────────────────────────────────────────
export function Field({
  label,
  help,
  error,
  right,
  ...input
}: TextInputProps & { label?: string; help?: string; error?: string | null; right?: ReactNode }) {
  const t = useTheme();
  return (
    <View style={{ gap: 6, minWidth: 0 }}>
      {label ? <T v="h3">{label}</T> : null}
      <Row gap={6} style={{ minWidth: 0 }}>
        <TextInput
          placeholderTextColor={t.faint}
          {...input}
          style={[
            {
              flex: 1,
              minWidth: 0,
              backgroundColor: t.cardAlt,
              color: t.text,
              borderRadius: radius.md,
              borderWidth: 1,
              borderColor: error ? t.danger : t.border,
              paddingHorizontal: 12,
              paddingVertical: input.multiline ? 10 : 11,
              fontSize: 15,
              minHeight: input.multiline ? 120 : 46,
              textAlignVertical: input.multiline ? 'top' : 'center',
            },
            input.style,
          ]}
        />
        {right}
      </Row>
      {error ? <T v="caption" color="danger">{error}</T> : help ? <T v="caption">{help}</T> : null}
    </View>
  );
}

// ── 숫자 카드·막대 그래프 ─────────────────────────────
export function Stat({ label, value, sub, tone }: { label: string; value: string; sub?: string; tone?: 'primary' | 'accent' }) {
  const t = useTheme();
  return (
    <View style={{ flexGrow: 1, flexBasis: '45%', backgroundColor: t.card, borderColor: t.border, borderWidth: StyleSheet.hairlineWidth, borderRadius: radius.md, padding: 12, gap: 2 }}>
      <T v="caption">{label}</T>
      <Text style={{ fontSize: 20, fontWeight: '800', color: tone ? t[tone] : t.text, letterSpacing: -0.3 }} numberOfLines={1} adjustsFontSizeToFit>
        {value}
      </Text>
      {sub ? <T v="caption">{sub}</T> : null}
    </View>
  );
}

export function Bars({
  data,
  color,
  format,
  height = 110,
}: {
  data: { label: string; value: number }[];
  color?: string;
  format?: (n: number) => string;
  height?: number;
}) {
  const t = useTheme();
  const max = Math.max(1, ...data.map((d) => d.value));
  const peak = data.reduce((best, d, i) => (d.value > data[best].value ? i : best), 0);
  const step = Math.max(1, Math.ceil(data.length / 7));
  return (
    <View style={{ gap: 6 }}>
      {format && data.length ? (
        <T v="caption">
          가장 높은 날 {data[peak].label} · {format(data[peak].value)}
        </T>
      ) : null}
      <View style={{ flexDirection: 'row', alignItems: 'flex-end', height, gap: data.length > 20 ? 2 : 4 }}>
        {data.map((d, i) => (
          <View
            key={`${d.label}-${i}`}
            accessibilityLabel={`${d.label} ${format ? format(d.value) : d.value}`}
            style={{
              flex: 1,
              height: Math.max(2, (d.value / max) * height),
              backgroundColor: color ?? t.bar,
              borderTopLeftRadius: 4,
              borderTopRightRadius: 4,
              opacity: i === peak ? 1 : 0.55,
            }}
          />
        ))}
      </View>
      <View style={{ flexDirection: 'row' }}>
        {data.map((d, i) => (
          <Text key={`l-${i}`} style={{ flex: 1, fontSize: 10, color: t.faint, textAlign: 'center' }} numberOfLines={1}>
            {i % step === 0 ? d.label : ''}
          </Text>
        ))}
      </View>
    </View>
  );
}

// ── 상품 이미지 ─────────────────────────────────────
export function Thumb({ uri, size = 72 }: { uri?: string; size?: number }) {
  const t = useTheme();
  if (uri && !uri.includes('example.com')) {
    return <Image source={{ uri }} style={{ width: size, height: size, borderRadius: radius.sm, backgroundColor: t.cardAlt }} />;
  }
  return (
    <View style={{ width: size, height: size, borderRadius: radius.sm, backgroundColor: t.cardAlt, alignItems: 'center', justifyContent: 'center' }}>
      <Icon name="box" size={size / 2.6} color={t.faint} />
    </View>
  );
}

export function Empty({ icon = 'info', title, children }: { icon?: IconName; title: string; children?: ReactNode }) {
  const t = useTheme();
  return (
    <View style={{ alignItems: 'center', paddingVertical: 28, gap: 8 }}>
      <Icon name={icon} size={30} color={t.faint} />
      <T v="h3" style={{ textAlign: 'center' }}>
        {title}
      </T>
      {typeof children === 'string' ? (
        <T v="sub" style={{ textAlign: 'center' }}>
          {children}
        </T>
      ) : (
        children
      )}
    </View>
  );
}

export function Loading({ label = '불러오는 중…' }: { label?: string }) {
  const t = useTheme();
  return (
    <Row style={{ justifyContent: 'center', paddingVertical: 24 }}>
      <ActivityIndicator color={t.primary} />
      <T v="sub">{label}</T>
    </Row>
  );
}
