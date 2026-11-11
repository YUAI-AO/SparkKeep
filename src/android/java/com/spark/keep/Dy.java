package com.spark.keep;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 抖音网页版自动化（Android 版）。
 * 桌面版走 Edge CDP，这里走一个隐藏的 WebView：注入脚本读会话、派发真实鼠标事件点会话、execCommand 输入。
 * 不 attach 到窗口，但手动 measure/layout 出 1080×1920 的视口，保证 DOM 有真实尺寸。
 */
public class Dy {

    public static final String CHAT_URL = "https://www.douyin.com/chat";
    public static final String HOME_URL = "https://www.douyin.com/";

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    public WebView wv;
    public String lastError = "";
    /** 手动读取时把后台页面真显示出来（不可见的 WebView 里抖音的懒加载列表可能不渲染） */
    public volatile boolean showUi = false;
    private boolean loaded = false;

    public Dy(Context c) {
        ctx = c.getApplicationContext();
    }

    // ---------------- JS 片段（与桌面版逐字同源） ----------------

    static final String CONV_JS = "(() => {\n" +
            "  const items = [...document.querySelectorAll('[data-e2e=\"conversation-item\"]')];\n" +
            "  const out = [];\n" +
            "  for (const it of items) {\n" +
            "    let nameEl = it.querySelector('.conversationConversationItemtitle');\n" +
            "    if (!nameEl) { const w = it.querySelector('[class*=\"ItemtitleWrapper\"]'); if (w) nameEl = w.firstElementChild; }\n" +
            "    const name = nameEl ? (nameEl.innerText || '').trim() : '';\n" +
            "    if (!name) continue;\n" +
            "    if (/^[0-9]{5,}$/.test(name)) continue;\n" +
            "    const streakBox = it.querySelector('[class*=\"streakContainer\"]');\n" +
            "    let iconName = '', streakNum = '';\n" +
            "    if (streakBox) {\n" +
            "      const im = streakBox.querySelector('img');\n" +
            "      if (im && im.src) iconName = im.src.split('/').pop().split('?')[0];\n" +
            "      const tn = streakBox.querySelector('[class*=\"Text\"]');\n" +
            "      if (tn) streakNum = (tn.innerText || '').trim();\n" +
            "    }\n" +
            "    const timeEl = it.querySelector('[class*=\"timeStr\"]');\n" +
            "    const descEl = it.querySelector('[class*=\"ConversationItemDescleft\"]');\n" +
            "    let avatar = '';\n" +
            "    for (const im of it.querySelectorAll('img')) {\n" +
            "      const s = im.src || '';\n" +
            "      if (s && !/bytednsdoc|streakicon|flame_icon/i.test(s)) { avatar = s; break; }\n" +
            "    }\n" +
            "    const isGroup = !it.querySelector('[class*=\"avatarContainer\"], [class*=\"AvatarContainer\"]');\n" +
            "    out.push({name:name, icon:iconName, streak:streakNum,\n" +
            "      time: timeEl ? (timeEl.innerText||'').trim() : '',\n" +
            "      desc: descEl ? (descEl.innerText||'').trim() : '',\n" +
            "      avatar: avatar, group: isGroup});\n" +
            "  }\n" +
            "  return JSON.stringify(out);\n" +
            "})()";

    static final String NAME_READY_JS = "(() => {\n" +
            "  const items = [...document.querySelectorAll('[data-e2e=\"conversation-item\"]')];\n" +
            "  if (!items.length) return false;\n" +
            "  let ok = 0;\n" +
            "  for (const it of items) {\n" +
            "    const el = it.querySelector('.conversationConversationItemtitle');\n" +
            "    const t = el ? (el.innerText || '').trim() : '';\n" +
            "    if (t && !/^[0-9]{5,}$/.test(t)) ok++;\n" +
            "  }\n" +
            "  return ok >= Math.min(4, items.length);\n" +
            "})()";

    static final String STATE_JS = "(() => {\n" +
            "  const n = document.querySelectorAll('[data-e2e=\"conversation-item\"]').length;\n" +
            "  const body = document.body ? (document.body.innerText || '') : '';\n" +
            "  if (body.indexOf('扫码登录') >= 0 && n === 0) return 'guest';\n" +
            "  if (n > 0) return 'user';\n" +
            "  if (body.indexOf('发送消息') >= 0) return 'user';\n" +
            "  return 'loading';\n" +
            "})()";

