package app.jarvis.urlapp;

// 앱이 직접 하는 음성 대화 (--mic 로 만든 앱, 사이트가 window.urlappAsk(질문) → 답 을 줄 때).
// 듣기(폰의 음성 인식) → 사이트에 묻기(urlappAsk) → 답 읽어 주기(폰의 TTS) → 다시 듣기 를 닫을 때까지 되풀이한다.
// 화면은 하이 빅스비처럼 떠 있는 둥근 카드: 빛나는 구슬(듣는 동안 내 목소리 크기에 맞춰 커졌다 작아짐, 생각 중엔 돌고,
// 말하는 중엔 숨 쉬듯), 지금 상태, 내가 한 말(큰 글씨), 답, 가운데 큰 마이크 단추, 오른쪽 위 닫기(✕).
// 마이크 단추: 듣는 중이면 말 끝내기, 말하는 중이면 멈추고 듣기, 쉬는 중이면 다시 듣기.
// (2026-11-20 고침: 예전엔 start() 가 상태를 '꺼짐'으로 둔 채 듣기를 불러 아무것도 듣지 않았고 '다시 듣기·끝내기'도 무시됐다.)

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

final class VoiceChat {
    interface Host {
        /** 사이트에 묻는다. 답은 answer(id, text, ok) 로 돌려준다. */
        void ask(String id, String question);

        void keepAwake(boolean on);

        /** 대화를 끝냈다 (닫기). 위젯의 대화 창은 여기서 닫는다. */
        default void ended() {
        }

        /** 두 번 말이 없어 쉬었다. 위젯의 대화 창은 잠깐 뒤 닫는다. */
        default void quiet() {
        }
    }

    static final int IDLE = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3, PAUSED = 4;
    private static final long ANSWER_TIMEOUT_MS = 90_000;

    private final Activity a;
    private final Voice voice;
    private final Host host;
    private final FrameLayout parent;
    private final int accent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean ko = "ko".equals(Locale.getDefault().getLanguage());

    private LinearLayout panel;
    private Orb orb;
    private TextView status, heard, reply;
    private ScrollView replyBox;
    private int state = IDLE;
    private int quiet;
    private int seq;
    private String waiting; // 답을 기다리는 질문 id

    VoiceChat(Activity a, Voice voice, Host host, FrameLayout parent, int accent) {
        this.a = a;
        this.voice = voice;
        this.host = host;
        this.parent = parent;
        this.accent = accent;
    }

    boolean active() {
        return state != IDLE;
    }

    void start() {
        if (state == IDLE) {
            show();
            host.keepAwake(true);
            state = PAUSED; // 켜짐 — 이제 듣기가 시작된다 (IDLE 로 두면 listen() 이 아무것도 안 한다)
            voice.setLevelListener(rms -> {
                if (orb != null && state == LISTENING) orb.level(rms);
            });
        }
        quiet = 0;
        listen();
    }

    void stop() {
        if (state == IDLE) return;
        state = IDLE;
        waiting = null;
        main.removeCallbacksAndMessages(null);
        voice.setLevelListener(null);
        voice.cancelListening(false);
        voice.stopSpeaking();
        host.keepAwake(false);
        if (panel != null) {
            if (orb != null) orb.stop();
            parent.removeView(panel);
            panel = null;
        }
        host.ended();
    }

    /** 사이트에 물을 수 없을 때 (연결 안 됨·로그인 전 등): 이유를 보이고 쉰다. */
    void fail(String why) {
        if (state == IDLE) return;
        waiting = null;
        pause(why);
    }

