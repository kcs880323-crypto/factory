package app.jarvis.urlapp;

// 앱이 직접 하는 음성 대화 (--mic 로 만든 앱, 사이트가 window.urlappAsk(질문) → 답 을 줄 때).
// 듣기(폰의 음성 인식) → 사이트에 묻기(urlappAsk) → 답 읽어 주기(폰의 TTS) → 다시 듣기 를 '끝내기'까지 되풀이한다.
// 화면 아래에 작은 판(지금 상태, 내가 한 말, 답, '다시 듣기'·'끝내기')을 띄우고, 위에서는 사이트 화면이 그대로 보인다.
// 사이트가 자기 음성 모드(window.urlappMic)를 주면 그것을 쓰고 이 판은 쓰지 않는다 (MainActivity 가 고른다).

import android.app.Activity;
import android.graphics.Color;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

final class VoiceChat {
    interface Host {
        /** 사이트에 묻는다. 답은 answer(id, text) 로 돌려준다. */
        void ask(String id, String question);

        void keepAwake(boolean on);

        /** 대화를 끝냈다 ('끝내기'). 위젯의 대화 창은 여기서 닫는다. */
        default void ended() {
        }

        /** 두 번 말이 없어 쉬었다. 위젯의 대화 창은 잠깐 뒤 닫는다. */
        default void quiet() {
        }
    }

    private static final int IDLE = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3, PAUSED = 4;
    private static final long ANSWER_TIMEOUT_MS = 90_000;

    private final Activity a;
    private final Voice voice;
    private final Host host;
    private final FrameLayout parent;
    private final int accent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean ko = "ko".equals(Locale.getDefault().getLanguage());

