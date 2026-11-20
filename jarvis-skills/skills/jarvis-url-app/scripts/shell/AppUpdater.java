package app.jarvis.urlapp;

// 앱 안 업데이트 (빌드할 때 --update-url 을 준 앱만).
// 앱을 열 때 6시간에 한 번(또는 '업데이트 확인'을 누르면 바로) 새 버전 정보 파일을 읽고, 새 버전이면 묻는다.
// '업데이트' → 앱 전용 임시 폴더로 받기 → 크기·SHA-256 확인 → 안드로이드 설치 화면(PackageInstaller).
// 같은 서명 키로 만든 APK 만 기존 앱 위에 설치된다 (안드로이드가 막는다). 로그인·설정은 그대로 남는다.

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AppUpdater {
    private AppUpdater() {}

    private static final String PREFS = "urlapp_update";
    private static final long AUTO_EVERY_MS = 6 * 3600 * 1000L;
    private static final ExecutorService exec = Executors.newSingleThreadExecutor();

    /** 빌드할 때 준 새 버전 정보 파일 주소 (assets/urlapp.json 의 update_url). 없으면 null. */
    static String manifestUrl(Context c) {
        try (InputStream in = c.getAssets().open("urlapp.json")) {
            String u = new JSONObject(readAll(in, 1_000_000)).optString("update_url", "");
            return u.startsWith("https://") ? u : null;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean enabled(Context c) {
        return manifestUrl(c) != null;
    }

    @SuppressWarnings("deprecation") // versionCode: 안드로이드 9 미만
    static long installedCode(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String installedName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** 새 버전 확인. userAsked 가 아니면 6시간에 한 번만, 결과가 '최신'이면 조용히. */
    static void check(Activity a, boolean userAsked) {
        String url = manifestUrl(a);
        if (url == null) {
            if (userAsked) toast(a, "이 앱에는 업데이트 주소가 없어요.");
            return;
        }
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!userAsked && now - p.getLong("checked_at", 0) < AUTO_EVERY_MS) return;
        p.edit().putLong("checked_at", now).apply();
        if (userAsked) toast(a, "업데이트 확인 중…");
        exec.execute(() -> {
            UpdateInfo info = null;
            String err = null;
            try {
                // 정보 파일은 몇 분 동안 저장(캐시)될 수 있어 1분 단위로 주소를 바꿔 새로 읽는다
                String json = new String(get(url + (url.contains("?") ? "&" : "?") + "t=" + now / 60_000, 64 * 1024, null),
                        StandardCharsets.UTF_8);
                info = UpdateInfo.parse(json, a.getPackageName(), url);
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final UpdateInfo f = info;
            final String fe = err;
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (f == null) {
                    if (userAsked) toast(a, "업데이트를 확인하지 못했어요 (" + fe + ")");
                    return;
                }
                if (!f.newerThan(installedCode(a))) {
                    if (userAsked) toast(a, "최신 버전이에요 (" + installedName(a) + ")");
                    return;
                }
                offer(a, f);
            });
        });
    }

    private static void offer(Activity a, UpdateInfo f) {
        new AlertDialog.Builder(a)
                .setTitle("새 버전이 있어요")
                .setMessage("지금 " + installedName(a) + " → 새 버전 " + f.versionName
                        + (f.notes.isEmpty() ? "" : "\n\n" + f.notes)
                        + "\n\n'업데이트'를 누르면 받은 뒤 안드로이드 설치 화면이 떠요. 거기서 '업데이트'를 누르면 끝나요. "
                        + "로그인과 설정은 그대로예요.")
                .setPositiveButton("업데이트", (d, w) -> download(a, f))
                .setNegativeButton("나중에", null)
                .show();
    }

    private static void download(Activity a, UpdateInfo f) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a)
                    .setTitle("설치 허용이 한 번 필요해요")
                    .setMessage("업데이트를 설치하려면 이 앱에 '출처를 알 수 없는 앱 설치'(이 출처 허용)를 한 번 켜 주세요. "
                            + "켠 뒤 돌아와서 다시 '업데이트'를 누르면 돼요.")
                    .setPositiveButton("설정 열기", (d, w) -> {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception e) {
                            toast(a, "설정을 열지 못했어요. 설정 → 앱 → 이 앱 → 출처를 알 수 없는 앱 설치에서 켜 주세요.");
                        }
                    })
                    .setNegativeButton("나중에", null)
                    .show();
            return;
        }
        final AlertDialog progress = new AlertDialog.Builder(a)
                .setTitle("업데이트 받는 중")
                .setMessage("0%")
                .setCancelable(false)
                .show();
        exec.execute(() -> {
            File dir = new File(a.getCacheDir(), "updates");
            File[] old = dir.listFiles();
            for (int i = 0; old != null && i < old.length; i++) //noinspection ResultOfMethodCallIgnored
                old[i].delete();
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File apk = new File(dir, "update-" + f.versionCode + ".apk");
            try {
                byte[] data = get(f.apkUrl, f.size + 1, pct -> a.runOnUiThread(() -> progress.setMessage(pct + "%")));
                if (data.length != f.size) throw new IOException("받은 크기가 달라요");
                String sha = hex(MessageDigest.getInstance("SHA-256").digest(data));
                if (!sha.equals(f.sha256)) throw new IOException("검사값(SHA-256)이 달라요 — 받은 파일이 손상됐어요");
                try (FileOutputStream out = new FileOutputStream(apk)) {
                    out.write(data);
                }
                install(a, apk);
                a.runOnUiThread(progress::dismiss);
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                final String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                a.runOnUiThread(() -> {
                    progress.dismiss();
                    if (!a.isFinishing()) {
                        new AlertDialog.Builder(a).setTitle("업데이트를 받지 못했어요").setMessage(why + "\n\n인터넷 연결을 확인하고 다시 해 주세요.")
                                .setPositiveButton("확인", null).show();
                    }
                });
            }
        });
    }

    /** 안드로이드 설치 화면으로 넘긴다 (결과는 UpdateReceiver 가 받는다). */
    private static void install(Activity a, File apk) throws IOException {
        PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(a.getPackageName());
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("base.apk", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent cb = new Intent(a, UpdateReceiver.class).setAction(UpdateReceiver.ACTION);
            // 설치기가 결과(상태·확인 화면)를 넣어 돌려줘야 해서 바꿀 수 있는(MUTABLE) PendingIntent 여야 한다. 받는 곳은 이 앱의 receiver 로 고정.
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(a, id, cb, flags).getIntentSender());
        }
    }

    interface Progress {
        void at(int percent);
    }

    /** https 로 받기 (최대 max 바이트). progress 가 있으면 퍼센트를 알려 준다. */
    private static byte[] get(String url, long max, Progress progress) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("https 주소가 아니에요");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            if (!"https".equalsIgnoreCase(c.getURL().getProtocol())) throw new IOException("https 가 아닌 곳으로 넘어갔어요");
            long total = c.getContentLengthLong();
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n, last = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > max) throw new IOException("파일이 너무 커요");
                    if (progress != null && total > 0) {
                        int pct = (int) (out.size() * 100L / total);
                        if (pct != last) {
                            last = pct;
                            progress.at(pct);
                        }
                    }
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new IOException("too big");
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    private static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
    }
}
