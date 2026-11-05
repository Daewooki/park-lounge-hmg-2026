package ai.nc.varcospaces;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Offline native AAOS shell. All remote navigation is blocked; QR is the only handoff. */
public final class MainActivity extends Activity {
    private static final String ORIGIN = "appassets.androidplatform.net";
    private static final String CONTACT_EMAIL = "dae9221@naver.com";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private AudioEngine audio;
    private PlaybackService playback;
    private boolean serviceBound;
    private ParkedState vehicle;
    private JSONObject catalog;
    private SharedPreferences preferences;
    private AlertDialog dialog;
    private ImageView dialogQr;
    private boolean licenseDialog;
    private boolean screenVisible;
    private boolean parked;
    private boolean destroyed;
    private long startupStartedAt;
    private boolean startupTraceStarted;
    private String sceneId = "";
    private String language = "ko";
    private String error = "";
    private String playbackError = "", connectionError = "";
    private final PlaybackService.Observer playbackObserver = () -> {
        if (playback == null || destroyed) return;
        sceneId = playback.sceneId();
        playbackError = playback.error();
        sendState();
    };
    private final ServiceConnection playbackConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (destroyed || !(binder instanceof PlaybackService.LocalBinder)) return;
            playback = ((PlaybackService.LocalBinder) binder).getService();
            audio = playback.audio();
            catalog = playback.catalog();
            sceneId = playback.sceneId();
            playback.addObserver(playbackObserver);
            playbackError = playback.error(); connectionError = "";
            sendCatalog();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            playback = null; audio = null;
            playbackError = "";
            connectionError = language.equals("en") ? "Reconnecting audio. Please wait." : "오디오에 다시 연결하고 있어요. 잠시 기다려 주세요.";
            sendState();
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        startupStartedAt = SystemClock.elapsedRealtime();
        preferences = getSharedPreferences("spaces", MODE_PRIVATE);
        language = preferences.getString("language", "ko");
        try (InputStream input = getAssets().open("catalog.json")) {
            catalog = new JSONObject(new String(readAll(input), StandardCharsets.UTF_8));
        } catch (Exception e) { catalog = new JSONObject(); error = "콘텐츠 정보를 불러오지 못했어요."; }
        sceneId = SceneCatalog.read(catalog, null).restore(preferences.getString("sceneId", ""));
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(0, 16, 29));
        WebSettings s = web.getSettings();
        applyFixedWebTextScale();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(false);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false); s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false); s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setBlockNetworkLoads(true);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return localAsset(request.getUrl()); }
            @Override public void onPageFinished(WebView view, String url) {
                if (url.equals("https://" + ORIGIN + "/web/index.html")) handler.postDelayed(() -> {
                    sendCatalog(); logViewport();
                    if (!startupTraceStarted && (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                        startupTraceStarted = true; traceModelStartup();
                    }
                }, 100);
            }
        });
        web.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                if (dialog != null && dialog.isShowing()) styleDialog();
                logViewport();
            }
        });
        setContentView(web);
        web.loadUrl("https://" + ORIGIN + "/web/index.html");
        vehicle = new ParkedState(this, handler, "Activity", allowed -> {
            parked = allowed;
            if (!allowed) {
                if (playback != null) playback.interruptForSafety();
                if (dialog != null) dialog.dismiss();
            }
            sendState();
        });
        vehicle.connect();
        Intent playbackIntent = new Intent(this, PlaybackService.class).setAction("ai.nc.varcospaces.BIND_PLAYBACK");
        serviceBound = bindService(playbackIntent, playbackConnection, BIND_AUTO_CREATE);
    }
    private boolean mayInteract() { return screenVisible && parked && playback != null && playback.isAllowed() && !destroyed; }
    private JSONObject findScene(String id) {
        if (id == null) return null;
        JSONArray scenes = catalog == null ? null : catalog.optJSONArray("scenes");
        if (scenes != null) for (int i = 0; i < scenes.length(); i++) {
            JSONObject scene = scenes.optJSONObject(i);
            if (scene != null && id.equals(scene.optString("id"))) return scene;
        }
        return null;
    }
    private void playScene(String id) {
        if (!mayInteract() || findScene(id) == null) return;
        error = "";
        playback.playScene(id);
        sceneId = playback.sceneId();
        sendState();
    }
    private void sendCatalog() {
        if (web == null || destroyed) return;
        web.evaluateJavascript("if(window.loadCatalog)window.loadCatalog(" + catalog.toString() + ");", null);
        sendState();
    }
    private void sendState() {
        if (web == null || destroyed) return;
        if (audio != null && audio.isPlaying() && mayInteract()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            JSONObject state = new JSONObject().put("parked", mayInteract()).put("audioPlaying", audio != null && audio.isPlaying())
                .put("preparing", audio != null && audio.isPreparing()).put("remainingSeconds", audio == null ? 600 : audio.remainingSeconds())
                .put("sceneId", sceneId).put("error", !connectionError.isEmpty() ? connectionError : !error.isEmpty() ? error : playbackError)
                .put("hasSelectedScene", playback != null && playback.hasSelectedScene())
                .put("volumes", audio == null ? new JSONArray() : audio.volumesJson())
                .put("timerMinutes", audio == null ? 10 : audio.getTimer()).put("language", language);
            web.evaluateJavascript("if(window.setNativeState)window.setNativeState(" + state + ");", null);
        } catch (Exception ignored) {}
    }
    private WebResourceResponse localAsset(Uri uri) {
        String path = uri.getPath();
        if (!"https".equals(uri.getScheme()) || !ORIGIN.equals(uri.getHost()) || path == null || !path.startsWith("/")) return blocked();
        path = path.substring(1);
        if (!SafetyPolicy.isPackagedPath(path)) return blocked();
        String type = path.endsWith(".js") ? "application/javascript" : path.endsWith(".css") ? "text/css"
            : path.endsWith(".json") ? "application/json" : path.endsWith(".glb") ? "model/gltf-binary"
            : path.endsWith(".png") ? "image/png" : path.endsWith(".svg") ? "image/svg+xml"
            : path.endsWith(".wav") ? "audio/wav" : "text/html";
        try {
            Map<String,String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            headers.put("X-Content-Type-Options", "nosniff");
            headers.put("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' blob: data:; media-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'");
            return new WebResourceResponse(type, "UTF-8", 200, "OK", headers, getAssets().open(path));
        } catch (Exception e) { return blocked(); }
    }
    private WebResourceResponse blocked() { return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<>(), new ByteArrayInputStream(new byte[0])); }
    private static byte[] readAll(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) >= 0) out.write(buffer, 0, n); return out.toByteArray();
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void applyFixedWebTextScale() {
        if (web == null) return;
        WebSettings settings = web.getSettings();
        // Vehicle controls use the authored CSS sizes, independent of system font scale.
        settings.setTextZoom(100);
        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
    }
    private void fixedTextSize(TextView view, int sizeDp) {
        view.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
    }
    private void logViewport() {
        if (web == null || destroyed || (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        web.evaluateJavascript("(()=>{const controls={};for(const selector of ['.product-wordmark','.scene-copy h1','.layer-label','.timer-options button','#play-button','#about-button','.language-button','.product-shortcut']){"
            + "const element=document.querySelector(selector);if(!element)continue;const rect=element.getBoundingClientRect(),style=getComputedStyle(element);"
            + "controls[selector]={fontSize:style.fontSize,lineHeight:style.lineHeight,width:Math.round(rect.width),height:Math.round(rect.height)};}"
            + "return JSON.stringify({width:innerWidth,height:innerHeight,dpr:devicePixelRatio,controls});})()",
            result -> android.util.Log.d("VarcoViewport", result + " nativeDensity=" + getResources().getDisplayMetrics().density
                + " systemFontScale=" + getResources().getConfiguration().fontScale
                + " textZoom=" + (web == null ? "destroyed" : web.getSettings().getTextZoom())));
    }
    private void traceModelStartup() {
        if (destroyed || web == null || (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        web.evaluateJavascript("window.spaceViewStatus || 'initializing'", result -> {
            if (destroyed) return;
            long elapsed = SystemClock.elapsedRealtime() - startupStartedAt;
            if ("\"ready\"".equals(result) || "\"error\"".equals(result) || elapsed >= 12000) {
                // Debug QA timing only: no identifiers, persistence or network transport.
                android.util.Log.d("VarcoStartup", "modelStatus=" + result + " elapsedMs=" + elapsed);
            } else handler.postDelayed(this::traceModelStartup, 200);
        });
    }
    private TextView dialogTitle(String title) {
        TextView heading = new TextView(this);
        heading.setText(title); heading.setTextColor(Color.WHITE); fixedTextSize(heading, 38);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(dp(28), dp(26), dp(28), dp(12));
        return heading;
    }
    private void styleDialog() {
        if (dialog == null || dialog.getWindow() == null) return;
        int width = web == null ? getResources().getDisplayMetrics().widthPixels : web.getWidth();
        int height = web == null ? getResources().getDisplayMetrics().heightPixels : web.getHeight();
        int dialogWidth = Math.min(dp(680), Math.max(1, width - dp(48)));
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(Color.rgb(7, 29, 45)); surface.setCornerRadius(dp(24));
        surface.setStroke(dp(1), Color.rgb(46, 68, 86));
        dialog.getWindow().setBackgroundDrawable(surface);
        int maxDialogHeight = Math.min(dp(licenseDialog ? 1000 : 900), Math.max(1, height - dp(48)));
        dialog.getWindow().setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT);
        if (dialogQr != null) {
            int size = Math.max(dp(120), Math.min(dp(320), Math.min(dialogWidth - dp(80), maxDialogHeight - dp(480))));
            dialogQr.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        }
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL}) {
            android.widget.Button button = dialog.getButton(which);
            if (button == null) continue;
            fixedTextSize(button, 32); button.setTextColor(Color.WHITE); button.setAllCaps(false);
            button.setMinHeight(dp(104)); button.setMinimumHeight(dp(104)); button.setMinimumWidth(dp(144));
            button.setPadding(dp(24), dp(12), dp(24), dp(12));
            android.view.ViewGroup.LayoutParams params = button.getLayoutParams();
            if (params != null && params.height > 0 && params.height < dp(104)) {
                params.height = dp(104); button.setLayoutParams(params);
            }
        }
        // Measure content at the final width. Short QR dialogs wrap; long documents
        // keep their scrolling content inside the limit without moving Close offscreen.
        android.view.View decor = dialog.getWindow().getDecorView();
        decor.measure(android.view.View.MeasureSpec.makeMeasureSpec(dialogWidth, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(maxDialogHeight, android.view.View.MeasureSpec.AT_MOST));
        dialog.getWindow().setLayout(dialogWidth, Math.min(maxDialogHeight, Math.max(1, decor.getMeasuredHeight())));
        logDialogMetrics();
    }
    private void logDialogMetrics() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        final AlertDialog shown = dialog;
        handler.postDelayed(() -> {
            if (destroyed || shown != dialog || !shown.isShowing() || shown.getWindow() == null) return;
            android.view.View decor = shown.getWindow().getDecorView();
            android.util.Log.d("VarcoDialog", "window=" + decor.getWidth() + "x" + decor.getHeight()
                + " density=" + getResources().getDisplayMetrics().density
                + " systemFontScale=" + getResources().getConfiguration().fontScale);
            logDialogViewMetrics(decor);
        }, 100);
    }
    private void logDialogViewMetrics(android.view.View view) {
        if (view.getVisibility() != android.view.View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            android.graphics.Rect visible = new android.graphics.Rect();
            if (text.getGlobalVisibleRect(visible)) {
                // Sizes only; do not log user-facing text or QR contents.
                android.util.Log.d("VarcoDialog", "type=" + text.getClass().getSimpleName() + " id=" + text.getId()
                    + " textPx=" + text.getTextSize() + " rect=" + text.getWidth() + "x" + text.getHeight()
                    + " visible=" + visible.toShortString());
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) logDialogViewMetrics(group.getChildAt(i));
        }
    }
    private void showQr(String title, String value, String detail) {
        if (!mayInteract()) return;
        if (dialog != null) dialog.dismiss();
        licenseDialog = false;
        try {
            Map<EncodeHintType,Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 4); hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 640, 640, hints);
            Bitmap bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[640 * 640];
            for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) pixels[y * 640 + x] = matrix.get(x,y) ? Color.BLACK : Color.WHITE;
            bitmap.setPixels(pixels, 0, 640, 0, 0, 640, 640);
            LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER); content.setPadding(dp(24), dp(16), dp(24), dp(20));
            dialogQr = new ImageView(this); dialogQr.setImageBitmap(bitmap);
            dialogQr.setContentDescription(title + (language.equals("en") ? " QR code" : " QR 코드"));
            content.addView(dialogQr, new LinearLayout.LayoutParams(dp(320), dp(320)));
            TextView description = new TextView(this); description.setText(detail); fixedTextSize(description, 30);
            description.setTextColor(Color.rgb(231, 239, 245));
            description.setGravity(Gravity.CENTER); description.setPadding(0, dp(16), 0, 0); content.addView(description);
            TextView url = new TextView(this); url.setText(value.startsWith("https:") ? Uri.parse(value).getHost() : CONTACT_EMAIL);
            fixedTextSize(url, 30); url.setTextColor(Color.rgb(171, 192, 208));
            url.setGravity(Gravity.CENTER); url.setPadding(0,dp(8),0,0); content.addView(url);
            ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.addView(content);
            dialog = new AlertDialog.Builder(this).setCustomTitle(dialogTitle(title)).setView(scroll).setPositiveButton(language.equals("en") ? "Close" : "닫기", null).create();
            dialog.show(); styleDialog();
        } catch (Exception e) { error = "QR 코드를 표시하지 못했어요."; sendState(); }
    }
    private void showPrivacy() {
        if (!mayInteract()) return;
        if (dialog != null) dialog.dismiss();
        dialogQr = null;
        licenseDialog = false;
        String message = language.equals("en")
            ? "Only scene, volume, timer and language settings are saved on this device. Gear and vehicle UI restrictions are used to confirm Park, without storing or transmitting them.\n\nThe offline app has no account, personal-data collection or transmission, microphone or location access, or advertising or analytics SDKs.\n\nProduct QR codes open official websites on your phone under their own privacy policies. Help and report emails are sent only when you send them from your phone.\n\nSupport: " + CONTACT_EMAIL
            : "공간·음량·타이머·언어 설정만 기기에 저장합니다. 주차 확인용 차량 기어·화면 제한 값은 저장·전송하지 않습니다.\n\n오프라인 앱으로, 회원가입·개인정보 수집 및 전송·마이크 및 위치 접근·광고 및 분석 SDK가 없습니다.\n\n제품 QR은 휴대폰의 공식 웹사이트로 연결되며 해당 사이트의 개인정보처리방침이 적용됩니다. 문의·신고 메일은 휴대폰에서 직접 보낼 때만 전송됩니다.\n\n문의: " + CONTACT_EMAIL;
        dialog = new AlertDialog.Builder(this).setCustomTitle(dialogTitle(language.equals("en") ? "Privacy & support" : "개인정보 및 문의"))
            .setMessage(message).setNeutralButton(language.equals("en") ? "Content & licenses" : "콘텐츠 및 라이선스", (d, w) -> showLicenses())
            .setPositiveButton(language.equals("en") ? "Close" : "닫기", null).create(); dialog.show();
        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) { fixedTextSize(messageView, 30); messageView.setTextColor(Color.rgb(231, 239, 245)); }
        styleDialog();
    }
    private void showLicenses() {
        if (!mayInteract()) return;
        if (dialog != null) dialog.dismiss();
        dialogQr = null;
        licenseDialog = true;
        boolean english = language.equals("en");
        StringBuilder text = new StringBuilder();
        try {
            for (String name : new String[]{"NOTICE.txt", "THREE-LICENSE.txt", "APACHE-2.0.txt"}) {
                try (InputStream input = getAssets().open("licenses/" + name)) {
                    text.append(new String(readAll(input), StandardCharsets.UTF_8)).append("\n\n");
                }
            }
            LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(24), dp(12), dp(24), dp(24));
            addModelLicenses(content, english);
            addLicenseText(content, english
                ? "Each 3D license applies only to its identified model. These notices do not apply to VARCO Sound audio."
                : "각 3D 라이선스는 함께 명시된 모델에만 적용되며, VARCO Sound 음원에는 적용되지 않습니다.", 30, false);
            addLicenseText(content, english ? "Open-source software" : "오픈소스 소프트웨어", 32, true);
            addLicenseText(content, text.toString(), 30, false);
            ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.addView(content);
            dialog = new AlertDialog.Builder(this).setCustomTitle(dialogTitle(english ? "Content & licenses" : "콘텐츠 및 라이선스")).setView(scroll)
                .setPositiveButton(english ? "Close" : "닫기", null).create(); dialog.show(); styleDialog();
        } catch (Exception e) { error = english ? "Could not load license information." : "라이선스 정보를 불러오지 못했어요."; sendState(); }
    }
    private void addModelLicenses(LinearLayout content, boolean english) {
        JSONArray scenes = catalog.optJSONArray("scenes");
        for (int i = 0; scenes != null && i < scenes.length(); i++) {
            JSONObject scene = scenes.optJSONObject(i);
            if (scene == null || !scene.optBoolean("available", false) || scene.optString("modelUrl").isEmpty()) continue;
            JSONObject info = scene.optJSONObject("modelProvenance");
            if (info == null) continue;
            String title = scene.optString(english ? "titleEn" : "title", scene.optString("id"));
            addLicenseText(content, (english ? "3D model · " : "3D 모델 · ") + title, 32, true);
            addLicenseField(content, english ? "Title" : "제목", info.optString("title", title));
            addLicenseField(content, english ? "Author" : "제작자", info.optString("author"));
            addLicenseField(content, english ? "Generation service · model" : "생성 서비스 · 모델", info.optString("service", info.optString("product")));
            addLicenseField(content, english ? "Source" : "원본 출처", info.optString("sourceUrl"));
            String license = info.optString("license"), link = info.optString("licenseUrl");
            addLicenseField(content, english ? "3D model license" : "3D 모델 라이선스", license + (link.isEmpty() ? "" : "\n" + link));
            addLicenseField(content, english ? "Changes" : "변경사항", info.optString(english ? "localModificationsEn" : "localModificationsKo", info.optString("localModifications")));
        }
    }
    private void addLicenseField(LinearLayout content, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        addLicenseText(content, label, 30, true);
        addLicenseText(content, value, 30, false);
    }
    private void addLicenseText(LinearLayout content, String text, int size, boolean heading) {
        TextView view = new TextView(this); view.setText(text); fixedTextSize(view, size);
        view.setTextColor(heading ? Color.WHITE : Color.rgb(231, 239, 245));
        if (heading) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(heading ? 18 : 6), 0, dp(4)); view.setLineSpacing(dp(3), 1f);
        // Attribution URLs are readable text; this parked-only screen never opens links.
        view.setAutoLinkMask(0); view.setLinksClickable(false); view.setTextIsSelectable(false);
        content.addView(view, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }
    private final class Bridge {
        @JavascriptInterface public void ready() { handler.post(MainActivity.this::sendCatalog); }
        @JavascriptInterface public void play(String id) { handler.post(() -> playScene(id)); }
        @JavascriptInterface public void selectScene(String id) { handler.post(() -> {
            if (!mayInteract()) return;
            error = ""; playback.selectScene(id); sceneId = playback.sceneId(); sendState();
        }); }
        @JavascriptInterface public void pause() { handler.post(() -> { error = ""; if (audio != null) audio.pause(); }); }
        @JavascriptInterface public void setLayerVolume(int index, double value, String expectedSceneId) { handler.post(() -> {
            if (!mayInteract() || index < 0 || index >= 3) return;
            // A delayed WebView gesture must not overwrite another space's saved mix.
            if (!playback.sceneId().equals(expectedSceneId)) { sendState(); return; }
            playback.setVolume(index, value); sendState();
        }); }
        @JavascriptInterface public void setTimer(int minutes) { handler.post(() -> {
            if (!mayInteract() || (minutes != 5 && minutes != 10 && minutes != 15)) return;
            playback.setTimer(minutes);
        }); }
        @JavascriptInterface public void openProduct(String product) { handler.post(() -> {
            if (!mayInteract() || product == null) return;
            if (product.equals("privacy")) { showPrivacy(); return; }
            if (!(product.equals("sound") || product.equals("threeD") || product.equals("api"))) return;
            JSONObject products = catalog.optJSONObject("products"); JSONObject info = products == null ? null : products.optJSONObject(product);
            if (info != null) showQr(info.optString("name"), info.optString("url"), language.equals("en") ? "Scan with your phone to create your own." : "휴대폰으로 스캔하고 직접 만들어 보세요.");
        }); }
        @JavascriptInterface public void reportContent(String id) { handler.post(() -> {
            if (!mayInteract()) return;
            String safeId = findScene(id) == null ? sceneId : id;
            boolean english = language.equals("en");
            JSONObject scene = findScene(safeId);
            String title = scene == null ? safeId : scene.optString(english ? "titleEn" : "title", safeId);
            if (title.isEmpty()) title = safeId;
            String subject = "[Park Lounge] " + (english ? "Help & report / " : "문의·신고 / ") + title;
            String version = english ? "Unknown" : "확인할 수 없음";
            try {
                String installedVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                if (installedVersion != null && !installedVersion.isEmpty()) version = installedVersion;
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
            String body = english
                ? "App version: " + version + "\nScene: " + title + "\nScene ID: " + safeId + "\nMessage: "
                : "앱 버전: " + version + "\n공간: " + title + "\n공간 ID: " + safeId + "\n문의·신고 내용: ";
            String mail = "mailto:" + CONTACT_EMAIL + "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body);
            showQr(english ? "Help & report" : "문의·신고", mail, english ? "Scan, write your message, then send from your email app." : "휴대폰으로 스캔한 뒤 내용을 작성해 보내 주세요.");
        }); }
        @JavascriptInterface public void savePreferences(String json) { handler.post(() -> {
            if (!mayInteract() || json == null || json.length() > 2048) return;
            try {
                JSONObject p = new JSONObject(json); SharedPreferences.Editor editor = preferences.edit();
                String lang = p.optString("language", language); if (lang.equals("ko") || lang.equals("en")) { language = lang; editor.putString("language", lang); }
                // Scene selection and mix updates use dedicated bridge calls. A stale
                // web snapshot must never replace a newly restored per-scene mix.
                editor.apply(); sendState();
            } catch (Exception ignored) {}
        }); }
    }
    @Override protected void onStart() {
        super.onStart(); screenVisible = true;
        // Pleos can restore a visible split pane to STARTED without RESUMED.
        // Resume WebView with visibility so wake-up never leaves a blank pane.
        if (web != null) { web.onResume(); web.invalidate(); }
        if (vehicle != null) vehicle.refresh();
        sendCatalog();
    }
    @Override protected void onResume() { super.onResume(); if (vehicle != null) vehicle.refresh(); sendState(); }
    @Override protected void onStop() {
        screenVisible = false;
        if (dialog != null) dialog.dismiss();
        sendState();
        if (web != null) web.onPause();
        super.onStop();
    }
    @Override protected void onDestroy() {
        screenVisible = false; destroyed = true;
        if (playback != null) playback.removeObserver(playbackObserver);
        if (serviceBound) { unbindService(playbackConnection); serviceBound = false; }
        playback = null; audio = null;
        if (vehicle != null) vehicle.close(); if (dialog != null) dialog.dismiss();
        handler.removeCallbacksAndMessages(null);
        if (web != null) { web.removeJavascriptInterface("Android"); web.stopLoading(); web.destroy(); web = null; }
        super.onDestroy();
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration); applyFixedWebTextScale(); sendState();
    }
    @Override public void onBackPressed() { if (dialog != null && dialog.isShowing()) { dialog.dismiss(); return; } super.onBackPressed(); }
}
