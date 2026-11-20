package app.jarvis.urlapp;

// 홈 화면 위젯을 앱을 열지 않고 새로 고친다 (위젯의 새로고침 단추, 자동 새로고침).
// 보이지 않는 웹 화면으로 사이트를 열어(앱과 같은 로그인 쿠키) 위젯 스크립트를 돌리고, 받은 요약을 저장한다.
// 사이트를 벗어나는 이동은 막고, 정한 시간 안에 요약을 못 받으면(로그인이 풀렸거나 인터넷이 없음) 그만둔다.
// 위젯 스크립트에 "// urlapp:refresh-min N" 이 있는 앱만 쓴다.
//
// 보통은 위젯의 단추·자동 알람을 받은 WidgetProvider 가 그 자리에서 runNow() 로 바로 돌린다 (goAsync, 45초 안에 끝냄).
// 작업 예약(JobScheduler)은 삼성 등의 절전이 한참 미루거나 도중에 끝내서 "새로고침 중…"이 남을 수 있어 예비로만 둔다.
// 언제 끝나든 WidgetProvider.noteRefresh 를 한 번 불러 위젯 아래 줄을 고친다. 한 번에 하나만 돈다.

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
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

public class WidgetRefreshJob extends JobService {
    static final int JOB = 7201;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean running; // 한 번에 하나만 (메인 스레드에서만 바꾼다)

    /** 예비: 작업 예약으로 새로 고치기 (인터넷이 되면). */
    static void enqueue(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        Jobs.safe(js, new JobInfo.Builder(JOB, new ComponentName(ctx, WidgetRefreshJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        runNow(getApplicationContext(), 60_000, () -> jobFinished(p, false));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        return false; // runNow 가 스스로 시간 안에 끝낸다
    }

    /**
     * 메인 스레드에서 부른다. timeoutMs 안에 반드시 끝나고, 끝나면 위젯 아래 줄을 고친 뒤 done 을 부른다.
     * 이미 하나가 돌고 있으면 새로 시작하지 않고 done 만 부른다.
     */
    static void runNow(Context ctx, long timeoutMs, Runnable done) {
        if (running) {
            done.run();
            return;
        }
        String widgetJs = asset(ctx, "urlapp-widget.js");
        JSONObject cfg;
        try {
            cfg = new JSONObject(asset(ctx, "urlapp.json"));
        } catch (Exception e) {
            WidgetProvider.noteRefresh(ctx, false);
            done.run();
            return;
        }
        String url = cfg.optString("url", "");
        if (widgetJs == null || url.isEmpty()) {
            WidgetProvider.noteRefresh(ctx, false);
            done.run();
            return;
        }
        final JSONArray hosts = cfg.optJSONArray("hosts");
        final String token = randomToken();
        final String script = widgetJs.replace("\"__URLAPP_TOKEN__\"", JSONObject.quote(token));
        final WebView web;
        try {
            web = new WebView(ctx);
        } catch (RuntimeException e) { // 웹 화면(WebView)을 만들 수 없는 상태
            WidgetProvider.noteRefresh(ctx, false);
            done.run();
            return;
        }
        running = true;
        final boolean[] finished = {false};
        final Runnable[] finish = new Runnable[2]; // [0] 실패로 끝, [1] 성공으로 끝
        java.util.function.Consumer<Boolean> end = ok -> {
            if (finished[0]) return;
            finished[0] = true;
            MAIN.removeCallbacks(finish[0]);
            running = false;
            WidgetProvider.noteRefresh(ctx, ok);
            try {
                web.stopLoading();
                web.destroy();
            } catch (RuntimeException ignored) {
                // 이미 정리됨
            }
            CookieManager.getInstance().flush();
            done.run();
        };
        finish[0] = () -> end.accept(false);
        finish[1] = () -> end.accept(true);

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
            public void widget(String t, String json) {
                if (!token.equals(t) || json == null || json.length() > 16000) return;
                try {
                    new JSONObject(json); // 모양만 확인
                } catch (Exception e) {
                    return;
                }
                WidgetProvider.save(ctx, json);
                MAIN.post(finish[1]);
            }
        }, "UrlAppBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !own(r.getUrl(), hosts); // 사이트 밖으로는 가지 않는다
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                if (!finished[0] && own(Uri.parse(u), hosts)) v.evaluateJavascript(script, null); // 위젯 실행기 + 사이트별 위젯 스크립트
            }
        });
        MAIN.postDelayed(finish[0], timeoutMs);
        web.loadUrl(url);
    }

    private static boolean own(Uri u, JSONArray hosts) {
        String h = u == null || u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        String scheme = u == null || u.getScheme() == null ? "" : u.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) return false;
        for (int i = 0; hosts != null && i < hosts.length(); i++) {
            String own = hosts.optString(i).toLowerCase(Locale.ROOT);
            if (!own.isEmpty() && (h.equals(own) || h.endsWith("." + own))) return true;
        }
        return false;
    }

    private static String asset(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
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
