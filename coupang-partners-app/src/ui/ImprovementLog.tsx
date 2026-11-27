import { useMemo, useState } from 'react';
import { ScrollView, View } from 'react-native';

import type { DateRange } from '@/src/api/types';
import { IMPROVEMENTS } from '@/src/data/improvements';
import {
  CATEGORY_LABEL,
  formatMonthDay,
  isInDateRange,
  selectImprovements,
  STATUS_LABEL,
  type ImprovementFilter,
} from '@/src/lib/improvements';
import { Card, Chip, Row, Section, T } from '@/src/ui/kit';
import { useTheme } from '@/src/ui/theme';

export function ImprovementLog({ range }: { range?: DateRange }) {
  const t = useTheme();
  const [filter, setFilter] = useState<ImprovementFilter>('all');

  const items = useMemo(() => selectImprovements(IMPROVEMENTS, filter), [filter]);

  return (
    <Section
      title="릴스·운영 개선 이력"
      right={<T v="caption">총 {items.length}건</T>}>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 8 }}>
        <Chip label="전체" selected={filter === 'all'} onPress={() => setFilter('all')} />
        <Chip label="조치 완료" selected={filter === 'done'} onPress={() => setFilter('done')} />
        <Chip label="진행 중" selected={filter === 'in_progress'} onPress={() => setFilter('in_progress')} />
      </ScrollView>

      <Card style={{ gap: 0 }}>
        {items.length === 0 ? (
          <T v="sub">표시할 개선 이력이 아직 없어요.</T>
        ) : (
          items.map((item, i) => {
            const inRange = range ? isInDateRange(item.date, range) : false;
            return (
              <View
                key={item.id}
                style={{
                  paddingVertical: 12,
                  borderTopWidth: i ? 1 : 0,
                  borderTopColor: t.border,
                  gap: 4,
                }}>
                <Row style={{ justifyContent: 'space-between', flexWrap: 'wrap' }}>
                  <Row gap={6}>
                    <T v="caption" color="accent" style={{ fontWeight: '700' }}>
                      {formatMonthDay(item.date)}
                    </T>
                    <T v="caption" color="faint">
                      · {CATEGORY_LABEL[item.category]} · {item.id}
                    </T>
                    {inRange ? (
                      <T v="caption" color="primary" style={{ fontWeight: '700' }}>
                        · 조회 기간 내
                      </T>
                    ) : null}
                  </Row>
                  <T
                    v="caption"
                    color={item.status === 'done' ? 'primary' : 'sub'}
                    style={{ fontWeight: '600' }}>
                    {STATUS_LABEL[item.status]}
                  </T>
                </Row>

                <T style={{ fontWeight: '700' }}>{item.title}</T>
                <T v="sub">{item.summary}</T>
                <T v="caption" color="faint">
                  대상: {item.target}
                  {item.owner ? ` · 담당: ${item.owner}` : ''}
                </T>
              </View>
            );
          })
        )}
      </Card>
      <T v="caption">실적 변화와 함께 어떤 개선 조치가 있었는지 날짜별로 비교할 수 있어요.</T>
    </Section>
  );
}