    /** 사이트의 답 (통로에서 받아 메인 스레드로 넘긴다). ok=false 면 사이트에서 오류가 났다. */
    void answer(String id, String text, boolean ok) {
        if (state != THINKING || id == null || !id.equals(waiting)) return;
        waiting = null;
        if (!ok) {
            pause(t("자비스가 답하지 못했어요. 마이크를 눌러 다시 말해 주세요.", "Couldn't get an answer. Tap the mic."));
            return;
        }
        String say = speakable(text);
        if (say.isEmpty()) { // 사이트가 화면에만 답했거나 할 말이 없음 → 다시 듣기
            listen();
            return;
        }
        reply.setText(text.length() > 600 ? text.substring(0, 600) + "…" : text);
        replyBox.setVisibility(View.VISIBLE);
        replyBox.post(() -> replyBox.scrollTo(0, 0));
        state = SPEAKING;
        mode(Orb.SPEAK);
        setStatus(t("말하는 중 · 마이크를 누르면 멈추고 들어요", "Speaking · tap the mic to interrupt"));
        voice.speak("chat" + (++seq), say, "", 1f, 1f, ev -> {
            String type = ev.optString("type");
            if (state != SPEAKING || "tts-start".equals(type)) return;
            if ("tts-error".equals(type) && "synthesis-unavailable".equals(ev.optString("error"))) {
                pause(t("이 폰에서 읽어 주기(TTS)를 쓸 수 없어요. 답은 위에 적혀 있어요.", "Text-to-speech isn't available."));
                return;
            }
            if ("interrupted".equals(ev.optString("error")) || "canceled".equals(ev.optString("error"))) return;
            listen();
        });
    }

    private void listen() {
        if (state == IDLE) return;
        voice.stopSpeaking();
        state = LISTENING;
        mode(Orb.LISTEN);
        setStatus(t("듣고 있어요", "Listening"));
        voice.listen("", true, ev -> {
            if (state != LISTENING) return;
            String type = ev.optString("type");
            if ("result".equals(type)) {
                String text = first(ev.optJSONArray("alts"));
                if (!ev.optBoolean("final")) {
                    if (!text.isEmpty()) setHeard(text, true);
                    return;
                }
                if (text.trim().isEmpty()) return;
                setHeard(text, false);
                quiet = 0;
                ask(text);
            } else if ("error".equals(type)) {
                String e = ev.optString("error");
                if ("no-speech".equals(e)) {
                    if (++quiet >= 2) {
                        pause(t("말씀이 없어서 쉬고 있어요. 마이크를 누르면 다시 들어요.", "Paused (no speech). Tap the mic."));
                        host.quiet();
                    } else {
                        main.postDelayed(() -> { if (state == LISTENING) listen(); }, 300);
                    }
                } else if (!"aborted".equals(e)) {
                    pause(errorText(e, ev.optString("message")));
                }
            }
        });
    }

    private void ask(String question) {
        state = THINKING;
        mode(Orb.THINK);
        setStatus(t("생각 중…", "Thinking…"));
        replyBox.setVisibility(View.GONE);
        final String id = "q" + (++seq);
        waiting = id;
        host.ask(id, question);
        main.postDelayed(() -> {
            if (state == THINKING && id.equals(waiting)) {
                waiting = null;
                pause(t("답이 오지 않았어요. 마이크를 눌러 다시 말해 주세요.", "No answer. Tap the mic."));
            }
        }, ANSWER_TIMEOUT_MS);
    }

    private void pause(String why) {
        if (state == IDLE) return;
        state = PAUSED;
        mode(Orb.IDLE);
        voice.cancelListening(false);
        setStatus(why);
    }

    /** 가운데 마이크 단추. */
    private void micTapped() {
        if (state == LISTENING) {
            voice.stopListening(); // 말 끝 — 들은 것으로 바로 묻는다
        } else {
            quiet = 0;
            waiting = null;
            listen();
        }
    }

    private String errorText(String e, String message) {
        if ("not-allowed".equals(e)) return t("마이크 권한이 없어요. 설정 → 앱 → 이 앱 → 권한에서 마이크를 켜 주세요.",
                "Microphone permission is off.");
        if ("network".equals(e)) return t("음성 인식이 인터넷에 닿지 않아요. 마이크를 눌러 다시 해 주세요.", "Speech recognition needs internet.");
        if ("audio-capture".equals(e)) return t("마이크를 쓸 수 없어요 (다른 앱이 쓰는 중일 수 있어요).", "Microphone is busy.");
        if ("service-not-allowed".equals(e)) return message.isEmpty()
                ? t("이 폰에서 음성 인식을 쓸 수 없어요.", "Speech recognition isn't available.") : message;
        if ("language-not-supported".equals(e)) return t("이 언어의 음성 인식이 없어요.", "Language not supported.");
        return t("음성 인식이 멈췄어요. 마이크를 눌러 다시 해 주세요.", "Speech recognition stopped.");
    }

