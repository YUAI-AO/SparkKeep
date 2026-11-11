package com.spark.keep;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 前端唯一的数据通道。app.html 里的 fetch 被替换成 SparkBridge.call(path, body)。
 * 返回体与桌面版 /api/* 完全同构，前端代码不需要改逻辑。
 */
public class Bridge {

    private final WeakReference<MainActivity> actRef;
    private final Spark spark;

    /** 同一块隐藏 WebView 同时只能被一个流程使用，否则一个在加载一个在求值，状态必然乱跳 */
    static final java.util.concurrent.locks.ReentrantLock DY_LOCK =
            new java.util.concurrent.locks.ReentrantLock();

    public Bridge(MainActivity a, Spark s) {
        actRef = new WeakReference<>(a);
        spark = s;
    }

    @JavascriptInterface
    public String call(String path, String body) {
        try {
            return route(path == null ? "" : path, body == null || body.isEmpty() ? "{}" : body).toString();
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try {
                err.put("ok", false);
                err.put("msg", "接口异常：" + e);
            } catch (Exception ignored) {
            }
            return err.toString();
        }
    }

    private JSONObject ok(String... kv) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            for (int i = 0; i + 1 < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
        } catch (Exception ignored) {
        }
        return o;
    }

    private JSONObject err(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("msg", msg);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** "HH:mm:ss" → 当天零点起算的秒数；解析不出来返回 -1（历史行里可能是 "--:--:--"） */
    private static long hms(String s) {
        if (s == null) return -1L;
        String[] p = s.trim().split(":");
        if (p.length < 2) return -1L;
        try {
            long h = Long.parseLong(p[0].trim());
            long m = Long.parseLong(p[1].trim());
            long sec = p.length > 2 ? Long.parseLong(p[2].trim()) : 0L;
            if (h < 0 || h > 23 || m < 0 || m > 59 || sec < 0 || sec > 59) return -1L;
            return h * 3600L + m * 60L + sec;
        } catch (Exception e) {
            return -1L;
        }
    }

    /** 日志类别统一成前端认识的五个值 */
    private static String kindOf(String k) {
        if (k == null) return "plan";
        String u = k.trim().toUpperCase();
        if ("SEND".equals(u) || "SENT".equals(u)) return "send";
        if ("ERROR".equals(u) || "ERR".equals(u)) return "error";
        if ("AUTH".equals(u) || "ACCOUNT".equals(u) || "LOGIN".equals(u)) return "account";
        if ("SYS".equals(u) || "SYSTEM".equals(u) || "INFO".equals(u)) return "system";
        return "plan";
    }

    private JSONObject route(String path, String body) throws Exception {
        JSONObject p = new JSONObject(body);
        boolean isPost = p.has("__post") || !"{}".equals(body);

        // 统一：/api/state 是轮询主入口
        if ("/api/ping".equals(path)) return ok("app", "sparkkeep", "platform", "android");
        if ("/api/state".equals(path)) {
            JSONObject st = spark.state();
            st.put("ok", true);
            if (spark.lastDiag != null && !spark.lastDiag.isEmpty()) st.put("diag", spark.lastDiag);
            return st;
        }
        if ("/api/selftest".equals(path)) {
            // 页面自检：把后台页面挂到界面上打开私信页并留在那儿，让人肉眼看清页面到底长什么样
            JSONObject o = ok();
            Thread t = new Thread(() -> {
                try {
                    DY_LOCK.lock();
                    spark.dy.showUi = true;
                    spark.dy.ensureWebView();
                    spark.dy.load(spark.dy.CHAT_URL);
                    Thread.sleep(15000);
                    String st = spark.dy.state();
                    String d = spark.dy.diag();
                    spark.lastDiag = d;
                    spark.log("sys", "页面自检 state=" + st + " · " + d);
                } catch (Exception e) {
                    spark.log("error", "页面自检失败：" + e);
                } finally {
                    spark.dy.showUi = false;
                    DY_LOCK.unlock();
                }
            }, "spark-diag");
            t.setDaemon(true);
            t.start();
            return o;
        }
        if ("/api/logs".equals(path)) {
            JSONObject o = ok();
            JSONArray arr = new JSONArray();
            List<String[]> logs = spark.store.readLogs();
            int start = Math.max(0, logs.size() - 160);
            // 磁盘里存的是「HH:mm:ss \t KIND \t 文本」，前端要的是「秒级时间戳 + 小写类别」，
            // 在这里换算，保证记录页的时间与分类和桌面版一致（不然会显示 NaN:NaN）
            java.util.Calendar z = java.util.Calendar.getInstance();
            z.set(java.util.Calendar.HOUR_OF_DAY, 0);
            z.set(java.util.Calendar.MINUTE, 0);
            z.set(java.util.Calendar.SECOND, 0);
            z.set(java.util.Calendar.MILLISECOND, 0);
            long base = z.getTimeInMillis() / 1000L;
            long prev = 0L;
            long shift = 0L;
            for (int i = start; i < logs.size(); i++) {
                String[] l = logs.get(i);
                long ts = base + shift + hms(l[0]);
                if (ts < 0) ts = base + shift;
                // 日志按时间追加：跨过零点时数字会变小，往后挪一天
                if (prev > 0 && ts < prev - 21600L) {
                    shift += 86400L;
                    ts += 86400L;
                }
                prev = ts;
                JSONObject it = new JSONObject();
                it.put("t", ts);
                it.put("kind", kindOf(l[1]));
                it.put("text", l[2]);
                arr.put(it);
            }
            o.put("logs", arr);
            o.put("total", logs.size());
            return o;
        }

        if ("/api/friends".equals(path)) {
            if (isPost && p.has("names")) {
                JSONArray names = p.optJSONArray("names");
                if (names == null && p.has("allow_empty")) names = new JSONArray();
                return saveFriends(names);
            }
            if (isPost) {
                // 点「重新读取」：真正去抓一次会话列表
                return readFriends();
            }
            JSONObject o = ok();
            o.put("friends", spark.friendsCache);
            o.put("at", spark.friendsAt);
            return o;
        }

        if ("/api/settings".equals(path)) {
            JSONObject patch = new JSONObject();
            java.util.Iterator<String> it = p.keys();
            while (it.hasNext()) {
                String k = it.next();
                if ("__post".equals(k)) continue;
                patch.put(k, p.opt(k));
            }
            spark.store.patch(patch);
            // 保活开关带副作用：关掉就停服务，打开就拉起来
            if (patch.has("keep_alive")) {
                if (patch.optBoolean("keep_alive")) SparkService.start(spark.ctx, false);
                else SparkService.stop(spark.ctx);
            }
            return ok();
        }

        if ("/api/wallpaper".equals(path)) {
            String data = p.optString("data", "");
            File w = new File(spark.store.dir, "wallpaper.img");
            if (data.isEmpty()) {
                if (w.exists()) w.delete();
                return ok();
            }
            String b64 = data;
            int comma = b64.indexOf(',');
            if (b64.startsWith("data:") && comma > 0) b64 = b64.substring(comma + 1);
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            Store.writeAll(w, "");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(w);
            fos.write(bytes);
            fos.flush();
            fos.close();
            if (bytes.length > 6 * 1024 * 1024) return err("图片太大");
            return ok();
        }

        if ("/api/login".equals(path)) {
            MainActivity a = actRef.get();
            if (a == null) return err("界面已关闭");
            a.openDouyinLogin();
            return ok("msg", "已打开抖音登录页");
        }

        if ("/api/account/check".equals(path)) {
            return checkAccount();
        }

        if ("/api/send-now".equals(path)) {
            boolean force = p.optBoolean("force", true);
            List<String> only = new ArrayList<>();
            JSONArray names = p.optJSONArray("names");
            if (names != null) for (int i = 0; i < names.length(); i++) only.add(names.optString(i));
            boolean started = spark.runJob("manual", only.isEmpty() ? null : only, force);
            return started ? ok("msg", "已开始") : err("已经有一个任务在跑");
        }

        if ("/api/stop".equals(path)) {
            return spark.stopJob() ? ok("msg", "正在停止") : err("当前没有任务");
        }

        if ("/api/keep".equals(path)) {
            JSONObject o = keepState();
            if (p.has("boot_start")) {
                JSONObject patch = new JSONObject().put("boot_start", p.optBoolean("boot_start"));
                spark.store.patch(patch);
            }
            if (p.has("keep_alive")) {
                JSONObject patch = new JSONObject().put("keep_alive", p.optBoolean("keep_alive"));
                spark.store.patch(patch);
                if (!p.optBoolean("keep_alive")) SparkService.stop(spark.ctx);
                else SparkService.start(spark.ctx, false);
            }
            if (p.optBoolean("request_ignore", false)) {
                requestIgnoreBattery();
            }
            if (p.has("boot_start") || p.has("keep_alive")) return keepState();
            return o;
        }

        if ("/api/perm".equals(path)) {
            return permApi(p, isPost);
        }

        if ("/api/quit".equals(path)) {
            MainActivity a = actRef.get();
            MainActivity.quitAll(a);
            return ok();
        }

        return err("未知接口 " + path);
    }

    /** 权限自检：把「能不能正常干活」需要的几项一次列清楚，缺哪个给个直达按钮 */
    private JSONObject permApi(JSONObject p, boolean isPost) {
        MainActivity a = actRef.get();
        Context c = a != null ? a : spark.ctx;
        if (isPost) return permOpen(c, a, p.optString("key", ""));
        JSONObject o = ok();
        JSONArray arr = new JSONArray();
        try {
            arr.put(permItem("notification", "通知", permNoti(c), "后台状态和失败提醒都靠它"));
            arr.put(permItem("battery", "电池优化白名单", permBattery(c), "不加的话，手机深睡时会把后台停掉"));
            arr.put(permItem("alarm", "精确闹钟", permAlarm(c), "到点没醒时的兜底，不精确可能晚几分钟"));
            arr.put(permItem("boot", "自启动 / 后台运行", "manual", "各家位置不一样，点「去设置」试试能不能直接跳过去"));
            arr.put(permItem("recent", "多任务锁定", "manual", "最近任务里长按卡片加锁，系统清后台时才留得下"));
            arr.put(permItem("storage", "相册 / 文件", "ok", "选壁纸走系统相框，不用额外授权"));
            o.put("items", arr);
        } catch (Exception ignored) {
        }
        return o;
    }

    private JSONObject permItem(String key, String title, String state, String note) {
        JSONObject i = new JSONObject();
        try {
            i.put("key", key);
            i.put("title", title);
            i.put("state", state);
            i.put("note", note);
        } catch (Exception ignored) {
        }
        return i;
    }

    private String permNoti(Context c) {
        try {
            Object nm = c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm instanceof android.app.NotificationManager) {
                return ((android.app.NotificationManager) nm).areNotificationsEnabled() ? "ok" : "need";
            }
        } catch (Exception ignored) {
        }
        return "need";
    }

    private String permBattery(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName()) ? "ok" : "need";
        } catch (Exception ignored) {
        }
        return "need";
    }

    private String permAlarm(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                android.app.AlarmManager am =
                        (android.app.AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                return am != null && am.canScheduleExactAlarms() ? "ok" : "need";
            }
        } catch (Exception ignored) {
        }
        return "ok";
    }

    private JSONObject permOpen(Context c, MainActivity a, String key) {
        try {
            if ("notification".equals(key)) {
                if (Build.VERSION.SDK_INT >= 33 && a != null) {
                    a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 4103);
                    return ok("msg", "已弹出通知授权");
                }
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
                return launch(c, a, i, "已打开通知设置");
            }
            if ("battery".equals(key)) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + c.getPackageName()));
                return launch(c, a, i, "已打开电池优化授权");
            }
            if ("alarm".equals(key)) {
                if (Build.VERSION.SDK_INT >= 31) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                    i.setData(Uri.parse("package:" + c.getPackageName()));
                    return launch(c, a, i, "已打开精确闹钟授权");
                }
                return ok("msg", "这个系统版本不需要授权");
            }
            if ("boot".equals(key)) {
                // 国产 ROM 的自启动开关不在标准设置里，只能按厂商各自的类名去试，
                // 试不中就退回应用详情页 —— 这也是「放行快捷键」唯一能做的事
                String[][] targets = {
                        {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                        {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                        {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                        {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                        {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                        {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
                        {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
                        {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
                        {"com.transsion.phonemaster", "com.transsion.phonemaster.ui.activity.AppAutoStartActivity"}
                };
                for (String[] t : targets) {
                    try {
                        Intent i = new Intent();
                        i.setClassName(t[0], t[1]);
                        if (a == null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        (a != null ? a : c).startActivity(i);
                        return ok("msg", "已打开系统的自启动管理");
                    } catch (Exception ignored) {
                    }
                }
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + c.getPackageName()));
                return launch(c, a, i, "已打开应用详情，去「自启动 / 后台运行」允许");
            }
            return ok("msg", "无需额外授权");
        } catch (Exception e) {
            return err("打不开系统设置：" + e);
        }
    }

    private JSONObject launch(Context c, MainActivity a, Intent i, String msg) {
        try {
            if (a == null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            (a != null ? a : c).startActivity(i);
            return ok("msg", msg);
        } catch (Exception e) {
            return err("打不开系统设置：" + e);
        }
    }

    private JSONObject keepState() {
        JSONObject o = ok();
        try {
            o.put("service", SparkService.ignoringBattery(spark.ctx) || spark.serviceRunning);
            o.put("running", spark.serviceRunning);
            o.put("boot_start", spark.store.all().optBoolean("boot_start", true));
            o.put("keep_alive", spark.store.all().optBoolean("keep_alive", true));
            o.put("ignore_battery", SparkService.ignoringBattery(spark.ctx));
            o.put("sdk", Build.VERSION.SDK_INT);
            String wv = Dy.webViewVersion(spark.ctx);
            o.put("wv", wv);
            o.put("wv_old", Dy.webViewTooOld(spark.ctx));
            o.put("release_delay", spark.store.all().optInt("release_delay", 30));
        } catch (Exception ignored) {
        }
        return o;
    }

    private void requestIgnoreBattery() {
        MainActivity a = actRef.get();
        Context c = a == null ? spark.ctx : a;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(c.getPackageName())) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + c.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(i);
                    return;
                }
            }
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private JSONObject saveFriends(JSONArray names) throws Exception {
        List<String> sel = new ArrayList<>();
        if (names != null) for (int i = 0; i < names.length(); i++) sel.add(names.optString(i));
        JSONArray cur = spark.friendsCache;
        JSONObject cfg = spark.store.copy();
        JSONArray fr = cfg.optJSONArray("friends");
        JSONArray out = new JSONArray();
        // 保留已经记录的元信息，按名单开关 enabled
        if (fr != null) {
            for (int i = 0; i < fr.length(); i++) {
                JSONObject f = fr.optJSONObject(i);
                if (f == null) continue;
                String n = f.optString("name");
                boolean on = sel.contains(n);
                f.put("enabled", on);
                out.put(f);
            }
        }
        // 名单里有但配置里没有的（新读到的会话）补进去
        for (String n : sel) {
            boolean exists = false;
            for (int i = 0; i < out.length(); i++) {
                if (n.equals(out.optJSONObject(i).optString("name"))) {
                    exists = true;
                    break;
                }
            }
            if (exists) continue;
            JSONObject f = new JSONObject();
            f.put("name", n);
            f.put("enabled", true);
            for (int i = 0; i < cur.length(); i++) {
                JSONObject c = cur.optJSONObject(i);
                if (c != null && n.equals(c.optString("name"))) {
                    f.put("avatar", c.optString("avatar", ""));
                    f.put("streak", c.optString("streak", ""));
                    f.put("kind", c.optString("kind", "none"));
                    break;
                }
            }
            out.put(f);
        }
        JSONObject patch = new JSONObject().put("friends", out);
        spark.store.patch(patch);
        return ok("count", String.valueOf(sel.size()));
    }

    private JSONObject readFriends() {
        if (spark.jobActive()) return err("正在发送中，稍后再读");
        // 连点两下「重新读取」不再排两条队：第二次直接告诉他在读，省一个重复的 WebView 页面
        if (spark.readingConvs) {
            JSONObject o = ok();
            try {
                o.put("pending", "busy");
            } catch (Exception ignored) {
            }
            spark.log("sys", "上一次读取还没结束，这次跳过");
            spark.lastDiag = "正在读取中";
            return o;
        }
        spark.readingConvs = true;
        JSONObject o = ok();
        try {
            o.put("pending", "reading");
        } catch (Exception ignored) {
        }
        // 没有登录 Cookie 就别开页面了：一张抖音页要 40 多秒才肯报「未登录」，
        // 用户点完只能干等。这里直接判掉，一句话把人送去登录页
        if (!spark.dy.hasSessionCookie()) {
            spark.readingConvs = false;
            spark.lastDiag = "没有登录 Cookie";
            spark.log("error", "还没登录，先去登录一次");
            try {
                o.put("pending", "nologin");
                o.put("msg", "还没登录，先去登录一次");
            } catch (Exception ignored) {
            }
            return o;
        }
        // 抓列表要开 WebView，走后台线程，界面每 4 秒轮询会自然看到结果
        Thread t = new Thread(() -> {
            DY_LOCK.lock();
            spark.dy.showUi = true;
            try {
                String st = spark.dy.openChat(45_000);
                if (!"user".equals(st)) {
                    if (spark.dy.hasSessionCookie() && !"guest".equals(st)) {
                        // Cookie 在、页面没到位：多半是页面还没加载完，别直接判未登录
                        spark.lastDiag = "state=" + st + " · " + spark.dy.diag();
                        spark.log("error", "私信页未就绪 · " + spark.lastDiag);
                        return;
                    }
                    if (!spark.dy.hasSessionCookie()) {
                        spark.lastDiag = "没有登录 Cookie state=" + st + " · " + spark.dy.diag();
                        spark.log("error", "读取会话列表失败：没有登录 Cookie，请先在应用内登录抖音");
                        return;
                    }
                    spark.lastDiag = "state=" + st + " · " + spark.dy.diag();
                    spark.log("error", "页面显示未登录（Cookie 存在，可能被风控）· " + spark.lastDiag);
                    return;
                }
                JSONArray convs = spark.dy.listConversations(8);
                spark.friendsCache = convs;
                spark.friendsAt = System.currentTimeMillis() / 1000;
                spark.saveConvs(convs);
                if (convs.length() == 0) {
                    spark.lastDiag = spark.dy.lastError;
                    spark.log("error", "会话列表为空 · " + spark.lastError);
                } else {
                    spark.lastDiag = "";
                    spark.log("sys", "读取到 " + convs.length() + " 个会话");
                }
                // 页面留着 90 秒：马上再点一次「重新读取」就不用重新加载抖音（释放见 finally）
            } catch (Exception e) {
                spark.log("error", "读取会话列表异常：" + e);
            } finally {
                spark.readingConvs = false;
                // 失败路径（没登录 / 页面没就绪）也要把页面安排在这 90 秒后释放，
                // 否则一次失败的读取会把 WebView 一直挂在后台
                spark.dy.keepWarm(90_000L);
                spark.dy.showUi = false;
                DY_LOCK.unlock();
            }
        }, "spark-read");
        t.setDaemon(true);
        t.start();
        return o;
    }

    public JSONObject checkAccount() {
        JSONObject o = ok();
        // Cookie 判定是瞬时的（<1ms），有 Cookie 就直接同步判「已登录」并返回，
        // 前端点完立刻拿到结果，不会再出现「界面写着已登录、toast 说没检测到」这种自相矛盾
        if (spark.dy.hasSessionCookie()) {
            try {
                JSONObject acc = new JSONObject();
                acc.put("logged_in", true);
                acc.put("checked_at", System.currentTimeMillis() / 1000);
                acc.put("nickname", "");
                spark.store.patch(new JSONObject().put("account", acc));
                spark.log("auth", "登录态正常");
                o.put("account", acc);
            } catch (Exception ignored) {
            }
            return o;
        }
        JSONObject cur = spark.store.all().optJSONObject("account");
        if (cur != null) {
            try {
                // 没有 Cookie 就不能沿用上次那句「已登录」，否则界面会在已登录/未登录之间来回跳
                cur = new JSONObject(cur.toString());
                cur.put("logged_in", false);
                o.put("account", cur);
            } catch (Exception ignored) {
            }
        }
        // 没有 Cookie 就直接判未登录。以前这里会开一次聊天页去「猜」，
        // 每次都要加载 20 秒并抢走同一块 WebView，正是界面状态来回跳、卡顿的来源。
        JSONObject acc = new JSONObject();
        try {
            acc.put("logged_in", false);
            acc.put("checked_at", System.currentTimeMillis() / 1000);
            acc.put("nickname", "");
            acc.put("reason", "浏览器里没有抖音登录凭据");
            spark.store.patch(new JSONObject().put("account", acc));
        } catch (Exception ignored) {
        }
        try {
            o.put("account", acc);
        } catch (Exception ignored) {
        }
        spark.log("auth", "还没登录，先去登录一次");
        return o;
    }
}
