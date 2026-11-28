package app.jarvis.urlapp;

// 폰의 알람(--alarms)이 울릴 때 워치도 같이 (2026-11-28 찰스님: "갤럭시 워치에도 같이 진동으로").
//   폰 → 워치 메시지 /urlapp/alarm {act:"ring", id, label, at} → 강한 반복 진동(알람 용도) + 알림(끄기 / 5분 뒤) +
//                                                               화면이 꺼져 있으면 전체 화면 창(WatchAlarmActivity)
//                    /urlapp/alarm {act:"stop"}                 → 폰에서 껐거나 미뤘거나 5분이 지남: 멈춘다
//   워치 → 폰 메시지 /urlapp/alarm-action {act:"dismiss"|"snooze", id} → 폰 알람도 같이 멈추고 폰이 사이트에 알린다
// 워치 혼자서는 알람을 예약하지 않는다 (폰이 정각에 알려 줌 — 폰과 연결이 끊겨 있으면 워치는 울리지 않는다).

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class WatchAlarm {
    static final String CHANNEL = "urlapp_alarm";
    static final String ACTION_DISMISS = "app.jarvis.urlapp.action.WATCH_ALARM_DISMISS";
    static final String ACTION_SNOOZE = "app.jarvis.urlapp.action.WATCH_ALARM_SNOOZE";
    static final int NOTE_ID = 7411;
    static final int SNOOZE_MIN = 5;
    static final long RING_MS = 5 * 60_000L;
    // 손목에서도 알 수 있게: 0.9초 진동 · 0.35초 쉼 ×3, 1.1초 쉬고 반복 (세기 최대)
    static final long[] PATTERN = {0, 900, 350, 900, 350, 900, 1100};
    static final int[] AMPS = {0, 255, 0, 255, 0, 255, 0};

    static volatile boolean ringing;
    static volatile String title = "";
    static volatile String ringId = "";
    private static Context app;
    private static Vibrator vibrator;
    private static PowerManager.WakeLock wake;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable TIMEOUT = () -> stop(app);

    private WatchAlarm() {
    }

    /** 폰이 보낸 /urlapp/alarm. */
    static void fromPhone(Context ctx, String json) {
        try {
            JSONObject o = new JSONObject(json == null ? "" : json);
            String act = o.optString("act", "");
            if ("ring".equals(act)) {
                String id = o.optString("id", "");
                if (!AlarmPlan.validId(id)) return;
                ring(ctx, id, AlarmPlan.cleanLabel(o.optString("label", "")), o.optLong("at", System.currentTimeMillis()));
            } else if ("stop".equals(act)) {
                stop(ctx);
            }
        } catch (Exception ignored) {
            // 모양이 틀린 메시지
        }
    }

    static synchronized void ring(Context ctx, String id, String label, long at) {
        app = ctx.getApplicationContext();
        ringing = true;
        ringId = id;
        title = label;
        vibrate(app);
        hold(app);
        notifyRing(app, label, at);
        HANDLER.removeCallbacks(TIMEOUT);
        HANDLER.postDelayed(TIMEOUT, RING_MS);
    }

    static synchronized void stop(Context ctx) {
        if (ctx == null) return;
        ringing = false;
        HANDLER.removeCallbacks(TIMEOUT);
        try {
            if (vibrator != null) vibrator.cancel();
        } catch (RuntimeException ignored) {
            // 이미 멈춤
        }
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (RuntimeException ignored) {
            // 이미 풀림
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTE_ID);
    }

    /** 워치에서 끄기·5분 뒤: 워치는 바로 멈추고 폰에 알린다 (폰 알람도 멈추고, 폰이 사이트에 알림). */
    static void act(Context ctx, boolean snooze) {
        String id = ringId;
        stop(ctx);
        try {
            WatchPhone.send(ctx, "/urlapp/alarm-action", new JSONObject()
                    .put("act", snooze ? "snooze" : "dismiss").put("id", id).toString());
        } catch (Exception ignored) {
            // 보낼 수 없음
        }
    }

    private static void vibrate(Context ctx) {
        try {
            Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm == null ? null : vm.getDefaultVibrator();
            } else {
                v = legacyVibrator(ctx);
            }
            if (v == null || !v.hasVibrator()) return;
            vibrator = v;
            v.cancel();
            VibrationEffect e = VibrationEffect.createWaveform(PATTERN, AMPS, 1);
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
            else vibrateAlarm(v, e);
        } catch (RuntimeException ignored) {
            // 진동을 못 하는 기기
        }
    }

    @SuppressWarnings("deprecation") // 워치 OS 3 (안드로이드 11)
    private static Vibrator legacyVibrator(Context ctx) {
        return (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    }

    @SuppressWarnings("deprecation") // 워치 OS 3·4 (안드로이드 11·12)
    private static void vibrateAlarm(Vibrator v, VibrationEffect e) {
        v.vibrate(e, new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
    }

    private static void hold(Context ctx) {
        try {
            if (wake == null) {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "urlapp:watch-alarm");
                wake.setReferenceCounted(false);
            }
            wake.acquire(RING_MS + 10_000L);
        } catch (RuntimeException ignored) {
            // 진동은 그대로
        }
    }

    private static PendingIntent broadcast(Context ctx, int code, String action) {
        return PendingIntent.getBroadcast(ctx, code, new Intent(ctx, WatchAlarmReceiver.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** 알림 (알람 분류, 끄기 / 5분 뒤) + 화면이 꺼져 있으면 전체 화면 창. 알림 권한이 없으면 진동만. */
    private static void notifyRing(Context ctx, String label, long at) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String appName = ctx.getApplicationInfo().loadLabel(ctx.getPackageManager()).toString();
        NotificationChannel ch = new NotificationChannel(CHANNEL, appName + " 알람", NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null);
        ch.enableVibration(true); // 위 진동을 못 하면 알림 진동이라도 (알람 진동이 돌고 있으면 그쪽이 이긴다)
        ch.setVibrationPattern(PATTERN);
        ch.setBypassDnd(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
        PendingIntent full = PendingIntent.getActivity(ctx, 7412, new Intent(ctx, WatchAlarmActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent dismiss = broadcast(ctx, 7413, ACTION_DISMISS);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(label)
                .setContentText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(at)) + " 알람")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setWhen(at)
                .setShowWhen(true)
                .setOngoing(true)
                .setFullScreenIntent(full, true)
                .setContentIntent(full)
                .setDeleteIntent(dismiss)
                .addAction(new Notification.Action.Builder(null, "끄기", dismiss).build())
                .addAction(new Notification.Action.Builder(null, SNOOZE_MIN + "분 뒤", broadcast(ctx, 7414, ACTION_SNOOZE)).build())
                .build();
        try {
            nm.notify(NOTE_ID, n);
        } catch (RuntimeException ignored) {
            // 알림 권한 없음 (워치 OS 4+ 는 워치 앱을 한 번 열어 허용)
        }
    }
}
