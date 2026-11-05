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

    private File photoDir() {
        File d = new File(getCacheDir(), "share/photos");
        d.mkdirs();
        return d;
    }

    private File[] photoFiles() {
        File[] fs = photoDir().listFiles(f -> f.isFile() && !f.getName().startsWith("."));
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

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
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

    @Override
    protected void onDestroy() {
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
