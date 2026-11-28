package app.jarvis.urlapp;

// '하이 자비스' 호출어 설정·시험 화면 (--hotword 앱, 코드로 그린다).
// 켜기/끄기, 필요한 것(음성 모델 받기·마이크·알림·다른 앱 위에 표시·배터리), 민감도, 화면 꺼짐 때도 듣기,
// 그리고 '시험': 이 화면이 보이는 동안 들린 말과 감지 여부를 바로 보여 준다 (대화 창은 띄우지 않음).
// 앱 아이콘 길게 누르기 → '하이 자비스', 또는 '듣는 중' 알림을 누르면 열린다.

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class HotwordActivity extends Activity {
    static final String EXTRA_START = "start";
    private static final int REQ_MIC = 61, REQ_NOTIFY = 62;
    private static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout box;
    private TextView statusView, modelView, heardView, hitView;
    private Button modelButton;
    private String shape = "";       // 화면 구조가 바뀌었는지 (권한·모델·켜짐)
    private boolean visible, installStarted;
    private String phrase = "하이 자비스";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!HotwordService.available(this)) {
            finish();
            return;
        }
        phrase = new HotwordMatch(HotwordMatch.phrases(HotwordModel.cfg(this)), HotwordMatch.NORMAL).display();
        setTitle("‘" + phrase + "’ 호출");
        ScrollView scroll = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(32));
        scroll.addView(box);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets in = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                v.setPadding(in.left, in.top, in.right, in.bottom);
                return insets;
            });
        }
        if (getIntent().getBooleanExtra(EXTRA_START, false) && HotwordService.enabled(this)) {
            // '다시 켜기' 알림을 눌렀다: 바로 켜고 닫는다 (안 되면 이유를 보이며 화면에 남는다)
            String err = HotwordService.start(this);
            if (err == null) {
                Toast.makeText(this, "‘" + phrase + "’ 다시 듣기 시작했어요", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        HotwordService.listener = (text, hit) -> showHeard(text, hit);
        HotwordService.testMode = true;
        render();
        tick();
    }

    @Override
    protected void onPause() {
        visible = false;
        HotwordService.testMode = false;
        HotwordService.listener = null;
        main.removeCallbacksAndMessages(null);
        super.onPause();
    }

    // ---------- 화면 ----------

    private String shapeKey() {
        return HotwordModel.ready(this) + "|" + has(Manifest.permission.RECORD_AUDIO) + "|" + notifyOk() + "|"
                + HotwordService.canDrawOverlays(this) + "|" + batteryFree() + "|" + HotwordService.enabled(this)
                + "|" + (HotwordService.running != null);
    }

    private void render() {
        shape = shapeKey();
        box.removeAllViews();
        title("‘" + phrase + "’ 호출");
        para("빅스비처럼 ‘" + phrase + "’ 라고 부르면 대화 창이 떠요. 소리는 폰 안에서만 듣고(오프라인 음성 모델) "
                + "어디에도 보내거나 저장하지 않아요.");

        Switch on = new Switch(this);
        on.setText("켜기");
        on.setTextSize(17);
        on.setTypeface(Typeface.DEFAULT_BOLD);
        on.setChecked(HotwordService.enabled(this));
        on.setPadding(0, dp(8), 0, dp(4));
        on.setOnCheckedChangeListener((b, checked) -> toggle(checked));
        box.addView(on);
        statusView = para("");
        statusView.setTextSize(14);

        section("필요한 것");
        boolean ready = HotwordModel.ready(this);
        LinearLayout r = rowView("음성 모델 (한국어, 받기 약 " + mb(HotwordModel.sizeBytes(this)) + "MB · 풀면 약 260MB)");
        modelView = para("");
        modelView.setTextSize(13);
        modelButton = new Button(this);
        modelButton.setAllCaps(false);
        r.addView(modelButton);
        modelButton.setOnClickListener(v -> modelAction());
        check("마이크 권한", has(Manifest.permission.RECORD_AUDIO), "허용",
                v -> requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQ_MIC));
        if (Build.VERSION.SDK_INT >= 33) {
            check("알림 (듣는 중 표시·다시 켜기)", notifyOk(), "허용",
                    v -> requestPermissions(new String[] {POST_NOTIFICATIONS}, REQ_NOTIFY));
        }
        check("다른 앱 위에 표시 (부르면 바로 창 띄우기)", HotwordService.canDrawOverlays(this), "설정 열기", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                appSettings();
            }
        });
        if (!HotwordService.canDrawOverlays(this)) {
            note("허용하지 않으면 부를 때 창 대신 '눌러서 말하기' 알림이 떠요.");
        }
        check("배터리 사용량 '제한 없음' (권장)", batteryFree(), "설정 열기", v -> appSettings());
        if (!batteryFree()) note("삼성 폰은 배터리 관리가 강해서, 빼 두지 않으면 듣기가 꺼질 수 있어요. 앱 정보 → 배터리 → '제한 없음'.");

        section("민감도");
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.HORIZONTAL);
        int cur = HotwordService.sensitivity(this);
        for (int s = HotwordMatch.LOW; s <= HotwordMatch.HIGH; s++) {
            RadioButton b = new RadioButton(this);
            b.setId(100 + s);
            b.setText(HotwordMatch.sensitivityName(s));
            b.setChecked(s == cur);
            g.addView(b);
        }
        g.setOnCheckedChangeListener((grp, id) -> HotwordService.setSensitivity(this, id - 100));
        box.addView(g);
        note("잘 못 알아들으면 '높게', 부르지 않았는데 뜨면 '낮게'.");

        Switch off = new Switch(this);
        off.setText("화면이 꺼져 있을 때도 듣기");
        off.setChecked(HotwordService.screenOff(this));
        off.setPadding(0, dp(12), 0, dp(4));
        off.setOnCheckedChangeListener((b, c) -> HotwordService.setScreenOff(this, c));
        box.addView(off);
        note("배터리를 더 써요 (기본은 화면이 켜져 있을 때만). 잠긴 폰에서는 잠금을 풀어야 대화가 시작돼요.");

        section("시험");
        para("켜 둔 채로 이 화면에서 ‘" + phrase + "’ 라고 말해 보세요. 들린 말이 아래에 보여요 "
                + "(이 화면에서는 대화 창을 띄우지 않아요).");
        heardView = para("들린 말: —");
        heardView.setTextSize(16);
        hitView = para("");
        hitView.setTextSize(20);
        hitView.setTypeface(Typeface.DEFAULT_BOLD);
        hitView.setTextColor(Color.rgb(22, 163, 74));

        section("알아 둘 것");
        note("• 앱 화면을 보는 동안에는 쉬어요 (앱 안에서는 사이트가 스스로 들어요). 대화 창이 떠 있을 때·통화 중·"
                + "다른 앱이 마이크를 쓸 때도 잠시 쉬어요.");
        note("• 듣는 동안 알림 줄에 '듣는 중' 알림이 늘 보여요 (안드로이드 규칙). 그 알림의 '끄기'로 바로 끌 수 있어요.");
        note("• 폰을 다시 켜거나 앱을 업데이트하면 안드로이드가 뒤에서 마이크를 못 켜게 해서, '다시 켜기' 알림을 한 번 눌러 주세요.");
        note("• 음성 모델은 alphacephei.com 의 Vosk 한국어 모델(Apache-2.0)이에요. 지우면 공간이 돌아와요.");
        update();
    }

    /** 0.7초마다: 상태·모델 진행 글만 고치고, 구조가 바뀌면 다시 그린다. */
    private void tick() {
        if (!visible) return;
        if (!shape.equals(shapeKey())) render();
        else update();
        maybeInstall();
        main.postDelayed(this::tick, 700);
    }

    private void update() {
        HotwordService run = HotwordService.running;
        String st;
        if (run != null) st = "상태: " + HotwordService.state;
        else st = HotwordService.enabled(this) ? "상태: 꺼져 있어요 — 위 '켜기'를 껐다 켜 주세요" : "상태: 꺼짐";
        String err = HotwordService.lastError;
        if (err != null && !err.isEmpty() && run == null) st += "\n" + err;
        statusView.setText(st);
        statusView.setTextColor(run != null ? Color.rgb(22, 163, 74) : Color.GRAY);

        String inst = HotwordModel.installing();
        int[] s = HotwordModel.status(this);
        if (HotwordModel.ready(this)) {
            modelView.setText("✅ 준비됨 (" + HotwordModel.usedMb(this) + "MB)");
            modelButton.setText("지우기");
        } else if (inst != null) {
            modelView.setText(inst);
            modelButton.setText("기다려 주세요");
        } else if (HotwordModel.downloading(this) && s != null) {
            int pct = s[2] > 0 ? (int) (100L * s[1] / s[2]) : 0;
            modelView.setText(String.format(Locale.ROOT, "받는 중… %d%% (%dMB)", pct, s[1] / 1024));
            modelButton.setText("취소");
        } else {
            String e = HotwordModel.error();
            modelView.setText(e.isEmpty() ? "아직 없어요" : "⚠ " + e);
            modelButton.setText("받기");
        }
    }

    private void maybeInstall() {
        if (installStarted || HotwordModel.ready(this) || HotwordModel.installing() != null) return;
        int[] s = HotwordModel.status(this);
        if (s == null || (s[0] != android.app.DownloadManager.STATUS_SUCCESSFUL && s[0] != android.app.DownloadManager.STATUS_FAILED)) {
            return;
        }
        installStarted = true;
        new Thread(() -> {
            boolean ok = HotwordModel.installIfDownloaded(getApplicationContext());
            main.post(() -> {
                installStarted = false;
                if (ok && HotwordService.enabled(this)) start();
                if (visible) render();
            });
        }, "hotword-install").start();
    }

    private void modelAction() {
        if (HotwordModel.ready(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("음성 모델 지우기")
                    .setMessage("지우면 ‘" + phrase + "’ 를 쓸 수 없어요 (다시 받을 수 있어요).")
                    .setPositiveButton("지우기", (d, w) -> {
                        HotwordService.setEnabled(this, false);
                        HotwordService.stop(this);
                        new Thread(() -> {
                            HotwordModel.delete(getApplicationContext());
                            main.post(this::render);
                        }).start();
                    })
                    .setNegativeButton("취소", null)
                    .show();
            return;
        }
        if (HotwordModel.installing() != null) return;
        if (HotwordModel.downloading(this)) {
            HotwordModel.cancelDownload(this);
            render();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("음성 모델 받기")
                .setMessage("약 " + mb(HotwordModel.sizeBytes(this)) + "MB 를 받아요 (와이파이 권장). 푼 뒤 약 260MB 를 차지해요.\n\n"
                        + "받는 곳: alphacephei.com (Vosk 한국어 모델). 받은 파일이 맞는지 확인한 뒤에만 풀어요.")
                .setPositiveButton("받기", (d, w) -> {
                    String err = HotwordModel.startDownload(this);
                    if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                    update();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void toggle(boolean on) {
        HotwordService.setEnabled(this, on);
        if (!on) {
            HotwordService.stop(this);
            main.postDelayed(this::render, 300);
            return;
        }
        if (!HotwordModel.ready(this)) {
            Toast.makeText(this, "먼저 음성 모델을 받아 주세요 (아래 '받기'). 다 받으면 바로 켜져요.", Toast.LENGTH_LONG).show();
            if (!HotwordModel.downloading(this)) modelAction();
            return;
        }
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !notifyOk()) {
            requestPermissions(new String[] {POST_NOTIFICATIONS}, REQ_NOTIFY); // 결과와 상관없이 켠다
            return;
        }
        start();
    }

    private void start() {
        String err = HotwordService.start(this);
        if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
        main.postDelayed(() -> {
            if (visible) render();
        }, 400);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (code == REQ_MIC && !granted) {
            Toast.makeText(this, "마이크 권한이 없으면 들을 수 없어요.", Toast.LENGTH_LONG).show();
        } else if (HotwordService.enabled(this) && HotwordModel.ready(this) && has(Manifest.permission.RECORD_AUDIO)) {
            if (code == REQ_MIC && Build.VERSION.SDK_INT >= 33 && !notifyOk()) {
                requestPermissions(new String[] {POST_NOTIFICATIONS}, REQ_NOTIFY);
                return;
            }
            start();
        }
        render();
    }

    private void showHeard(String text, HotwordMatch.Hit hit) {
        if (!visible || heardView == null) return;
        if (text != null && !text.isEmpty()) heardView.setText("들린 말: " + text);
        if (hit != null) {
            hitView.setText("✓ 감지됨 — ‘" + hit.phrase + "’"
                    + (hit.conf >= 0 ? String.format(Locale.ROOT, " (확신 %.0f%%)", hit.conf * 100) : ""));
            main.removeCallbacks(clearHit);
            main.postDelayed(clearHit, 4000);
        }
    }

    private final Runnable clearHit = () -> {
        if (hitView != null) hitView.setText("");
    };

    // ---------- 도우미 ----------

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean notifyOk() {
        return Build.VERSION.SDK_INT < 33 || has(POST_NOTIFICATIONS);
    }

    private boolean batteryFree() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void appSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private static long mb(long bytes) {
        return Math.max(1, Math.round(bytes / 1048576.0));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void title(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(22);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, 0, 0, dp(8));
        box.addView(v);
    }

    private void section(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(17);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(18), 0, dp(4));
        box.addView(v);
    }

    private TextView para(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(15);
        v.setPadding(0, dp(4), 0, dp(4));
        box.addView(v);
        return v;
    }

    private void note(String t) {
        TextView v = para(t);
        v.setTextSize(13);
        v.setAlpha(0.75f);
    }

    private LinearLayout rowView(String label) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(4), 0, dp(4));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(r);
        return r;
    }

    private void check(String label, boolean ok, String action, View.OnClickListener click) {
        LinearLayout r = rowView((ok ? "✅ " : "⚠ ") + label);
        if (!ok) {
            Button b = new Button(this);
            b.setText(action);
            b.setAllCaps(false);
            b.setOnClickListener(click);
            r.addView(b);
        }
    }
}
