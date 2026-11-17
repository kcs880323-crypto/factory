// 웹용 확인 대화상자. (휴대폰 앱은 시스템 Alert를 쓰고, 이 창은 열리지 않는다.)
import { Modal, Pressable, View } from 'react-native';

import { useApp } from '../store/AppContext';
import { Button, Row, T } from './kit';
import { radius, useTheme } from './theme';

export function ConfirmDialog() {
  const { dialog, closeDialog } = useApp();
  const t = useTheme();
  if (!dialog) return null;
  return (
    <Modal transparent visible animationType="fade" onRequestClose={closeDialog}>
      <Pressable
        accessibilityLabel="닫기"
        onPress={closeDialog}
        style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.45)', alignItems: 'center', justifyContent: 'center', padding: 24 }}>
        <Pressable
          onPress={() => undefined}
          accessibilityRole="alert"
          style={{ width: '100%', maxWidth: 420, backgroundColor: t.card, borderRadius: radius.lg, padding: 20, gap: 12 }}>
          <T v="h2">{dialog.title}</T>
          <T v="sub">{dialog.message}</T>
          <View style={{ height: 4 }} />
          <Row>
            <Button style={{ flex: 1 }} kind="ghost" label="취소" onPress={closeDialog} />
            <Button
              style={{ flex: 1 }}
              kind="danger"
              label={dialog.ok}
              onPress={() => {
                const run = dialog.onOk;
                closeDialog();
                run();
              }}
            />
          </Row>
        </Pressable>
      </Pressable>
    </Modal>
  );
}
