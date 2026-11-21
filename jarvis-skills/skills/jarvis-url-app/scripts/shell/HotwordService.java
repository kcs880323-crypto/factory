package app.jarvis.urlapp;

// '하이 자비스' 상시 듣기 (--hotword 앱). 빅스비처럼 폰이 듣고 있다가 호출어가 들리면 위젯 마이크와 같은 대화 창(VoiceActivity)을 띄운다.
//
//  - 마이크를 쓰는 '전경 서비스'라 알림 줄에 '듣는 중' 알림이 늘 보인다 (안드로이드 규칙. 끄기 단추가 그 알림에 있다).
//  - 소리는 폰 안에서만 Vosk 한국어 모델(HotwordEngine)로 듣고 어디에도 보내거나 저장하지 않는다.
//  - 배터리: 조용할 때는 소리 크기만 보고(인식 안 함), 소리가 날 때만 인식기에 넣는다. 기본은 화면이 켜져 있을 때만 듣는다.
//  - 앱 화면이 보이는 동안(사이트가 스스로 듣는다), 대화 창이 떠 있는 동안, 통화 중, 다른 앱이 마이크를 쓰는 동안은 마이크를 놓는다.
//  - 안드로이드 14+ 는 앱이 뒤에 있을 때 마이크 서비스를 새로 켤 수 없다 → 앱·설정 화면을 열 때 켜고,
//    폰을 다시 켰거나 시스템이 끈 뒤에는 '눌러서 다시 켜기' 알림을 띄운다.
//  - 호출어가 들리면 대화 창을 바로 띄운다 ('다른 앱 위에 표시' 허용이 있어야 뒤에서도 뜬다. 없으면 '눌러서 말하기' 알림).
//    잠긴 폰에서는 대화 창이 잠금 풀기를 먼저 요청한다 (잠긴 채로는 자비스에 묻지 않는다).

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HotwordService extends Service {
    static final String PREFS = "urlapp_hotword";
    static final String ACTION_START = "app.jarvis.urlapp.action.HOTWORD_START";
    static final String ACTION_STOP = "app.jarvis.urlapp.action.HOTWORD_STOP";
    private static final String CHANNEL = "urlapp_hotword";
    private static final String CHANNEL_CALL = "urlapp_hotword_call";
    static final int NOTE_ID = 0x7301, CALL_ID = 0x7302, REMIND_ID = 0x7303;
    private static final int RATE = 16000, FRAME = 320; // 20ms
    private static final int PRE_FRAMES = 25;          // 말 시작 전 0.5초도 같이 넣는다 (첫 소리가 잘리지 않게)
    private static final String ENGINE_CLASS = "app.jarvis.urlapp.VoskEngine";
    private static final Object ENGINE_LOCK = new Object(); // 모델(수백 MB)이 두 번 올라가지 않게
    private static final long RELEASE_AFTER_MS = 10 * 60_000L;  // 10분 넘게 쉬면 모델을 메모리에서 내린다

    // ---- 같은 앱 안의 신호 (앱 화면·대화 창·설정 화면이 쓴다) ----
    private static final Set<String> holds = Collections.synchronizedSet(new HashSet<String>());
    static volatile HotwordService running;
    static volatile Listener listener;   // 설정·시험 화면
    static volatile boolean testMode;     // 시험 화면이 보이는 동안: 대화 창을 띄우지 않고 들린 것만 알린다
    static volatile String state = "꺼짐";
    static volatile String lastError = "";

    interface Listener {
        void heard(String text, HotwordMatch.Hit hit);
    }

    // ---------- 설정 ----------

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("enabled", false);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("enabled", on).apply();
    }

    static int sensitivity(Context c) {
        return prefs(c).getInt("sensitivity", HotwordMatch.NORMAL);
    }

    static void setSensitivity(Context c, int s) {
        prefs(c).edit().putInt("sensitivity", s).apply();
        HotwordService r = running;
        if (r != null) {
            r.regrammar = true;
            r.poke();
        }
    }

    /** 화면이 꺼져 있을 때도 듣기 (기본 꺼짐 — 배터리). */
    static boolean screenOff(Context c) {
        return prefs(c).getBoolean("screen_off", false);
    }

    static void setScreenOff(Context c, boolean on) {
        prefs(c).edit().putBoolean("screen_off", on).apply();
        HotwordService r = running;
        if (r != null) r.updateScreenHold();
    }

    /** 이 앱에 호출어 기능이 들어 있나 (설정 + 엔진). */
    static boolean available(Context c) {
        if (!NotifyJob.config(c).has("hotword")) return false;
        try {
            Class.forName(ENGINE_CLASS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean canDrawOverlays(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    // ---------- 켜기·끄기·잠시 쉬기 ----------

    /** 켜져 있고 준비됐으면 듣기 시작. 앱이 앞에 보일 때만 부른다 (안드로이드 14+ 는 뒤에서 마이크 서비스를 못 켠다).
     *  null = 시작했거나 이미 듣는 중, 아니면 이유. */
    static String start(Context c) {
        if (!available(c)) return "이 앱에는 호출어 기능이 없어요.";
        if (!HotwordModel.ready(c)) return "음성 모델을 먼저 받아 주세요.";
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "마이크 권한이 필요해요.";
        }
        if (running != null) return null;
        try {
            Intent i = new Intent(c, HotwordService.class).setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
            cancelRemind(c);
            return null;
        } catch (Exception e) { // ForegroundServiceStartNotAllowedException 등
            lastError = "듣기를 시작하지 못했어요 (" + e.getClass().getSimpleName() + "). 앱을 열어 다시 켜 주세요.";
            return lastError;
        }
    }

    /** 앱 화면이 보일 때 (MainActivity.onResume): 켜 둔 호출어면 듣기를 켠다 (받기가 끝났으면 먼저 푼다). */
    static void appVisible(Context c) {
        if (!available(c) || !enabled(c) || running != null) return;
        if (HotwordModel.ready(c)) {
            start(c);
            return;
        }
        Context app = c.getApplicationContext();
        new Thread(() -> {
            if (HotwordModel.installIfDownloaded(app)) new Handler(Looper.getMainLooper()).post(() -> start(app));
        }, "hotword-install").start();
    }

    static void stop(Context c) {
        HotwordService r = running;
        if (r != null) r.shutdown(true);
        else c.stopService(new Intent(c, HotwordService.class));
    }

    /** 마이크를 잠시 놓는다/다시 듣는다 (이유별). */
    static void hold(String why, boolean on) {
        boolean changed = on ? holds.add(why) : holds.remove(why);
        HotwordService r = running;
        if (changed && r != null) r.poke();
    }

    static boolean held() {
        return !holds.isEmpty();
    }

    // ---------- 서비스 ----------

    private final Object lock = new Object();
    private Handler main;
    private Thread worker;
    private volatile boolean quit, userStopped, regrammar;
    private AudioRecord rec;          // 작업 스레드만 만진다
    private volatile int mySession = -1;
    private BroadcastReceiver screenRx;
    private Object recCb;             // AudioManager.AudioRecordingCallback (API 24+)
    private AudioManager am;
    private String shownState = "";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) { // 알림의 '끄기'
            setEnabled(this, false);
            shutdown(true);
            return START_NOT_STICKY;
        }
        try {
            Notification n = note("시작하는 중…");
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTE_ID, n);
        } catch (Exception e) { // 뒤에서 켜려 했거나 마이크 권한이 없음
            lastError = "듣기를 시작하지 못했어요 (" + e.getClass().getSimpleName() + "). 앱을 열어 다시 켜 주세요.";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running == null) {
            running = this;
            begin();
        }
        // 시스템이 서비스를 다시 살려도 뒤에서는 마이크를 못 켜므로 다시 시작하지 않는다 (대신 알림)
        return START_NOT_STICKY;
    }

    private void begin() {
        quit = false;
        lastError = "";
        screenRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                updateScreenHold();
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenRx, f);
        updateScreenHold();
        if (Build.VERSION.SDK_INT >= 29) watchSilencing();
        worker = new Thread(this::run, "hotword");
        worker.start();
    }

    void updateScreenHold() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        hold("screen", !screenOff(this) && pm != null && !pm.isInteractive());
    }

    /** 다른 앱(통화·녹음·다른 음성 비서)이 마이크를 가져가 우리 녹음이 '무음 처리'되면 잠시 놓았다가 다시 해 본다. */
    private void watchSilencing() {
        try {
            AudioManager.AudioRecordingCallback cb = new AudioManager.AudioRecordingCallback() {
                @Override
                public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                    for (AudioRecordingConfiguration c : configs) {
                        if (c.getClientAudioSessionId() == mySession && c.isClientSilenced()) {
                            hold("other", true);
                            main.postDelayed(() -> hold("other", false), 8000);
                        }
                    }
                }
            };
            am.registerAudioRecordingCallback(cb, main);
            recCb = cb;
        } catch (Throwable t) {
            recCb = null;
        }
    }

    private void poke() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void shutdown(boolean user) {
        userStopped = user;
        quit = true;
        poke();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        quit = true;
        poke();
        if (screenRx != null) {
            try {
                unregisterReceiver(screenRx);
            } catch (Exception e) {
                // 이미 풀림
            }
        }
        if (recCb != null && Build.VERSION.SDK_INT >= 24) {
            am.unregisterAudioRecordingCallback((AudioManager.AudioRecordingCallback) recCb);
        }
        if (running == this) running = null;
        holds.remove("screen");
        holds.remove("other");
        state = "꺼짐";
        if (!userStopped && enabled(this)) remind(this, true); // 시스템이 껐다: 다시 켜기 알림
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 최근 앱 목록에서 앱을 지워도 듣기는 계속한다 (전경 서비스)
        super.onTaskRemoved(rootIntent);
    }

    // ---------- 듣기 (작업 스레드) ----------

    private void run() {
        synchronized (ENGINE_LOCK) {
            List<String> fillers = HotwordMatch.parseFillers(asset("hotword-fillers.txt"));
            while (!quit) {
                HotwordEngine eng = null;
                try {
                    setState("모델 여는 중…");
                    HotwordMatch m = matcher();
                    eng = (HotwordEngine) Class.forName(ENGINE_CLASS).newInstance();
                    eng.open(HotwordModel.dir(this).getAbsolutePath(), m.grammar(fillers), RATE);
                    if (!quit) listen(eng, m, fillers); // 끝나면: 끄기, 또는 오래 쉬어서 모델을 내릴 때
                } catch (Throwable t) { // UnsatisfiedLinkError(지원하지 않는 폰), 모델 파일 손상 등
                    lastError = "호출어 엔진을 열지 못했어요: " + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : " " + t.getMessage());
                    main.post(() -> shutdown(false));
                    return;
                } finally {
                    closeRecord();
                    if (eng != null) {
                        try {
                            eng.close();
                        } catch (Throwable t) {
                            // 무시
                        }
                    }
                }
                // 오래 쉬는 동안(화면 꺼짐 등)은 모델(메모리 수백 MB)을 내려 둔다. 다시 들을 때 연다 (몇 초).
                while (!quit && held()) {
                    setState(holdText());
                    checkCall();
                    synchronized (lock) {
                        try {
                            if (!quit && held()) lock.wait(2000);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private HotwordMatch matcher() {
        return new HotwordMatch(HotwordMatch.phrases(HotwordModel.cfg(this)), sensitivity(this));
    }

    private void listen(HotwordEngine eng, HotwordMatch m, List<String> fillers) throws Exception {
        short[] buf = new short[FRAME];
        short[][] ring = new short[PRE_FRAMES][];
        int ringPos = 0, ringN = 0;
        double floor = -1;
        boolean open = false;
        long openAt = 0, lastLoud = 0, cooldownUntil = 0;
        int frames = 0, stable = 0;
        String stablePhrase = null;
        long heldSince = 0;
        while (!quit) {
            if (regrammar) {
                regrammar = false;
                m = matcher();
                eng.grammar(m.grammar(fillers));
                open = false;
            }
            if ((frames++ & 31) == 0) checkCall();
            if (held()) {
                closeRecord();
                if (open) {
                    eng.reset();
                    open = false;
                }
                long t = SystemClock.elapsedRealtime();
                if (heldSince == 0) heldSince = t;
                else if (t - heldSince > RELEASE_AFTER_MS) return; // 모델을 내린다 (run 이 다시 연다)
                setState(holdText());
                synchronized (lock) {
                    if (held() && !quit) lock.wait(1000);
                }
                frames = 0;
                continue;
            }
            heldSince = 0;
            if (rec == null) {
                if (!openRecord()) {
                    setState("마이크를 쓸 수 없어 잠시 뒤 다시 해 봐요");
                    synchronized (lock) {
                        if (!quit) lock.wait(3000);
                    }
                    continue;
                }
                setState("듣는 중");
                floor = -1;
                ringN = 0;
                open = false;
            }
            int n = rec.read(buf, 0, FRAME);
            if (n <= 0) {
                if (n < 0) {
                    closeRecord();
                    SystemClock.sleep(500);
                }
                continue;
            }
            double rms = rms(buf, n);
            long now = SystemClock.elapsedRealtime();
            if (floor < 0) floor = rms;
            if (!open) {
                // 주변 소음 크기: 내려갈 때는 빨리, 올라갈 때는 천천히 따라간다
                floor = rms < floor ? floor * 0.9 + rms * 0.1 : floor * 0.995 + rms * 0.005;
                if (rms > Math.max(floor * 3.0, 120) && now >= cooldownUntil) {
                    open = true;
                    openAt = now;
                    lastLoud = now;
                    stable = 0;
                    stablePhrase = null;
                    for (int k = 0; k < ringN; k++) {
                        short[] f = ring[(ringPos - ringN + k + PRE_FRAMES) % PRE_FRAMES];
                        eng.accept(f, f.length);
                    }
                    ringN = 0;
                } else {
                    short[] slot = ring[ringPos];
                    if (slot == null || slot.length != n) slot = ring[ringPos] = new short[n];
                    System.arraycopy(buf, 0, slot, 0, n);
                    ringPos = (ringPos + 1) % PRE_FRAMES;
                    ringN = Math.min(ringN + 1, PRE_FRAMES);
                    continue;
                }
            }
            if (rms > Math.max(floor * 2.0, 90)) lastLoud = now;
            String res = null;
            if (eng.accept(buf, n)) {
                res = eng.result();
            } else if (now - lastLoud > 700 || now - openAt > 6000) { // 조용해졌거나 너무 길면 끊어서 본다
                res = eng.finish();
                open = false;
            }
            HotwordMatch.Hit hit = null;
            if (res != null) {
                hit = m.matchResult(res);
                report(HotwordMatch.heard(res), hit);
                stable = 0;
                stablePhrase = null;
            } else if (frames % 5 == 0) { // 0.1초마다 중간 결과 (말을 이어 가도 호출어에서 바로 뜨게)
                String part = eng.partial();
                String ph = m.matchPartial(part);
                if (ph != null && ph.equals(stablePhrase)) {
                    stable++;
                } else {
                    stablePhrase = ph;
                    stable = ph == null ? 0 : 1;
                }
                if (stable >= 2) hit = new HotwordMatch.Hit(ph, -1, HotwordMatch.heard(part));
                if (testMode && listener != null) report(HotwordMatch.heard(part), hit);
                else if (hit != null) report(HotwordMatch.heard(part), hit);
            }
            if (hit != null) {
                eng.reset();
                open = false;
                stable = 0;
                stablePhrase = null;
                cooldownUntil = now + 2500;
                if (!testMode) called();
            }
        }
    }

    private boolean openRecord() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lastError = "마이크 권한이 꺼져 있어요.";
            return false;
        }
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE)); // 0.5초 버퍼
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return false;
            }
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                r.release();
                return false;
            }
            rec = r;
            mySession = r.getAudioSessionId();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void closeRecord() {
        AudioRecord r = rec;
        rec = null;
        mySession = -1;
        if (r == null) return;
        try {
            r.stop();
        } catch (Exception e) {
            // 무시
        }
        r.release();
    }

    private void checkCall() {
        int mode = am == null ? AudioManager.MODE_NORMAL : am.getMode();
        hold("call", mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == AudioManager.MODE_RINGTONE);
    }

    private static double rms(short[] b, int n) {
        double s = 0;
        for (int i = 0; i < n; i++) s += (double) b[i] * b[i];
        return Math.sqrt(s / Math.max(1, n));
    }

    private void report(String text, HotwordMatch.Hit hit) {
        Listener l = listener;
        if (l == null || (text.isEmpty() && hit == null)) return;
        main.post(() -> {
            Listener now = listener;
            if (now != null) now.heard(text, hit);
        });
    }

    // ---------- 호출 ----------

    /** 호출어가 들렸다: 마이크를 바로 놓고 대화 창을 띄운다 (못 띄우면 '눌러서 말하기' 알림). */
    private void called() {
        hold("launch", true);
        closeRecord();
        long at = SystemClock.elapsedRealtime();
        main.post(() -> {
            buzz();
            boolean shown = false;
            if (canDrawOverlays(this)) {
                try {
                    startActivity(voiceIntent(this));
                    shown = true;
                } catch (Exception e) {
                    shown = false;
                }
            }
            if (!shown) callNote();
            main.postDelayed(() -> {
                if (VoiceActivity.shownAt < at && canDrawOverlays(this)) callNote(); // 띄웠는데 안 떴으면 (기기 제한)
                hold("launch", false);
            }, 3000);
        });
    }

    static Intent voiceIntent(Context c) {
        return new Intent(c, VoiceActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(VoiceActivity.EXTRA_HOTWORD, true);
    }

    private void buzz() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(40);
        } catch (Exception e) {
            // 진동 없음
        }
    }

    private void callNote() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        channels(this);
        PendingIntent pi = PendingIntent.getActivity(this, 2, voiceIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = builder(this, CHANNEL_CALL)
                .setContentTitle("‘" + matcher().display() + "’ — 눌러서 말하기")
                .setContentText("대화 창을 바로 띄우려면 설정에서 '다른 앱 위에 표시'를 허용해 주세요.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_CALL)
                .setFullScreenIntent(pi, true);
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(20_000);
        nm.notify(CALL_ID, b.build());
    }

    // ---------- 알림 ----------

    private void setState(String s) {
        state = s;
        if (s.equals(shownState) || quit) return;
        shownState = s;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTE_ID, note(s));
    }

    private String holdText() {
        if (holds.contains("voice") || holds.contains("launch")) return "대화 중";
        if (holds.contains("app")) return "앱을 보는 중이라 잠시 쉬어요";
        if (holds.contains("call")) return "통화 중이라 잠시 쉬어요";
        if (holds.contains("other")) return "다른 앱이 마이크를 써서 잠시 쉬어요";
        if (holds.contains("screen")) return "화면이 꺼져 있어 쉬어요";
        return "잠시 쉬어요";
    }

    private Notification note(String text) {
        channels(this);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, HotwordActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, HotwordService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return builder(this, CHANNEL)
                .setContentTitle("‘" + matcher().display() + "’ " + ("듣는 중".equals(text) ? "듣는 중" : "호출"))
                .setContentText("듣는 중".equals(text) ? "소리는 폰 안에서만 들어요 · 눌러서 설정" : text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "끄기", stop).build())
                .build();
    }

    private static Notification.Builder builder(Context c, String channel) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
        int icon = c.getResources().getIdentifier("urlapp_hotword", "drawable", c.getPackageName());
        if (icon == 0) icon = c.getApplicationInfo().icon;
        b.setSmallIcon(icon);
        int accent = c.getResources().getIdentifier("widget_accent", "color", c.getPackageName());
        if (accent != 0) b.setColor(c.getColor(accent));
        return b;
    }

    private static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel q = new NotificationChannel(CHANNEL, "음성 호출어 (듣는 중 표시)", NotificationManager.IMPORTANCE_LOW);
        q.setShowBadge(false);
        q.setSound(null, null);
        nm.createNotificationChannel(q);
        NotificationChannel call = new NotificationChannel(CHANNEL_CALL, "음성 호출어 — 눌러서 말하기", NotificationManager.IMPORTANCE_HIGH);
        call.setSound(null, null);
        nm.createNotificationChannel(call);
    }

    /** 켜 둔 호출어가 꺼져 있을 때 (폰을 다시 켰거나·업데이트·시스템이 끔): 눌러서 다시 켜기.
     *  force=false(15분 확인 작업)는 6시간에 한 번만 (지운 알림을 자꾸 다시 띄우지 않게). */
    static void remind(Context c, boolean force) {
        if (!enabled(c) || running != null || !available(c)) return;
        long now = System.currentTimeMillis();
        if (!force && now - prefs(c).getLong("reminded_at", 0) < 6 * 3600_000L) return;
        prefs(c).edit().putLong("reminded_at", now).apply();
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        channels(c);
        PendingIntent pi = PendingIntent.getActivity(c, 3, new Intent(c, HotwordActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(HotwordActivity.EXTRA_START, true),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String phrase = new HotwordMatch(HotwordMatch.phrases(HotwordModel.cfg(c)), HotwordMatch.NORMAL).display();
        nm.notify(REMIND_ID, builder(c, CHANNEL)
                .setContentTitle("‘" + phrase + "’ 가 꺼져 있어요")
                .setContentText("눌러서 다시 켜기 (폰을 다시 켜면 한 번 눌러야 해요)")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }

    static void cancelRemind(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(REMIND_ID);
    }

    private String asset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int r;
            while ((r = in.read(b)) > 0) o.write(b, 0, r);
            return o.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** 폰을 다시 켰을 때·앱을 업데이트했을 때: 켜 둔 호출어면 '다시 켜기' 알림 (뒤에서는 마이크를 못 켜서). */
    public static class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i == null ? null : i.getAction();
            if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) remind(c, true);
        }
    }

    static List<String> holdsNow() {
        synchronized (holds) {
            return new ArrayList<>(holds);
        }
    }
}
