package app.jarvis.urlapp;

// 워치의 대화 화면: 열리면 바로 듣고(워치의 음성 입력), 질문을 폰에 보내고(/urlapp/ask), 폰이 사이트에 물어 받은 답을
// 화면에 보여 주며 소리로 읽는다 (소리 끔 가능). 타일의 '말하기'·앱 아이콘으로 연다.
// 워치에 음성 입력이 없으면 글자 입력(워치 키보드의 음성 단추 포함)으로 대신한다.
// 답을 기다리는 동안은 화면을 켜 둔다 (120초 안에 답이 없으면 이유를 보인다). 화면을 닫은 뒤 온 답은 보관했다가 다음에 보인다.

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Locale;

public class WatchAskActivity extends Activity {
    private static final int REQ_SPEECH = 41;
    private static final long WAIT_MS = 120_000;
    private static final long KEEP_ANSWER_MS = 10 * 60_000L;
    private static WatchAskActivity current;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status, question, answer, mic, sound;
    private EditText typing;
    private ScrollView scroll;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String waitingId;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = this;
        askNotifyOnce(); // 폰 알람을 워치에서도 알림(끄기 / 5분 뒤)으로 보이려면 (워치 OS 4+)
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int side = Math.max(dp(18), (int) (Math.min(dm.widthPixels, dm.heightPixels) * 0.12)); // 둥근 화면 가장자리
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true); // 베젤(회전)로 넘겨 보기
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(side, dp(26), side, dp(34));
        scroll.addView(box);
        setContentView(scroll);

        status = text(box, "", 12, 0xFF9AA0A6, false);
        question = text(box, "", 14, 0xFFE8EAED, true);
        mic = new TextView(this);
        mic.setText("🎙");
        mic.setTextSize(24);
        mic.setGravity(Gravity.CENTER);
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        round.setColor(accent());
        mic.setBackground(round);
        mic.setContentDescription("말하기");
        mic.setOnClickListener(v -> listen());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(56));
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        box.addView(mic, lp);
        answer = text(box, "", 15, Color.WHITE, false);
        answer.setGravity(Gravity.START);
        sound = text(box, "", 12, 0xFF9AA0A6, false);
        sound.setPadding(0, dp(10), 0, 0);
        sound.setOnClickListener(v -> {
            prefs(this).edit().putBoolean("mute", !muted()).apply();
            if (muted() && tts != null) tts.stop();
            showSound();
        });
        showSound();

        tts = new TextToSpeech(this, s -> {
            if (s == TextToSpeech.SUCCESS && tts != null) {
                int r = tts.setLanguage(Locale.KOREAN);
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });

        if (state == null) {
            String last = prefs(this).getString("last", null);
            long lastAt = prefs(this).getLong("last_at", 0);
            String crash = CrashLog.take(this); // 지난번에 워치 앱(타일 포함)이 갑자기 꺼졌으면 그 이유를 한 번 보여 준다
            if (crash != null) {
                answer.setTextColor(0xFFFF8A80);
                answer.setTextSize(11);
                answer.setGravity(Gravity.START);
                answer.setText(crash);
                status.setText("지난번에 앱이 꺼진 이유예요 — 화면을 찍어 전해 주세요. 마이크를 누르면 물어요");
            } else if (last != null && System.currentTimeMillis() - lastAt < KEEP_ANSWER_MS) { // 화면을 닫은 뒤 온 답
                prefs(this).edit().remove("last").apply();
                showAnswer(last, true, false);
                status.setText("지난 질문의 답이에요");
            } else {
                listen();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        main.removeCallbacksAndMessages(null);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }

    // ---------- 듣기·묻기 ----------

    private void listen() {
        if (tts != null) tts.stop();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(getApplicationInfo().labelRes) + "에게 말해 보세요");
        try {
            startActivityForResult(i, REQ_SPEECH);
            status.setText("듣는 중…");
        } catch (ActivityNotFoundException | SecurityException e) {
            showTyping(); // 이 워치에는 음성 입력이 없다
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_SPEECH) return;
        ArrayList<String> heard = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        String q = heard == null || heard.isEmpty() ? "" : heard.get(0).trim();
        if (result != RESULT_OK || q.isEmpty()) {
            status.setText("마이크를 누르면 다시 들어요");
            return;
        }
        ask(q);
    }

    private void showTyping() {
        status.setText("음성 입력이 없어 글자로 물어요");
        if (typing != null) {
            typing.requestFocus();
            return;
        }
        typing = new EditText(this);
        typing.setHint("질문 쓰기");
        typing.setTextColor(Color.WHITE);
        typing.setHintTextColor(0xFF9AA0A6);
        typing.setSingleLine(true);
        typing.setInputType(InputType.TYPE_CLASS_TEXT);
        typing.setImeOptions(EditorInfo.IME_ACTION_SEND);
        typing.setOnEditorActionListener((v, action, ev) -> {
            String q = typing.getText().toString().trim();
            if (!q.isEmpty()) {
                typing.setText("");
                ask(q);
            }
            return true;
        });
        ((LinearLayout) question.getParent()).addView(typing, 2,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        typing.requestFocus();
    }

    private void ask(String q) {
        if (q.length() > 500) q = q.substring(0, 500);
        String id = newId();
        waitingId = id;
        question.setText(q);
        answer.setText("");
        status.setText("폰에 보내는 중…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String json;
        try {
            json = new JSONObject().put("id", id).put("q", q).toString();
        } catch (Exception e) {
            return;
        }
        WatchPhone.send(this, "/urlapp/ask", json, err -> main.post(() -> {
            if (!id.equals(waitingId)) return;
            if (err != null) fail(err);
            else status.setText("폰에 보냈어요 — 답을 기다리는 중…");
        }));
        main.postDelayed(() -> {
            if (id.equals(waitingId)) fail("120초 안에 답이 오지 않았어요. 폰의 인터넷과 자비스 앱을 확인해 주세요.");
        }, WAIT_MS);
    }

    private void fail(String why) {
        waitingId = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.setText("다시 물으려면 마이크를 누르세요");
        showAnswer(why, false, false);
    }

    // ---------- 폰에서 온 것 ----------

    /** WatchListener 가 부른다 (아무 스레드). 화면이 없으면 답을 보관해 다음에 보인다. */
    static void fromPhone(Context ctx, String path, String json) {
        WatchAskActivity a = current;
        if (a != null) {
            a.runOnUiThread(() -> a.onPhone(path, json));
            return;
        }
        if ("/urlapp/answer".equals(path)) {
            prefs(ctx).edit().putString("last", json).putLong("last_at", System.currentTimeMillis()).apply();
        }
    }

    private void onPhone(String path, String json) {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (Exception e) {
            return;
        }
        String id = o.optString("id", "");
        if (waitingId == null || !waitingId.equals(id)) return; // 지난 질문의 것
        if ("/urlapp/status".equals(path)) {
            status.setText(WatchStore.clean(o.optString("text", ""), 60));
            return;
        }
        showAnswer(json, true, true);
    }

    /** 답(JSON 또는 이유 글)을 보이고, 소리가 켜져 있으면 읽는다. */
    private void showAnswer(String jsonOrText, boolean isJson, boolean speak) {
        String text = jsonOrText;
        boolean ok = true;
        if (isJson) {
            try {
                JSONObject o = new JSONObject(jsonOrText);
                text = o.optString("text", "");
                ok = o.optBoolean("ok", false);
            } catch (Exception e) {
                text = "";
                ok = false;
            }
        }
        waitingId = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (ok) status.setText("마이크를 누르면 이어서 물어요");
        answer.setTextColor(ok ? Color.WHITE : 0xFFFF8A80);
        answer.setText(text.length() > 4000 ? text.substring(0, 4000) + "…" : text);
        scroll.post(() -> {
            scroll.smoothScrollTo(0, Math.max(0, mic.getTop() - dp(8)));
            scroll.requestFocus();
        });
        if (speak && ok && ttsReady && !muted() && tts != null) {
            tts.speak(spoken(text), TextToSpeech.QUEUE_FLUSH, null, "answer");
        }
    }

    /** 읽어 줄 글: 링크·표 기호를 빼고 길면 앞부분만. */
    static String spoken(String text) {
        String t = text == null ? "" : text.replaceAll("https?://\\S+", " ").replaceAll("[#*_`>]+", "")
                .replace('|', ' ').replaceAll("\\s+", " ").trim();
        return t.length() > 1500 ? t.substring(0, 1500) : t;
    }

    // ---------- 도우미 ----------

    private void showSound() {
        sound.setText(muted() ? "🔇 소리 끔 (누르면 켬)" : "🔊 답을 소리로 읽어요 (누르면 끔)");
    }

    private boolean muted() {
        return prefs(this).getBoolean("mute", false);
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(WatchStore.PREFS, Context.MODE_PRIVATE);
    }

    private TextView text(LinearLayout box, String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(2), 0, dp(2));
        box.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private int accent() {
        int id = getResources().getIdentifier("watch_accent", "color", getPackageName());
        return id == 0 ? 0xFF2DD4DA : getResources().getColor(id, null);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String newId() {
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    /** 알림 권한 (워치 OS 4 = 안드로이드 13+): 매니페스트에 있을 때만, 처음 한 번 묻는다. 거절해도 진동은 울린다. */
    private void askNotifyOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        String perm = "android.permission.POST_NOTIFICATIONS";
        if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences p = getSharedPreferences("urlapp_watch_perm", MODE_PRIVATE);
        if (p.getBoolean("notify_asked", false)) return;
        try {
            String[] declared = getPackageManager().getPackageInfo(getPackageName(),
                    android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (declared == null || !java.util.Arrays.asList(declared).contains(perm)) return;
        } catch (Exception e) {
            return;
        }
        p.edit().putBoolean("notify_asked", true).apply();
        requestPermissions(new String[] {perm}, 7415);
    }
}
