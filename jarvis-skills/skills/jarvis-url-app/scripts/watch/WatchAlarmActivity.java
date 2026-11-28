package app.jarvis.urlapp;

// 워치 알람 창 (꺼진 화면·잠금 위에 전체 화면): 지금 시각, 알람 이름, '끄기'·'5분 뒤' 둥근 단추.
// 폰에서 껐거나 5분이 지나 알람이 멈추면 스스로 닫힌다.

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class WatchAlarmActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView clock, label;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!WatchAlarm.ringing) {
                finish();
                return;
            }
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            label.setText(WatchAlarm.title);
            handler.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int side = Math.max(dp(16), (int) (Math.min(dm.widthPixels, dm.heightPixels) * 0.1)); // 둥근 화면 가장자리
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(side, dp(14), side, dp(14));
        setContentView(root);

        clock = text(root, 34, Color.WHITE, true);
        label = text(root, 15, 0xFFE8EAED, false);
        label.setMaxLines(2);
        label.setPadding(0, dp(2), 0, dp(10));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView snooze = button(row, WatchAlarm.SNOOZE_MIN + "분", 0xFF3C4043);
        TextView stop = button(row, "끄기", 0xFFE5484D);
        ((LinearLayout.LayoutParams) stop.getLayoutParams()).leftMargin = dp(12);
        snooze.setOnClickListener(v -> {
            WatchAlarm.act(this, true);
            finish();
        });
        stop.setOnClickListener(v -> {
            WatchAlarm.act(this, false);
            finish();
        });
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

    private TextView text(LinearLayout box, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private TextView button(LinearLayout row, String title, int color) {
        TextView v = new TextView(this);
        v.setText(title);
        v.setTextSize(15);
        v.setTextColor(Color.WHITE);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        v.setClickable(true);
        row.addView(v, new LinearLayout.LayoutParams(dp(60), dp(60)));
        return v;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
