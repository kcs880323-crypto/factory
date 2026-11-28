package app.jarvis.urlapp;

// 알람이 울릴 때 잠금 화면·꺼진 화면 위에 뜨는 전체 화면 창 (시계 앱처럼): 지금 시각, 알람 이름, '끄기'·'5분 뒤' 큰 단추.
// 알림의 전체 화면 인텐트로 열린다 (폰을 쓰는 중이면 안드로이드가 창 대신 위쪽 알림을 보여 준다).
// 단추는 AlarmReceiver 로 보낸다. 알람이 다른 곳(알림 단추·5분 시간 끝)에서 멈추면 창도 스스로 닫힌다.

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AlarmActivity extends Activity {
    private static final int BG = Color.rgb(11, 18, 24), INK = Color.WHITE, SUB = Color.rgb(148, 163, 184);
    private static final int RED = Color.rgb(229, 72, 77), GRAY = Color.rgb(51, 65, 85);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView clock, label;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!AlarmService.ringing) { // 다른 곳에서 껐거나 5분이 지남
                finish();
                return;
            }
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            label.setText(AlarmService.ringTitle);
            handler.postDelayed(this, 700);
        }
    };

    @Override
    @SuppressWarnings("deprecation") // 안드로이드 8.1 미만의 잠금 화면 위 표시 플래그
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(28), dp(96), dp(28), dp(48));
        setContentView(root);

        TextView kind = text(root, 18, SUB, false);
        kind.setText("알람");
        clock = text(root, 76, INK, true);
        clock.setPadding(0, dp(8), 0, dp(8));
        label = text(root, 24, INK, false);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(3);

        LinearLayout space = new LinearLayout(this);
        root.addView(space, new LinearLayout.LayoutParams(1, 0, 1f));

        Button snooze = button(root, AlarmService.SNOOZE_MIN + "분 뒤 다시", GRAY);
        snooze.setOnClickListener(v -> send(AlarmReceiver.ACTION_SNOOZE));
        Button stop = button(root, "끄기", RED);
        ((LinearLayout.LayoutParams) stop.getLayoutParams()).topMargin = dp(14);
        stop.setOnClickListener(v -> send(AlarmReceiver.ACTION_DISMISS));
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void send(String action) {
        sendBroadcast(new Intent(this, AlarmReceiver.class).setAction(action));
        finish();
    }

    private TextView text(LinearLayout box, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private Button button(LinearLayout box, String title, int color) {
        Button v = new Button(this);
        v.setAllCaps(false);
        v.setText(title);
        v.setTextSize(22);
        v.setTextColor(INK);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(36));
        v.setBackground(g);
        box.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
        return v;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