    /** 抓不到会话时用来自证：页面到底停在哪、有没有会话节点、正文长什么样。 */
    static final String DIAG_JS = "(() => {\n" +
            "  const items = document.querySelectorAll('[data-e2e=\"conversation-item\"]').length;\n" +
            "  const body = document.body ? (document.body.innerText || '') : '';\n" +
            "  const head = body.replace(/\\s+/g, ' ').slice(0, 160);\n" +
            "  return JSON.stringify({url: location.href, title: document.title,\n" +
            "    items: items, len: body.length, head: head});\n" +
            "})()";

    static final String HEADER_JS = "(() => {\n" +
            "  const h = document.querySelector('[class*=\"RightPanelHeaderconvHeader\"]')\n" +
            "        || document.querySelector('[class*=\"RightPanelHeader\"]');\n" +
            "  if (!h) return '';\n" +
            "  return (h.innerText || '').trim().split('\\n')[0];\n" +
            "})()";

    static final String SCROLL_JS = "(() => {\n" +
            "  const it = document.querySelector('[data-e2e=\"conversation-item\"]');\n" +
            "  if (!it) return 0;\n" +
            "  let p = it.parentElement, box = null;\n" +
            "  while (p) {\n" +
            "    if (p.scrollHeight > p.clientHeight + 20) { box = p; break; }\n" +
            "    p = p.parentElement;\n" +
            "  }\n" +
            "  if (!box) return 0;\n" +
            // 按可见高度滚一屏（原来固定 620，大屏手机上翻不全、小屏又翻太多）
            "  box.scrollTop = box.scrollTop + Math.max(400, Math.round(box.clientHeight * 0.85));\n" +
            "  return Math.round(box.scrollTop);\n" +
            "})()";

    static final String SCROLL_TOP_JS = "(() => {\n" +
            "  const it = document.querySelector('[data-e2e=\"conversation-item\"]');\n" +
            "  if (!it) return 0;\n" +
            "  let p = it.parentElement, box = null;\n" +
            "  while (p) { if (p.scrollHeight > p.clientHeight + 20) { box = p; break; } p = p.parentElement; }\n" +
            "  if (box) box.scrollTop = 0;\n" +
            "  return 1;\n" +
            "})()";

    // ---------------- 基础 ----------------

    public static String kindOf(String icon) {
        if (icon == null || icon.isEmpty()) return "none";
        return icon.startsWith("gray") ? "cooling" : "alive";
    }