    private LinearLayout panel;
    private TextView status, heard, reply, again;
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
        }
        quiet = 0;
        listen();
    }

    void stop() {
        if (state == IDLE) return;
        state = IDLE;
        waiting = null;
        main.removeCallbacksAndMessages(null);
        voice.cancelListening(false);
        voice.stopSpeaking();
        host.keepAwake(false);
        if (panel != null) {
            parent.removeView(panel);
            panel = null;
        }
        host.ended();
    }

    /** 사이트에 물을 수 없을 때 (연결 안 됨·로그인 전 등): 답을 기다리던 중이면 이유를 보이고 쉰다. */
    void fail(String why) {
        if (state == IDLE) return;
        waiting = null;
        pause(why);
    }

    /** 사이트의 답 (MainActivity 가 통로에서 받아 메인 스레드로 넘긴다). ok=false 면 사이트에서 오류가 났다. */
    void answer(String id, String text, boolean ok) {
        if (state != THINKING || id == null || !id.equals(waiting)) return;
        waiting = null;
        if (!ok) {
            pause(t("사이트가 답하지 못했어요. '다시 듣기'를 누르세요.", "The site couldn't answer. Tap 'Listen'."));
            return;
        }
        String say = speakable(text);
        if (say.isEmpty()) { // 사이트가 화면에만 답했거나 할 말이 없음 → 다시 듣기
            listen();
            return;
        }
        reply.setText(text.length() > 400 ? text.substring(0, 400) + "…" : text);
        reply.setVisibility(View.VISIBLE);
        state = SPEAKING;
        setStatus(t("말하는 중… ('다시 듣기'를 누르면 멈추고 들어요)", "Speaking… (tap 'Listen' to interrupt)"));
        voice.speak("chat" + (++seq), say, "", 1f, 1f, ev -> {
            String type = ev.optString("type");
            if (state != SPEAKING || "tts-start".equals(type)) return;
            if ("tts-error".equals(type) && "synthesis-unavailable".equals(ev.optString("error"))) {
                pause(t("이 폰에서 읽어 주기(TTS)를 쓸 수 없어요. 답은 화면에서 보세요.", "Text-to-speech isn't available."));
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
        setStatus(t("듣고 있어요… 말씀하세요", "Listening… go ahead"));
        voice.listen("", true, ev -> {
            if (state != LISTENING) return;
            String type = ev.optString("type");
            if ("result".equals(type)) {
                String text = first(ev.optJSONArray("alts"));
                if (!ev.optBoolean("final")) {
                    if (!text.isEmpty()) heard.setText(text);
                    return;
                }
                if (text.trim().isEmpty()) return;
                heard.setText(t("나: ", "Me: ") + text);
                quiet = 0;
                ask(text);
            } else if ("error".equals(type)) {
                String e = ev.optString("error");
                if ("no-speech".equals(e)) {
                    if (++quiet >= 2) {
                        pause(t("말씀이 없어서 쉬고 있어요. '다시 듣기'를 누르세요.", "Paused (no speech). Tap 'Listen'."));
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
        setStatus(t("생각 중…", "Thinking…"));
        reply.setVisibility(View.GONE);
        final String id = "q" + (++seq);
        waiting = id;
        host.ask(id, question);
        main.postDelayed(() -> {
            if (state == THINKING && id.equals(waiting)) {
                waiting = null;
                pause(t("답이 오지 않았어요. '다시 듣기'를 누르세요.", "No answer. Tap 'Listen'."));
            }
        }, ANSWER_TIMEOUT_MS);
    }

    private void pause(String why) {
        if (state == IDLE) return;
        state = PAUSED;
        voice.cancelListening(false);
        setStatus(why);
    }

    private String errorText(String e, String message) {
        if ("not-allowed".equals(e)) return t("마이크 권한이 없어요. 설정 → 앱 → 이 앱 → 권한에서 마이크를 켜 주세요.",
                "Microphone permission is off.");
        if ("network".equals(e)) return t("음성 인식이 인터넷에 닿지 않아요. '다시 듣기'를 누르세요.", "Speech recognition needs internet.");
        if ("audio-capture".equals(e)) return t("마이크를 쓸 수 없어요 (다른 앱이 쓰는 중일 수 있어요).", "Microphone is busy.");
        if ("service-not-allowed".equals(e)) return message.isEmpty()
                ? t("이 폰에서 음성 인식을 쓸 수 없어요.", "Speech recognition isn't available.") : message;
        if ("language-not-supported".equals(e)) return t("이 언어의 음성 인식이 없어요.", "Language not supported.");
        return t("음성 인식이 멈췄어요. '다시 듣기'를 누르세요.", "Speech recognition stopped.");
    }

    private void setStatus(String s) {
        if (status != null) status.setText(s);
        if (again != null) again.setText(state == SPEAKING ? t("멈추고 듣기", "Listen") : t("다시 듣기", "Listen"));
    }

    // ---------- 화면 ----------

    private void show() {
        if (panel != null) return;
        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(242, 15, 23, 32));
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), withAlpha(accent, 140));
        panel.setBackground(bg);
        panel.setElevation(dp(8));
        panel.setClickable(true); // 아래 웹 화면으로 눌림이 새지 않게

        status = text(15, Color.WHITE, true);
        status.setCompoundDrawablePadding(dp(8));
        panel.addView(status);
        heard = text(14, Color.rgb(203, 213, 225), false);
        heard.setMaxLines(3);
        heard.setEllipsize(TextUtils.TruncateAt.START);
        heard.setPadding(0, dp(6), 0, 0);
        panel.addView(heard);
        reply = text(14, accent, false);
        reply.setMaxLines(5);
        reply.setEllipsize(TextUtils.TruncateAt.END);
        reply.setPadding(0, dp(6), 0, 0);
        reply.setVisibility(View.GONE);
        panel.addView(reply);

        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(10), 0, 0);
        again = pill(t("다시 듣기", "Listen"), accent, Color.rgb(2, 6, 23));
        again.setOnClickListener(v -> {
            quiet = 0;
            waiting = null;
            listen();
        });
        TextView end = pill(t("끝내기", "End"), Color.argb(60, 255, 255, 255), Color.WHITE);
        end.setOnClickListener(v -> stop());
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.setMarginEnd(dp(8));
        buttons.addView(again, gap);
        buttons.addView(end);
        panel.addView(buttons);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins(dp(12), 0, dp(12), dp(12));
        parent.addView(panel, lp);
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView v = new TextView(a);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private TextView pill(String label, int bgColor, int fg) {
        TextView v = text(14, fg, true);
        v.setText(label);
        v.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable d = new GradientDrawable();
        d.setColor(bgColor);
        d.setCornerRadius(dp(18));
        v.setBackground(d);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private static int withAlpha(int c, int alpha) {
        return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c));
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
        String x = s.replaceAll("(?s)```.*?```", " ")
                .replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("https?://\\S+", " ")
                .replaceAll("[*_#>`~|]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return x;
    }
}
