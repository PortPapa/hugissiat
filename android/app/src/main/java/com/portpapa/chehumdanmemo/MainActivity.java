package com.portpapa.chehumdanmemo;

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
    private static final String REMOTE = "https://portpapa.github.io/chehumdan-memo/";
    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";
    private volatile boolean trusted = false;  // 지금 열린 페이지가 우리 페이지일 때만 브리지 허용
    private boolean fellBack = false;

    private static boolean ours(Uri u) {
        if (u == null || !"https".equals(u.getScheme())) return false;
        String h = u.getHost(), p = u.getPath() == null ? "" : u.getPath();
        return "appassets.androidplatform.net".equals(h)
                || ("portpapa.github.io".equals(h) && p.startsWith("/chehumdan-memo"));
    }

    private void fallback() {
        if (fellBack) return;
        fellBack = true;
        web.loadUrl(HOME);
    }
    private static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";
    private static final int MAX_BODY = 4 * 1024 * 1024;

    private WebView web;
    private String incoming = "";
    private String pendingPlan = "";
    private String pendingAction = "";
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

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
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        // 열 때마다 최신 화면을 받는다 (GitHub Pages 10분 캐시 무시). 오프라인이면 앱 내장 화면으로
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                trusted = ours(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                trusted = ours(Uri.parse(url));
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
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
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
        if (!incoming.isEmpty()) {
            String t = incoming;
            incoming = "";
            web.evaluateJavascript("window.chdIncoming&&window.chdIncoming(" + JSONObject.quote(t) + ")", null);
        }
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
    private static final int REQ_PICK = 41;

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
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 240) s *= 2;
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
            b.compress(Bitmap.CompressFormat.JPEG, 70, out);
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private String photosJson() {
        JSONArray a = new JSONArray();
        for (File f : photoFiles()) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", f.getName());
                o.put("thumb", thumbOf(f));
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
        pool.execute(() -> driveUpload(token, job));
    }

    private static String q(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private JSONObject driveCall(String token, String method, String url, String ctype, byte[] body) throws Exception {
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
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
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

    private void driveCreate(String token, String parent, String name, String mime, byte[] data) throws Exception {
        String bd = "chd" + System.nanoTime();
        JSONObject m = new JSONObject();
        m.put("name", name);
        m.put("parents", new JSONArray().put(parent));
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(("--" + bd + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + m + "\r\n--" + bd
                + "\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        b.write(data);
        b.write(("\r\n--" + bd + "--").getBytes(StandardCharsets.UTF_8));
        driveCall(token, "POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
                "multipart/related; boundary=" + bd, b.toByteArray());
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

    private void driveUpload(String token, String jobJson) {
        try {
            JSONObject job = new JSONObject(jobJson);
            JSONArray path = job.getJSONArray("path");
            String parent = "root";
            for (int i = 0; i < path.length(); i++) {
                String nm = path.getString(i).replaceAll("[\\\\/]", "_").trim();
                if (!nm.isEmpty()) parent = driveFolder(token, nm, parent);
            }
            String mdName = job.optString("mdName", "후기 소스.md").replaceAll("[\\\\/:*?\"<>|]", "_");
            byte[] md = job.optString("md", "").getBytes(StandardCharsets.UTF_8);
            String mdId = driveFind(token, mdName, parent, false);
            if (mdId != null) {
                driveCall(token, "PATCH", "https://www.googleapis.com/upload/drive/v3/files/" + mdId + "?uploadType=media",
                        "text/markdown; charset=UTF-8", md);
            } else {
                driveCreate(token, parent, mdName, "text/markdown; charset=UTF-8", md);
            }
            int added = 0, total = 0;
            for (File f : photoFilesOf(job.optString("key", photoKey))) {
                total++;
                if (driveFind(token, f.getName(), parent, false) != null) continue;
                String ext = f.getName().replaceAll("^.*\\.", "").toLowerCase(java.util.Locale.ROOT);
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                driveCreate(token, parent, f.getName(), mime == null ? "image/jpeg" : mime, readAll(f));
                added++;
            }
            JSONObject o = new JSONObject();
            o.put("folder", parent);
            o.put("url", "https://drive.google.com/drive/folders/" + parent);
            o.put("added", added);
            o.put("photos", total);
            o.put("path", path);
            driveCb(true, o);
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("HTTP 401")) appPrefs().edit().putBoolean("drive", false).apply();
            driveFail(m.contains("HTTP 401") ? "구글 연결이 끊겼어요. 다시 연결해 주세요" : "드라이브에 저장하지 못했어요");
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
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        final ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        runOnUiThread(() -> Toast.makeText(this, "사진 " + uris.size() + "장을 불러오는 중이에요", Toast.LENGTH_SHORT).show());
        pool.execute(() -> {
            int no = nextPhotoNo();
            ContentResolver cr = getContentResolver();
            for (Uri u : uris) {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(cr.getType(u));
                if (ext == null || ext.isEmpty()) ext = "jpg";
                if ("jpeg".equals(ext)) ext = "jpg";
                File out = new File(photoDir(), String.format(java.util.Locale.ROOT, "%02d.%s", no++, ext));
                try (InputStream in = cr.openInputStream(u); OutputStream os = new FileOutputStream(out)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) os.write(b, 0, n);
                } catch (Exception e) {
                    out.delete();
                }
            }
            pushPhotos();
        });
    }

    private void handleBack() {
        web.evaluateJavascript("(window.chdBack&&window.chdBack())?'1':'0'", v -> {
            if (!"\"1\"".equals(v)) finish();
        });
    }

    /* ---------- 말로 남기기: 폰 음성 인식. 말이 끊겨도 1분까지 자동으로 다시 듣는다 ---------- */
    private static final int REQ_MIC = 51, REQ_NOTI = 52;
    private static final long VOICE_MAX = 60_000;
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
        @Override public void onRmsChanged(float v) {}
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
        super.onDestroy();
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
                    i = new Intent(MediaStore.ACTION_PICK_IMAGES);
                    i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, Math.min(30, MediaStore.getPickImagesMaxLimit()));
                } else {
                    i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("image/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try { startActivityForResult(i, REQ_PICK); } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "사진을 고를 수 없어요", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public String getPhotos() {
            if (!trusted) return "[]";
            return photosJson();
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

        /** job = {path:[...], mdName, md, key} */
        @JavascriptInterface
        public void driveSave(String job) {
            if (!trusted || job == null) return;
            runOnUiThread(() -> driveAuth(job));
        }

        /** MD 파일 + 고른 사진을 카톡·메일·드라이브 등으로 한 번에 보낸다 */
        @JavascriptInterface
        public void shareAll(final String name, final String md, final String biz) {
            if (!trusted) return;
            runOnUiThread(() -> {
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
                    for (File f : photoFiles()) uris.add(FileProvider.getUriForFile(MainActivity.this, auth, f));
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
                    startActivity(Intent.createChooser(send, "후기 소스 보내기"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "보내지 못했어요. MD 복사를 써주세요.", Toast.LENGTH_LONG).show();
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

        @JavascriptInterface
        public void saveFile(final String name, final String text) {
            if (!trusted) return;
            pool.execute(() -> {
                String msg;
                try {
                    ContentResolver cr = getContentResolver();
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, "text/markdown");
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
