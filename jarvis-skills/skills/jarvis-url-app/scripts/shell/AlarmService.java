package app.jarvis.urlapp;

// 네이티브 알람(--alarms)이 울리는 동안 도는 전경 서비스 — 앱이 꺼져 있거나 화면이 꺼져 있어도 시계 앱처럼:
//   · 강한 반복 진동 (알람 용도 — 무음·진동 모드에서도 울림), 5분 동안 아무것도 안 누르면 멈추고 '놓친 알람' 알림
//   · 알림(알람 분류, 끄기 / 5분 뒤 단추) + 잠금 화면이면 전체 화면 창(AlarmActivity)
//   · 끄기·5분 뒤는 사이트에도 알린다 (AlarmSync.post), 그 뒤 사이트에서 다음 알람을 다시 읽는다 (반복 알람)
// 소리는 내지 않는다 (사용자 요청: 진동으로). 전경 서비스 종류는 '알람을 이어 가는' systemExempted (정확한 알람 권한이 있는 앱),
// 안 되면 shortService(3분)로, 그것도 안 되면 진동하는 알림만 띄운다.

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class AlarmService extends Service {
    static final String ACTION_RING = "app.jarvis.urlapp.action.ALARM_RING";
    static final String CHANNEL = "urlapp_alarm";           // 울리는 알람 (진동은 서비스가 직접)
    static final String CHANNEL_INFO = "urlapp_alarm_info"; // '다시 울려요'·'놓친 알람'
    static final String CHANNEL_FALLBACK = "urlapp_alarm_vibrate"; // 서비스를 못 켤 때: 알림 자체가 반복 진동
    static final int NOTE_ID = 7401;
    static final int SNOOZE_MIN = 5;
    static final long RING_MS = 5 * 60_000L;
    // 강하게: 1초 진동 · 0.4초 쉼 ×3, 1.2초 쉬고 반복 (세기 최대)
    static final long[] PATTERN = {0, 1000, 400, 1000, 400, 1000, 1200};
    static final int[] AMPS = {0, 255, 0, 255, 0, 255, 0};

    static volatile boolean ringing;
    static volatile String ringTitle = "";
    static volatile long ringAt;

    private final LinkedHashMap<String, String> current = new LinkedHashMap<>(); // 울리는 알람 id → 이름
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::timeOut;
    private Vibrator vibrator;
    private PowerManager.WakeLock wake;

    /** 정각: 서비스를 켜서 울린다 (정확한 알람의 콜백이라 앱이 뒤에 있어도 전경 서비스를 켤 수 있다). */
    static void ring(Context ctx, String id, String label, long fireMs, boolean snooze) {
        if (id == null || !AlarmPlan.validId(id)) return;
        Intent i = new Intent(ctx, AlarmService.class).setAction(ACTION_RING)
                .putExtra("id", id).putExtra("label", AlarmPlan.cleanLabel(label)).putExtra("fire_ms", fireMs).putExtra("snooze", snooze);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (RuntimeException e) {
            fallback(ctx, id, AlarmPlan.cleanLabel(label), fireMs);
        }
    }

    /** 끄기·5분 뒤 (알림 단추·잠금 화면 창). 울리는 중이 아니면 알림만 치운다. */
    static void action(Context ctx, String action) {
        if (!ringing) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTE_ID);
            String fid = AlarmSync.prefs(ctx).getString("fallback", null); // 진동 알림(대체)으로 울린 알람
            if (fid != null) {
                AlarmSync.prefs(ctx).edit().remove("fallback").apply();
                if (AlarmReceiver.ACTION_DISMISS.equals(action)) AlarmSync.post(ctx, "dismiss", fid, 0);
            }
            return;
        }
        try {
            ctx.startService(new Intent(ctx, AlarmService.class).setAction(action));
        } catch (RuntimeException ignored) {
            // 서비스가 막 끝났음
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_RING.equals(a)) {
            String id = intent.getStringExtra("id");
            if (id == null || !AlarmPlan.validId(id)) {
                if (current.isEmpty()) stopSelf();
                return START_NOT_STICKY;
            }
            String label = AlarmPlan.cleanLabel(intent.getStringExtra("label"));
            long at = intent.getLongExtra("fire_ms", System.currentTimeMillis());
            if (intent.getBooleanExtra("snooze", false)) {
                AlarmSync.forgetSnooze(this, id); // 미룬 알람이 울림: 기록만 지운다 (예약은 이미 쓰였다)
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(snoozeNoteId(id));
            }
            current.put(id, label);
            ringAt = at;
            ringTitle = title();
            Notification n = ringNote(this, ringTitle, at);
            if (!startFg(n)) {
                current.remove(id);
                fallback(this, id, label, at);
                if (current.isEmpty()) stopSelf();
                return START_NOT_STICKY;
            }
            ringing = true;
            vibrate();
            hold();
            handler.removeCallbacks(timeout);
            handler.postDelayed(timeout, RING_MS);
        } else if (AlarmReceiver.ACTION_DISMISS.equals(a)) {
            finish(false);
        } else if (AlarmReceiver.ACTION_SNOOZE.equals(a)) {
            finish(true);
        } else if (current.isEmpty()) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private String title() {
        String first = current.isEmpty() ? "알람" : current.values().iterator().next();
        return current.size() > 1 ? first + " 외 " + (current.size() - 1) + "개" : first;
    }

    private boolean startFg(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
            else startForeground(NOTE_ID, n);
            return true;
        } catch (RuntimeException e) {
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE); // 3분까지
                    return true;
                } catch (RuntimeException ignored) {
                    // 아래 대체로
                }
            }
            return false;
        }
    }

    @Override
    public void onTimeout(int startId) { // shortService 3분이 끝남 (안드로이드 14)
        timeOut();
    }

    @Override
    public void onTimeout(int startId, int fgsType) { // 안드로이드 15+
        timeOut();
    }

    /** 끄기(snooze=false) 또는 5분 뒤(true): 울리던 알람 모두. */
    private void finish(boolean snooze) {
        for (Map.Entry<String, String> e : current.entrySet()) {
            if (snooze) {
                long until = AlarmSync.snooze(this, e.getKey(), e.getValue(), SNOOZE_MIN);
                AlarmSync.post(this, "snooze", e.getKey(), SNOOZE_MIN);
                snoozeNote(this, e.getKey(), e.getValue(), until);
            } else {
                AlarmSync.clearSnooze(this, e.getKey());
                AlarmSync.post(this, "dismiss", e.getKey(), 0);
            }
        }
        current.clear();
        quiet();
        AlarmSync.requestSync(this, snooze ? "snooze" : "dismiss", 4000); // 반복 알람은 다음 시각을 다시 읽는다
        stopFg();
        stopSelf();
    }

    /** 5분 동안 아무것도 안 누름: 멈추고 '놓친 알람' 알림. 사이트에는 알리지 않는다 (앱을 열면 사이트가 알려 준다). */
    private void timeOut() {
        for (Map.Entry<String, String> e : current.entrySet()) missedNote(this, e.getKey(), e.getValue(), ringAt);
        current.clear();
        quiet();
        AlarmSync.requestSync(this, "missed", 4000);
        stopFg();
        stopSelf();
    }

    private void stopFg() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTE_ID);
    }

    @SuppressWarnings("deprecation") // VIBRATOR_SERVICE·vibrate(effect, AudioAttributes): 안드로이드 12·13 미만
    private void vibrate() {
        try {
            Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
                v = vm == null ? null : vm.getDefaultVibrator();
            } else {
                v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            }
            if (v == null || !v.hasVibrator()) return;
            vibrator = v;
            v.cancel();
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(VibrationEffect.createWaveform(PATTERN, AMPS, 1),
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
            } else if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(PATTERN, AMPS, 1), new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            } else {
                vibrateOld(v);
            }
        } catch (RuntimeException ignored) {
            // 진동을 못 하는 기기
        }
    }

    @SuppressWarnings("deprecation") // 안드로이드 8 미만
    private static void vibrateOld(Vibrator v) {
        v.vibrate(PATTERN, 1);
    }

    private void hold() {
        try {
            if (wake == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm == null) return;
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "urlapp:alarm");
                wake.setReferenceCounted(false);
            }
            wake.acquire(RING_MS + 15_000L);
        } catch (RuntimeException ignored) {
            // 진동은 그대로
        }
    }

    private void quiet() {
        ringing = false;
        handler.removeCallbacks(timeout);
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
    }

    @Override
    public void onDestroy() {
        quiet();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- 알림 ----------

    static int snoozeNoteId(String id) {
        return AlarmPlan.snoozeCode(id) ^ 0x5A5A5A;
    }

    private static String hhmm(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    private static int icon(Context ctx) {
        int i = ctx.getResources().getIdentifier("urlapp_alarm", "drawable", ctx.getPackageName());
        return i != 0 ? i : android.R.drawable.ic_lock_idle_alarm;
    }

    private static PendingIntent broadcast(Context ctx, int code, String action, String id) {
        Intent i = new Intent(ctx, AlarmReceiver.class).setAction(action);
        if (id != null) i.putExtra("id", id);
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void channels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String app = ctx.getApplicationInfo().loadLabel(ctx.getPackageManager()).toString();
        NotificationChannel ring = new NotificationChannel(CHANNEL, app + " 알람", NotificationManager.IMPORTANCE_HIGH);
        ring.setDescription("알람 시각에 진동으로 알려요 (끄기 / 5분 뒤)");
        ring.setSound(null, null);
        ring.enableVibration(false); // 진동은 서비스가 알람 용도로 직접 (무음 모드에서도)
        ring.setBypassDnd(true);
        ring.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ring);
        NotificationChannel info = new NotificationChannel(CHANNEL_INFO, app + " 알람 안내", NotificationManager.IMPORTANCE_DEFAULT);
        info.setSound(null, null);
        info.enableVibration(false);
        nm.createNotificationChannel(info);
        NotificationChannel vib = new NotificationChannel(CHANNEL_FALLBACK, app + " 알람 (진동 알림)", NotificationManager.IMPORTANCE_HIGH);
        vib.setSound(null, null);
        vib.enableVibration(true);
        vib.setVibrationPattern(PATTERN);
        vib.setBypassDnd(true);
        vib.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(vib);
    }

    @SuppressWarnings("deprecation") // Notification.Builder(Context)·setPriority: 안드로이드 8 미만
    private static Notification.Builder builder(Context ctx, String channel) {
        channels(ctx);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, channel) : new Notification.Builder(ctx);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MAX);
        return b.setSmallIcon(icon(ctx)).setVisibility(Notification.VISIBILITY_PUBLIC).setShowWhen(true);
    }

    private static Notification ringNote(Context ctx, String title, long at) {
        PendingIntent full = PendingIntent.getActivity(ctx, 7403, new Intent(ctx, AlarmActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent dismiss = broadcast(ctx, 7404, AlarmReceiver.ACTION_DISMISS, null);
        Notification.Builder b = builder(ctx, CHANNEL)
                .setContentTitle(title)
                .setContentText(hhmm(at) + " 알람")
                .setCategory(Notification.CATEGORY_ALARM)
                .setWhen(at)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(full, true)
                .setContentIntent(full)
                .setDeleteIntent(dismiss) // 밀어서 없애면 끄기
                .addAction(new Notification.Action.Builder(null, "끄기", dismiss).build())
                .addAction(new Notification.Action.Builder(null, SNOOZE_MIN + "분 뒤",
                        broadcast(ctx, 7405, AlarmReceiver.ACTION_SNOOZE, null)).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private static void snoozeNote(Context ctx, String id, String label, long until) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.notify(snoozeNoteId(id), builder(ctx, CHANNEL_INFO)
                .setContentTitle(label)
                .setContentText(hhmm(until) + "에 다시 울려요")
                .setCategory(Notification.CATEGORY_ALARM)
                .setWhen(until)
                .setOngoing(false)
                .addAction(new Notification.Action.Builder(null, "끄기",
                        broadcast(ctx, snoozeNoteId(id), AlarmReceiver.ACTION_CANCEL_SNOOZE, id)).build())
                .build());
    }

    private static void missedNote(Context ctx, String id, String label, long at) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        PendingIntent open = PendingIntent.getActivity(ctx, 7406, new Intent(ctx, MainActivity.class)
                        .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(snoozeNoteId(id), builder(ctx, CHANNEL_INFO)
                .setContentTitle("놓친 알람 · " + label)
                .setContentText(hhmm(at) + " 알람이 5분 동안 울렸어요")
                .setWhen(at)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build());
    }

    /** 서비스를 켤 수 없을 때: 알림 자체가 반복 진동 (끌 때까지 — FLAG_INSISTENT). */
    static void fallback(Context ctx, String id, String label, long at) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        PendingIntent dismiss = broadcast(ctx, 7404, AlarmReceiver.ACTION_DISMISS, null);
        Notification n = builder(ctx, CHANNEL_FALLBACK)
                .setContentTitle(label)
                .setContentText(hhmm(at) + " 알람")
                .setCategory(Notification.CATEGORY_ALARM)
                .setWhen(at)
                .setAutoCancel(true)
                .setDeleteIntent(dismiss)
                .addAction(new Notification.Action.Builder(null, "끄기", dismiss).build())
                .build();
        n.flags |= Notification.FLAG_INSISTENT;
        nm.notify(NOTE_ID, n);
        AlarmSync.prefs(ctx).edit().putString("fallback", id).apply(); // 끄면 사이트에도 알린다 (action)
    }
}