    private void setStatus(String s) {
        if (status != null) status.setText(s);
    }

    private void setHeard(String s, boolean partial) {
        if (heard == null) return;
        heard.setText(s);
        heard.setTextColor(partial ? Color.argb(200, 255, 255, 255) : Color.WHITE);
    }

    private void mode(int m) {
        if (orb != null) orb.mode(m);
    }

    // ---------- 화면 ----------

    private void show() {
        if (panel != null) return;
        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(20), dp(12), dp(20), dp(20));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {Color.argb(246, 24, 34, 46), Color.argb(250, 10, 16, 24)});
        bg.setCornerRadius(dp(32));
        bg.setStroke(dp(1), withAlpha(accent, 90));
        panel.setBackground(bg);
        panel.setElevation(dp(16));
        panel.setClickable(true); // 아래로 눌림이 새지 않게

        // 위: 이름(왼쪽)·닫기(오른쪽)
        FrameLayout top = new FrameLayout(a);
        TextView name = text(12, accent, true);
        name.setText(appName());
        name.setLetterSpacing(0.12f);
        top.addView(name, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));
        TextView close = text(20, Color.rgb(148, 163, 184), false);
        close.setText("✕");
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(t("닫기", "Close"));
        close.setOnClickListener(v -> stop());
        top.addView(close, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END | Gravity.CENTER_VERTICAL));
        panel.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        orb = new Orb(a, accent);
        panel.addView(orb, new LinearLayout.LayoutParams(dp(112), dp(112)));

        status = text(13, Color.rgb(148, 163, 184), false);
        status.setGravity(Gravity.CENTER);
        panel.addView(status, wide(dp(6)));

        heard = text(22, Color.WHITE, false);
        heard.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heard.setGravity(Gravity.CENTER);
        heard.setMaxLines(3);
        heard.setEllipsize(TextUtils.TruncateAt.START);
        heard.setHint(t("자비스에게 말해 보세요", "Say something to Jarvis"));
        heard.setHintTextColor(Color.rgb(100, 116, 139));
        panel.addView(heard, wide(dp(10)));

        replyBox = new ScrollView(a);
        replyBox.setVerticalScrollBarEnabled(false);
        reply = text(16, mix(accent, Color.WHITE, 0.45f), false);
        reply.setGravity(Gravity.CENTER);
        reply.setLineSpacing(dp(3), 1f);
        replyBox.addView(reply);
        replyBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams rp = wide(dp(12));
        rp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        panel.addView(replyBox, rp);
        // 답이 길면 카드가 화면을 덮지 않게 답 칸 높이를 200dp 로 묶는다 (넘치면 넘겨 본다)
        reply.addOnLayoutChangeListener((v, l, t2, r, b, ol, ot, or, ob) -> {
            ViewGroup.LayoutParams lp = replyBox.getLayoutParams();
            int want = Math.min(b - t2, dp(200));
            if (lp.height != want && want > 0) {
                lp.height = want;
                replyBox.setLayoutParams(lp);
            }
        });

        // 가운데 큰 마이크 단추
        FrameLayout mic = new FrameLayout(a);
        GradientDrawable mbg = new GradientDrawable();
        mbg.setShape(GradientDrawable.OVAL);
        mbg.setColor(accent);
        mic.setBackground(mbg);
        mic.setElevation(dp(6));
        mic.setContentDescription(t("마이크", "Microphone"));
        int icon = a.getResources().getIdentifier("urlapp_widget_mic", "drawable", a.getPackageName());
        if (icon != 0) {
            ImageView iv = new ImageView(a);
            iv.setImageResource(icon);
            iv.setColorFilter(Color.rgb(2, 6, 23));
            mic.addView(iv, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        } else {
            TextView dot = text(24, Color.rgb(2, 6, 23), true);
            dot.setText("●");
            mic.addView(dot, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }
        mic.setOnClickListener(v -> micTapped());
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(64), dp(64));
        mp.topMargin = dp(16);
        panel.addView(mic, mp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins(dp(12), 0, dp(12), dp(12));
        parent.addView(panel, lp);
        panel.setTranslationY(dp(40));
        panel.setAlpha(0f);
        panel.animate().translationY(0).alpha(1f).setDuration(220).start(); // 아래에서 살짝 떠오르기
    }

    private LinearLayout.LayoutParams wide(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    private String appName() {
        try {
            return a.getApplicationInfo().loadLabel(a.getPackageManager()).toString().toUpperCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView v = new TextView(a);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private static int withAlpha(int c, int alpha) {
        return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int mix(int c, int d, float t) {
        return Color.rgb(Math.round(Color.red(c) + (Color.red(d) - Color.red(c)) * t),
                Math.round(Color.green(c) + (Color.green(d) - Color.green(c)) * t),
                Math.round(Color.blue(c) + (Color.blue(d) - Color.blue(c)) * t));
    }

    private String t(String k, String e) {
        return ko ? k : e;
    }

    private static String first(JSONArray alts) {
        JSONObject o = alts == null ? null : alts.optJSONObject(0);
        return o == null ? "" : o.optString("t", "");
    }

    /** 읽어 줄 글: 마크다운 기호·코드·주소를 걷어낸다. */
    static String speakable(String s) {
        if (s == null) return "";
        return s.replaceAll("(?s)```.*?```", " ")
                .replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("https?://\\S+", " ")
                .replaceAll("[*_#>`~|]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** 빛나는 구슬: 듣는 중엔 목소리 크기만큼 커지고, 생각 중엔 빨리 돌고, 말하는 중엔 숨 쉬듯 커졌다 작아진다. */
    static final class Orb extends View {
        static final int IDLE = 0, LISTEN = 1, THINK = 2, SPEAK = 3;
        private final int accent;
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float target, shown, phase;
        private int mode = IDLE;
        private boolean running = true;

        Orb(Context c, int accent) {
            super(c);
            this.accent = accent;
        }

        /** 음성 인식의 소리 크기(dB, 대략 -2~10). */
        void level(float rmsDb) {
            target = Math.max(0f, Math.min(1f, (rmsDb + 2f) / 12f));
        }

        void mode(int m) {
            mode = m;
            if (m != LISTEN) target = 0f;
            invalidate();
        }

        void stop() {
            running = false;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) / 2f * 0.56f;
            shown += (target - shown) * 0.22f;
            float amp;
            switch (mode) {
                case LISTEN: amp = 0.08f + shown; break;
                case SPEAK: amp = 0.30f + 0.22f * (float) Math.sin(phase * 2.4f); break;
                case THINK: amp = 0.12f; break;
                default: amp = 0.04f + 0.03f * (float) Math.sin(phase);
            }
            float gr = r * (1.30f + 0.50f * amp);
            glow.setShader(new RadialGradient(cx, cy, gr, new int[] {withAlpha(accent, 150), withAlpha(accent, 0)},
                    null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, gr, glow);
            float rr = r * (1f + 0.20f * amp);
            c.save();
            c.rotate((float) Math.toDegrees(phase) * (mode == THINK ? 3f : 1f), cx, cy);
            ring.setShader(new SweepGradient(cx, cy,
                    new int[] {accent, 0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899, accent}, null));
            c.drawCircle(cx, cy, rr, ring);
            c.restore();
            core.setShader(new RadialGradient(cx - rr * 0.3f, cy - rr * 0.35f, rr * 1.25f,
                    new int[] {0xFFFFFFFF, withAlpha(accent, 255), 0xFF0B1220}, new float[] {0f, 0.42f, 1f},
                    Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, rr * 0.84f, core);
            phase += mode == THINK ? 0.09f : 0.05f;
            if (running && isAttachedToWindow()) postInvalidateOnAnimation();
        }
    }
}
