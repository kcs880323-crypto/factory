package app.jarvis.urlapp;

// 네이티브 알람(--alarms)의 받는 곳 (내보내지 않음 — 이 앱의 AlarmManager 예약과 알림 단추만 부른다).
//   ALARM_FIRE  : 정각이 됐다 → AlarmService 가 진동·알림·잠금 화면 창으로 울린다
//   DISMISS     : 끄기 (알림 단추·잠금 화면 창·알림을 밀어 없앰)
//   SNOOZE      : 5분 뒤 다시
//   CANCEL_SNOOZE: '다시 울려요' 알림의 끄기 (미룬 알람을 취소)
// Boot: 폰을 다시 켠 뒤·앱을 업데이트한 뒤·시각/시간대를 바꾼 뒤 저장한 알람을 다시 걸고 사이트에서 새로 읽는다
// (AlarmManager 예약은 재부팅하면 사라진다).

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    static final String ACTION_DISMISS = "app.jarvis.urlapp.action.ALARM_DISMISS";
    static final String ACTION_SNOOZE = "app.jarvis.urlapp.action.ALARM_SNOOZE";
    static final String ACTION_CANCEL_SNOOZE = "app.jarvis.urlapp.action.ALARM_CANCEL_SNOOZE";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (a == null || !AlarmSync.enabled(ctx)) return;
        switch (a) {
            case AlarmSync.ACTION_FIRE:
                AlarmService.ring(ctx, intent.getStringExtra("id"), intent.getStringExtra("label"),
                        intent.getLongExtra("fire_ms", System.currentTimeMillis()), intent.getBooleanExtra("snooze", false));
                break;
            case ACTION_DISMISS:
            case ACTION_SNOOZE:
                AlarmService.action(ctx, a);
                break;
            case ACTION_CANCEL_SNOOZE: {
                String id = intent.getStringExtra("id");
                if (id == null || !AlarmPlan.validId(id)) return;
                AlarmSync.clearSnooze(ctx, id);
                AlarmSync.post(ctx, "dismiss", id, 0);
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(AlarmService.snoozeNoteId(id));
                break;
            }
            default:
                break;
        }
    }

    /** 폰을 다시 켠 뒤 등: 저장한 알람을 다시 건다 (시스템이 보내는 것만 받는다). */
    public static class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String a = intent == null ? null : intent.getAction();
            if (a == null || !AlarmSync.enabled(ctx)) return;
            switch (a) {
                case Intent.ACTION_BOOT_COMPLETED:
                case Intent.ACTION_MY_PACKAGE_REPLACED:
                case Intent.ACTION_TIME_CHANGED:
                case Intent.ACTION_TIMEZONE_CHANGED:
                case "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED":
                    AlarmSync.reschedule(ctx);
                    AlarmSync.requestSync(ctx, "boot", 20_000); // 인터넷이 잡힐 즈음 사이트에서 새로 읽기
                    NotifyJob.schedule(ctx);
                    break;
                default:
                    break;
            }
        }
    }
}
