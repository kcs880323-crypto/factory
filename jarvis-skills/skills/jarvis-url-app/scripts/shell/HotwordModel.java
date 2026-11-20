package app.jarvis.urlapp;

// 호출어 음성 모델 받기·풀기 (--hotword 앱). 모델은 APK 에 넣지 않고(약 87MB) 사용자가 설정 화면에서 '받기'를 누를 때
// 폰의 다운로드 관리자로 받는다 (진행 상황이 알림에 보이고, 앱을 닫아도 계속 받는다).
// 받은 파일은 크기·SHA-256 을 앱 설정(urlapp.json 의 hotword)과 맞춰 본 뒤에만 앱 전용 폴더에 푼다
// (경로가 모델 폴더 밖으로 나가는 항목·너무 큰 압축은 거절). 다 풀리면 표시 파일(.ok)을 남긴다.

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class HotwordModel {
    private static final String PREFS = "urlapp_hotword";
    private static final long MAX_UNPACKED = 450L * 1024 * 1024; // 한국어 small 은 풀면 약 253MB
    private static volatile String installing; // 푸는 중이면 진행 글
    private static volatile String lastError = "";

    private HotwordModel() {
    }

    static JSONObject cfg(Context c) {
        JSONObject h = NotifyJob.config(c).optJSONObject("hotword");
        return h == null ? new JSONObject() : h;
    }

    static String dirName(Context c) {
        String d = cfg(c).optString("model_dir", "");
        return d.matches("[A-Za-z0-9._-]{1,80}") ? d : "model";
    }

    static File root(Context c) {
        return new File(c.getFilesDir(), "hotword");
    }

    static File dir(Context c) {
        return new File(root(c), dirName(c));
    }

    private static File marker(Context c) {
        return new File(dir(c), ".ok");
    }

    static boolean ready(Context c) {
        String sha = cfg(c).optString("model_sha256", "");
        File m = marker(c);
        if (!m.isFile()) return false;
        try {
            return sha.equalsIgnoreCase(read(m).trim());
        } catch (IOException e) {
            return false;
        }
    }

    static long sizeBytes(Context c) {
        return cfg(c).optLong("model_size", 0);
    }

    static String error() {
        return lastError;
    }

    static String installing() {
        return installing;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File zipFile(Context c) {
        File ext = c.getExternalFilesDir(null);
        return new File(ext == null ? c.getFilesDir() : ext, "hotword-model.zip");
    }

    /** 받기 시작. 이미 받는 중이면 그대로. 실패하면 이유. */
    static String startDownload(Context c) {
        JSONObject h = cfg(c);
        String url = h.optString("model_url", "");
        if (!url.startsWith("https://")) return "앱 설정에 모델 주소가 없어요.";
        long need = sizeBytes(c) * 4; // 받은 파일 + 푼 파일 (+여유)
        if (free(c.getFilesDir()) < need) {
            return String.format(Locale.ROOT, "저장 공간이 모자라요 (약 %dMB 필요).", need / 1048576);
        }
        if (downloading(c)) return null;
        File z = zipFile(c);
        if (z.exists()) z.delete();
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
        r.setTitle("음성 호출어 모델");
        r.setDescription("폰 안에서만 쓰는 한국어 음성 모델");
        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        r.setAllowedOverMetered(true);
        r.setDestinationInExternalFilesDir(c, null, z.getName());
        try {
            long id = dm.enqueue(r);
            prefs(c).edit().putLong("dl_id", id).apply();
            lastError = "";
            return null;
        } catch (Exception e) {
            // 일부 기기는 앱 전용 외부 폴더를 대상으로 못 쓰면 거절한다
            return "다운로드를 시작하지 못했어요: " + e.getClass().getSimpleName();
        }
    }

    static boolean downloading(Context c) {
        int[] s = status(c);
        return s != null && (s[0] == DownloadManager.STATUS_PENDING || s[0] == DownloadManager.STATUS_RUNNING
                || s[0] == DownloadManager.STATUS_PAUSED);
    }

    /** {상태, 받은 바이트, 전체 바이트, 이유} 또는 null (받은 적 없음). */
    static int[] status(Context c) {
        long id = prefs(c).getLong("dl_id", -1);
        if (id < 0) return null;
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        try (Cursor q = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (q == null || !q.moveToFirst()) return null;
            return new int[] {
                q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                (int) (q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)) / 1024),
                (int) (q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)) / 1024),
                q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            };
        } catch (Exception e) {
            return null;
        }
    }

    /** 다운로드 관리자가 실제로 저장한 파일 (같은 이름이 있으면 이름을 바꿔 저장할 수 있어서). */
    private static File downloadedFile(Context c) {
        long id = prefs(c).getLong("dl_id", -1);
        File fallback = zipFile(c);
        if (id < 0) return fallback;
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        try (Cursor q = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (q != null && q.moveToFirst()) {
                String u = q.getString(q.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                if (u != null && u.startsWith("file:")) {
                    File f = new File(Uri.parse(u).getPath());
                    File ext = c.getExternalFilesDir(null);
                    if (ext != null && f.getCanonicalPath().startsWith(ext.getCanonicalPath() + File.separator)) return f;
                }
            }
        } catch (Exception e) {
            // 아래 기본 이름
        }
        return fallback;
    }

    static void cancelDownload(Context c) {
        long id = prefs(c).getLong("dl_id", -1);
        if (id >= 0) {
            ((DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE)).remove(id);
            prefs(c).edit().remove("dl_id").apply();
        }
        File z = zipFile(c);
        if (z.exists()) z.delete();
    }

    /** 받기가 끝났으면 검사하고 푼다 (작업 스레드에서 부른다). true = 모델 준비됨. */
    static synchronized boolean installIfDownloaded(Context c) {
        if (ready(c)) return true;
        int[] s = status(c);
        if (s == null || s[0] != DownloadManager.STATUS_SUCCESSFUL) {
            if (s != null && s[0] == DownloadManager.STATUS_FAILED) {
                lastError = "받기에 실패했어요 (코드 " + s[3] + "). 인터넷을 확인하고 다시 받아 주세요.";
                prefs(c).edit().remove("dl_id").apply();
            }
            return false;
        }
        File z = downloadedFile(c);
        try {
            installing = "받은 파일 확인 중…";
            JSONObject h = cfg(c);
            long size = h.optLong("model_size", -1);
            if (size > 0 && z.length() != size) throw new IOException("받은 파일 크기가 달라요 (" + z.length() + ")");
            String want = h.optString("model_sha256", "");
            String got = sha256(z);
            if (want.isEmpty() || !want.equalsIgnoreCase(got)) throw new IOException("받은 파일이 예상과 달라요 (SHA-256)");
            unpack(c, z, want);
            lastError = "";
            return true;
        } catch (Exception e) {
            lastError = "모델을 풀지 못했어요: " + e.getMessage();
            return false;
        } finally {
            installing = null;
            if (z.exists()) z.delete();
            prefs(c).edit().remove("dl_id").apply();
        }
    }

    private static void unpack(Context c, File zip, String sha) throws IOException {
        String name = dirName(c);
        File root = root(c);
        File tmp = new File(root, "tmp-" + System.currentTimeMillis());
        deleteTree(tmp);
        if (!tmp.mkdirs()) throw new IOException("폴더를 만들지 못했어요");
        String base = tmp.getCanonicalPath() + File.separator;
        long total = 0;
        byte[] buf = new byte[1 << 16];
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String n = e.getName().replace('\\', '/');
                if (!n.startsWith(name + "/") || n.contains("../") || n.startsWith("/")) {
                    throw new IOException("예상하지 못한 항목: " + n);
                }
                File out = new File(tmp, n);
                if (!out.getCanonicalPath().startsWith(base)) throw new IOException("폴더 밖 항목: " + n);
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        total += r;
                        if (total > MAX_UNPACKED) throw new IOException("압축이 너무 커요");
                        o.write(buf, 0, r);
                    }
                }
                installing = String.format(Locale.ROOT, "모델 푸는 중… %dMB", total / 1048576);
            }
        } catch (IOException e) {
            deleteTree(tmp);
            throw e;
        }
        File unpacked = new File(tmp, name);
        if (!new File(unpacked, "am/final.mdl").isFile()) {
            deleteTree(tmp);
            throw new IOException("모델 파일이 없어요");
        }
        File dest = dir(c);
        deleteTree(dest);
        if (!unpacked.renameTo(dest)) {
            deleteTree(tmp);
            throw new IOException("모델 폴더를 옮기지 못했어요");
        }
        deleteTree(tmp);
        try (OutputStream o = new FileOutputStream(new File(dest, ".ok"))) {
            o.write(sha.getBytes("UTF-8"));
        }
    }

    static void delete(Context c) {
        cancelDownload(c);
        deleteTree(root(c));
    }

    /** 모델이 차지한 크기 (MB). */
    static long usedMb(Context c) {
        return treeSize(root(c)) / 1048576;
    }

    private static long treeSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long s = 0;
        File[] k = f.listFiles();
        if (k != null) for (File x : k) s += treeSize(x);
        return s;
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] k = f.listFiles();
        if (k != null) for (File x : k) deleteTree(x);
        f.delete();
    }

    static long free(File where) {
        try {
            return new StatFs(where.getPath()).getAvailableBytes();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[1 << 16];
            int r;
            while ((r = in.read(b)) > 0) md.update(b, 0, r);
            StringBuilder s = new StringBuilder();
            for (byte x : md.digest()) s.append(String.format(Locale.ROOT, "%02x", x));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 4096)];
            int n = in.read(b);
            return new String(b, 0, Math.max(0, n), "UTF-8");
        }
    }
}