    private boolean onMain() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    /** 在 UI 线程求值，后台线程可用（内部 latch 等待）。 */
    public String evalRaw(String js, long timeoutMs) {
        if (wv == null) return null;
        if (onMain()) {
            final Object[] box = new Object[1];
            wv.evaluateJavascript(js, v -> box[0] = v);
            // 回调可能在本轮消息之后，靠 Handler 再抽一次；简单起见这里同步调用直接返回空
            return box[0] instanceof String ? (String) box[0] : null;
        }
        final Object[] box = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> {
            try {
                if (wv != null) {
                    wv.evaluateJavascript(js, v -> {
                        box[0] = v;
                        latch.countDown();
                    });
                } else {
                    latch.countDown();
                }
            } catch (Exception e) {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return box[0] instanceof String ? (String) box[0] : null;
    }

    public String evalString(String js, long timeoutMs) {
        String raw = evalRaw(js, timeoutMs);
        if (raw == null) return null;
        String t = raw.trim();
        if (t.startsWith("\"")) {
            try {
                Object v = new JSONTokener(t).nextValue();
                return v == null ? null : String.valueOf(v);
            } catch (Exception e) {
                return null;
            }
        }
        if ("null".equals(t)) return null;
        return t;
    }

    public boolean evalBool(String js, long timeoutMs) {
        String s = evalString(js, timeoutMs);
        return "true".equals(s);
    }

    // ---------------- 生命周期 ----------------

    @SuppressLint("SetJavaScriptEnabled")
    public void ensureWebView() {
        Runnable r = () -> {
            if (wv != null) return;
            // 用 application context 建 WebView 拿不到宿主主题与窗口资源，容易白屏 / 空页；
            // 有界面时一定用 Activity 作 context
            MainActivity act = MainActivity.current;
            Context base = act != null ? act : ctx;
            WebView w = new WebView(base);
            WebSettings s = w.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setLoadsImagesAutomatically(true);
            s.setBlockNetworkImage(false);
            s.setMediaPlaybackRequiresUserGesture(true);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            s.setUserAgentString(desktopUA(s.getUserAgentString()));
            s.setSupportZoom(false);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(false);
            s.setTextZoom(100);
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
            w.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    loaded = true;
                }
            });
            w.setBackgroundColor(0xFF050607);
            if (act != null && showUi) {
                // 真挂到界面上：可见的 WebView 才会跑 rAF，抖音的懒加载会话列表才出得来
                act.workOpen("正在打开抖音私信页…");
                act.attachWork(w);
            } else {
                // 没有界面的后台场景：给真实视口尺寸，至少让 getBoundingClientRect 不是全 0
                w.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
                w.layout(0, 0, 1080, 1920);
            }
            wv = w;
        };
        if (onMain()) r.run();
        else main.post(r);
    }

    static String desktopUA(String base) {
        // 桌面 UA：抖音 web 版私信页在移动 UA 下入口不同，保持与桌面版一致
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0";
    }

    /** 系统 WebView 内核版本（主版本号），不依赖 WebView 实例，可在任意线程取。 */
    static String webViewVersion(Context c) {
        try {
            String ua = WebSettings.getDefaultUserAgent(c);
            int i = ua.indexOf("Chrome/");
            if (i < 0) return "";
            int j = ua.indexOf('.', i + 7);
            if (j < 0) return "";
            return ua.substring(i + 7, j);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 抖音 2026 的前端要 Chrome 95+ 的语法/接口；内核过旧时整个私信模块不会加载，
     * 页面永远停在「加载中」，会话列表一行为空。这里是判定阈值。
     */
    static boolean webViewTooOld(Context c) {
        String v = webViewVersion(c);
        try {
            return Integer.parseInt(v) < 95;
        } catch (Exception e) {
            return false;
        }
    }

    public void destroy() {
        Runnable r = () -> {
            if (wv != null) {
                try {
                    android.view.ViewGroup p = (android.view.ViewGroup) wv.getParent();
                    if (p != null) p.removeView(wv);
                } catch (Exception ignored) {
                }
                try {
                    wv.loadUrl("about:blank");
                    wv.stopLoading();
                    wv.destroy();
                } catch (Exception ignored) {
                }
                wv = null;
            }
            loaded = false;
        };
        if (onMain()) r.run();
        else main.post(r);
    }

    private Runnable idleKiller;

    /**
     * 读完会话先别急着销毁页面：连着点两次「重新读取」时，第二次能直接复用已经加载好的抖音页，
     * 省掉十几秒的冷启动；没人用就 ms 之后自动释放，不占着内存。
     */
    public void keepWarm(long ms) {
        if (idleKiller != null) main.removeCallbacks(idleKiller);
        idleKiller = () -> {
            idleKiller = null;
            boolean busy = false;
            try {
                busy = Spark.get(ctx).job.optBoolean("active", false);
            } catch (Exception ignored) {
            }
            if (!busy) destroy();
        };
        main.postDelayed(idleKiller, ms);
    }

    public void load(final String url) {
        loaded = false;
        Runnable r = () -> {
            if (wv == null) return;
            wv.loadUrl(url);
        };
        if (onMain()) r.run();
        else main.post(r);
    }

    // ---------------- 登录态 ----------------

    /** 最可靠的登录判据：WebView 的 Cookie 里有没有 sessionid。页面文案会骗人，Cookie 不会。 */
    public boolean hasSessionCookie() {
        try {
            String c = CookieManager.getInstance().getCookie("https://www.douyin.com");
            if (c == null) return false;
            return c.contains("sessionid=") || c.contains("sessionid_ss=")
                    || c.contains("sid_guard=");
        } catch (Exception e) {
            return false;
        }
    }

    /** 页面实况，用于日志定位。 */
    public String diag() {
        String d = evalString(DIAG_JS, 8000);
        if (d == null) return "页面无响应(可能未加载完或被销毁)";
        return d.length() > 260 ? d.substring(0, 260) : d;
    }

    // ---------------- 后台页面可见化 ----------------

    private void showWork(final String title) {
        if (!showUi) return;
        final MainActivity a = MainActivity.current;
        if (a == null) return;
        main.post(() -> {
            a.workOpen(title);
            if (wv != null) a.attachWork(wv);
        });
    }

    private void hideWork() {
        final MainActivity a = MainActivity.current;
        if (a == null) return;
        main.post(() -> {
            a.detachWork(wv);
            a.workClose();
        });
    }

    // ---------------- 打开私信页 ----------------

    public String openChat(long waitMs) {
        ensureWebView();
        showWork("正在打开抖音私信页…");
        try {
            load(CHAT_URL);
            long end = System.currentTimeMillis() + waitMs;
            String st = null;
            while (System.currentTimeMillis() < end) {
                st = evalString(STATE_JS, 6000);
                if ("user".equals(st) || "guest".equals(st)) return st;
                sleep(900);
            }
            return st;
        } finally {
            hideWork();
        }
    }

    public String state() {
        return evalString(STATE_JS, 6000);
    }

    // ---------------- 会话列表 ----------------

    public JSONArray listConversations(int maxScroll) {
        LinkedHashMap<String, JSONObject> seen = new LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        showWork("正在读取会话列表…");
        try {
            String st = null;
            for (int i = 0; i < 3; i++) {
                st = evalString(STATE_JS, 6000);
                if ("guest".equals(st)) {
                    lastError = "未登录";
                    break;
                }
                if ("user".equals(st)) break;
                sleep(1200);
            }
            // 抖音的首屏第一次加载经常停在「加载中」，重载一次大多能救回来；
            // 直接报 0 没有意义，先自己再试一回
            if ("loading".equals(st) || st == null) {
                load(CHAT_URL);
                for (int i = 0; i < 8; i++) {
                    sleep(1800);
                    String s2 = evalString(STATE_JS, 6000);
                    if ("user".equals(s2)) break;
                    if ("guest".equals(s2)) {
                        lastError = "未登录";
                        break;
                    }
                }
            }
            for (int i = 0; i < 4; i++) {
                if (evalBool(NAME_READY_JS, 6000)) break;
                sleep(1500);
            }
            // 首屏先给几次机会：抖音的懒加载列表经常第一抓是空的，
            // 以前一抓空就直接去翻页、翻页也空就报「0 人」，看着像没读到
            for (int i = 0; i < 4 && seen.isEmpty(); i++) {
                harvestInto(seen);
                if (seen.isEmpty() && i < 3) sleep(1200);
            }
            // 翻页：虚拟列表在滚动后常有一轮空窗，连续两轮没新增才认为真的到底
            int idle = 0;
            for (int i = 0; i < maxScroll && idle < 2; i++) {
                if (seen.size() >= 800) break;                        // 内存有上限，别无限往 Map 里塞
                if (System.currentTimeMillis() - t0 > 100_000L) break; // 一次读取最多 100 秒
                String pos = evalString(SCROLL_JS, 6000);
                sleep(pos == null ? 300 : 700);
                int added = harvestInto(seen);
                if (added == 0) idle++; else idle = 0;
            }
            evalString(SCROLL_TOP_JS, 4000);
            if (seen.isEmpty()) {
                // 抓不到就留下现场，记录页能直接看到页面停在哪
                lastError = "读取为空 · " + diag();
            }
            JSONArray out = new JSONArray();
            for (JSONObject o : seen.values()) out.put(o);
            return out;
        } finally {
            hideWork();
        }
    }

    private int harvestInto(LinkedHashMap<String, JSONObject> seen) {
        String raw = evalRaw(CONV_JS, 9000);
        if (raw == null) return 0;
        JSONArray arr;
        try {
            Object v = new JSONTokener(raw).nextValue();
            if (v instanceof String) v = new JSONTokener((String) v).nextValue();
            if (!(v instanceof JSONArray)) return 0;
            arr = (JSONArray) v;
        } catch (Exception e) {
            lastError = "解析会话列表失败: " + e.getMessage();
            return 0;
        }
        int added = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            String name = it.optString("name", "");
            if (name.isEmpty() || seen.containsKey(name)) continue;
            try {
                it.put("kind", kindOf(it.optString("icon", "")));
            } catch (Exception ignored) {
            }
            seen.put(name, it);
            added++;
        }
        return added;
    }

    // ---------------- 点会话 ----------------

    public boolean openConversation(String name) {
        String cur = evalString(HEADER_JS, 6000);
        if (name.equals(cur)) return true;
        String js = "(() => {\n" +
                "  const target = " + JSONObject.quote(name) + ";\n" +
                "  const items = [...document.querySelectorAll('[data-e2e=\"conversation-item\"]')];\n" +
                "  for (const it of items) {\n" +
                "    let nameEl = it.querySelector('.conversationConversationItemtitle');\n" +
                "    if (!nameEl) { const w = it.querySelector('[class*=\"ItemtitleWrapper\"]'); if (w) nameEl = w.firstElementChild; }\n" +
                "    const n = nameEl ? (nameEl.innerText || '').trim() : '';\n" +
                "    if (n !== target) continue;\n" +
                "    try { it.scrollIntoView({block:'center'}); } catch (e) {}\n" +
                "    const opts = {bubbles:true, cancelable:true, view:window, button:0, detail:1};\n" +
                "    const fire = (t) => {\n" +
                "      try { it.dispatchEvent(new PointerEvent(t, opts)); } catch (e) {}\n" +
                "      try { it.dispatchEvent(new MouseEvent(t, opts)); } catch (e) {}\n" +
                "    };\n" +
                "    fire('pointerdown'); fire('mousedown'); fire('pointerup'); fire('mouseup');\n" +
                "    fire('click');\n" +
                "    return 1;\n" +
                "  }\n" +
                "  return 0;\n" +
                "})()";
        for (int attempt = 0; attempt < 2; attempt++) {
            if ("1".equals(evalString(js, 8000))) break;
            sleep(600);
        }
        long end = System.currentTimeMillis() + 9000;
        while (System.currentTimeMillis() < end) {
            cur = evalString(HEADER_JS, 6000);
            if (name.equals(cur)) {
                sleep(600);
                return true;
            }
            sleep(400);
        }
        lastError = "切换会话超时（当前是「" + cur + "」）";
        return false;
    }

    // ---------------- 发送 ----------------

    private String editorText() {
        return evalString("(() => { const e = document.querySelector('[data-e2e=\"msg-input\"]');"
                + " return e ? (e.innerText || '').replace(/\\u200b/g,'').trim() : ''; })()", 6000);
    }

    /**
     * 真正的回车：走 WebView 的按键管线，事件 isTrusted=true。
     * 之前用 dispatchEvent(new KeyboardEvent(...)) 造出来的假事件，抖音的编辑器不认，
     * 于是每次都退化成"点发送按钮"，最后拿"输入框清空"当成功——消息根本没出去。
     */
    private void pressEnter() {
        Runnable r = () -> {
            if (wv == null) return;
            try {
                long t = android.os.SystemClock.uptimeMillis();
                wv.requestFocus();
                wv.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0));
                wv.dispatchKeyEvent(new KeyEvent(t, t + 40, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0));
            } catch (Throwable ignored) {
            }
        };
        if (onMain()) r.run();
        else main.post(r);
    }

    /** 页面上已经出现几条这条文本（排除输入框自身）。发送前记一次、发送后比一次，才是"真发出去了"。 */
    private int countText(String text) {
        String js = "(() => {\n" +
                "  const t = " + JSONObject.quote(text) + ";\n" +
                "  const ed = document.querySelector('[data-e2e=\"msg-input\"]');\n" +
                "  const norm = (s) => (s || '').replace(/\\s+/g, ' ').trim();\n" +
                "  let n = 0;\n" +
                "  for (const el of document.querySelectorAll('div,span,p,li')) {\n" +
                "    if (el.children && el.children.length) continue;\n" +
                "    if (ed && ed.contains(el)) continue;\n" +
                "    if (norm(el.innerText) !== t) continue;\n" +
                "    const r = el.getBoundingClientRect();\n" +
                "    if (r.width <= 0 || r.height <= 0) continue;\n" +
                "    n++;\n" +
                "  }\n" +
                "  return '' + n;\n" +
                "})()";
        String v = evalString(js, 8000);
        if (v == null) return 0;
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 点发送按钮。第一版把页面里所有 div 都当候选、按宽度取最大，点到的经常不是发送键。 */
    private String clickSendBtn() {
        String js = "(() => {\n" +
                "  const ed = document.querySelector('[data-e2e=\"msg-input\"]');\n" +
                "  if (!ed) return 'no-editor';\n" +
                "  const er = ed.getBoundingClientRect();\n" +
                "  const near = (e) => {\n" +
                "    const r = e.getBoundingClientRect();\n" +
                "    if (r.width < 16 || r.width > 120 || r.height < 16 || r.height > 120) return false;\n" +
                "    const cy = r.top + r.height / 2, ey = er.top + er.height / 2;\n" +
                "    if (Math.abs(cy - ey) > Math.max(60, er.height)) return false;\n" +
                "    return r.left >= er.right - 40;\n" +
                "  };\n" +
                "  let cand = [...document.querySelectorAll('[data-e2e*=\"send\"],[data-e2e*=\"Send\"]')].filter(near);\n" +
                "  if (!cand.length) {\n" +
                "    cand = [...document.querySelectorAll('button,[role=\"button\"],span,div')]\n" +
                "      .filter(e => near(e) && (e.innerText || '').trim() === '发送');\n" +
                "  }\n" +
                "  if (!cand.length) {\n" +
                "    cand = [...document.querySelectorAll('button,[role=\"button\"]')].filter(e => near(e) && !e.disabled);\n" +
                "  }\n" +
                "  if (!cand.length) return 'no-btn';\n" +
                "  let el = cand[cand.length - 1];\n" +
                "  if (el.tagName && el.tagName.toLowerCase() === 'svg' && el.parentElement) el = el.parentElement;\n" +
                "  const r = el.getBoundingClientRect();\n" +
                "  const pt = {bubbles:true, cancelable:true, view:window, button:0, detail:1,\n" +
                "    clientX:Math.round(r.left + r.width/2), clientY:Math.round(r.top + r.height/2)};\n" +
                "  el.dispatchEvent(new PointerEvent('pointerdown', pt));\n" +
                "  el.dispatchEvent(new MouseEvent('mousedown', pt));\n" +
                "  el.dispatchEvent(new PointerEvent('pointerup', pt));\n" +
                "  el.dispatchEvent(new MouseEvent('mouseup', pt));\n" +
                "  el.dispatchEvent(new MouseEvent('click', pt));\n" +
                "  return 'ok:' + (el.tagName || '?') + ' ' + Math.round(r.width) + 'x' + Math.round(r.height);\n" +
                "})()";
        return evalString(js, 8000);
    }

    public boolean sendMessage(String text) {
        String has = evalString("(() => { const e = document.querySelector('[data-e2e=\"msg-input\"]');"
                + " if (!e) return ''; const r = e.getBoundingClientRect();"
                + " return r.height > 0 ? '1' : '0'; })()", 6000);
        if (!"1".equals(has)) {
            lastError = "找不到输入框";
            return false;
        }
        // 发送前先数一遍页面里现有的这条文本，后面只有变多才算真发出去
        int before = countText(text);
        // 清草稿 + 注入文本（execCommand 会带出 input 事件，编辑器才认）
        evalString("(() => { const e = document.querySelector('[data-e2e=\"msg-input\"]');"
                + " if (!e) return '0'; e.focus();"
                + " try { document.execCommand('selectAll', false, null); document.execCommand('delete', false, null); } catch (err) {}"
                + " return '1'; })()", 6000);
        sleep(300);
        String typed = evalString("(() => { const e = document.querySelector('[data-e2e=\"msg-input\"]');"
                + " if (!e) return 'no-editor'; e.focus();"
                + " try { document.execCommand('insertText', false, " + JSONObject.quote(text) + "); }"
                + " catch (err) { return 'err:' + err.message; } return 'ok'; })()", 8000);
        if (!"ok".equals(typed)) {
            lastError = "注入失败: " + typed;
            return false;
        }
        sleep(450);
        String got = editorText();
        if (got == null || !got.contains(text)) {
            lastError = "文本没有进入输入框";
            return false;
        }

        // 一：真实回车
        pressEnter();
        sleep(1800);
        int a1 = countText(text);
        if (a1 > before) return true;

        // 二：点发送按钮
        String btn = clickSendBtn();
        sleep(1500);
        int a2 = countText(text);
        if (a2 > before) return true;

        // 三：再回车一次（有些时候第一次被焦点切换吃掉）
        pressEnter();
        sleep(1600);
        int a3 = countText(text);
        if (a3 > before) return true;

        String tail = editorText();
        lastError = "没能确认发出（同文本 " + before + "→" + a1 + "→" + a2 + "→" + a3
                + "，发送键=" + btn + "，输入框剩=" + (tail == null || tail.isEmpty() ? "空" : "非空") + "）";
        return false;
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
