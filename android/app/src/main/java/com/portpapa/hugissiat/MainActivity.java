package com.portpapa.hugissiat;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.core.content.FileProvider;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import java.net.URLEncoder;
import androidx.webkit.WebViewAssetLoader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 후기씨앗: 페이지(assets/index.html)를 WebView로 띄우고,
 * 네이버 조회·공유 받기·복사·공유·파일 저장만 앱이 맡는다.
 */
public class MainActivity extends Activity {
    /** 화면은 GitHub Pages에서 불러와서 앱을 다시 안 깔아도 자동 반영된다. 인터넷이 안 되면 앱에 넣어둔 화면으로 연다. */
    private static final String REMOTE = "https://portpapa.github.io/hugissiat/";
    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";
    private volatile boolean trusted = false;  // 지금 열린 페이지가 우리 페이지일 때만 브리지 허용
    private boolean fellBack = false;

    private static boolean ours(Uri u) {
        if (u == null || !"https".equals(u.getScheme())) return false;
        String h = u.getHost(), p = u.getPath() == null ? "" : u.getPath();
        return "appassets.androidplatform.net".equals(h)
                || ("portpapa.github.io".equals(h) && p.startsWith("/hugissiat"));
    }

    /* ---------- 빠른 시작: 화면 파일 보관 ---------- */
    private volatile boolean pageServedCache = false;
    private volatile long pageFetchAt = 0;
    private File pageFile() { return new File(getFilesDir(), "page/index.html"); }
    private WebResourceResponse cachedPage(WebResourceRequest req) {
        try {
            if (!req.isForMainFrame() || !"GET".equals(req.getMethod())) return null;
            Uri u = req.getUrl();
            if (!"portpapa.github.io".equals(u.getHost())) return null;
            String p = u.getPath() == null ? "" : u.getPath();
            if (!("/hugissiat/".equals(p) || "/hugissiat".equals(p) || "/hugissiat/index.html".equals(p))) return null;
            if (u.getQuery() != null && u.getQuery().contains("fresh")) { refreshPage(false); return null; }
            File f = pageFile();
            refreshPage(f.isFile());
            if (!f.isFile() || f.length() < 10000) return null;
            pageServedCache = true;
            WebResourceResponse r = new WebResourceResponse("text/html", "utf-8", new FileInputStream(f));
            java.util.HashMap<String, String> h = new java.util.HashMap<>(); h.put("Cache-Control", "no-store");
            r.setResponseHeaders(h);
            return r;
        } catch (Throwable e) { return null; }
    }
    /** 최신 화면을 받아 보관한다. 지금 보관본을 띄웠는데 새 버전이 오면 화면에 알려 준다 (한가할 때 다시 그린다) */
    private void refreshPage(boolean notify) {
        long now = System.currentTimeMillis();
        if (now - pageFetchAt < 60_000) return;
        pageFetchAt = now;
        pool.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(REMOTE + "?t=" + now).openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(20000); c.setUseCaches(false);
                c.setRequestProperty("Cache-Control", "no-cache");
                if (c.getResponseCode() != 200) return;
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); }
                byte[] body = bo.toByteArray();
                String head = new String(body, 0, Math.min(body.length, 4000), StandardCharsets.UTF_8);
                if (body.length < 10000 || !head.toLowerCase(java.util.Locale.ROOT).contains("<html")) return;
                File f = pageFile(); f.getParentFile().mkdirs();
                boolean same = false;
                if (f.isFile() && f.length() == body.length) { byte[] old = java.nio.file.Files.readAllBytes(f.toPath()); same = Arrays.equals(old, body); }
                if (same) return;
                File tmp = new File(f.getParentFile(), "index.part");
                try (OutputStream os = new FileOutputStream(tmp)) { os.write(body); }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
                if (notify && pageServedCache) runOnUiThread(() -> web.evaluateJavascript("window.chdNewVersion&&window.chdNewVersion()", null));
            } catch (Throwable ignored) {}
        });
    }

    private void fallback() {
        if (fellBack) return;
        fellBack = true;
        web.loadUrl(HOME);
    }
    private static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";
    private static final int MAX_BODY = 4 * 1024 * 1024;

    private WebView web;
    /** 웹 화면이 다 뜨면 true → 로딩 화면을 내린다 */
    private volatile boolean ready = false;
    private String incoming = "";
    private String pendingPlan = "";
    private String pendingAction = "";
    private int incomingPhotos = 0;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    /** 드라이브 저장은 한 줄로 차례차례 (화면은 바로 다음 기록을 쓸 수 있게) */
    private final ExecutorService upq = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicInteger upWait = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile String upRid = "", upName = "";
    private static final String UP_CH = "upload";
    private static final int UP_NOTI = 7301;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 앱이 멈추면 원인을 파일로 남겨두고, 다음에 열 때 "오류 보내기"를 물어본다
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            try (java.io.PrintWriter pw = new java.io.PrintWriter(new FileOutputStream(new File(getFilesDir(), "crash.txt")))) {
                pw.println(new java.util.Date() + " / " + Build.MODEL + " / Android " + Build.VERSION.RELEASE);
                ex.printStackTrace(pw);
            } catch (Exception ignored) {}
            if (prev != null) prev.uncaughtException(th, ex);
        });
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        // 앱처럼 보이게: 웹뷰 스크롤바·끝 당김 효과 숨김
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(root);

        // 로딩 화면: 웹 화면이 다 뜰 때까지(최대 5초) 첫 그림을 미룬다 → 시스템 스플래시가 그대로 보임
        final View content = findViewById(android.R.id.content);
        content.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (!ready) return false;
                content.getViewTreeObserver().removeOnPreDrawListener(this);
                getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFFFEFAF3));
                return true;
            }
        });
        new Handler(Looper.getMainLooper()).postDelayed(() -> { ready = true; content.invalidate(); }, 5000);

        // Android 15+ 는 화면 끝까지 그려지므로 상태바·내비바·키보드 만큼 여백을 준다
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/thumbs/", path -> serveLocal(path, true))
                .addPathHandler("/photo/", path -> serveLocal(path, false))
                .build();
        pool.execute(this::shrinkStoredPhotos);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        // 화면(index.html)은 폰에 보관해 둔 것으로 바로 띄우고 새 버전은 뒤에서 받는다 (cachedPage).
        // 글꼴·그림 같은 나머지 파일은 보통 캐시를 쓴다
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                WebResourceResponse pg = cachedPage(req);
                if (pg != null) return pg;
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                trusted = ours(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                trusted = ours(Uri.parse(url));
                if (!ready) view.postDelayed(() -> { ready = true; findViewById(android.R.id.content).invalidate(); }, 150);
                if (hasWindowFocus()) checkClipboard();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, android.webkit.WebResourceError err) {
                if (req.isForMainFrame()) fallback();
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse res) {
                if (req.isForMainFrame() && res.getStatusCode() >= 400) fallback();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (ours(u)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) {
                    // 네이버 지도 앱이 없으면 웹 지도로
                    if ("nmap".equals(u.getScheme())) {
                        String q = u.getQueryParameter("name");
                        if (q == null) q = u.getQueryParameter("query");
                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://map.naver.com/p/search/" + Uri.encode(q == null ? "" : q)))); } catch (Exception ignored) {}
                    }
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "ChdNative");

        handleIntent(getIntent());
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        if (web.getUrl() == null) web.loadUrl(REMOTE);

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        if (!pendingAction.isEmpty()) {
            String a = pendingAction;
            pendingAction = "";
            incoming = "";
            web.evaluateJavascript("window.chdAction&&window.chdAction(" + JSONObject.quote(a) + ")", null);
            return;
        }
        if (!pendingPlan.isEmpty()) {
            String p = pendingPlan;
            pendingPlan = "";
            incoming = "";
            web.evaluateJavascript("window.chdOpenPlan&&window.chdOpenPlan(" + JSONObject.quote(p) + ")", null);
            return;
        }
        if (incomingPhotos > 0) {
            int n = incomingPhotos;
            web.evaluateJavascript("window.chdIncomingPhotos&&window.chdIncomingPhotos(" + n + ")", null);
            return;
        }
        if (!incoming.isEmpty()) {
            String t = incoming;
            incoming = "";
            web.evaluateJavascript("window.chdIncoming&&window.chdIncoming(" + JSONObject.quote(t) + ")", null);
        }
    }

    /** 다른 앱에서 공유받은 사진을 임시 폴더로 복사해 둔다 (권한이 잠깐만 유지되므로 바로 복사) */
    private File incomingDir() {
        File d = new File(getCacheDir(), "incoming");
        d.mkdirs();
        return d;
    }

    private int copyIncoming(ArrayList<Uri> us) {
        File d = incomingDir();
        File[] old = d.listFiles();
        if (old != null) for (File f : old) f.delete();
        int n = 0;
        ContentResolver cr = getContentResolver();
        for (Uri u : us) {
            String ext = extOf(cr, u);
            File out = new File(d, String.format(java.util.Locale.ROOT, "%03d.%s", n, ext));
            try (InputStream in = cr.openInputStream(u); OutputStream os = new FileOutputStream(out)) {
                byte[] b = new byte[65536];
                int k;
                while ((k = in.read(b)) > 0) os.write(b, 0, k);
                n++;
            } catch (Exception e) { out.delete(); }
        }
        return n;
    }

    /** 사진·영상 확장자: MIME으로 정하고, 모르면 영상은 mp4, 사진은 jpg */
    private static String extOf(ContentResolver cr, Uri u) {
        String mime = null;
        try { mime = cr.getType(u); } catch (Exception ignored) {}
        String ext = mime == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (ext == null || ext.isEmpty()) ext = mime != null && mime.startsWith("video/") ? "mp4" : "jpg";
        ext = ext.toLowerCase(java.util.Locale.ROOT);
        return "jpeg".equals(ext) ? "jpg" : ext;
    }

    private static final java.util.regex.Pattern VIDEO_RE = java.util.regex.Pattern.compile(".*\\.(mp4|mov|3gp|3g2|webm|mkv|m4v|avi)$", java.util.regex.Pattern.CASE_INSENSITIVE);
    static boolean isVideo(File f) { return VIDEO_RE.matcher(f.getName()).matches(); }

    /** 영상 첫 장면을 max px 이하 JPEG data URL로. 한 번 만든 건 캐시(vthumb)에 둔다 */
    private String videoData(File f, int max, int quality) {
        File dir = new File(getCacheDir(), "vthumb");
        dir.mkdirs();
        File c = new File(dir, Integer.toHexString((f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified()).hashCode()) + "_" + max + ".jpg");
        try {
            if (!c.exists() || c.length() == 0) {
                Bitmap b = null;
                try { b = ThumbnailUtils.createVideoThumbnail(f, new android.util.Size(max, max), null); } catch (Throwable ignored) {}
                if (b == null) {
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    try { r.setDataSource(f.getAbsolutePath()); b = r.getFrameAtTime(0); } catch (Throwable ignored) {} finally { try { r.release(); } catch (Throwable ignored) {} }
                }
                if (b == null) return "";
                float sc = Math.min(1f, max / (float) Math.max(b.getWidth(), b.getHeight()));
                if (sc < 1f) {
                    Bitmap b2 = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * sc)), Math.max(1, Math.round(b.getHeight() * sc)), true);
                    if (b2 != b) b.recycle();
                    b = b2;
                }
                try (OutputStream os = new FileOutputStream(c)) { b.compress(Bitmap.CompressFormat.JPEG, quality, os); }
                b.recycle();
            }
            return "data:image/jpeg;base64," + Base64.encodeToString(readAll(c), Base64.NO_WRAP);
        } catch (Throwable e) {
            c.delete();
            return "";
        }
    }

    private int nextPhotoNoOf(String key) {
        int max = 0;
        for (File f : photoFilesOf(key)) {
            try { max = Math.max(max, Integer.parseInt(f.getName().replaceAll("\\..*$", ""))); } catch (Exception ignored) {}
        }
        return max + 1;
    }

    /* ---------- 백업·복원: 드라이브 '후기씨앗 백업' 폴더 ---------- */
    private String driveChild(String token, String parent, String name, boolean folder) throws Exception {
        return driveFind(token, name, parent, folder);
    }

    private java.util.HashSet<String> driveNames(String token, String parent) throws Exception {
        java.util.HashSet<String> s = new java.util.HashSet<>();
        String page = null;
        do {
            String q = URLEncoder.encode("'" + parent + "' in parents and trashed = false", "UTF-8");
            JSONObject r = driveCall(token, "GET", "https://www.googleapis.com/drive/v3/files?pageSize=1000&fields=nextPageToken,files(name)&spaces=drive&q=" + q
                    + (page != null ? "&pageToken=" + URLEncoder.encode(page, "UTF-8") : ""), null, null);
            JSONArray a = r.optJSONArray("files");
            if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.getJSONObject(i).optString("name"));
            page = r.optString("nextPageToken", null);
            if (page != null && page.isEmpty()) page = null;
        } while (page != null);
        return s;
    }

    private JSONArray driveList(String token, String parent, boolean folders) throws Exception {
        String q = URLEncoder.encode("'" + parent + "' in parents and trashed = false" + (folders ? " and mimeType = 'application/vnd.google-apps.folder'" : ""), "UTF-8");
        JSONObject r = driveCall(token, "GET", "https://www.googleapis.com/drive/v3/files?pageSize=1000&fields=files(id,name)&spaces=drive&q=" + q, null, null);
        JSONArray a = r.optJSONArray("files");
        return a == null ? new JSONArray() : a;
    }

    private byte[] driveDownload(String token, String id) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://www.googleapis.com/drive/v3/files/" + id + "?alt=media").openConnection();
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        if (c.getResponseCode() >= 400) throw new Exception("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    private void backupEvent(JSONObject o) {
        String js = "window.chdBackup&&window.chdBackup(" + JSONObject.quote(o.toString()) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private void driveBackupRun(String token, JSONObject job) {
        JSONObject o = new JSONObject();
        try {
            o.put("op", "backup");
            final boolean sil = job.optBoolean("silent", false);
            String data0 = job.optString("data", "");
            if (sil && data0.length() < 20) throw new Exception("empty");
            byte[] data = (data0.isEmpty() ? "{}" : data0).getBytes(StandardCharsets.UTF_8);
            File base0 = new File(getFilesDir(), "photos");
            File[] keys0 = base0.listFiles(File::isDirectory);
            long tot = data.length;
            int cnt = 0;
            // 영상은 기록 폴더에만 올리고 백업에는 넣지 않는다 (드라이브 용량 두 배 방지)
            if (keys0 != null) for (File k : keys0) { File[] fs0 = k.listFiles(f -> f.isFile() && !f.getName().startsWith(".") && !isVideo(f)); if (fs0 != null) for (File f : fs0) { tot += f.length(); cnt++; } }
            final long allB = tot;
            final int pn = cnt;
            final long[] done = {0};
            int idx = 0;
            driveProg("backup", sil, "prep", 0, pn, 0, allB, true);
            String root = driveFolder(token, "후기씨앗 백업", "root");
            String id = driveFind(token, "backup.json", root, false);
            if (id != null) driveCall(token, "PATCH", "https://www.googleapis.com/upload/drive/v3/files/" + id + "?uploadType=media", "application/json; charset=UTF-8", data);
            else driveCreate(token, root, "backup.json", "application/json; charset=UTF-8", data);
            done[0] += data.length;
            int up = 0;
            File base = new File(getFilesDir(), "photos");
            File[] keys = base.listFiles(File::isDirectory);
            if (keys != null && keys.length > 0) {
                String pr = driveFolder(token, "photos", root);
                for (File k : keys) {
                    File[] fs = k.listFiles(f -> f.isFile() && !f.getName().startsWith(".") && !isVideo(f));
                    if (fs == null || fs.length == 0) continue;
                    String kf = driveFolder(token, k.getName(), pr);
                    java.util.HashSet<String> have = driveNames(token, kf);
                    for (File f : fs) {
                        idx++;
                        final int ix = idx;
                        driveProg("backup", sil, "photo", ix, pn, done[0], allB, true);
                        if (have.contains(f.getName())) { done[0] += f.length(); continue; }
                        String ext = f.getName().replaceAll("^.*\\.", "").toLowerCase(java.util.Locale.ROOT);
                        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                        final long b0 = done[0];
                        driveCreate(token, kf, f.getName(), mime == null ? "image/jpeg" : mime, readAll(f), n -> driveProg("backup", sil, "photo", ix, pn, b0 + n, allB, false));
                        done[0] += f.length();
                        up++;
                    }
                }
            }
            o.put("ok", true);
            o.put("photos", up);
            o.put("at", System.currentTimeMillis());
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", "백업하지 못했어요"); } catch (Exception ignored) {}
        }
        backupEvent(o);
    }

    private void driveRestoreRun(String token) {
        JSONObject o = new JSONObject();
        try {
            o.put("op", "restore");
            String root = driveFind(token, "후기씨앗 백업", "root", true);
            if (root == null) throw new Exception("none");
            String id = driveFind(token, "backup.json", root, false);
            if (id == null) throw new Exception("none");
            driveProg("restore", false, "prep", 0, 0, 0, 1, true);
            String data = new String(driveDownload(token, id), StandardCharsets.UTF_8);
            int down = 0;
            String pr = driveFind(token, "photos", root, true);
            if (pr != null) {
                java.util.ArrayList<Object[]> todo = new java.util.ArrayList<>();
                JSONArray ks = driveList(token, pr, true);
                for (int i = 0; i < ks.length(); i++) {
                    JSONObject k = ks.getJSONObject(i);
                    File dir = photoDirOf(k.getString("name"));
                    JSONArray fs = driveList(token, k.getString("id"), false);
                    for (int j = 0; j < fs.length(); j++) {
                        JSONObject f = fs.getJSONObject(j);
                        String nm = f.getString("name");
                        if (nm.contains("/") || nm.contains("..")) continue;
                        File out = new File(dir, nm);
                        if (!out.exists()) todo.add(new Object[]{out, f.getString("id")});
                    }
                }
                int n = todo.size();
                for (int i = 0; i < n; i++) {
                    driveProg("restore", false, "photo", i + 1, n, i, n, true);
                    Object[] t = todo.get(i);
                    File dst = (File) t[0], tmp = new File(dst.getPath() + ".part");
                    try {
                        byte[] bytes = driveDownload(token, (String) t[1]);
                        try (OutputStream os = new FileOutputStream(tmp)) { os.write(bytes); }
                        if (tmp.renameTo(dst)) down++; else tmp.delete();
                    } catch (Throwable pe) { tmp.delete(); }
                }
            }
            o.put("ok", true);
            o.put("data", data);
            o.put("photos", down);
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", "none".equals(e.getMessage()) ? "드라이브에 백업이 없어요" : "복원하지 못했어요"); } catch (Exception ignored) {}
        }
        backupEvent(o);
    }

    /** 앱이 화면에 올라올 때마다(포커스) 클립보드의 네이버 지도 링크를 확인한다 */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) web.postDelayed(this::checkClipboard, 200);
    }

    private void checkClipboard() {
        if (web != null) web.evaluateJavascript("window.chdFocus&&window.chdFocus()", null);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getStringExtra("action") != null) {
            pendingAction = intent.getStringExtra("action");
            intent.removeExtra("action");
            return;
        }
        if (intent != null && intent.getStringExtra("plan") != null) {
            pendingPlan = intent.getStringExtra("plan");
            intent.removeExtra("plan");
            return;
        }
        String type = intent == null ? null : intent.getType();
        boolean media = type != null && (type.startsWith("image/") || type.startsWith("video/") || "*/*".equals(type));
        if (intent != null && media
                && (Intent.ACTION_SEND.equals(intent.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))) {
            ArrayList<Uri> us = new ArrayList<>();
            if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
                ArrayList<Uri> l = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (l != null) us.addAll(l);
            } else {
                Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) us.add(u);
            }
            // 사진·영상이 많으면 복사가 오래 걸려 화면이 멈추므로 따로 돌린다. 다 받은 뒤 화면에 알린다.
            if (us.isEmpty() && "*/*".equals(type)) {
                CharSequence t0 = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (t0 != null) incoming = t0.toString();
                return;
            }
            intent.setAction(null);
            incomingPhotos = 0;
            if (us.isEmpty()) return;
            if (us.size() >= 10) Toast.makeText(this, "사진·영상 " + us.size() + "개를 받는 중이에요", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                int n = copyIncoming(us);
                runOnUiThread(() -> {
                    incomingPhotos = n;
                    if (n > 0 && web != null)
                        web.evaluateJavascript("window.chdBooted&&window.chdIncomingPhotos&&window.chdIncomingPhotos(" + n + ")", null);
                });
            }).start();
            return;
        }
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            CharSequence t = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null) incoming = t.toString();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    /* ---------- 사진: 고른 사진을 캐시에 01.jpg, 02.jpg…로 복사해 두고 보내기 때 같이 보낸다 ---------- */
    private static final int REQ_PICK = 41, REQ_GUIDE = 42;
    private volatile String guideKey = "_";

    /* ---------- 캠페인 가이드: PDF·캡처·텍스트 파일. 드라이브 저장 때 '가이드' 폴더로 ---------- */
    private File guideDirOf(String key) {
        File d = new File(getFilesDir(), "guides/" + safeKey(key));
        d.mkdirs();
        return d;
    }
    private File[] guideFilesOf(String key) {
        File[] fs = guideDirOf(key).listFiles(f -> f.isFile() && !f.getName().startsWith("."));
        if (fs == null) return new File[0];
        Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
        return fs;
    }
    private String guidesJson(String key) {
        JSONArray a = new JSONArray();
        for (File f : guideFilesOf(key)) {
            try {
                String n = f.getName(), ext = n.replaceAll("^.*\\.", "").toLowerCase(java.util.Locale.ROOT);
                JSONObject o = new JSONObject();
                o.put("name", n);
                o.put("size", f.length());
                o.put("kind", ext.equals("pdf") ? "pdf" : ext.matches("jpe?g|png|webp|heic|gif") ? "img" : "file");
                if (ext.matches("jpe?g|png|webp|heic|gif")) o.put("thumb", imageData(f, 160, 65));
                a.put(o);
            } catch (Exception ignored) {}
        }
        return a.toString();
    }
    private void pushGuides(String key) {
        String js = "window.chdGuides&&window.chdGuides(" + JSONObject.quote(key) + "," + JSONObject.quote(guidesJson(key)) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }
    private String displayName(Uri u) {
        try (android.database.Cursor c = getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return null;
    }

    /** 가게(플레이스 ID)마다 사진 폴더를 따로 둔다: files/photos/<key>/ */
    private volatile String photoKey = "_";

    private static String safeKey(String k) {
        if (k == null) return "_";
        String s = k.replaceAll("[^0-9A-Za-z_-]", "");
        return s.isEmpty() ? "_" : s;
    }

    private File photoDirOf(String key) {
        File d = new File(getFilesDir(), "photos/" + safeKey(key));
        d.mkdirs();
        return d;
    }

    private File photoDir() {
        return photoDirOf(photoKey);
    }

    private File[] photoFiles() {
        return photoFilesOf(photoKey);
    }

    private File[] photoFilesOf(String key) {
        File[] fs = photoDirOf(key).listFiles(f -> f.isFile() && !f.getName().startsWith("."));
        if (fs == null) return new File[0];
        Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
        return fs;
    }

    private int nextPhotoNo() {
        int max = 0;
        for (File f : photoFiles()) {
            try { max = Math.max(max, Integer.parseInt(f.getName().replaceAll("\\..*$", ""))); } catch (Exception ignored) {}
        }
        return max + 1;
    }

    private String thumbOf(File f) {
        return isVideo(f) ? videoData(f, 240, 70) : imageData(f, 240, 70);
    }

    /** 사진을 max px 이하로 줄여 data URL로 (크게 보기용은 1600) */
    private String imageData(File f, int max, int quality) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b == null) return "";
            int rot = 0;
            try {
                int ori = new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                rot = ori == 6 ? 90 : ori == 3 ? 180 : ori == 8 ? 270 : 0;
            } catch (Exception ignored) {}
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, quality, out);
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /* ---------- 사진은 가볍게: 썸네일은 파일로 한 번만 만들어 두고, 화면은 일반 이미지 주소로 불러온다 ---------- */
    static final String LOCAL = "https://appassets.androidplatform.net";
    private String thumbUrl(String key, File f) {
        try { return LOCAL + "/thumbs/" + safeKey(key) + "/" + URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20") + "?v=" + f.length(); } catch (Exception e) { return ""; }
    }
    private String photoUrl(String key, File f) {
        try { return LOCAL + "/photo/" + safeKey(key) + "/" + URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20") + "?v=" + f.length(); } catch (Exception e) { return ""; }
    }
    private final Object thumbLock = new Object();
    /** /thumbs/KEY/NAME 은 320px 썸네일(없으면 만들어 저장), /photo/KEY/NAME 은 보관 중인 사진 */
    private WebResourceResponse serveLocal(String path, boolean thumb) {
        try {
            String[] p = path.split("/", 2);
            if (p.length < 2) return null;
            String name = java.net.URLDecoder.decode(p[1], "UTF-8");
            if (name.contains("/") || name.contains("..")) return null;
            File src = new File(photoDirOf(p[0]), name);
            if (!src.isFile()) return null;
            if (!thumb) {
                String ext = name.replaceAll("^.*\\.", "").toLowerCase(java.util.Locale.ROOT);
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                return new WebResourceResponse(mime == null ? "application/octet-stream" : mime, null, new FileInputStream(src));
            }
            File dir = new File(getFilesDir(), "thumbs/" + safeKey(p[0]));
            File tf = new File(dir, name + "-" + src.length() + ".jpg");
            if (!tf.isFile()) {
                synchronized (thumbLock) {
                    if (!tf.isFile()) {
                        dir.mkdirs();
                        String du = (isVideo(src) && !isFreed(src)) ? videoData(src, 320, 72) : imageData(src, 320, 72);
                        int c = du.indexOf(',');
                        if (c < 0) return null;
                        File tmp = new File(dir, tf.getName() + ".part");
                        try (OutputStream os = new FileOutputStream(tmp)) { os.write(Base64.decode(du.substring(c + 1), Base64.DEFAULT)); }
                        File[] old = dir.listFiles(x -> x.getName().startsWith(name + "-") && !x.getName().endsWith(".part"));
                        if (old != null) for (File o : old) o.delete();
                        tmp.renameTo(tf);
                    }
                }
            }
            WebResourceResponse r = new WebResourceResponse("image/jpeg", null, new FileInputStream(tf));
            java.util.HashMap<String, String> h = new java.util.HashMap<>(); h.put("Cache-Control", "max-age=31536000");
            r.setResponseHeaders(h);
            return r;
        } catch (Throwable e) { return null; }
    }
    /** 들여온 사진을 2048px JPEG로 줄여 보관 (블로그와 드라이브엔 이 크기면 충분). 영상과 작은 사진은 그대로 */
    private File shrinkPhoto(File f, boolean allowRename) {
        try {
            if (isVideo(f)) return f;
            String ext = f.getName().replaceAll("^.*\\.", "").toLowerCase(java.util.Locale.ROOT);
            boolean jpg = ext.equals("jpg") || ext.equals("jpeg");
            if (!jpg && !allowRename) return f;
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0) return f;
            if (Math.max(o.outWidth, o.outHeight) <= UP_MAX && (jpg || f.length() < 400_000) && f.length() < 1_600_000) return f;
            Object[] r = upPhoto(f);
            if (r == null) return f;
            byte[] data = (byte[]) r[0];
            if (data.length >= f.length() && jpg) return f;
            File out = new File(f.getParentFile(), jpg ? f.getName() : f.getName().replaceAll("\\.[^.]+$", "") + ".jpg");
            File tmp = new File(f.getParentFile(), "." + out.getName() + ".part");
            try (OutputStream os = new FileOutputStream(tmp)) { os.write(data); }
            if (!jpg) f.delete();
            if (tmp.renameTo(out)) return out;
            tmp.delete();
        } catch (Throwable ignored) {}
        return f;
    }
    /** 예전에 원본 그대로 받아 둔 큰 사진을 한 번 줄여 둔다 (JPEG만, 이름 그대로) */
    private void shrinkStoredPhotos() {
        try {
            if (appPrefs().getBoolean("shrunk1", false)) return;
            File[] ks = new File(getFilesDir(), "photos").listFiles(File::isDirectory);
            if (ks != null) for (File k : ks) { File[] fs = k.listFiles(File::isFile); if (fs != null) for (File f : fs) if (f.length() > 1_600_000) shrinkPhoto(f, false); }
            appPrefs().edit().putBoolean("shrunk1", true).apply();
        } catch (Throwable ignored) {}
    }

    /* ---------- 저장 공간 정리: 드라이브에 올라간 사진은 작은 미리보기만 남긴다 (.freed = 이름\t크기) ---------- */
    private java.util.HashMap<String, Long> freedOf(File dir) {
        java.util.HashMap<String, Long> m = new java.util.HashMap<>();
        try { File f = new File(dir, ".freed"); if (!f.isFile()) return m;
            for (String ln : new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) { String[] p = ln.split("\t"); if (p.length == 2) try { m.put(p[0], Long.parseLong(p[1].trim())); } catch (Exception ignored) {} }
        } catch (Exception ignored) {}
        return m;
    }
    private void saveFreed(File dir, java.util.HashMap<String, Long> m) {
        try { StringBuilder b = new StringBuilder(); for (java.util.Map.Entry<String, Long> e : m.entrySet()) b.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            File f = new File(dir, ".freed"); if (m.isEmpty()) { f.delete(); return; }
            try (OutputStream os = new FileOutputStream(f)) { os.write(b.toString().getBytes(StandardCharsets.UTF_8)); } } catch (Exception ignored) {}
    }
    private boolean isFreed(File f) { Long n = freedOf(f.getParentFile()).get(f.getName()); return n != null && n == f.length(); }

    private String photosJson() {
        JSONArray a = new JSONArray();
        final java.util.HashMap<String, Long> fr = freedOf(photoDir());
        for (File f : photoFiles()) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", f.getName());
                o.put("thumb", thumbUrl(photoKey, f));
                if (isVideo(f)) o.put("video", true);
                if (fr.containsKey(f.getName()) && fr.get(f.getName()) == f.length()) o.put("freed", true);
                a.put(o);
            } catch (Exception ignored) {}
        }
        return a.toString();
    }

    private void pushPhotos() {
        pool.execute(() -> {
            String js = "window.chdPhotos&&window.chdPhotos(" + JSONObject.quote(photosJson()) + ")";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        });
    }

    /* ---------- 구글 드라이브: 후기씨앗/2026년 11월/06 가게이름/ 에 MD + 사진 저장 ---------- */
    private static final int REQ_DRIVE = 61;
    private static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file";
    private String pendingDriveJob = null;

    private SharedPreferences appPrefs() {
        return getSharedPreferences("chd", 0);
    }

    private void driveCb(boolean ok, JSONObject o) {
        try { o.put("ok", ok); } catch (Exception ignored) {}
        String js = "window.chdDrive&&window.chdDrive(" + JSONObject.quote(o.toString()) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private void driveFail(String msg) {
        JSONObject o = new JSONObject();
        try { o.put("error", msg); } catch (Exception ignored) {}
        driveCb(false, o);
    }

    private void driveAuth(String job) {
        AuthorizationRequest req = AuthorizationRequest.builder()
                .setRequestedScopes(Arrays.asList(new Scope(DRIVE_SCOPE))).build();
        Identity.getAuthorizationClient(this).authorize(req)
                .addOnSuccessListener(r -> {
                    if (r.hasResolution() && r.getPendingIntent() != null) {
                        if (job != null && job.contains("\"silent\":true")) return; // 자동 백업은 화면 없이만
                        pendingDriveJob = job;
                        try {
                            startIntentSenderForResult(r.getPendingIntent().getIntentSender(), REQ_DRIVE, null, 0, 0, 0, null);
                        } catch (Exception e) {
                            driveFail("구글 연결 화면을 열 수 없어요");
                        }
                    } else {
                        driveToken(r.getAccessToken(), job);
                    }
                })
                .addOnFailureListener(e -> driveFail("구글 연결에 실패했어요"));
    }

    private void driveToken(String token, String job) {
        if (token == null || token.isEmpty()) { driveFail("구글 연결에 실패했어요"); return; }
        appPrefs().edit().putBoolean("drive", true).apply();
        if (job == null) {
            JSONObject o = new JSONObject();
            try { o.put("connected", true); } catch (Exception ignored) {}
            driveCb(true, o);
            return;
        }
        try {
            JSONObject j = new JSONObject(job);
            String op = j.optString("op", "");
            if ("backup".equals(op)) { pool.execute(() -> driveBackupRun(token, j)); return; }
            if ("restore".equals(op)) { pool.execute(() -> driveRestoreRun(token)); return; }
            if ("inbox".equals(op)) { pool.execute(() -> driveInboxRun(token)); return; }
            if ("sharePub".equals(op)) { pool.execute(() -> driveSharePub(token, j)); return; }
            if ("pick".equals(op)) { final String k = "folder".equals(j.optString("kind")) ? "folder" : "shared"; runOnUiThread(() -> openPicker(token, k)); return; }
            if ("sharedOff".equals(op)) { pool.execute(() -> driveSharedOff(token)); return; }
            if ("trash".equals(op)) { upq.execute(() -> driveTrashRun(token, j)); return; }
            if ("inboxDone".equals(op)) { final String fid = j.optString("id", ""); pool.execute(() -> { try { if (!fid.isEmpty()) driveCall(token, "DELETE", "https://www.googleapis.com/drive/v3/files/" + fid, null, null); } catch (Exception ignored) {} }); return; }
        } catch (Exception ignored) {}
        upWait.incrementAndGet();
        upq.execute(() -> { upWait.decrementAndGet(); driveUpload(token, job); });
    }

    /** 사진 한 장이 드라이브에 올라갔다(또는 이미 있었다): 화면 썸네일에 표시 */
    private void upDone(String rid, String name) {
        String js = "window.chdUpDone&&window.chdUpDone(" + JSONObject.quote(rid) + "," + JSONObject.quote(name) + ")";
        runOnUiThread(() -> { try { web.evaluateJavascript(js, null); } catch (Exception ignored) {} });
    }

    /* ---------- 업로드 알림: 앱을 나가도 진행 상황이 보이게 ---------- */
    private android.app.NotificationManager upNm() {
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(UP_CH) == null)
            nm.createNotificationChannel(new android.app.NotificationChannel(UP_CH, "드라이브 업로드", android.app.NotificationManager.IMPORTANCE_LOW));
        return nm;
    }
    private void upNoti(String title, String text, int max, int prog, boolean ongoing) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
            android.app.NotificationManager nm = upNm(); if (nm == null) return;
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 7301, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            android.app.Notification.Builder b = new android.app.Notification.Builder(this, UP_CH).setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title).setContentText(text).setContentIntent(pi).setOnlyAlertOnce(true).setOngoing(ongoing).setAutoCancel(!ongoing);
            if (max > 0) b.setProgress(max, prog, false); else if (ongoing) b.setProgress(0, 0, true);
            nm.notify(UP_NOTI, b.build());
        } catch (Exception ignored) {}
    }

    /** 올릴 사진: 긴 변 2048px·JPEG 88로 줄인다(블로그엔 충분, 용량 1/5~1/10). 이미 작으면 원본 */
    private static final int UP_MAX = 2048;
    private Object[] upPhoto(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int w = o.outWidth, h = o.outHeight;
            boolean jpg = f.getName().toLowerCase(java.util.Locale.ROOT).matches(".*\\.(jpe?g)$");
            if (w <= 0 || h <= 0) return new Object[]{readAll(f), f.getName()};
            if (Math.max(w, h) <= UP_MAX && jpg && f.length() < 1_500_000) return new Object[]{readAll(f), f.getName()};
            int ss = 1;
            while (Math.max(w, h) / (ss * 2) >= UP_MAX) ss *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = ss;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b == null) return new Object[]{readAll(f), f.getName()};
            float sc = Math.min(1f, UP_MAX / (float) Math.max(b.getWidth(), b.getHeight()));
            int rot = 0;
            try {
                int ori = new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                rot = ori == 6 ? 90 : ori == 3 ? 180 : ori == 8 ? 270 : 0;
            } catch (Exception ignored) {}
            if (sc < 1f || rot != 0) {
                Matrix m = new Matrix();
                if (sc < 1f) m.postScale(sc, sc);
                if (rot != 0) m.postRotate(rot);
                Bitmap b2 = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (b2 != b) b.recycle();
                b = b2;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 88, out);
            b.recycle();
            return new Object[]{out.toByteArray(), f.getName().replaceAll("\\.[^.]+$", "") + ".jpg"};
        } catch (Throwable e) {
            try { return new Object[]{readAll(f), f.getName()}; } catch (Exception x) { return null; }
        }
    }

    /** 웹(hugissiat.com)에서 추가한 갈 곳: 드라이브 '후기씨앗/웹에서 추가.json'을 읽어 화면에 넘긴다. 화면이 반영한 뒤 inboxDone으로 지운다 */
    private void driveInboxRun(String token) {
        JSONObject o = new JSONObject();
        try {
            o.put("op", "inbox");
            String root = driveFind(token, "후기씨앗", "root", true);
            String fid = root == null ? null : driveFind(token, "웹에서 추가.json", root, false);
            String cfg = root == null ? null : driveFind(token, "블로그 폴더.json", root, false);
            // 공유받은 기록: 웹에서 고른 파일(공유받은 기록.json의 sources)을 읽어 온다
            String sh = root == null ? null : driveFind(token, "공유받은 기록.json", root, false);
            if (sh != null) {
                try {
                    JSONObject sc = driveCall(token, "GET", "https://www.googleapis.com/drive/v3/files/" + sh + "?alt=media", null, null);
                    JSONArray src = sc.optJSONArray("sources"), out = new JSONArray();
                    if (src != null) for (int i = 0; i < src.length() && i < 5; i++) {
                        JSONObject e = src.optJSONObject(i); if (e == null) continue;
                        JSONObject x = new JSONObject(); x.put("id", e.optString("id")); x.put("label", e.optString("label"));
                        try { x.put("data", driveCall(token, "GET", "https://www.googleapis.com/drive/v3/files/" + e.optString("id") + "?alt=media", null, null)); }
                        catch (Exception ex) { x.put("error", String.valueOf(ex.getMessage()).contains("404") ? "gone" : "fail"); }
                        out.put(x);
                    }
                    o.put("shared", out);
                } catch (Exception ignored) {}
            }
            if (cfg != null) { try { o.put("cfg", driveCall(token, "GET", "https://www.googleapis.com/drive/v3/files/" + cfg + "?alt=media", null, null)); } catch (Exception ignored) {} }
            if (fid != null) {
                JSONObject data = driveCall(token, "GET", "https://www.googleapis.com/drive/v3/files/" + fid + "?alt=media", null, null);
                o.put("id", fid);
                o.put("data", data);
            }
            driveCb(true, o);
        } catch (Exception e) {
            driveCb(false, o);
        }
    }

    /* ---------- 앱 안에서 드라이브 파일 고르기 (0.9.23~): 구글 선택 창을 앱 화면 위에 띄운다.
       구글 규칙상 앱은 사용자가 직접 고른 파일만 읽을 수 있어서, 공유받은 "공유 기록.json"은 이 창에서 한 번 골라야 한다 ---------- */
    private static final String PICK_HOST = "hugissiat.com";
    private View pickLayer;
    private WebView pickWeb;
    private String pickToken = "";

    private void openPicker(String token, String kind) {
        closePicker();
        pickToken = token;
        final float dp = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFFFEFAF3);
        box.setClickable(true);
        FrameLayout bar = new FrameLayout(this);
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("공유받은 기록 연결");
        title.setTextSize(16);
        title.setTextColor(0xFF26302A);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        title.setPadding((int) (18 * dp), 0, 0, 0);
        android.widget.TextView close = new android.widget.TextView(this);
        close.setText("닫기");
        close.setTextSize(15);
        close.setTextColor(0xFF5F6B62);
        close.setGravity(android.view.Gravity.CENTER);
        close.setPadding((int) (18 * dp), 0, (int) (18 * dp), 0);
        close.setOnClickListener(v -> pickCancel());
        bar.addView(title, new FrameLayout.LayoutParams(-1, -1));
        bar.addView(close, new FrameLayout.LayoutParams(-2, -1, android.view.Gravity.END));
        box.addView(bar, new android.widget.LinearLayout.LayoutParams(-1, (int) (52 * dp)));

        WebView w = new WebView(this);
        WebSettings ws = w.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setSupportMultipleWindows(false);
        try { android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(w, true); } catch (Exception ignored) {}
        w.setWebViewClient(new WebViewClient() {
            private boolean handle(Uri u) {
                if (u == null || !"https".equals(u.getScheme()) || !PICK_HOST.equals(u.getHost())) return false;
                String p = u.getPath() == null ? "" : u.getPath();
                if (!p.startsWith("/pick-done")) return false;
                pickDone(u);
                return true;
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (!req.isForMainFrame()) return false; // 구글 선택 창(안쪽 틀)은 그대로 둔다
                Uri u = req.getUrl();
                if (handle(u)) return true;
                String p = u.getPath() == null ? "" : u.getPath();
                boolean pick = "https".equals(u.getScheme()) && PICK_HOST.equals(u.getHost()) && ("/pick".equals(p) || "/pick.html".equals(p));
                return !pick; // 고르기 화면 말고는 이 창에서 열지 않는다
            }
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                try { handle(Uri.parse(url)); } catch (Exception ignored) {}
            }
        });
        box.addView(w, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));
        ((android.view.ViewGroup) web.getParent()).addView(box, new FrameLayout.LayoutParams(-1, -1));
        pickLayer = box;
        pickWeb = w;
        w.loadUrl("https://" + PICK_HOST + "/pick?k=" + kind + "#t=" + Uri.encode(token));
    }

    private void closePicker() {
        final View layer = pickLayer;
        final WebView w = pickWeb;
        pickLayer = null;
        pickWeb = null;
        pickToken = "";
        if (layer == null) return;
        try { ((android.view.ViewGroup) layer.getParent()).removeView(layer); } catch (Exception ignored) {}
        if (w != null) new Handler(Looper.getMainLooper()).post(() -> { try { w.stopLoading(); w.destroy(); } catch (Exception ignored) {} });
    }

    private void pickCancel() {
        if (pickLayer == null) return;
        closePicker();
        JSONObject o = new JSONObject();
        try { o.put("op", "pick"); o.put("cancel", true); } catch (Exception ignored) {}
        driveCb(true, o);
    }

    private void pickDone(Uri u) {
        if (pickLayer == null) return;
        final String token = pickToken;
        final boolean ok = "1".equals(u.getQueryParameter("ok"));
        final String id = u.getQueryParameter("id");
        final boolean viaWeb = "1".equals(u.getQueryParameter("web"));
        if (viaWeb) { // 선택 창이 안 뜨는 폰: 브라우저에서 고르게 한다
            pickCancel();
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://hugissiat.com/settings#shared"))); } catch (Exception ignored) {}
            return;
        }
        if (!ok || id == null || !id.matches("[A-Za-z0-9_-]{10,}")) { pickCancel(); return; }
        closePicker();
        pool.execute(() -> driveSharedLink(token, id));
    }

    /** 후기씨앗/공유받은 기록.json: 내가 보고 있는 공유 기록 파일 목록 (웹 설정과 같은 파일) */
    private void writeSharedCfg(String token, JSONArray sources) throws Exception {
        String root = driveFolder(token, "후기씨앗", "root");
        JSONObject c = new JSONObject();
        c.put("v", 1);
        c.put("sources", sources);
        byte[] b = c.toString().getBytes(StandardCharsets.UTF_8);
        String fid = driveFind(token, "공유받은 기록.json", root, false);
        if (fid != null) driveCall(token, "PATCH", "https://www.googleapis.com/upload/drive/v3/files/" + fid + "?uploadType=media", "application/json; charset=UTF-8", b);
        else driveCreate(token, root, "공유받은 기록.json", "application/json; charset=UTF-8", b);
    }

    /** 고른 "공유 기록.json"을 확인하고 연결한 뒤, 바로 읽어 와 기록 탭에 보여 준다 */
    private void driveSharedLink(String token, String id) {
        JSONObject o = new JSONObject();
        try {
            o.put("op", "pick");
            JSONObject d;
            try { d = driveCall(token, "GET", "https://www.googleapis.com/drive/v3/files/" + id + "?alt=media&supportsAllDrives=true", null, null); }
            catch (Exception e) { o.put("error", "파일을 읽지 못했어요. 다시 골라 주세요"); driveCb(false, o); return; }
            if (d == null || d.optJSONArray("records") == null) { o.put("error", "후기씨앗 공유 기록 파일이 아니에요"); driveCb(false, o); return; }
            String label = d.optString("label", "");
            JSONObject src = new JSONObject();
            src.put("id", id);
            src.put("label", label);
            src.put("at", System.currentTimeMillis());
            writeSharedCfg(token, new JSONArray().put(src)); // 무료: 1명
            o.put("label", label);
            driveCb(true, o);
            driveInboxRun(token);
        } catch (Exception e) {
            try { o.put("error", "연결하지 못했어요. 잠시 뒤 다시 해 주세요"); } catch (Exception ignored) {}
            driveCb(false, o);
        }
    }

    private void driveSharedOff(String token) {
        JSONObject o = new JSONObject();
        try {
            o.put("op", "sharedOff");
            writeSharedCfg(token, new JSONArray());
            driveCb(true, o);
            driveInboxRun(token);
        } catch (Exception e) {
            driveCb(false, o);
        }
    }

    /** 내 기록 공유: 후기씨앗/공유 기록.json 갱신, email이 있으면 후기씨앗 폴더를 보기 권한으로 공유, unshare면 공유 해제 */
    private void driveSharePub(String token, JSONObject j) {
        JSONObject o = new JSONObject();
        try {
            o.put("op", "sharePub");
            String root = driveFolder(token, "후기씨앗", "root");
            String data = j.optString("json", "");
            if (!data.isEmpty()) {
                byte[] b = data.getBytes(StandardCharsets.UTF_8);
                String fid = driveFind(token, "공유 기록.json", root, false);
                if (fid != null) driveCall(token, "PATCH", "https://www.googleapis.com/upload/drive/v3/files/" + fid + "?uploadType=media", "application/json; charset=UTF-8", b);
                else fid = driveCreate(token, root, "공유 기록.json", "application/json; charset=UTF-8", b);
                o.put("fileId", fid);
            }
            String email = j.optString("email", "").trim();
            if (!email.isEmpty()) {
                if (j.optBoolean("unshare", false)) {
                    JSONObject ps = driveCall(token, "GET", "https://www.googleapis.com/drive/v3/files/" + root + "/permissions?fields=permissions(id,emailAddress,role)", null, null);
                    JSONArray a = ps.optJSONArray("permissions");
                    if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject p = a.getJSONObject(i);
                        if (email.equalsIgnoreCase(p.optString("emailAddress")) && !"owner".equals(p.optString("role"))) driveCall(token, "DELETE", "https://www.googleapis.com/drive/v3/files/" + root + "/permissions/" + p.optString("id"), null, null); }
                    o.put("unshared", email);
                } else {
                    JSONObject p = new JSONObject(); p.put("role", "reader"); p.put("type", "user"); p.put("emailAddress", email);
                    String msg = "후기씨앗 기록을 공유했어요. 후기씨앗 앱의 내 정원 > 기록 공유 > 공유받은 기록 연결에서 '공유 기록.json'을 한 번 고르면 기록 탭에서 볼 수 있어요.";
                    driveCall(token, "POST", "https://www.googleapis.com/drive/v3/files/" + root + "/permissions?sendNotificationEmail=true&emailMessage=" + URLEncoder.encode(msg, "UTF-8"), "application/json; charset=UTF-8", p.toString().getBytes(StandardCharsets.UTF_8));
                    o.put("shared", email);
                }
            }
            driveCb(true, o);
        } catch (Exception e) {
            try { o.put("error", String.valueOf(e.getMessage()).contains("HTTP 400") ? "이메일을 확인해 주세요 (구글 계정이어야 해요)" : "공유하지 못했어요"); } catch (Exception ignored) {}
            driveCb(false, o);
        }
    }

    private static String q(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    interface Bytes { void on(long n); }
    private volatile long progT = 0;
    /** 드라이브 작업 진행률을 화면에 알린다 (op: save/backup/restore) */
    private void driveProg(String op, boolean silent, String stage, int i, int n, long done, long total, boolean force) {
        if (silent) return;
        long now = System.currentTimeMillis();
        if (!force && now - progT < 180) return;
        progT = now;
        try {
            JSONObject o = new JSONObject();
            o.put("op", op); o.put("stage", stage); o.put("i", i); o.put("n", n); o.put("done", done); o.put("total", total);
            if ("save".equals(op)) { o.put("rid", upRid); o.put("name", upName); o.put("queue", upWait.get()); }
            String js = "window.chdProgress&&window.chdProgress(" + JSONObject.quote(o.toString()) + ")";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        } catch (Exception ignored) {}
    }

    private JSONObject driveCall(String token, String method, String url, String ctype, byte[] body) throws Exception {
        return driveCall(token, method, url, ctype, body, null);
    }

    private JSONObject driveCall(String token, String method, String url, String ctype, byte[] body, Bytes cb) throws Exception {
        // 공유받은 폴더가 공유 드라이브에 있어도 되게
        if (url.contains("googleapis.com/") && url.contains("/drive/v3/files") && !url.contains("supportsAllDrives")) {
            url += (url.contains("?") ? "&" : "?") + "supportsAllDrives=true";
            if (url.contains("files?") && url.contains("q=")) url += "&includeItemsFromAllDrives=true";
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        if ("PATCH".equals(method)) {
            c.setRequestMethod("POST");
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        } else {
            c.setRequestMethod(method);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", ctype);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) {
                if (cb == null) os.write(body);
                else {
                    int off = 0;
                    while (off < body.length) {
                        int len = Math.min(32768, body.length - off);
                        os.write(body, off, len);
                        off += len;
                        cb.on(off);
                    }
                }
            }
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            in.close();
        }
        String s = out.toString("UTF-8");
        if (code >= 400) throw new Exception("HTTP " + code + " " + s);
        return s.isEmpty() ? new JSONObject() : new JSONObject(s);
    }

    private String driveFind(String token, String name, String parent, boolean folder) throws Exception {
        String qs = "name = '" + q(name) + "' and '" + parent + "' in parents and trashed = false"
                + (folder ? " and mimeType = 'application/vnd.google-apps.folder'" : "");
        JSONObject r = driveCall(token, "GET", "https://www.googleapis.com/drive/v3/files?fields=files(id)&spaces=drive&q="
                + URLEncoder.encode(qs, "UTF-8"), null, null);
        JSONArray a = r.optJSONArray("files");
        return a != null && a.length() > 0 ? a.getJSONObject(0).getString("id") : null;
    }

    private String driveFolder(String token, String name, String parent) throws Exception {
        String id = driveFind(token, name, parent, true);
        if (id != null) return id;
        JSONObject m = new JSONObject();
        m.put("name", name);
        m.put("mimeType", "application/vnd.google-apps.folder");
        m.put("parents", new JSONArray().put(parent));
        return driveCall(token, "POST", "https://www.googleapis.com/drive/v3/files?fields=id",
                "application/json; charset=UTF-8", m.toString().getBytes(StandardCharsets.UTF_8)).getString("id");
    }

    private String driveCreate(String token, String parent, String name, String mime, byte[] data) throws Exception {
        return driveCreate(token, parent, name, mime, data, null);
    }

    private String driveCreate(String token, String parent, String name, String mime, byte[] data, Bytes cb) throws Exception {
        String bd = "chd" + System.nanoTime();
        JSONObject m = new JSONObject();
        m.put("name", name);
        m.put("parents", new JSONArray().put(parent));
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(("--" + bd + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + m + "\r\n--" + bd
                + "\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        b.write(data);
        b.write(("\r\n--" + bd + "--").getBytes(StandardCharsets.UTF_8));
        byte[] all = b.toByteArray();
        final long head = all.length - data.length;
        return driveCall(token, "POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
                "multipart/related; boundary=" + bd, all, cb == null ? null : n -> cb.on(Math.max(0, Math.min(data.length, n - head)))).optString("id", "");
    }

    /** 폴더 안 파일들: 이름 → id (하위 폴더 제외) */
    private java.util.LinkedHashMap<String, String> driveFiles(String token, String parent) throws Exception {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        String page = null;
        do {
            String q = URLEncoder.encode("'" + parent + "' in parents and trashed = false and mimeType != 'application/vnd.google-apps.folder'", "UTF-8");
            JSONObject r = driveCall(token, "GET", "https://www.googleapis.com/drive/v3/files?pageSize=1000&fields=nextPageToken,files(id,name)&spaces=drive&q=" + q
                    + (page != null ? "&pageToken=" + URLEncoder.encode(page, "UTF-8") : ""), null, null);
            JSONArray a = r.optJSONArray("files");
            if (a != null) for (int i = 0; i < a.length(); i++) m.put(a.getJSONObject(i).optString("name"), a.getJSONObject(i).optString("id"));
            page = r.optString("nextPageToken", null);
            if (page != null && page.isEmpty()) page = null;
        } while (page != null);
        return m;
    }
    /** 휴지통으로 (30일 안에 드라이브에서 되살릴 수 있음) */
    private void driveTrash(String token, String id) throws Exception {
        driveCall(token, "PATCH", "https://www.googleapis.com/drive/v3/files/" + id + "?fields=id", "application/json; charset=UTF-8", "{\"trashed\":true}".getBytes(StandardCharsets.UTF_8));
    }
    private void driveRename(String token, String id, String name) throws Exception {
        JSONObject m = new JSONObject(); m.put("name", name);
        driveCall(token, "PATCH", "https://www.googleapis.com/drive/v3/files/" + id + "?fields=id", "application/json; charset=UTF-8", m.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static final java.util.regex.Pattern PHOTO_RE = java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2} .+ \\d{2,3}( \\(\\d+\\))?\\.(jpe?g|png|heic|heif|webp|gif|mp4|mov|3gp|3g2|webm|mkv|m4v|avi)$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 기록 폴더를 휴지통으로 (앱에서 기록을 지울 때 사용자가 고르면) */
    private void driveTrashRun(String token, JSONObject job) {
        JSONObject o = new JSONObject();
        try {
            o.put("op", "trash"); o.put("rid", job.optString("rid", ""));
            String fid = job.optString("folderId", "");
            if (fid.isEmpty()) throw new Exception("no folder");
            driveTrash(token, fid);
            driveCb(true, o);
        } catch (Exception e) { driveCb(false, o); }
    }

    private static String readStream(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream x = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = x.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** 큰 파일(영상)을 이어올리기 방식으로 파일에서 바로 흘려 올린다. 메모리에 통째로 올리지 않는다 */
    private String driveUploadFile(String token, String parent, String name, String mime, File f, Bytes cb) throws Exception {
        JSONObject m = new JSONObject();
        m.put("name", name);
        m.put("parents", new JSONArray().put(parent));
        byte[] meta = m.toString().getBytes(StandardCharsets.UTF_8);
        long len = f.length();
        HttpURLConnection c = (HttpURLConnection) new URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id").openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("X-Upload-Content-Type", mime);
        c.setRequestProperty("X-Upload-Content-Length", String.valueOf(len));
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(meta.length);
        try (OutputStream os = c.getOutputStream()) { os.write(meta); }
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("HTTP " + code + " " + readStream(c.getErrorStream()));
        String loc = c.getHeaderField("Location");
        try { readStream(c.getInputStream()); } catch (Exception ignored) {}
        c.disconnect();
        if (loc == null || loc.isEmpty()) throw new Exception("업로드 주소를 받지 못했어요");
        HttpURLConnection p = (HttpURLConnection) new URL(loc).openConnection();
        p.setConnectTimeout(15000);
        p.setReadTimeout(180000);
        p.setRequestMethod("PUT");
        p.setRequestProperty("Authorization", "Bearer " + token);
        p.setRequestProperty("Content-Type", mime);
        p.setDoOutput(true);
        p.setFixedLengthStreamingMode(len);
        try (InputStream in = new FileInputStream(f); OutputStream os = p.getOutputStream()) {
            byte[] b = new byte[262144];
            int n;
            long off = 0;
            while ((n = in.read(b)) > 0) {
                os.write(b, 0, n);
                off += n;
                if (cb != null) cb.on(off);
            }
        }
        int pc = p.getResponseCode();
        String s = readStream(pc < 400 ? p.getInputStream() : p.getErrorStream());
        if (pc >= 400) throw new Exception("HTTP " + pc + " " + s);
        return s.isEmpty() ? "" : new JSONObject(s).optString("id", "");
    }

    private static byte[] readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    /** 기록 하나를 드라이브와 맞춘다: 폴더(이름·위치), 후기 소스 MD, 사진(새로 올리기·이름 맞추기·빠진 건 휴지통), 가이드 */
    private void driveUpload(String token, String jobJson) {
        String rid = "", name = "";
        try {
            JSONObject job = new JSONObject(jobJson);
            rid = job.optString("rid", job.optString("key", ""));
            name = job.optString("name", "");
            upRid = rid; upName = name;
            final boolean mirror = job.optBoolean("mirror", false);
            JSONArray path = job.getJSONArray("path");
            File[] photos = photoFilesOf(job.optString("key", photoKey));
            byte[] md = job.optString("md", "").getBytes(StandardCharsets.UTF_8);
            final int pn = photos.length;
            final long unit = 1000, allB = (pn + 1) * unit;
            final String nmShow = name.isEmpty() ? "기록" : name;
            final String ridF = rid;
            driveProg("save", false, "prep", 0, pn, 0, allB, true);
            upNoti("드라이브에 올리는 중", nmShow, 0, 0, true);
            // 1) 폴더: 알고 있는 폴더면 이름·위치만 바꾸고, 없으면 경로대로 찾거나 만든다
            //    base가 있으면 (블로그별 공유 폴더) 그 폴더 아래에 만든다
            String base = job.optString("base", "");
            if (!base.isEmpty()) {
                JSONObject bi = driveCall(token, "GET", "https://www.googleapis.com/drive/v3/files/" + base + "?fields=id,trashed,capabilities(canAddChildren)", null, null);
                JSONObject cap = bi.optJSONObject("capabilities");
                if (bi.optBoolean("trashed", false) || (cap != null && !cap.optBoolean("canAddChildren", true))) throw new Exception("공유 폴더에 저장할 권한이 없어요");
            }
            String parent = base.isEmpty() ? "root" : base;
            for (int i = 0; i < path.length() - 1; i++) {
                String nm = path.getString(i).replaceAll("[\\\\/]", "_").trim();
                if (!nm.isEmpty()) parent = driveFolder(token, nm, parent);
            }
            String leaf = path.length() > 0 ? path.getString(path.length() - 1).replaceAll("[\\\\/]", "_").trim() : "기록";
            String folder = null;
            String knownF = job.optString("folderId", "");
            if (!knownF.isEmpty()) {
                try {
                    JSONObject fi = driveCall(token, "GET", "https://www.googleapis.com/drive/v3/files/" + knownF + "?fields=id,name,parents,trashed", null, null);
                    if (!fi.optBoolean("trashed", false)) {
                        folder = knownF;
                        JSONArray ps = fi.optJSONArray("parents");
                        boolean inParent = false; StringBuilder rm = new StringBuilder();
                        if (ps != null) for (int i = 0; i < ps.length(); i++) { String p = ps.optString(i); if (p.equals(parent)) inParent = true; else { if (rm.length() > 0) rm.append(','); rm.append(p); } }
                        if (!leaf.equals(fi.optString("name")) || !inParent) {
                            JSONObject m = new JSONObject(); m.put("name", leaf);
                            String q = "?fields=id" + (inParent ? "" : "&addParents=" + parent + (rm.length() > 0 ? "&removeParents=" + rm : ""));
                            driveCall(token, "PATCH", "https://www.googleapis.com/drive/v3/files/" + folder + q, "application/json; charset=UTF-8", m.toString().getBytes(StandardCharsets.UTF_8));
                        }
                    }
                } catch (Exception ignored) { folder = null; }
            }
            if (folder == null) folder = driveFolder(token, leaf, parent);
            final String folderF = folder;
            final java.util.LinkedHashMap<String, String> list = driveFiles(token, folder);
            // 2) 후기 소스 MD (이름이 바뀌었으면 예전 MD는 휴지통)
            String mdName = job.optString("mdName", "후기 소스.md").replaceAll("[\\\\/:*?\"<>|]", "_");
            driveProg("save", false, "md", 0, pn, 0, allB, true);
            String mdId = list.get(mdName);
            if (mdId != null) driveCall(token, "PATCH", "https://www.googleapis.com/upload/drive/v3/files/" + mdId + "?uploadType=media", "text/markdown; charset=UTF-8", md);
            else driveCreate(token, folder, mdName, "text/markdown; charset=UTF-8", md);
            if (mirror) for (java.util.Map.Entry<String, String> e : list.entrySet())
                if (e.getKey().toLowerCase(java.util.Locale.ROOT).endsWith(".md") && !e.getKey().equals(mdName)) { try { driveTrash(token, e.getValue()); } catch (Exception ignored) {} }
            // 3) 캠페인 가이드
            String gText = job.optString("guideText", "").trim();
            File[] gFiles = guideFilesOf(job.optString("guideKey", job.optString("key", "")));
            boolean guideFail = false;
            try {
                String gf = (gText.isEmpty() && gFiles.length == 0) ? null : driveFolder(token, "가이드", folder);
                if (gf == null && mirror) { String old = driveFind(token, "가이드", folder, true); if (old != null) driveTrash(token, old); }
                if (gf != null) {
                    driveProg("save", false, "guide", 0, pn, unit, allB, true);
                    java.util.LinkedHashMap<String, String> gl = driveFiles(token, gf);
                    if (!gText.isEmpty()) {
                        byte[] gt = gText.getBytes(StandardCharsets.UTF_8);
                        String gid = gl.get("가이드.txt");
                        if (gid != null) driveCall(token, "PATCH", "https://www.googleapis.com/upload/drive/v3/files/" + gid + "?uploadType=media", "text/plain; charset=UTF-8", gt);
                        else driveCreate(token, gf, "가이드.txt", "text/plain; charset=UTF-8", gt);
                    }
                    java.util.HashSet<String> keepG = new java.util.HashSet<>();
                    if (!gText.isEmpty()) keepG.add("가이드.txt");
                    for (File g : gFiles) {
                        keepG.add(g.getName());
                        if (gl.containsKey(g.getName())) continue;
                        if (g.length() > 40L * 1024 * 1024) { guideFail = true; continue; }
                        String ext = g.getName().replaceAll("^.*\\.", "").toLowerCase(java.util.Locale.ROOT);
                        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                        driveCreate(token, gf, g.getName(), mime == null ? "application/octet-stream" : mime, readAll(g));
                    }
                    if (mirror) for (java.util.Map.Entry<String, String> e : gl.entrySet()) if (!keepG.contains(e.getKey())) { try { driveTrash(token, e.getValue()); } catch (Exception ignored) {} }
                }
            } catch (Throwable ge) { guideFail = true; }
            // 4) 사진: 알던 파일은 이름만 맞추고, 없는 것만 올린다
            final String prefix = job.optString("prefix", "").replaceAll("[\\\\/:*?\"<>|]", " ").trim();
            JSONObject known = job.optJSONObject("files");
            final java.util.concurrent.ConcurrentHashMap<String, String> map = new java.util.concurrent.ConcurrentHashMap<>();
            final java.util.HashMap<String, String> byId = new java.util.HashMap<>();
            for (java.util.Map.Entry<String, String> e : list.entrySet()) byId.put(e.getValue(), e.getKey());
            final java.util.concurrent.atomic.AtomicLong doneU = new java.util.concurrent.atomic.AtomicLong(unit);
            final java.util.concurrent.atomic.AtomicInteger fin = new java.util.concurrent.atomic.AtomicInteger(0), added = new java.util.concurrent.atomic.AtomicInteger(0);
            final long[] part = new long[pn];
            final Exception[] err = {null};
            ExecutorService px = Executors.newFixedThreadPool(3);
            java.util.List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
            for (int k = 0; k < pn; k++) {
                final int idx = k; final File f = photos[k];
                final boolean vid = isVideo(f);
                final String orig = (prefix.isEmpty() ? "" : prefix + " ") + f.getName();
                final String want = vid ? orig : (prefix.isEmpty() ? "" : prefix + " ") + f.getName().replaceAll("\\.[^.]+$", "") + ".jpg";
                final String kid = known != null ? known.optString(f.getName(), "") : "";
                fs.add(px.submit(() -> {
                    if (err[0] != null) return;
                    try {
                        String id = null;
                        if (!kid.isEmpty() && byId.containsKey(kid)) { id = kid; if (!want.equals(byId.get(kid))) driveRename(token, kid, want); }
                        else if (list.containsKey(want)) id = list.get(want);
                        else if (list.containsKey(orig)) id = list.get(orig);
                        if (id == null && isFreed(f)) { upDone(ridF, f.getName()); fin.incrementAndGet(); return; } // 정리한 사진: 드라이브에 원본이 없으면 작은 미리보기를 올리지 않는다
                        if (id == null && vid) {
                            // 영상: 줄이지 않고 파일에서 바로 흘려 올린다 (통째로 메모리에 올리면 앱이 죽는다)
                            String ext = f.getName().replaceAll("^.*\\.", "").toLowerCase(java.util.Locale.ROOT);
                            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                            final long len = Math.max(1, f.length());
                            id = driveUploadFile(token, folderF, orig, mime == null ? "video/mp4" : mime, f, n -> {
                                long v = n * unit / len; long d; synchronized (part) { d = v - part[idx]; part[idx] = v; }
                                driveProg("save", false, "photo", fin.get() + 1, pn, doneU.addAndGet(d), allB, false);
                            });
                            added.incrementAndGet();
                        }
                        if (id == null) {
                            Object[] p = upPhoto(f);
                            if (p == null) throw new Exception("사진을 읽지 못했어요");
                            byte[] data = (byte[]) p[0];
                            String ext = String.valueOf(p[1]).replaceAll("^.*\\.", "").toLowerCase(java.util.Locale.ROOT);
                            String up = ext.equals("jpg") ? want : orig;
                            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                            final long len = Math.max(1, data.length);
                            id = driveCreate(token, folderF, up, mime == null ? "image/jpeg" : mime, data, n -> {
                                long v = n * unit / len; long d; synchronized (part) { d = v - part[idx]; part[idx] = v; }
                                driveProg("save", false, "photo", fin.get() + 1, pn, doneU.addAndGet(d), allB, false);
                            });
                            added.incrementAndGet();
                        }
                        if (id != null && !id.isEmpty()) map.put(f.getName(), id);
                        upDone(ridF, f.getName());
                        long d; synchronized (part) { d = unit - part[idx]; part[idx] = unit; }
                        long now = doneU.addAndGet(d);
                        int c = fin.incrementAndGet();
                        driveProg("save", false, "photo", Math.min(pn, c + 1), pn, now, allB, true);
                        upNoti("드라이브에 올리는 중", nmShow + " · 사진 " + c + " / " + pn, pn, c, true);
                    } catch (Throwable e) { err[0] = e instanceof Exception ? (Exception) e : new Exception("사진이 너무 커요", e); }
                }));
            }
            for (java.util.concurrent.Future<?> fu : fs) { try { fu.get(); } catch (java.util.concurrent.ExecutionException ee) { if (err[0] == null) err[0] = new Exception(String.valueOf(ee.getCause()), ee.getCause()); } catch (Exception ignored) {} }
            px.shutdown();
            if (err[0] != null) throw err[0];
            // 5) 앱에서 뺀 사진은 드라이브에서도 휴지통으로 (후기씨앗이 올린 이름 모양만)
            int trashed = 0;
            if (mirror) {
                java.util.HashSet<String> keepIds = new java.util.HashSet<>(map.values());
                for (java.util.Map.Entry<String, String> e : list.entrySet())
                    if (!keepIds.contains(e.getValue()) && PHOTO_RE.matcher(e.getKey()).matches()) { try { driveTrash(token, e.getValue()); trashed++; } catch (Exception ignored) {} }
            }
            driveProg("save", false, "done", pn, pn, allB, allB, true);
            JSONObject o = new JSONObject();
            o.put("rid", rid);
            o.put("name", name);
            o.put("folder", folder);
            o.put("url", "https://drive.google.com/drive/folders/" + folder);
            o.put("added", added.get());
            o.put("trashed", trashed);
            o.put("photos", pn);
            o.put("path", path);
            o.put("files", new JSONObject(map));
            o.put("t0", job.optLong("t0", 0));
            o.put("queue", upWait.get());
            if (guideFail) o.put("guideFail", true);
            if (upWait.get() == 0) upNoti("드라이브에 저장했어요", nmShow + (added.get() > 0 ? " · 사진 " + added.get() + "장" : ""), 0, 0, false);
            driveCb(true, o);
        } catch (Throwable e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("HTTP 401")) appPrefs().edit().putBoolean("drive", false).apply();
            String msg = m.contains("HTTP 401") ? "구글 연결이 끊겼어요. 다시 연결해 주세요" : m.contains("공유 폴더") ? m : (m.contains("HTTP 404") && jobJson.contains("\"base\"")) ? "공유 폴더를 찾을 수 없어요. 웹 설정에서 폴더를 다시 골라 주세요" : "드라이브에 저장하지 못했어요";
            upNoti("드라이브 저장 실패", (name.isEmpty() ? "" : name + " · ") + msg, 0, 0, false);
            JSONObject o = new JSONObject();
            try { o.put("error", msg); o.put("rid", rid); o.put("name", name); o.put("queue", upWait.get()); } catch (Exception ignored) {}
            driveCb(false, o);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_DRIVE) {
            String job = pendingDriveJob;
            pendingDriveJob = null;
            if (res != RESULT_OK) { driveFail("연결을 취소했어요"); return; }
            try {
                AuthorizationResult r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data);
                driveToken(r.getAccessToken(), job);
            } catch (Exception e) {
                driveFail("구글 연결에 실패했어요");
            }
            return;
        }
        if (req == REQ_GUIDE) {
            final String key = "_".equals(guideKey) ? appPrefs().getString("guideKey", "_") : guideKey;
            if (res != RESULT_OK || data == null) return;
            final ArrayList<Uri> gs = new ArrayList<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) gs.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) gs.add(data.getData());
            pool.execute(() -> {
                ContentResolver cr = getContentResolver();
                File dir = guideDirOf(key);
                int no = guideFilesOf(key).length + 1;
                for (Uri u : gs) {
                    String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(cr.getType(u));
                    String nm = displayName(u);
                    if (nm == null || nm.trim().isEmpty()) nm = String.format(java.util.Locale.ROOT, "가이드 %02d.%s", no, ext == null ? "bin" : ext);
                    nm = nm.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                    if (!nm.contains(".") && ext != null) nm = nm + "." + ext;
                    File out = new File(dir, nm);
                    int dot = nm.lastIndexOf('.'); String nb = dot > 0 ? nm.substring(0, dot) : nm, ne = dot > 0 ? nm.substring(dot) : "";
                    for (int k = 2; out.exists(); k++) out = new File(dir, nb + " (" + k + ")" + ne);
                    try (InputStream in = cr.openInputStream(u); OutputStream os = new FileOutputStream(out)) {
                        byte[] b = new byte[65536];
                        int n;
                        while ((n = in.read(b)) > 0) os.write(b, 0, n);
                        no++;
                    } catch (Exception e) { out.delete(); }
                }
                pushGuides(key);
            });
            return;
        }
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        final ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        runOnUiThread(() -> Toast.makeText(this, "사진·영상 " + uris.size() + "개를 불러오는 중이에요", Toast.LENGTH_SHORT).show());
        pool.execute(() -> {
            int no = nextPhotoNo();
            ContentResolver cr = getContentResolver();
            for (Uri u : uris) {
                String ext = extOf(cr, u);
                File out = new File(photoDir(), String.format(java.util.Locale.ROOT, "%02d.%s", no++, ext));
                try (InputStream in = cr.openInputStream(u); OutputStream os = new FileOutputStream(out)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) os.write(b, 0, n);
                } catch (Exception e) {
                    out.delete();
                    continue;
                }
                shrinkPhoto(out, true);
            }
            pushPhotos();
        });
    }

    /** 뒤로가기: 열린 창·질문·탭을 먼저 되돌리고, 홈에서는 2초 안에 한 번 더 눌러야 나간다 (앱은 끄지 않고 뒤로 보냄) */
    private long lastBack = 0;

    private void handleBack() {
        if (pickLayer != null) { pickCancel(); return; }
        web.evaluateJavascript("(window.chdBack&&window.chdBack())?'1':'0'", v -> {
            if ("\"1\"".equals(v)) return;
            long now = System.currentTimeMillis();
            if (now - lastBack < 2000) {
                lastBack = 0;
                moveTaskToBack(true);
            } else {
                lastBack = now;
                Toast.makeText(this, "한 번 더 누르면 나가요", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /* ---------- 말로 남기기: 폰 음성 인식. 말이 끊겨도 5분까지 자동으로 다시 듣는다 ---------- */
    private static final int REQ_MIC = 51, REQ_NOTI = 52;
    private static final long VOICE_MAX = 300_000;
    private long rmsAt = 0;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private boolean voiceOn = false;
    private long voiceStart = 0;

    private void voiceEvent(String type, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("text", text == null ? "" : text);
            String js = "window.chdVoice&&window.chdVoice(" + JSONObject.quote(o.toString()) + ")";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        } catch (Exception ignored) {}
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!voiceOn) return;
            long left = VOICE_MAX - (System.currentTimeMillis() - voiceStart);
            if (left <= 0) { voiceOn = false; if (sr != null) sr.stopListening(); return; }
            voiceEvent("tick", String.valueOf((left + 999) / 1000));
            ui.postDelayed(this, 1000);
        }
    };

    private void beginVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceEvent("error", "이 폰에서는 음성 인식을 쓸 수 없어요. 키보드 마이크를 써주세요.");
            voiceEvent("end", "");
            return;
        }
        if (sr == null) {
            sr = SpeechRecognizer.createSpeechRecognizer(this);
            sr.setRecognitionListener(listener);
        }
        voiceOn = true;
        voiceStart = System.currentTimeMillis();
        listen();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    private void listen() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L);
        try { sr.startListening(i); } catch (Exception e) { voiceOn = false; voiceEvent("end", ""); }
    }

    private boolean canContinue() {
        return voiceOn && System.currentTimeMillis() - voiceStart < VOICE_MAX;
    }

    private void finishVoice() {
        voiceOn = false;
        ui.removeCallbacks(ticker);
        voiceEvent("end", "");
    }

    private static String first(Bundle b) {
        if (b == null) return "";
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (r == null || r.isEmpty()) ? "" : r.get(0);
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle p) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float v) {
            long now = System.currentTimeMillis();
            if (now - rmsAt < 90) return;
            rmsAt = now;
            voiceEvent("rms", String.valueOf(v));
        }
        @Override public void onBufferReceived(byte[] b) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int t, Bundle p) {}
        @Override public void onPartialResults(Bundle b) { voiceEvent("partial", first(b)); }
        @Override public void onResults(Bundle b) {
            voiceEvent("final", first(b));
            if (canContinue()) listen(); else finishVoice();
        }
        @Override public void onError(int e) {
            if (canContinue() && (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) { listen(); return; }
            if (canContinue() && e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) { ui.postDelayed(MainActivity.this::listen, 400); return; }
            if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) voiceEvent("error", "마이크 권한이 필요해요");
            else if (e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) voiceEvent("error", "인터넷 연결을 확인해주세요");
            finishVoice();
        }
    };

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_MIC) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) beginVoice();
            else { voiceEvent("error", "마이크 권한을 허용하면 말로 남길 수 있어요"); voiceEvent("end", ""); }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (voiceOn) { voiceOn = false; if (sr != null) sr.stopListening(); }
    }

    @Override
    protected void onDestroy() {
        if (sr != null) { try { sr.destroy(); } catch (Exception ignored) {} }
        pool.shutdownNow();
        upq.shutdown();
        super.onDestroy();
    }

    private static final String PRODUCT_JS = "(function(){try{var s=window.__PRELOADED_STATE__;var o={};if(s&&s.simpleProductForDetailPage){var p=s.simpleProductForDetailPage.A||s.simpleProductForDetailPage;o.name=p.dispName||p.name;o.brand=(p.naverShoppingSearchInfo&&p.naverShoppingSearchInfo.brandName)||'';o.store=(p.channel&&p.channel.channelName)||'';o.cat=(p.category&&p.category.wholeCategoryName)||'';o.price=p.salePrice||0;o.sale=(p.benefitsView&&p.benefitsView.discountedSalePrice)||0;o.reviews=(p.reviewAmount&&p.reviewAmount.totalReviewCount)||0;o.score=(p.reviewAmount&&p.reviewAmount.averageReviewScore)||0;o.img=[p.representativeImageUrl].concat((p.optionalImageUrls||[]).slice(0,4)).filter(Boolean);o.no=String(p.productNo||p.id||'')}var ld=[].slice.call(document.querySelectorAll('script[type=\"application/ld+json\"]')).map(function(x){try{return JSON.parse(x.textContent)}catch(e){return null}}).filter(function(x){return x&&x['@type']==='Product'})[0];if(ld){o.name=o.name||ld.name;o.brand=o.brand||(ld.brand&&(ld.brand.name||ld.brand))||'';var of=ld.offers&&(ld.offers.price||ld.offers.lowPrice);if(!o.sale&&of)o.sale=Number(of);if(!o.img||!o.img.length)o.img=[].concat(ld.image||[]);if(ld.aggregateRating){o.reviews=o.reviews||Number(ld.aggregateRating.reviewCount||0);o.score=o.score||Number(ld.aggregateRating.ratingValue||0)}o.cat=o.cat||ld.category||''}var og=function(k){var e=document.querySelector('meta[property=\"'+k+'\"]');return e&&e.content};o.name=o.name||og('og:title')||'';if(!o.img||!o.img.length){var im=og('og:image');o.img=im?[im]:[]}if(!o.sale){var t=document.querySelector('.total-price strong,.prod-sale-price .total-price,[class*=final-price]');if(t)o.sale=Number(t.textContent.replace(/[^0-9]/g,''))||0}o.url=location.href;return JSON.stringify(o)}catch(e){return JSON.stringify({error:String(e)})}})()";

    private static boolean shopHost(String url) {
        try {
            String h = new URL(url).getHost().toLowerCase();
            return h.endsWith("smartstore.naver.com") || h.endsWith("brand.naver.com") || h.endsWith("shopping.naver.com")
                    || h.endsWith("coupang.com") || h.equals("coupa.ng");
        } catch (Exception e) {
            return false;
        }
    }

    private void productLoad(final String id, final String url) {
        if (!shopHost(url)) {
            JSONObject o = new JSONObject();
            try { o.put("error", "스마트스토어·쿠팡 링크만 가져올 수 있어요"); } catch (Exception ignored) {}
            callback(id, o);
            return;
        }
        final WebView w = new WebView(this);
        w.getSettings().setJavaScriptEnabled(true);
        w.getSettings().setDomStorageEnabled(true);
        w.setAlpha(0f);
        final FrameLayout root = (FrameLayout) web.getParent();
        root.addView(w, new FrameLayout.LayoutParams(2, 2));
        final boolean[] done = {false};
        final int[] tries = {0};
        final Runnable finish = () -> {
            try { root.removeView(w); w.destroy(); } catch (Exception ignored) {}
        };
        final Runnable timeout = () -> {
            if (done[0]) return;
            done[0] = true;
            JSONObject o = new JSONObject();
            try { o.put("error", "제품 정보를 못 가져왔어요"); } catch (Exception ignored) {}
            callback(id, o);
            finish.run();
        };
        ui.postDelayed(timeout, 20000);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String u) {
                v.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (done[0]) return;
                        v.evaluateJavascript(PRODUCT_JS, res -> {
                            if (done[0]) return;
                            try {
                                String js = new JSONArray("[" + res + "]").getString(0);
                                JSONObject o = new JSONObject(js);
                                if (o.optString("name").isEmpty() && tries[0]++ < 3) { v.postDelayed(this, 1500); return; }
                                done[0] = true;
                                ui.removeCallbacks(timeout);
                                callback(id, o);
                                finish.run();
                            } catch (Exception e) {
                                if (tries[0]++ < 3) v.postDelayed(this, 1500);
                            }
                        });
                    }
                }, 1200);
            }
        });
        w.loadUrl(url);
    }

    private static boolean allowedHost(String url) {
        try {
            URL u = new URL(url);
            if (!"https".equals(u.getProtocol())) return false;
            String h = u.getHost().toLowerCase();
            return h.equals("naver.me") || h.equals("naver.com") || h.endsWith(".naver.com");
        } catch (Exception e) {
            return false;
        }
    }

    private void callback(String id, JSONObject res) {
        String js = "window.__chdNative(" + JSONObject.quote(id) + "," + JSONObject.quote(res.toString()) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    /** 페이지에서 window.ChdNative 로 부르는 함수들 */
    private class Bridge {
        @JavascriptInterface
        public void httpGet(final String id, final String url) {
            if (!trusted) return;
            pool.execute(() -> {
                JSONObject res = new JSONObject();
                HttpURLConnection c = null;
                try {
                    if (!allowedHost(url)) throw new Exception("네이버 링크만 열 수 있어요");
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setInstanceFollowRedirects(true);
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    c.setRequestProperty("User-Agent", UA);
                    c.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9");
                    int code = c.getResponseCode();
                    String finalUrl = c.getURL().toString();
                    if (!allowedHost(finalUrl)) throw new Exception("네이버 밖으로 이동하는 링크예요");
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    if (in != null) {
                        byte[] b = new byte[16384];
                        int n;
                        while ((n = in.read(b)) > 0 && buf.size() < MAX_BODY) buf.write(b, 0, n);
                        in.close();
                    }
                    res.put("status", code);
                    res.put("url", finalUrl);
                    res.put("body", buf.toString(StandardCharsets.UTF_8.name()));
                } catch (Exception e) {
                    try { res.put("error", e.getMessage() == null ? "연결에 실패했어요" : e.getMessage()); } catch (Exception ignored) {}
                } finally {
                    if (c != null) c.disconnect();
                }
                callback(id, res);
            });
        }

        @JavascriptInterface
        public void startVoice() {
            if (!trusted) return;
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    return;
                }
                beginVoice();
            });
        }

        @JavascriptInterface
        public void stopVoice() {
            runOnUiThread(() -> {
                if (voiceOn) { voiceOn = false; ui.removeCallbacks(ticker); if (sr != null) sr.stopListening(); }
                else voiceEvent("end", "");
            });
        }

        /** 다녀온 뒤 알림 예약. id=가게 번호, at=밀리초 */
        @JavascriptInterface
        public void scheduleReminder(String id, String at, String title, String body) {
            if (!trusted || id == null) return;
            try {
                long t = Long.parseLong(at);
                ReminderReceiver.save(MainActivity.this, id, t, title, body);
                ReminderReceiver.schedule(MainActivity.this, id, t, title, body);
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTI));
                }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void cancelReminder(String id) {
            if (!trusted || id == null) return;
            ReminderReceiver.remove(MainActivity.this, id);
            ReminderReceiver.cancel(MainActivity.this, id);
        }

        @JavascriptInterface
        public String takeAction() {
            if (!trusted) return "";
            String a = pendingAction;
            pendingAction = "";
            return a;
        }

        /** 갈 곳·작성 중 개수를 저장하고 홈 화면 위젯을 새로 그린다 */
        @JavascriptInterface
        public void syncWidget(String json) {
            if (!trusted || json == null) return;
            getSharedPreferences(Widgets.PREF, 0).edit().putString("data", json).apply();
            Widgets.updateAll(MainActivity.this);
        }

        @JavascriptInterface
        public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public String takePlan() {
            if (!trusted) return "";
            String p = pendingPlan;
            pendingPlan = "";
            return p;
        }

        @JavascriptInterface
        public String takeIncoming() {
            if (!trusted) return "";
            String t = incoming;
            incoming = "";
            return t;
        }

        @JavascriptInterface
        public void pickPhotos() {
            if (!trusted) return;
            runOnUiThread(() -> {
                Intent i;
                if (Build.VERSION.SDK_INT >= 33) {
                    // 사진과 영상을 함께 보여 준다(타입 지정 없음). 기기가 허용하는 최대치(보통 100개)까지, 고른 순서대로
                    i = new Intent(MediaStore.ACTION_PICK_IMAGES);
                    i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
                    i.putExtra(MediaStore.EXTRA_PICK_IMAGES_IN_ORDER, true);
                } else {
                    i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try { startActivityForResult(i, REQ_PICK); } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "사진을 고를 수 없어요", Toast.LENGTH_SHORT).show();
                }
            });
        }

        /** 가이드 캡처: 사진 선택기로 여러 장 한 번에 (고른 순서대로) */
        @JavascriptInterface
        public void pickGuideImages(String key) {
            if (!trusted) return;
            guideKey = safeKey(key);
            appPrefs().edit().putString("guideKey", guideKey).apply();
            runOnUiThread(() -> {
                Intent i;
                if (Build.VERSION.SDK_INT >= 33) {
                    i = new Intent(MediaStore.ACTION_PICK_IMAGES);
                    i.setType("image/*");
                    i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
                    i.putExtra(MediaStore.EXTRA_PICK_IMAGES_IN_ORDER, true);
                } else {
                    i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("image/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try { startActivityForResult(i, REQ_GUIDE); } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "캡처를 고를 수 없어요", Toast.LENGTH_SHORT).show();
                }
            });
        }

        /** 영상은 폰의 동영상 앱으로 연다 */
        @JavascriptInterface
        public void openVideo(String name) {
            if (!trusted || name == null || name.contains("/") || name.contains("..")) return;
            final File f = new File(photoDir(), name);
            if (!f.exists()) return;
            runOnUiThread(() -> {
                try {
                    Uri u = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                    String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.replaceAll("^.*\\.", "").toLowerCase(java.util.Locale.ROOT));
                    Intent v = new Intent(Intent.ACTION_VIEW);
                    v.setDataAndType(u, mime == null ? "video/*" : mime);
                    v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(v);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "영상을 열 앱이 없어요", Toast.LENGTH_SHORT).show();
                }
            });
        }

        /** 그 기록의 사진 파일 이름만 (썸네일 없이 가볍게) */
        @JavascriptInterface
        public String photoNames(String key) {
            if (!trusted) return "[]";
            JSONArray a = new JSONArray();
            for (File f : photoFilesOf(key)) a.put(f.getName());
            return a.toString();
        }

        @JavascriptInterface
        public void pickGuide(String key) {
            if (!trusted) return;
            guideKey = safeKey(key);
            appPrefs().edit().putString("guideKey", guideKey).apply();
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "image/*", "text/plain"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(i, REQ_GUIDE); } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "파일을 고를 수 없어요", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public String guideFiles(String key) {
            if (!trusted) return "[]";
            return guidesJson(key);
        }

        @JavascriptInterface
        public void removeGuide(String key, String name) {
            if (!trusted || name == null || name.contains("/") || name.contains("..")) return;
            new File(guideDirOf(key), name).delete();
        }

        @JavascriptInterface
        public String getPhotos() {
            if (!trusted) return "[]";
            return photosJson();
        }

        /** 사진 순서 바꾸기: 받은 이름 순서대로 01, 02…로 다시 이름 붙인다 */
        @JavascriptInterface
        public String reorderPhotos(String json) {
            if (!trusted) return photosJson();
            try {
                JSONArray a = new JSONArray(json);
                File d = photoDir();
                java.util.HashMap<String, Long> fr0 = freedOf(d), fr1 = new java.util.HashMap<>();
                ArrayList<File> tmp = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    String n = a.getString(i);
                    if (n.contains("/") || n.contains("..")) continue;
                    File f = new File(d, n);
                    if (!f.exists()) continue;
                    File t = new File(d, ".r" + i + "_" + n);
                    if (f.renameTo(t)) tmp.add(t);
                }
                for (int i = 0; i < tmp.size(); i++) {
                    File t = tmp.get(i);
                    String n = t.getName();
                    String ext = n.substring(n.lastIndexOf('.') + 1);
                    String nn = String.format(java.util.Locale.ROOT, "%02d.%s", i + 1, ext);
                    String on = n.replaceFirst("^\\.r\\d+_", "");
                    if (fr0.containsKey(on)) fr1.put(nn, fr0.get(on));
                    t.renameTo(new File(d, nn));
                }
                saveFreed(d, fr1);
            } catch (Exception ignored) {}
            return photosJson();
        }

        @JavascriptInterface
        public String photoFull(String name) {
            if (!trusted || name == null || name.contains("/") || name.contains("..")) return "";
            File f = new File(photoDir(), name);
            if (f.exists() && isFreed(f)) return "freed";
            return f.exists() ? (isVideo(f) ? videoData(f, 1280, 85) : photoUrl(photoKey, f)) : "";
        }

        @JavascriptInterface
        public void removePhoto(String name) {
            if (!trusted || name == null || name.contains("/") || name.contains("..")) return;
            new File(photoDir(), name).delete();
        }

        @JavascriptInterface
        public void clearPhotos() {
            if (!trusted) return;
            for (File f : photoFiles()) f.delete();
        }

        /** 지금 쓰는 가게를 알려주면 그 가게 사진 폴더로 바꾼다 */
        @JavascriptInterface
        public void setPhotoKey(String key) {
            if (!trusted) return;
            photoKey = safeKey(key);
        }

        /** 기록을 지울 때 그 가게 사진도 지운다 */
        @JavascriptInterface
        public void deletePhotos(String key) {
            if (!trusted) return;
            File d = photoDirOf(key);
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            d.delete();
            File g = guideDirOf(key); File[] gs = g.listFiles(); if (gs != null) for (File f : gs) f.delete(); g.delete(); File th = new File(getFilesDir(), "thumbs/" + safeKey(key)); File[] ts = th.listFiles(); if (ts != null) for (File f : ts) f.delete(); th.delete();
        }

        @JavascriptInterface
        public boolean driveLinked() {
            return trusted && appPrefs().getBoolean("drive", false);
        }

        @JavascriptInterface
        public void driveConnect() {
            if (!trusted) return;
            runOnUiThread(() -> driveAuth(null));
        }

        @JavascriptInterface
        public void driveUnlink() {
            if (!trusted) return;
            appPrefs().edit().putBoolean("drive", false).apply();
        }

        /** 제품 링크(스마트스토어·쿠팡): 안 보이는 웹뷰로 상품 페이지를 열어 이름·가격·사진을 읽는다 */
        @JavascriptInterface
        public void loadProduct(final String id, final String url) {
            if (!trusted) return;
            runOnUiThread(() -> productLoad(id, url));
        }

        /** 공유받은 사진 개수 (앱이 처음 열릴 때 확인) */
        @JavascriptInterface
        public int incomingPhotoCount() {
            return trusted ? incomingPhotos : 0;
        }

        /** 공유받은 사진을 그 가게 사진 폴더로 옮긴다 */
        @JavascriptInterface
        public int importShared(String key) {
            if (!trusted) return 0;
            File[] fs = incomingDir().listFiles(File::isFile);
            incomingPhotos = 0;
            if (fs == null) return 0;
            Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
            int no = nextPhotoNoOf(key), n = 0;
            File dir = photoDirOf(key);
            for (File f : fs) {
                String ext = f.getName().replaceAll("^.*\\.", "");
                File dst = new File(dir, String.format(java.util.Locale.ROOT, "%02d.%s", no++, ext));
                if (f.renameTo(dst)) { n++; shrinkPhoto(dst, true); }
            }
            if (safeKey(key).equals(photoKey)) pushPhotos();
            return n;
        }

        @JavascriptInterface
        public void dropShared() {
            if (!trusted) return;
            incomingPhotos = 0;
            File[] fs = incomingDir().listFiles();
            if (fs != null) for (File f : fs) f.delete();
        }

        /** 백업: data = 기록 JSON. silent면 연결 화면 없이 조용히 */
        @JavascriptInterface
        public void driveBackup(String data, boolean silent) {
            if (!trusted) return;
            try {
                JSONObject j = new JSONObject();
                j.put("op", "backup");
                j.put("data", data);
                j.put("silent", silent);
                String job = j.toString();
                runOnUiThread(() -> driveAuth(job));
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void driveRestore() {
            if (!trusted) return;
            runOnUiThread(() -> driveAuth("{\"op\":\"restore\"}"));
        }

        /** 지난번에 앱이 멈췄으면 그 기록을 한 번 돌려주고 지운다 */
        @JavascriptInterface
        public String lastCrash() {
            if (!trusted) return "";
            File f = new File(getFilesDir(), "crash.txt");
            if (!f.exists()) return "";
            try {
                String s = new String(readAll(f), StandardCharsets.UTF_8);
                f.delete();
                return s.length() > 3000 ? s.substring(0, 3000) : s;
            } catch (Exception e) { return ""; }
        }

        /** 모든 데이터 지우기: 사진, 알림, 위젯, 드라이브 연결 */
        @JavascriptInterface
        public void deleteAll() {
            if (!trusted) return;
            for (String dn : new String[]{"photos", "guides", "thumbs"}) {
                File[] ks = new File(getFilesDir(), dn).listFiles();
                if (ks != null) for (File k : ks) {
                    File[] fs = k.listFiles();
                    if (fs != null) for (File f : fs) f.delete();
                    k.delete();
                }
            }
            try {
                JSONObject all = new JSONObject(getSharedPreferences("reminders", 0).getString("all", "{}"));
                java.util.Iterator<String> it = all.keys();
                while (it.hasNext()) ReminderReceiver.cancel(MainActivity.this, it.next());
            } catch (Exception ignored) {}
            getSharedPreferences("reminders", 0).edit().clear().apply();
            getSharedPreferences(Widgets.PREF, 0).edit().clear().apply();
            appPrefs().edit().clear().apply();
            Widgets.updateAll(MainActivity.this);
        }

        /** job = {path:[...], mdName, md, key} */
        @JavascriptInterface
        public void driveSave(String job) {
            if (!trusted || job == null) return;
            runOnUiThread(() -> driveAuth(job));
        }

        /** 앱 안에서 드라이브 파일 고르기. kind = shared(공유받은 "공유 기록.json") */
        @JavascriptInterface
        public void drivePick(String kind) {
            if (!trusted) return;
            final String k = "folder".equals(kind) ? "folder" : "shared";
            runOnUiThread(() -> driveAuth("{\"op\":\"pick\",\"kind\":\"" + k + "\"}"));
        }

        /** 사진 이름을 '2026-11-06 가게이름 01.jpg'로 바꿔서 보낸다 */
        @JavascriptInterface
        public void shareAll2(final String name, final String md, final String biz, final String prefix) {
            if (!trusted) return;
            shareFiles(name, md, biz, prefix);
        }

        /** MD 파일 + 고른 사진을 카톡·메일·드라이브 등으로 한 번에 보낸다 */
        @JavascriptInterface
        public void shareAll(final String name, final String md, final String biz) {
            if (!trusted) return;
            shareFiles(name, md, biz, "");
        }

        private void shareFiles(final String name, final String md, final String biz, final String prefix) {
            // 영상까지 복사하면 오래 걸리므로 복사는 뒤에서 하고, 공유 창만 화면에서 연다
            pool.execute(() -> {
                try {
                    File dir = new File(getCacheDir(), "share");
                    dir.mkdirs();
                    File[] old = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".md"));
                    if (old != null) for (File f : old) f.delete();
                    String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                    File mdFile = new File(dir, safe);
                    try (OutputStream os = new FileOutputStream(mdFile)) { os.write(md.getBytes(StandardCharsets.UTF_8)); }
                    String auth = getPackageName() + ".files";
                    ArrayList<Uri> uris = new ArrayList<>();
                    uris.add(FileProvider.getUriForFile(MainActivity.this, auth, mdFile));
                    String pre = prefix == null ? "" : prefix.replaceAll("[\\\\/:*?\"<>|]", " ").trim();
                    File out = new File(dir, "out");
                    out.mkdirs();
                    File[] olds = out.listFiles();
                    if (olds != null) for (File f : olds) f.delete();
                    for (File f : photoFiles()) {
                        File g = f;
                        if (!pre.isEmpty()) {
                            g = new File(out, pre + " " + f.getName());
                            try (InputStream in = new FileInputStream(f); OutputStream os = new FileOutputStream(g)) {
                                byte[] buf = new byte[65536];
                                int n;
                                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                            } catch (Exception e) { g = f; }
                        }
                        uris.add(FileProvider.getUriForFile(MainActivity.this, auth, g));
                    }
                    String subject = "후기씨앗" + (biz == null || biz.isEmpty() ? "" : " - " + biz);
                    Intent send;
                    if (uris.size() == 1) {
                        send = new Intent(Intent.ACTION_SEND);
                        send.setType("text/markdown");
                        send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                    } else {
                        send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                        send.setType("*/*");
                        send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    }
                    send.putExtra(Intent.EXTRA_SUBJECT, subject);
                    send.putExtra(Intent.EXTRA_TEXT, md);
                    ClipData clip = ClipData.newRawUri(subject, uris.get(0));
                    for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
                    send.setClipData(clip);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    final Intent chooser = Intent.createChooser(send, "후기 소스 보내기");
                    runOnUiThread(() -> {
                        try { startActivity(chooser); } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "보내지 못했어요. MD 복사를 써주세요.", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "보내지 못했어요. MD 복사를 써주세요.", Toast.LENGTH_LONG).show());
                }
            });
        }

        /** 네이버 지도에서 "링크 복사" 후 앱을 열었을 때를 위해 클립보드를 읽는다 (앱이 앞에 있을 때만 가능) */
        @JavascriptInterface
        public String readClipboard() {
            if (!trusted) return "";
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData d = cm.getPrimaryClip();
                if (d == null || d.getItemCount() == 0) return "";
                CharSequence t = d.getItemAt(0).coerceToText(MainActivity.this);
                return t == null ? "" : t.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void copyText(final String text) {
            if (!trusted) return;
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("후기씨앗", text));
            });
        }

        @JavascriptInterface
        public void shareText(final String text) {
            if (!trusted) return;
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, "후기 소스 보내기"));
            });
        }

        /** 기록별 사진 용량: {"total":바이트,"files":{"01.jpg":바이트},"freed":["03.jpg"]} */
        @JavascriptInterface
        public String photoSizes(String key) {
            JSONObject o = new JSONObject();
            try { long t = 0; JSONObject fsz = new JSONObject(); JSONArray fd = new JSONArray(); File d = photoDirOf(key); java.util.HashMap<String, Long> fr = freedOf(d);
                for (File f : photoFilesOf(key)) { t += f.length(); fsz.put(f.getName(), f.length()); Long n = fr.get(f.getName()); if (n != null && n == f.length()) fd.put(f.getName()); }
                o.put("total", t); o.put("files", fsz); o.put("freed", fd);
            } catch (Exception ignored) {}
            return o.toString();
        }

        /** 저장 공간 정리: 고른 사진(드라이브에 올라간 것)을 640px 미리보기로 바꿔 둔다. 비운 바이트 수를 돌려준다 */
        @JavascriptInterface
        public long freePhotos(String key, String namesJson) {
            if (!trusted || key == null) return 0;
            long saved = 0;
            try {
                File d = photoDirOf(key); java.util.HashMap<String, Long> fr = freedOf(d);
                JSONArray a = new JSONArray(namesJson);
                for (int i = 0; i < a.length(); i++) {
                    String n = a.getString(i); if (n.contains("/") || n.contains("..")) continue;
                    File f = new File(d, n); if (!f.isFile()) continue;
                    Long was = fr.get(n); if (was != null && was == f.length()) continue;
                    String du = isVideo(f) ? videoData(f, 640, 75) : imageData(f, 640, 75);
                    int c = du.indexOf(','); if (c < 0) continue;
                    byte[] small = Base64.decode(du.substring(c + 1), Base64.DEFAULT);
                    if (small.length == 0 || small.length >= f.length()) continue;
                    long before = f.length();
                    File tmp = new File(d, "." + n + ".part");
                    try (OutputStream os = new FileOutputStream(tmp)) { os.write(small); }
                    if (!tmp.renameTo(f)) { tmp.delete(); continue; }
                    fr.put(n, f.length()); saved += before - f.length();
                }
                saveFreed(d, fr);
                if (safeKey(key).equals(photoKey)) pushPhotos();
            } catch (Throwable ignored) {}
            return saved;
        }

        @JavascriptInterface
        public void saveFile(final String name, final String text) {
            if (!trusted) return;
            pool.execute(() -> {
                String msg;
                try {
                    ContentResolver cr = getContentResolver();
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, name.toLowerCase(java.util.Locale.ROOT).endsWith(".csv") ? "text/csv" : "text/markdown");
                    v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/");
                    Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new Exception();
                    try (OutputStream os = cr.openOutputStream(uri)) {
                        os.write(text.getBytes(StandardCharsets.UTF_8));
                    }
                    msg = "다운로드 폴더에 저장했어요: " + name;
                } catch (Exception e) {
                    msg = "저장하지 못했어요. 복사나 공유를 써주세요.";
                }
                final String m = msg;
                runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show());
            });
        }
    }
}
