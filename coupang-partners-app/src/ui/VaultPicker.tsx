// 보관함에서 상품 고르기 — 짧은 글·블로그 글·홍보 영상 화면이 함께 쓴다.
// 검색칸(이름·메모·주소) + 목록(사진·가격·저장일). 누르면 그 링크를 고르고 닫힌다.
import { useMemo, useState } from 'react';
import { Modal, Pressable, ScrollView, View } from 'react-native';

import { dateTimeText, won } from '../lib/format';
import type { SavedLink } from '../store/AppContext';
import { Badge, Button, Field, Icon, Row, T, Thumb } from './kit';
import { radius, useTheme } from './theme';

export function VaultPickerButton({ links, selectedId, onPick }: { links: SavedLink[]; selectedId?: string; onPick: (l: SavedLink) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button small kind="secondary" icon="box" label={`보관함에서 선택하기 (${links.length})`} onPress={() => setOpen(true)} disabled={!links.length} />
      {open ? (
        <VaultPicker
          links={links}
          selectedId={selectedId}
          onClose={() => setOpen(false)}
          onPick={(l) => {
            setOpen(false);
            onPick(l);
          }}
        />
      ) : null}
    </>
  );
}

export function VaultPicker({ links, selectedId, onPick, onClose }: { links: SavedLink[]; selectedId?: string; onPick: (l: SavedLink) => void; onClose: () => void }) {
  const t = useTheme();
  const [q, setQ] = useState('');
  const shown = useMemo(() => {
    const words = q.toLowerCase().split(/\s+/).filter(Boolean);
    if (!words.length) return links;
    return links.filter((l) => {
      const hay = `${l.title} ${l.memo ?? ''} ${l.url} ${l.category ?? ''}`.toLowerCase();
      return words.every((w) => hay.includes(w));
    });
  }, [links, q]);

  return (
    <Modal transparent visible animationType="fade" onRequestClose={onClose}>
      <Pressable accessibilityLabel="닫기" onPress={onClose} style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.45)', alignItems: 'center', justifyContent: 'center', padding: 16 }}>
        <Pressable
          onPress={() => undefined}
          accessibilityRole="none"
          style={{ width: '100%', maxWidth: 520, maxHeight: '88%', backgroundColor: t.card, borderRadius: radius.lg, padding: 16, gap: 10 }}>
          <Row style={{ justifyContent: 'space-between' }}>
            <T v="h2">보관함에서 선택</T>
            <Pressable onPress={onClose} accessibilityRole="button" accessibilityLabel="닫기" hitSlop={10}>
              <Icon name="close" color={t.sub} />
            </Pressable>
          </Row>
          <Field value={q} onChangeText={setQ} placeholder="이름·메모로 찾기" autoCorrect={false} />
          <T v="caption">{q ? `${shown.length}개 찾음` : `${links.length}개 · 최근에 저장한 순서`}</T>
          <ScrollView style={{ flexGrow: 0 }} contentContainerStyle={{ gap: 6 }}>
            {shown.length === 0 ? <T v="sub">찾는 상품이 없어요.</T> : null}
            {shown.map((l) => {
              const on = l.id === selectedId;
              return (
                <Pressable
                  key={l.id}
                  accessibilityRole="button"
                  accessibilityLabel={`${l.title} 고르기`}
                  onPress={() => onPick(l)}
                  style={({ pressed }) => ({
                    flexDirection: 'row',
                    gap: 10,
                    alignItems: 'center',
                    padding: 8,
                    borderRadius: radius.md,
                    borderWidth: 1,
                    borderColor: on ? t.primary : t.border,
                    backgroundColor: on ? t.primarySoft : pressed ? t.cardAlt : t.card,
                  })}>
                  <Thumb uri={l.image} size={48} />
                  <View style={{ flex: 1, minWidth: 0, gap: 3 }}>
                    <T numberOfLines={2} style={{ fontWeight: '600' }}>
                      {l.title}
                    </T>
                    <Row gap={6} style={{ flexWrap: 'wrap' }}>
                      {l.price ? <Badge label={won(l.price)} tone="primary" /> : null}
                      {l.source === 'ai' ? <Badge label="AI 검색" tone="accent" /> : null}
                      {l.kind === 'travel' ? <Badge label="여행" tone="info" /> : null}
                      <T v="caption">{dateTimeText(l.createdAt)}</T>
                    </Row>
                  </View>
                  {on ? <Icon name="checked" color={t.primary} /> : null}
                </Pressable>
              );
            })}
          </ScrollView>
        </Pressable>
      </Pressable>
    </Modal>
  );
}
