package app.jarvis.urlapp;

// 워치에서 말한 질문을 폰이 대신 사이트에 묻는다 (--watch 앱). 워치는 사설 주소·로그인을 모르므로 폰을 거친다.
// 폰의 PhoneWatchService 가 질문을 받으면 이 작업을 '급한 작업(expedited)'으로 예약하고, 작업은 보이지 않는 웹 화면으로
// 사이트를 열어(앱과 같은 로그인 쿠키) window.urlappAsk(질문) 의 답을 받아 워치로 보낸다 (/urlapp/answer).
// 사이트 밖으로는 가지 않고, 정한 시간 안에 답이 없으면(로그인 풀림·연결 안 됨·자비스가 늦음) 이유를 보낸다.

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

public class WatchAskJob extends JobService {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int JOB_BASE = 7400, JOB_SLOTS = 20;
    static final long TIMEOUT_MS = 110_000;      // 워치는 120초 기다린다
    static final int MAX_QUESTION = 500;
    private static int next;

    interface Done {
        void answer(boolean ok, String text);
    }

    /** 질문을 예약한다 (PhoneWatchService 가 부른다). 예약을 못 하면 false. */
    static boolean enqueue(Context ctx, String node, String id, String question) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return false;
        PersistableBundle b = new PersistableBundle();
        b.putString("node", node);
        b.putString("id", id);
        b.putString("q", question);
        int jobId;
        synchronized (WatchAskJob.class) {
            jobId = JOB_BASE + (next++ % JOB_SLOTS);
        }
        ComponentName cn = new ComponentName(ctx, WatchAskJob.class);
        if (Build.VERSION.SDK_INT >= 31) {
            JobInfo fast = new JobInfo.Builder(jobId, cn).setExtras(b).setExpedited(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build();
            if (Jobs.safe(js, fast)) return true; // 급한 작업 몫이 없으면 아래 보통 작업으로
        }
        return Jobs.safe(js, new JobInfo.Builder(jobId, cn).setExtras(b).setOverrideDeadline(0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        PersistableBundle b = p.getExtras();
        final String node = b.getString("node", ""), id = b.getString("id", ""), q = b.getString("q", "");
        final Context ctx = getApplicationContext();
        ask(ctx, q, TIMEOUT_MS, (ok, text) -> {
            WatchBridge.send(ctx, node, "/urlapp/answer", reply(id, ok, text));
            jobFinished(p, false);
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        return false; // ask 가 스스로 시간 안에 끝낸다
    }

    static String reply(String id, boolean ok, String text) {
        try {
            return new JSONObject().put("id", id).put("ok", ok).put("text", text == null ? "" : text).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 메인 스레드에서 부른다. timeoutMs 안에 반드시 done 을 한 번 부른다. */
    static void ask(Context ctx, String question, long timeoutMs, Done done) {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty() || q.length() > MAX_QUESTION) {
            done.answer(false, "질문이 비었거나 너무 길어요.");
            return;
        }
        JSONObject cfg;
        try {
            cfg = new JSONObject(asset(ctx, "urlapp.json"));
        } catch (Exception e) {
            done.answer(false, "앱 설정을 읽지 못했어요.");
            return;
        }
        String url = cfg.optString("url", "");
        if (url.isEmpty()) {
            done.answer(false, "앱 설정에 주소가 없어요.");
            return;
        }
        final JSONArray hosts = cfg.optJSONArray("hosts");
        final String token = randomToken();
        final WebView web;
        try {
            web = new WebView(ctx);
        } catch (RuntimeException e) {
            done.answer(false, "폰에서 웹 화면을 열 수 없어요.");
            return;
        }
        final boolean[] state = {false, false}; // [0] 끝남, [1] 물어봄
        final String[] why = {"자비스가 시간 안에 답하지 않았어요."};
        final Runnable[] timeout = new Runnable[1];
        java.util.function.BiConsumer<Boolean, String> end = (ok, text) -> {
            if (state[0]) return;
            state[0] = true;
            MAIN.removeCallbacks(timeout[0]);
            try {
                web.stopLoading();
                web.destroy();
            } catch (RuntimeException ignored) {
                // 이미 정리됨
            }
            CookieManager.getInstance().flush();
            done.answer(ok, text);
        };
        timeout[0] = () -> end.accept(false, why[0]);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        if (cfg.optBoolean("desktop", false)) {
            s.setUserAgentString(s.getUserAgentString().replaceAll("\\(Linux; Android[^)]*\\)", "(X11; Linux x86_64)")
                    .replace(" Mobile", ""));
        }
        CookieManager.getInstance().setAcceptCookie(true);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void answer(String t, String text, boolean ok) {
                if (!token.equals(t)) return;
                String a = text == null ? "" : text.trim();
                MAIN.post(() -> end.accept(ok && !a.isEmpty(), ok ? (a.isEmpty() ? "답이 비어 있어요." : a)
                        : "자비스가 답하지 못했어요."));
            }
        }, "UrlAppAsk");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !own(r.getUrl(), hosts); // 사이트 밖으로는 가지 않는다
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                if (!state[0] && !state[1] && own(Uri.parse(u), hosts)) probe(v, 0);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    MAIN.post(() -> end.accept(false, "폰이 자비스에 연결하지 못했어요. 폰의 인터넷(Tailscale)을 확인해 주세요."));
                }
            }

            /** 사이트가 urlappAsk 를 준비했는지 (약 12초). 로그인 화면이면 준비되지 않는다. */
            private void probe(WebView v, int attempt) {
                if (state[0] || state[1]) return;
                v.evaluateJavascript("(function(){return typeof window.urlappAsk==='function'?'ask':'none';})()", r -> {
                    if (state[0] || state[1]) return;
                    if (r != null && r.contains("ask")) {
                        state[1] = true;
                        why[0] = "자비스가 시간 안에 답하지 않았어요.";
                        v.evaluateJavascript(askJs(q, token, ChatSession.current(ctx)), null);
                    } else if (attempt < 40) {
                        MAIN.postDelayed(() -> probe(v, attempt + 1), 300);
                    } else {
                        end.accept(false, "자비스에 음성 대화가 준비되지 않았어요. 폰에서 자비스 앱을 한 번 열어 로그인해 주세요.");
                    }
                });
            }
        });
        MAIN.postDelayed(timeout[0], timeoutMs);
        web.loadUrl(url);
    }

    /** 사이트의 urlappAsk 로 묻고 답을 UrlAppAsk.answer 로 돌려주는 스크립트 (VoiceActivity 와 같은 모양). */
    static String askJs(String question, String token, String session) {
        // 두 번째 인자 {session}: 대화 창과 같은 표시 (ChatSession) — 15분 안에 이어 묻는 말(예: "응")을 사이트가 같은 대화로 볼 수 있게
        return "(function(q,tok,ses){Promise.resolve().then(function(){return window.urlappAsk(q,ses?{session:ses}:undefined);})"
                + ".then(function(r){var s=r&&typeof r==='object'?(r.text||r.reply||r.answer||''):(r==null?'':r);"
                + "UrlAppAsk.answer(tok,String(s).slice(0,20000),true);},"
                + "function(){UrlAppAsk.answer(tok,'',false);});})("
                + JSONObject.quote(question) + "," + JSONObject.quote(token) + "," + JSONObject.quote(session == null ? "" : session) + ")";
    }

    private static boolean own(Uri u, JSONArray hosts) {
        String h = u == null || u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        String scheme = u == null || u.getScheme() == null ? "" : u.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) return false;
        for (int i = 0; hosts != null && i < hosts.length(); i++) {
            String o = hosts.optString(i).toLowerCase(Locale.ROOT);
            if (!o.isEmpty() && (h.equals(o) || h.endsWith("." + o))) return true;
        }
        return false;
    }

    private static String asset(Context ctx, String name) throws java.io.IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String randomToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }
}
