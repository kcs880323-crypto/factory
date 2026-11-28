package app.jarvis.urlapp;

// 카드 알림 읽기 '사용 중지' (일시중지) — 은행 앱(NH pay 등)이 알림을 읽는 앱을 '악성 앱'으로 막을 때 잠깐 꺼 두는 용도 (2026-11-23 사용자 요청).
// 앱 아이콘을 길게 누르면 나오는 '사용 중지' 바로가기 → CardPauseActivity.
// 일시중지 = 알림 듣기 서비스(CardListenerService)의 시스템 구성요소를 꺼서 안드로이드가 그 서비스를 더 이상 묶지 않게 한다.
// 시스템의 '알림 접근' 허용 자체는 앱이 끌 수 없다(사용자가 설정에서) → 화면이 그 설정 화면으로 안내한다.
// 켜짐/꺼짐의 기준은 시스템에 남은 구성요소 상태 하나뿐이다 (앱 업데이트·재부팅 뒤에도 그대로 유지된다).

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

final class CardPause {
    private CardPause() {
    }

    static ComponentName listener(Context c) {
        return new ComponentName(c, CardListenerService.class);
    }

    /** 일시중지 중인지 (알림 듣기 서비스 구성요소가 꺼져 있는지). 이 앱에 듣기 서비스가 없으면 false. */
    static boolean paused(Context c) {
        try {
            return c.getPackageManager().getComponentEnabledSetting(listener(c)) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 이 앱에 알림 듣기 서비스가 들어 있는지 (--cards-via listener 로 만든 앱만). */
    static boolean available(Context c) {
        try {
            c.getPackageManager().getServiceInfo(listener(c), PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    /** 시스템의 '알림 접근' 허용이 아직 켜져 있는지 (끌 수 있는 것은 사용자뿐). */
    static boolean accessGranted(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(listener(c).flattenToString());
    }

    /** 일시중지: 듣기 서비스를 끄고 지금 묶여 있으면 풀어 달라고 한다. 앱 프로세스는 그대로 둔다(이 화면이 아직 떠 있다). */
    static boolean pause(Context c) {
        try {
            c.getPackageManager().setComponentEnabledSetting(listener(c), PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (RuntimeException e) {
            return false;
        }
        CardListenerService.unbindNow();
        journal(c, "일시중지했어요 (은행 앱용 — 알림 읽기를 멈춤)");
        return paused(c);
    }

    /** 다시 켜기: 구성요소를 기본(켜짐)으로 돌리고, 알림 접근이 켜져 있으면 바로 다시 묶어 달라고 한다. */
    static boolean resume(Context c) {
        try {
            c.getPackageManager().setComponentEnabledSetting(listener(c), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP);
        } catch (RuntimeException e) {
            return false;
        }
        if (accessGranted(c)) {
            try {
                NotificationListenerService.requestRebind(listener(c));
            } catch (RuntimeException ignored) {
                // 다음에 시스템이 알아서 묶는다
            }
        }
        journal(c, "다시 켰어요");
        return !paused(c);
    }

    /** 시스템의 이 앱 '알림 접근' 화면 (안드로이드 11+ 는 이 앱 전용 화면, 아니면 목록). */
    static Intent accessSettings(Context c) {
        if (Build.VERSION.SDK_INT >= 30) {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener(c).flattenToString());
            if (i.resolveActivity(c.getPackageManager()) != null) return i;
        }
        return new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
    }

    private static void journal(Context c, String what) {
        try {
            if (CardEngine.available(c)) CardEngine.get(c).store.journal("듣기", what);
        } catch (RuntimeException ignored) {
            // 기록을 못 남겨도 중지·켜기는 그대로
        }
    }
}
