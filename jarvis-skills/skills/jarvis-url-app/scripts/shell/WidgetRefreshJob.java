package app.jarvis.urlapp;

// 홈 화면 위젯을 앱을 열지 않고 새로 고친다 (위젯의 새로고침 단추, 자동 새로고침).
// 보이지 않는 웹 화면으로 사이트를 열어(앱과 같은 로그인 쿠키) 위젯 스크립트를 돌리고, 받은 요약을 저장한다.
// 사이트를 벗어나는 이동은 막고, 60초 안에 요약을 못 받으면(로그인이 풀렸거나 인터넷이 없음) 그만둔다.
// 위젯 스크립트에 "// urlapp:refresh-min N" 이 있는 앱만 쓴다 (매니페스트에 이 서비스가 들어간다).

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
    private static final long TIMEOUT_MS = 60_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private JobParameters params;
    private boolean done;

    /** 지금 새로 고치기 (인터넷이 되면 바로). 이미 대기 중이면 바꿔 끼운다. */
    static void enqueue(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        js.schedule(new JobInfo.Builder(JOB, new ComponentName(ctx, WidgetRefreshJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        params = p;
        String widgetJs = asset("urlapp-widget.js");
        JSONObject cfg;
        try {
            cfg = new JSONObject(asset("urlapp.json"));
        } catch (Exception e) {
            return false;
        }
        String url = cfg.optString("url", "");
        if (widgetJs == null || url.isEmpty()) return false;
        final JSONArray hosts = cfg.optJSONArray("hosts");
        final String token = randomToken();
        final String script = widgetJs.replace("\"__URLAPP_TOKEN__\"", JSONObject.quote(token));

        try {
            web = new WebView(getApplicationContext());
        } catch (RuntimeException e) { // 웹 화면(WebView)을 만들 수 없는 상태
            WidgetProvider.noteRefresh(this, false);
            return false;
        }
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
                if (!token.equals(t) || json == null || json.length() > 4000) return;
                try {
                    new JSONObject(json); // 모양만 확인
                } catch (Exception e) {
                    return;
                }
                WidgetProvider.save(getApplicationContext(), json);
                main.post(() -> finish(true));
            }
        }, "UrlAppBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !own(r.getUrl(), hosts); // 사이트 밖으로는 가지 않는다
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                if (own(Uri.parse(u), hosts)) v.evaluateJavascript(script, null); // 위젯 실행기 + 사이트별 위젯 스크립트
            }
        });
        main.postDelayed(() -> finish(false), TIMEOUT_MS);
        web.loadUrl(url);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        finish(false);
        return false;
    }

    private void finish(boolean ok) {
        if (done) return;
        done = true;
        main.removeCallbacksAndMessages(null);
        WidgetProvider.noteRefresh(this, ok);
        if (web != null) {
            try {
                web.stopLoading();
                web.destroy();
            } catch (RuntimeException ignored) {
                // 이미 정리됨
            }
            web = null;
        }
        CookieManager.getInstance().flush();
        jobFinished(params, false);
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

    private String asset(String name) {
        try (InputStream in = getAssets().open(name)) {
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
