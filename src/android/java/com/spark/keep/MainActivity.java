package com.spark.keep;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;

/**
 * 主界面：整块 WebView 渲染 assets/app.html（与桌面版同一份前端）。
 * 退出（Activity 销毁）时只销毁 WebView，后台 SparkService 继续跑。
 */
public class MainActivity extends Activity {

    public static MainActivity current;
    private WebView web;
    private FrameLayout root;
    private Spark spark;
    private static final int REQ_FILE = 4101;
    private static final int REQ_PERM = 4102;
    private ValueCallback<Uri[]> fileCb;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable releaseTask;
    private Bridge bridge;
    private String phoneUA;
    private boolean douyinMode = false;
    /** 状态栏（含刘海区）高度，单位 px；页面靠 --safe-top 让位 */
    private int safeTopPx = 0;
    private final HashMap<String, byte[]> memAvatars = new HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;
        spark = Spark.get(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF050607);
        setContentView(root);

        // 页面调试通道：真机上出现"页面抓不到东西"时可以用 chrome://inspect / devtools 直接看 WebView
        if (Build.VERSION.SDK_INT >= 19) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }

        mountWeb();
        applyInsets();

        // Android 13+ 前台服务的常驻通知需要运行时授权，否则用户看不到"保活中"提示
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
        }

        if (spark.store.all().optBoolean("keep_alive", true)) {
            SparkService.start(this, false);
        }
    }

    private void applyInsets() {
        if (Build.VERSION.SDK_INT < 23) return;
        // 加在最外层容器上，而不是 WebView 上：WebView 自己会消费 insets，
        // 之前挂在 web 上时状态栏一直压在页面顶部的 READY 行上
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, ime = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets kb = insets.getInsets(WindowInsets.Type.ime());
                top = bars.top;
                bottom = bars.bottom;
                ime = kb.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            // 顶部不再用 padding 顶：状态栏设成透明后，把 WebView 整块铺到屏幕顶边，
            // 壁纸就一路铺到状态栏底下，不会留出那条黑边；页面自己按 --safe-top 给标题让位。
            safeTopPx = top;
            // 键盘必须单独让位：setDecorFitsSystemWindows(false) 之后窗口不会为 IME 自己缩，
            // 而 systemBars() 又不含 IME，只按系统栏留白的话输入框会被键盘整块盖住
            v.setPadding(0, 0, 0, Math.max(bottom, ime));
            pushSafeTop(web);
            return insets;
        });
        root.requestApplyInsets();
    }

    /**
     * 状态栏高度写进页面的 --safe-top，页面标题据此下移（WebView 自己是铺满全屏的）。
     * insets 给的是物理像素，页面里 1px 是 CSS px（= dp），必须先按 density 换算，
     * 否则 2.75 倍屏上等于把整页往下推了近 70dp。
     */
    private void pushSafeTop(WebView w) {
        if (w == null) return;
        try {
            float d = getResources().getDisplayMetrics().density;
            if (d <= 0) d = 1f;
            int cssTop = Math.round(safeTopPx / d);
            w.evaluateJavascript(
                    "try{document.documentElement.style.setProperty('--safe-top','" + cssTop + "px');}"
                            + "catch(e){}", null);
        } catch (Exception ignored) {
        }
    }

    // ---------------- 后台页面的可见容器 ----------------
    // 抖音私信页放在一个不可见 WebView 里时，Chrome 会停掉 rAF / 绘制，
    // 会话列表这种懒加载内容可能永远渲染不出来。所以手动读取时把这块 WebView
    // 真挂到界面上（顶部一条提示，下面是真实页面），读完再摘掉。

    private FrameLayout overlay;
    private FrameLayout workHost;
    private TextView workTitle;

    /** 显示工作区（已在主线程调用）。 */
    public void workOpen(String title) {
        if (root == null) return;
        if (overlay == null) {
            overlay = new FrameLayout(this);
            overlay.setBackgroundColor(0xFF050607);

            workTitle = new TextView(this);
            workTitle.setTextColor(0xFF8B9096);
            workTitle.setTextSize(11);
            workTitle.setLetterSpacing(0.06f);
            workTitle.setPadding(dp(16), dp(12), dp(16), dp(12));
            overlay.addView(workTitle, new FrameLayout.LayoutParams(-1, -2));

            workHost = new FrameLayout(this);
            FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-1, -1);
            hl.topMargin = dp(38);
            overlay.addView(workHost, hl);

            root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        }
        // 读会话那块的标题也要避开状态栏（这块浮层同样是全屏铺开的）
        overlay.setPadding(0, safeTopPx, 0, 0);
        if (workTitle != null && title != null) workTitle.setText(title);
        overlay.setVisibility(View.VISIBLE);
    }

    public void attachWork(WebView w) {
        if (workHost == null || w == null) return;
        if (w.getParent() == workHost) return;
        ViewGroup old = (ViewGroup) w.getParent();
        if (old != null) old.removeView(w);
        workHost.addView(w, new FrameLayout.LayoutParams(-1, -1));
        try {
            w.onResume();
        } catch (Exception ignored) {
        }
        w.requestLayout();
    }

    public void detachWork(WebView w) {
        if (w == null) return;
        ViewGroup p = (ViewGroup) w.getParent();
        if (p != null) p.removeView(w);
        try {
            w.onPause();
        } catch (Exception ignored) {
        }
    }

    public void workClose() {
        if (overlay != null) overlay.setVisibility(View.GONE);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private WebView buildWebView() {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);
        s.setUseWideViewPort(false);
        // 让 CSS 的 1px 与 dp 对齐，页面的 rem/px 布局在手机上不会被放大成"平板视图"
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(false);
        w.setBackgroundColor(0xFF050607);
        try {
            phoneUA = s.getUserAgentString();
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        } catch (Exception ignored) {
        }
        bridge = new Bridge(this, spark);
        w.addJavascriptInterface(bridge, "SparkBridge");
        // 没有这个回调，页面里的 <input type="file"> 点了等于没点 —— 壁纸「选择图片」一直没反应就是这个原因
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                fileCb = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("image/*");
                    startActivityForResult(Intent.createChooser(i, "选择图片"), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
            }
        });
        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pushSafeTop(view);
                if (!douyinMode || url == null || !url.contains("douyin.com")) return;
                // 桌面版抖音首页的二维码藏在右上角「登录」里，进页面后替用户点一下
                view.evaluateJavascript(LOGIN_JS, null);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return intercept(req.getUrl().toString());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return intercept(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return handleUrl(req.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }
        });
        return w;
    }

    private boolean handleUrl(String url) {
        if (url == null) return false;
        if (url.startsWith("file:///android_asset")) return false;
        if (url.startsWith("http://spark.local")) return false;
        // 登录过程会经过验证码 / 校验域名，这期间所有 http(s) 都留在 WebView 里，
        // 不然一跳外部浏览器，二维码那一步就断了
        if (douyinMode && (url.startsWith("http://") || url.startsWith("https://"))) return false;
        if (!douyinMode) {
            // 外链不在应用里开
            try {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url));
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {
            }
            return true;
        }
        return false;
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private WebResourceResponse intercept(String url) {
        if (!url.startsWith("http://spark.local")) return null;
        try {
            if (url.contains("/avatar?u=")) {
                String u = url.substring(url.indexOf("u=") + 2);
                u = java.net.URLDecoder.decode(u, "UTF-8");
                byte[] bytes = avatarBytes(u);
                if (bytes == null) return new WebResourceResponse("image/png", "utf-8",
                        new ByteArrayInputStream(new byte[0]));
                return new WebResourceResponse(guessMime(u), "utf-8", new ByteArrayInputStream(bytes));
            }
            if (url.contains("/wallpaper")) {
                File f = new File(spark.store.dir, "wallpaper.img");
                if (!f.exists()) return new WebResourceResponse("image/jpeg", "utf-8", new ByteArrayInputStream(new byte[0]));
                byte[] bytes = Store.readBytes(f);
                return new WebResourceResponse("image/jpeg", "utf-8", new ByteArrayInputStream(bytes));
            }
        } catch (Exception ignored) {
        }
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    private static String guessMime(String u) {
        String s = u.toLowerCase();
        if (s.contains(".webp")) return "image/webp";
        if (s.contains(".png")) return "image/png";
        if (s.contains(".gif")) return "image/gif";
        return "image/jpeg";
    }

    /** 抖音 CDN 有防盗链，必须带 referer 下载；下载后按 url 哈希缓存到本地。 */
    private byte[] avatarBytes(String url) {
        if (url == null || url.isEmpty()) return null;
        byte[] mem = memAvatars.get(url);
        if (mem != null) return mem;
        try {
            String key = md5(url);
            File f = new File(spark.store.avatars, key);
            if (f.exists() && f.length() > 0) {
                byte[] b = Store.readBytes(f);
                memAvatars.put(url, b);
                return b;
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0");
            c.setRequestProperty("Referer", "https://www.douyin.com/");
            c.setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");
            InputStream in = c.getInputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            byte[] bytes = bos.toByteArray();
            if (bytes.length == 0) return null;
            if (f.length() == 0 || f.length() < bytes.length) {
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(bytes);
                fos.close();
            }
            if (memAvatars.size() > 60) memAvatars.clear();
            memAvatars.put(url, bytes);
            return bytes;
        } catch (Exception e) {
            return null;
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] b = d.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    /** 打开抖音登录页（复用同一块 WebView，登录后 Cookie 全局共享给后台）。 */
    public void openDouyinLogin() {
        runOnUiThread(() -> {
            if (web == null) return;
            douyinMode = true;
            WebSettings s = web.getSettings();
            if (phoneUA == null) phoneUA = s.getUserAgentString();
            // 关键：抖音站以 UA 分版本。手机 UA（含 wv/Android）只会拿到「下载抖音」引导页，
            // 换桌面 UA + 桌面宽视口，才会渲染带二维码的电脑版页面。
            s.setUserAgentString(Dy.desktopUA(phoneUA));
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setSupportZoom(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
            web.loadUrl(Dy.HOME_URL);
            Toast.makeText(this, "二维码没自动出现的话，点页面右上角「登录」", Toast.LENGTH_LONG).show();
        });
    }

    /** 从抖音登录页回到自己的界面：还原手机 UA 与视口，落盘 Cookie，顺手核一次登录态。 */
    private void leaveDouyin() {
        douyinMode = false;
        if (web != null) {
            WebSettings s = web.getSettings();
            if (phoneUA != null) s.setUserAgentString(phoneUA);
            s.setUseWideViewPort(false);
            s.setLoadWithOverviewMode(false);
            s.setSupportZoom(false);
            s.setBuiltInZoomControls(false);
            web.loadUrl("file:///android_asset/app.html");
        }
        try {
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {
        }
        if (bridge != null) {
            Thread t = new Thread(() -> bridge.checkAccount(), "spark-after-login");
            t.setDaemon(true);
            t.start();
        }
    }

    /** 在抖音桌面版首页里找「登录」入口并点开二维码。 */
    private static final String LOGIN_JS =
            "(function(){" +
            "var n=0;" +
            "function byText(){" +
            "var els=document.querySelectorAll('button,a,div,span');" +
            "for(var i=0;i<els.length;i++){var e=els[i];var t=(e.textContent||'').trim();" +
            "if(t==='登录'||t==='登录/注册'||t==='登录抖音'){" +
            "if(e.offsetParent===null)continue;var r=e.getBoundingClientRect();" +
            "if(r.width<10)continue;e.click();return true}}return false}" +
            "function byAttr(){" +
            "var sels=['[data-e2e*=\"login\"]','[class*=\"login-btn\"]','[class*=\"loginBtn\"]','.login-button'];" +
            "for(var j=0;j<sels.length;j++){var e=document.querySelector(sels[j]);" +
            "if(e&&e.offsetParent!==null){e.click();return true}}return false}" +
            "var id=setInterval(function(){" +
            "if(byText()||byAttr()||(++n>16))clearInterval(id)},700);" +
            "})()";

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (overlay != null && overlay.getVisibility() == View.VISIBLE) {
                // 读取中的后台页面：先收起来，读取本身继续在后台跑
                overlay.setVisibility(View.GONE);
                return true;
            }
            if (douyinMode) {
                leaveDouyin();
                return true;
            }
            // 关界面但不动后台：退到桌面，Activity 保留在后台栈里
            moveTaskToBack(true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyInsets();
    }

    private void mountWeb() {
        if (web != null) return;
        web = buildWebView();
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        web.loadUrl("file:///android_asset/app.html");
    }

    /**
     * 把界面那一整块 WebView 还给系统。用户最在意的是「不打开网页的时候别占内存」，
     * 所以一离开前台就整块销毁，回到前台再重新挂一份（本地页面，重建不到一秒）。
     */
    private void releaseWeb() {
        try {
            if (web != null) {
                root.removeView(web);
                web.stopLoading();
                web.loadUrl("about:blank");
                web.removeJavascriptInterface("SparkBridge");
                web.destroy();
                web = null;
            }
        } catch (Exception ignored) {
        }
        memAvatars.clear();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 用户又回来了：撤销待执行的释放，界面接着用，不重建
        if (releaseTask != null) {
            uiHandler.removeCallbacks(releaseTask);
            releaseTask = null;
        }
        if (!douyinMode) mountWeb();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isChangingConfigurations() || douyinMode) return;
        // 释放界面内存不立刻做：默认等 30 秒（设置页可调，0 = 立即）。
        // 用户切出去回条微信再回来，界面还在原地，不用重新渲染。
        int delay = 30;
        try {
            delay = spark.store.all().optInt("release_delay", 30);
        } catch (Exception ignored) {
        }
        if (delay < 0) delay = 0;
        if (releaseTask != null) uiHandler.removeCallbacks(releaseTask);
        releaseTask = () -> {
            releaseTask = null;
            releaseWeb();
        };
        if (delay == 0) {
            releaseWeb();
            releaseTask = null;
        } else {
            uiHandler.postDelayed(releaseTask, delay * 1000L);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            if (fileCb != null) {
                Uri[] arr = null;
                if (res == RESULT_OK && data != null && data.getData() != null) {
                    arr = new Uri[]{data.getData()};
                }
                fileCb.onReceiveValue(arr);
                fileCb = null;
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        // 登录页里按返回 = 退出抖音页，回到自己的界面
        if (douyinMode) {
            leaveDouyin();
            return;
        }
        // 就一页，没有上一页可退：直接关掉界面，把整块 WebView 的内存还给系统，
        // 前台服务留在后台继续待机（这是「退出只释放界面内存」的落点）
        finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        // 释放界面内存：WebView 整块销毁，后台服务不受影响
        releaseWeb();
        if (current == this) current = null;
        super.onDestroy();
    }

    public static void quitAll(MainActivity a) {
        if (a == null) return;
        SparkService.stop(a);
        a.runOnUiThread(() -> {
            a.finishAndRemoveTask();
            System.exit(0);
        });
    }
}
