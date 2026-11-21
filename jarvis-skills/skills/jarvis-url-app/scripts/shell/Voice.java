package app.jarvis.urlapp;

// 음성: 안드로이드의 음성 인식(SpeechRecognizer)과 읽어 주기(TextToSpeech). (--mic 로 만든 앱만 쓴다)
// 앱의 웹 화면(WebView)에는 웹 음성 기능(SpeechRecognition·speechSynthesis)이 없거나 동작하지 않아서, 폰의 것을 대신 쓴다.
// 결과는 {"type": "start|speechstart|speechend|result|error|end|tts-start|tts-end|tts-error", …} 모양으로 알린다.
// 메인 스레드에서만 부른다. 마이크 권한(RECORD_AUDIO)은 MainActivity 가 먼저 받는다.

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class Voice {
    interface Events {
        void on(JSONObject ev);
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 듣는 동안의 소리 크기(dB) — 대화 창의 구슬이 목소리에 맞춰 움직이게. 사이트 쪽(speech.js)으로는 보내지 않는다. */
    interface Level {
        void on(float rmsDb);
    }

    private Level level;

    void setLevelListener(Level l) {
        level = l;
    }

    // 듣기: 한 번에 하나
    private SpeechRecognizer sr;
    private Object session; // 지금 듣는 회차 (끝난 회차의 늦은 알림은 버린다)
    private Events srEvents;

    // 읽어 주기
    private TextToSpeech tts;
    private int ttsState; // 0 아직, 1 준비됨, -1 쓸 수 없음
    private final ArrayList<Runnable> ttsWaiting = new ArrayList<>();
    private final LinkedHashMap<String, Events> ttsEvents = new LinkedHashMap<>();
    private final HashSet<String> ttsStarted = new HashSet<>();

    Voice(Context c) {
        ctx = c;
    }

    boolean canListen() {
        try {
            return SpeechRecognizer.isRecognitionAvailable(ctx);
        } catch (RuntimeException e) {
            return false;
        }
    }

    boolean listening() {
        return sr != null;
    }

    // ---------- 듣기 ----------

    /** lang: "ko-KR" 처럼 (비면 폰의 언어). partial: 말하는 중간 결과도 알린다. */
    void listen(String lang, boolean partial, Events ev) {
        cancelListening(true);
        if (!canListen()) {
            emit(ev, ev("error").put2("error", "service-not-allowed")
                    .put2("message", "이 폰에 음성 인식 서비스가 없어요 (Google 앱의 음성 인식이 필요해요)"));
            emit(ev, ev("end"));
            return;
        }
        final Object me = new Object();
        session = me;
        srEvents = ev;
        try {
            sr = SpeechRecognizer.createSpeechRecognizer(ctx);
        } catch (RuntimeException e) {
            sr = null;
            session = null;
            emit(ev, ev("error").put2("error", "service-not-allowed"));
            emit(ev, ev("end"));
            return;
        }
        sr.setRecognitionListener(new RecognitionListener() {
            private boolean mine() {
                return session == me;
            }

            @Override
            public void onReadyForSpeech(Bundle b) {
                if (mine()) emit(ev, ev("start"));
            }

            @Override
            public void onBeginningOfSpeech() {
                if (mine()) emit(ev, ev("speechstart"));
            }

            @Override
            public void onRmsChanged(float v) {
                Level l = level;
                if (l != null && mine()) l.on(v);
            }

            @Override
            public void onBufferReceived(byte[] b) {
            }

            @Override
            public void onEndOfSpeech() {
                if (mine()) emit(ev, ev("speechend"));
            }

            @Override
            public void onError(int code) {
                if (!mine()) return;
                finishSession();
                emit(ev, ev("error").put2("error", errorName(code)));
                emit(ev, ev("end"));
            }

            @Override
            public void onResults(Bundle b) {
                if (!mine()) return;
                finishSession();
                emit(ev, result(b, true));
                emit(ev, ev("end"));
            }

            @Override
            public void onPartialResults(Bundle b) {
                if (mine()) emit(ev, result(b, false));
            }

            @Override
            public void onEvent(int type, Bundle b) {
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        String l = lang == null ? "" : lang.trim();
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, l.isEmpty() ? Locale.getDefault().toLanguageTag() : l);
        sr.startListening(i);
    }

    /** 말을 다 했다고 보고 결과를 받는다 (결과 뒤에 end). */
    void stopListening() {
        if (sr != null) sr.stopListening();
    }

    /** 듣기를 그만둔다. notify 면 듣던 쪽에 end 를 알린다. */
    void cancelListening(boolean notify) {
        Events ev = srEvents;
        boolean was = sr != null;
        finishSession();
        if (notify && was && ev != null) emit(ev, ev("end"));
    }

    private void finishSession() {
        session = null;
        srEvents = null;
        if (sr != null) {
            try {
                sr.cancel();
                sr.destroy();
            } catch (RuntimeException ignored) {
                // 이미 끝남
            }
            sr = null;
        }
    }

    private static JSONObject result(Bundle b, boolean isFinal) {
        JSONArray alts = new JSONArray();
        ArrayList<String> texts = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        float[] scores = b == null ? null : b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
        for (int i = 0; texts != null && i < texts.size(); i++) {
            JSONObject a = new JSONObject();
            try {
                a.put("t", texts.get(i) == null ? "" : texts.get(i));
                if (scores != null && i < scores.length && scores[i] >= 0) a.put("c", (double) scores[i]);
            } catch (Exception ignored) {
                // 값이 이상하면 뺀다
            }
            alts.put(a);
        }
        return ev("result").put2("final", isFinal).put2("alts", alts);
    }

    static String errorName(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "no-speech";
            case SpeechRecognizer.ERROR_AUDIO:
                return "audio-capture";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                return "network";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "not-allowed";
            case 12: // ERROR_LANGUAGE_NOT_SUPPORTED (안드로이드 12+)
            case 13: // ERROR_LANGUAGE_UNAVAILABLE
                return "language-not-supported";
            default: // ERROR_CLIENT, ERROR_RECOGNIZER_BUSY …
                return "aborted";
        }
    }

    // ---------- 읽어 주기 ----------

    void speak(String id, String text, String lang, float rate, float pitch, Events ev) {
        if (ttsState < 0) {
            emit(ev, ev("tts-error").put2("id", id).put2("error", "synthesis-unavailable"));
            return;
        }
        Runnable go = () -> doSpeak(id, text, lang, rate, pitch, ev);
        if (ttsState == 1) {
            go.run();
            return;
        }
        ttsWaiting.add(go);
        if (tts != null) return; // 준비 중
        tts = new TextToSpeech(ctx, status -> main.post(() -> {
            ttsState = status == TextToSpeech.SUCCESS ? 1 : -1;
            if (ttsState == 1) tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String uid) {
                    main.post(() -> {
                        Events e = ttsEvents.get(uid);
                        if (e == null) return;
                        ttsStarted.add(uid);
                        emit(e, ev("tts-start").put2("id", uid));
                    });
                }

                @Override
                public void onDone(String uid) {
                    main.post(() -> ttsFinished(uid, null));
                }

                @Override
                @SuppressWarnings("deprecation") // onError(String): 안드로이드 5 이전부터의 것 (반드시 구현)
                public void onError(String uid) {
                    main.post(() -> ttsFinished(uid, "synthesis-failed"));
                }

                @Override
                public void onError(String uid, int code) {
                    main.post(() -> ttsFinished(uid, "synthesis-failed"));
                }

                @Override
                public void onStop(String uid, boolean interrupted) {
                    main.post(() -> ttsFinished(uid, interrupted ? "interrupted" : "canceled"));
                }
            });
            ArrayList<Runnable> waiting = new ArrayList<>(ttsWaiting);
            ttsWaiting.clear();
            for (Runnable r : waiting) r.run();
        }));
    }

    private void doSpeak(String id, String text, String lang, float rate, float pitch, Events ev) {
        if (ttsState < 0 || tts == null) {
            emit(ev, ev("tts-error").put2("id", id).put2("error", "synthesis-unavailable"));
            return;
        }
        String l = lang == null ? "" : lang.trim();
        Locale loc = l.isEmpty() ? Locale.getDefault() : Locale.forLanguageTag(l);
        if (tts.setLanguage(loc) < 0) tts.setLanguage(Locale.getDefault());
        tts.setSpeechRate(clamp(rate, 0.1f, 4f));
        tts.setPitch(clamp(pitch, 0.1f, 2f));
        String say = text == null ? "" : text;
        int max = TextToSpeech.getMaxSpeechInputLength();
        if (say.length() > max) say = say.substring(0, max);
        ttsEvents.put(id, ev);
        if (tts.speak(say, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
            ttsFinished(id, "synthesis-failed");
        }
    }

    private void ttsFinished(String id, String error) {
        Events e = ttsEvents.remove(id);
        boolean started = ttsStarted.remove(id);
        if (e == null) return;
        if (error == null) emit(e, ev("tts-end").put2("id", id));
        else emit(e, ev("tts-error").put2("id", id).put2("error", started && "canceled".equals(error) ? "interrupted" : error));
    }

    boolean speaking() {
        return !ttsEvents.isEmpty();
    }

    /** 읽던 것과 기다리던 것을 모두 멈춘다 (읽던 것은 interrupted, 기다리던 것은 canceled). */
    void stopSpeaking() {
        ttsWaiting.clear();
        if (tts != null && ttsState == 1) tts.stop();
        ArrayList<Map.Entry<String, Events>> left = new ArrayList<>(ttsEvents.entrySet());
        ttsEvents.clear();
        for (Map.Entry<String, Events> x : left) {
            boolean started = ttsStarted.remove(x.getKey());
            emit(x.getValue(), ev("tts-error").put2("id", x.getKey()).put2("error", started ? "interrupted" : "canceled"));
        }
    }

    void destroy() {
        cancelListening(false);
        stopSpeaking();
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (RuntimeException ignored) {
                // 이미 꺼짐
            }
            tts = null;
            ttsState = 0;
        }
    }

    // ---------- 도우미 ----------

    private static float clamp(float v, float lo, float hi) {
        return Float.isNaN(v) ? 1f : Math.max(lo, Math.min(hi, v));
    }

    private static void emit(Events e, JSONObject o) {
        if (e != null) e.on(o);
    }

    /** put 이 예외를 던지지 않는 JSONObject (값은 우리가 만든 것뿐). */
    static final class Ev extends JSONObject {
        Ev put2(String k, Object v) {
            try {
                put(k, v);
            } catch (Exception ignored) {
                // 숫자가 NaN 같은 경우만
            }
            return this;
        }
    }

    static Ev ev(String type) {
        return new Ev().put2("type", type);
    }
}
