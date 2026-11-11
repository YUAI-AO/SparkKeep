package com.spark.keep;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * 引擎：等价于桌面版的 server.py App —— 状态聚合、定时判断、任务执行。
 * Activity 与 Service 共享同一个实例（进程内单例）。
 */
public class Spark {

    private static Spark I;

    public final Context ctx;
    public final Store store;
    public final Dy dy;
    private final Random rnd = new Random();

    public final Object jobLock = new Object();
    public JSONObject job = new JSONObject();
    public volatile JSONArray friendsCache = new JSONArray();
    public volatile long friendsAt = 0;
    /** 会话列表读取中（防止连点「重新读取」开出两条队列） */
    public volatile boolean readingConvs = false;
    /** 最近一次抓取失败时的页面现场（URL / 标题 / 节点数 / 正文摘要），前端直接显示 */
    public volatile String lastDiag = "";
    public volatile boolean serviceRunning = false;
    /** 任务状态一变就回调一次，前台服务据此立刻刷新通知，不必高频轮询 */
    public volatile Runnable jobHook;

    /** 叫一下前台服务；没有服务在跑就什么也不做。 */
    private void kickJob() {
        Runnable h = jobHook;
        if (h == null) return;
        try {
            h.run();
        } catch (Exception ignored) {
        }
    }

    private Spark(Context c) {
        ctx = c.getApplicationContext();
        store = new Store(ctx);
        dy = new Dy(ctx);
        resetJob();
        loadConvs();
    }

    public static synchronized Spark get(Context c) {
        if (I == null) I = new Spark(c);
        return I;
    }

    public static synchronized Spark peek() {
        return I;
    }

    private void resetJob() {
        try {
            job = new JSONObject();
            job.put("active", false);
            job.put("step", "待机");
            job.put("done", 0);
            job.put("total", 0);
            job.put("current", "");
            job.put("wait", 0);
            job.put("stop", false);
            job.put("trigger", "");
            job.put("results", new JSONArray());
        } catch (Exception ignored) {
        }
    }

    // ---------------- 时间 ----------------

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
    }

    public static String hhmm() {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
    }

    /** 解析配置里的 "HH:MM"，坏值回退 09:30。 */
    public static int[] parseTime(String s) {
        try {
            if (s == null) return new int[]{9, 30};
            String[] p = s.trim().split(":");
            int h = Integer.parseInt(p[0].trim());
            int m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
            if (h < 0 || h > 23 || m < 0 || m > 59) return new int[]{9, 30};
            return new int[]{h, m};
        } catch (Exception e) {
            return new int[]{9, 30};
        }
    }

    /** 今天的目标时刻（含抖动），与桌面版 _daily_target 同义。 */
    public Calendar dailyTarget() {
        JSONObject cfg = store.copy();
        JSONObject sch = cfg.optJSONObject("schedule");
        int[] hm = parseTime(sch == null ? "09:30" : sch.optString("time", "09:30"));
        int jitter = sch == null ? 0 : sch.optInt("jitter_minutes", 0);
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hm[0]);
        c.set(Calendar.MINUTE, hm[1]);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (jitter > 0) {
            // 用当天秒数做种子，同一天抖动幅度稳定（重启不会乱跳）
            long seed = Long.parseLong(today().replace("-", ""));
            int off = (int) (new Random(seed).nextInt(jitter * 2 + 1) - jitter);
            c.add(Calendar.MINUTE, off);
        }
        return c;
    }

    public JSONObject nextRunInfo() {
        JSONObject cfg = store.copy();
        Calendar target = dailyTarget();
        Calendar now = Calendar.getInstance();
        boolean ran = today().equals(cfg.optString("last_run_date", ""))
                || (today().equals(cfg.optString("last_try_date", ""))
                    && cfg.optInt("last_try_count", 0) >= 2);
        if (ran) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        } else if (target.getTimeInMillis() <= now.getTimeInMillis()) {
            long late = now.getTimeInMillis() - target.getTimeInMillis();
            if (late > 6L * 3600 * 1000) target.add(Calendar.DAY_OF_YEAR, 1);
        }
        if (target.getTimeInMillis() < now.getTimeInMillis()) target = (Calendar) now.clone();
        JSONObject o = new JSONObject();
        try {
            o.put("at", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(target.getTime()));
            o.put("ts", target.getTimeInMillis() / 1000);
            long sec = Math.max(0, (target.getTimeInMillis() - now.getTimeInMillis()) / 1000);
            o.put("seconds", sec);
            o.put("ran_today", ran);
            JSONObject sch = cfg.optJSONObject("schedule");
            String t = sch == null ? "09:30" : sch.optString("time", "09:30");
            int j = sch == null ? 0 : sch.optInt("jitter_minutes", 0);
            o.put("plan", t + " ±" + j + " min");
        } catch (Exception ignored) {
        }
        return o;
    }

    // ---------------- 今日已发 ----------------

    public Set<String> sentToday() {
        Set<String> out = new HashSet<>();
        JSONObject rec = store.all().optJSONObject("sent_today");
        if (rec != null && today().equals(rec.optString("date", ""))) {
            JSONArray names = rec.optJSONArray("names");
            if (names != null) for (int i = 0; i < names.length(); i++) out.add(names.optString(i));
        }
        return out;
    }

    public void markSent(List<String> names) {
        Set<String> cur = sentToday();
        cur.addAll(names);
        List<String> list = new ArrayList<>(cur);
        java.util.Collections.sort(list);
        JSONArray arr = new JSONArray();
        for (String s : list) arr.put(s);
        JSONObject rec = new JSONObject();
        try {
            rec.put("date", today());
            rec.put("names", arr);
            JSONObject p = new JSONObject().put("sent_today", rec);
            store.patch(p);
        } catch (Exception ignored) {
        }
    }

    // ---------------- 会话缓存 ----------------

    private File convsFile() {
        return new File(store.dir, "conversations.json");
    }

    public void loadConvs() {
        try {
            File f = convsFile();
            if (!f.exists()) return;
            JSONObject o = new JSONObject(Store.readAll(f));
            friendsCache = o.optJSONArray("items") == null ? new JSONArray() : o.optJSONArray("items");
            friendsAt = o.optLong("at", 0);
        } catch (Exception ignored) {
        }
    }

    public void saveConvs(JSONArray convs) {
        maybeRotateAvatars();
        try {
            JSONObject o = new JSONObject();
            o.put("at", friendsAt);
            o.put("items", convs);
            Store.writeAll(convsFile(), o.toString());
        } catch (Exception ignored) {
        }
    }

    private void maybeRotateAvatars() {
        File idx = new File(store.avatars, ".idx");
        long now = System.currentTimeMillis();
        long last = idx.exists() ? idx.lastModified() : 0;
        if (now - last < 24L * 3600 * 1000) return;
        // 超过一天：清掉 48 小时前的头像，保留最近一批
        File[] fs = store.avatars.listFiles();
        if (fs == null) return;
        long keep = 12L * 3600 * 1000;
        for (File f : fs) {
            if (now - f.lastModified() > keep) f.delete();
        }
        try {
            idx.createNewFile();
        } catch (Exception ignored) {
        }
    }

    // ---------------- 状态 ----------------

    public JSONObject state() {
        JSONObject cfg = store.copy();
        JSONObject runtime = new JSONObject();
        try {
            JSONObject j = new JSONObject(job.toString());
            runtime.put("job", j);
            runtime.put("browser_alive", dy.wv != null);
            runtime.put("wallpaper", wallpaperUrl());
            runtime.put("today", today());
            JSONObject met = new JSONObject();
            Runtime rt = Runtime.getRuntime();
            double mb = (rt.totalMemory() - rt.freeMemory()) / 1048576.0;
            met.put("mem_mb", Math.round(mb * 10) / 10.0);
            met.put("cpu", 0.0);
            runtime.put("metrics", met);
            runtime.put("service", serviceRunning);
            runtime.put("sdk", Build.VERSION.SDK_INT);
            runtime.put("platform", "android");
            runtime.put("keep_alive", cfg.optBoolean("keep_alive", true));
            runtime.put("boot_start", cfg.optBoolean("boot_start", true));
            runtime.put("ignore_battery", SparkService.ignoringBattery(ctx));
            runtime.put("autostart", cfg.optBoolean("boot_start", true));
            runtime.put("wv", Dy.webViewVersion(ctx));
            runtime.put("wv_old", Dy.webViewTooOld(ctx));
            runtime.put("release_delay", cfg.optInt("release_delay", 30));
        } catch (Exception ignored) {
        }
        JSONObject out = new JSONObject();
        try {
            out.put("runtime", runtime);
            out.put("config", cfg);
            out.put("friends", friendsCache);
            out.put("friends_at", friendsAt);
            out.put("friends_reading", readingConvs);
            JSONArray sent = new JSONArray();
            List<String> s = new ArrayList<>(sentToday());
            java.util.Collections.sort(s);
            for (String n : s) sent.put(n);
            out.put("today_sent", sent);
            out.put("next_run", nextRunInfo());
        } catch (Exception ignored) {
        }
        return out;
    }

    public String wallpaperUrl() {
        File w = new File(store.dir, "wallpaper.img");
        return w.exists() ? ("http://spark.local/wallpaper?v=" + w.lastModified()) : "";
    }

    // ---------------- 任务 ----------------

    public boolean jobActive() {
        return job.optBoolean("active", false);
    }

    public synchronized boolean runJob(String trigger, List<String> onlyNames, boolean forceAll) {
        if (jobActive()) return false;
        JSONObject fresh = new JSONObject();
        try {
            fresh.put("active", true);
            fresh.put("step", "准备中");
            fresh.put("done", 0);
            fresh.put("total", 0);
            fresh.put("current", "");
            fresh.put("wait", 0);
            fresh.put("stop", false);
            fresh.put("trigger", trigger);
            fresh.put("results", new JSONArray());
        } catch (Exception ignored) {
        }
        job = fresh;
        kickJob();
        Thread t = new Thread(() -> worker(trigger, onlyNames, forceAll), "spark-job");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public boolean stopJob() {
        if (!jobActive()) return false;
        try {
            job.put("stop", true);
            job.put("wait", 0);
            job.put("step", "正在停止…");
        } catch (Exception ignored) {
        }
        return true;
    }

    private void step(String s) {
        try {
            job.put("step", s);
        } catch (Exception ignored) {
        }
    }

    private void worker(String trigger, List<String> onlyNames, boolean forceAll) {
        JSONObject cfg = store.copy();
        List<JSONObject> sel = enabledFriends(cfg, onlyNames);
        Dy.sleep(150);
        try {
            Bridge.DY_LOCK.lock();
            if (!serviceRunning) {
                log("error", "后台服务没有运行，请先在设置里保持后台");
            }
            step("打开聊天页");
            String st = dy.openChat(45_000);
            if (st == null || "loading".equals(st)) {
                fail("打不开抖音私信页：看看网络，或者抖音是不是被限了");
                return;
            }
            if (!"user".equals(st)) {
                fail("未登录，请先在「账号」页扫码登录");
                return;
            }
            if (sel.isEmpty() && !cfg.optBoolean("auto_enroll", true)) {
                log("error", "名单为空，请先在好友页勾选需要续的人");
                step("名单为空");
                markRan();
                return;
            }

            step("读取会话列表");
            JSONArray convs = dy.listConversations(8);
            if (convs.length() > 0) {
                friendsCache = convs;
                friendsAt = System.currentTimeMillis() / 1000;
                saveConvs(convs);
                // 真读到会话，说明登录是好的：以「能不能读到数据」为准回写登录态，
                // 比看 Cookie 更可靠（有的系统把 Cookie 读成空）
                try {
                    store.patch(new JSONObject().put("account",
                            new JSONObject().put("logged_in", true)
                                    .put("checked_at", friendsAt)
                                    .put("nickname", "")));
                } catch (Exception ignored) {
                }
            }
            java.util.Map<String, JSONObject> byName = new java.util.HashMap<>();
            for (int i = 0; i < convs.length(); i++) {
                JSONObject c = convs.optJSONObject(i);
                if (c != null) byName.put(c.optString("name"), c);
            }

            // 自动纳入有火花的人
            if (convs.length() > 0 && cfg.optBoolean("auto_enroll", true)) {
                Set<String> have = new HashSet<>();
                JSONArray fr = cfg.optJSONArray("friends");
                if (fr != null) for (int i = 0; i < fr.length(); i++) have.add(fr.optJSONObject(i).optString("name"));
                JSONArray added = new JSONArray();
                for (int i = 0; i < convs.length(); i++) {
                    JSONObject c = convs.optJSONObject(i);
                    if (c == null) continue;
                    String kind = c.optString("kind", "");
                    if (c.optBoolean("group", false)) continue;
                    if (!"alive".equals(kind) && !"cooling".equals(kind)) continue;
                    String n = c.optString("name", "");
                    if (n.isEmpty() || have.contains(n)) continue;
                    JSONObject f = new JSONObject();
                    try {
                        f.put("name", n);
                        f.put("enabled", true);
                        f.put("avatar", c.optString("avatar", ""));
                        f.put("streak", c.optString("streak", ""));
                        f.put("kind", kind);
                    } catch (Exception ignored) {
                    }
                    added.put(f);
                    have.add(n);
                }
                if (added.length() > 0) {
                    JSONArray all = new JSONArray();
                    if (fr != null) for (int i = 0; i < fr.length(); i++) all.put(fr.opt(i));
                    for (int i = 0; i < added.length(); i++) all.put(added.opt(i));
                    JSONObject p = new JSONObject();
                    try {
                        p.put("friends", all);
                        store.patch(p);
                    } catch (Exception ignored) {
                    }
                    cfg = store.copy();
                    sel = enabledFriends(cfg, onlyNames);
                    log("plan", "自动纳入 " + added.length() + " 位有火花的人");
                }
            }

            boolean checkToday = cfg.optBoolean("check_today", true);
            Set<String> sent = sentToday();
            List<JSONObject> targets = new ArrayList<>();
            JSONArray jr = job.optJSONArray("results");
            if (jr == null) jr = new JSONArray();
            for (JSONObject f : sel) {
                String name = f.optString("name");
                JSONObject c = byName.get(name);
                if (c == null) {
                    addResult(jr, name, false, "会话列表里没找到", null);
                    continue;
                }
                if (c.optBoolean("group", false)) {
                    addResult(jr, name, true, "群聊，跳过", null);
                    continue;
                }
                if (checkToday && !forceAll && sent.contains(name)) {
                    addResult(jr, name, true, "今日已续，跳过", null);
                    continue;
                }
                if (checkToday && !forceAll && "alive".equals(c.optString("kind"))) {
                    addResult(jr, name, true, "今日已互动，跳过", null);
                    continue;
                }
                targets.add(f);
            }
            try {
                job.put("results", jr);
            } catch (Exception ignored) {
            }

            if (targets.isEmpty()) {
                if (sel.isEmpty()) {
                    log("error", "名单为空，请先在好友页勾选需要续的人");
                    step("名单为空");
                } else {
                    log("plan", "名单里的人都不用续");
                    step("无可发送对象");
                }
                markRan();
                return;
            }

            try {
                job.put("total", targets.size());
                job.put("step", "发送中");
            } catch (Exception ignored) {
            }

            JSONArray msgs = cfg.optJSONArray("messages");
            List<String> messages = new ArrayList<>();
            if (msgs != null) for (int i = 0; i < msgs.length(); i++) messages.add(msgs.optString(i));
            if (messages.isEmpty()) messages.add("续火花");

            JSONObject lim = cfg.optJSONObject("limits");
            int minGap = lim == null ? 2 : lim.optInt("min_gap_seconds", 2);
            int maxGap = lim == null ? 5 : lim.optInt("max_gap_seconds", 5);
            if (maxGap < minGap) maxGap = minGap;

            boolean vary = cfg.optBoolean("vary_messages", true);
            Set<String> used = new HashSet<>();
            List<String> okNames = new ArrayList<>();

            for (int i = 0; i < targets.size(); i++) {
                JSONObject f = targets.get(i);
                String name = f.optString("name");
                if (job.optBoolean("stop", false)) {
                    addResult(jr, name, false, "已手动停止", null);
                    break;
                }
                try {
                    job.put("current", name);
                    job.put("wait", 0);
                    job.put("step", "正在发送 · " + name);
                } catch (Exception ignored) {
                }
                if (!dy.openConversation(name)) {
                    addResult(jr, name, false, dy.lastError, null);
                    continue;
                }
                String text;
                if (vary) {
                    List<String> pool = new ArrayList<>();
                    for (String m : messages) if (!used.contains(m)) pool.add(m);
                    if (pool.isEmpty()) pool = messages;
                    text = pool.get(rnd.nextInt(pool.size()));
                    used.add(text);
                } else {
                    text = messages.get(rnd.nextInt(messages.size()));
                }
                boolean ok = dy.sendMessage(text);
                addResult(jr, name, ok, ok ? "已发送" : dy.lastError, text);
                if (ok) okNames.add(name);
                log(ok ? "send" : "error", ok ? (name + " ← " + text) : (name + " 发送失败：" + dy.lastError));

                if (i < targets.size() - 1 && !job.optBoolean("stop", false)) {
                    int gap = minGap + (maxGap > minGap ? rnd.nextInt(maxGap - minGap + 1) : 0);
                    int left = gap;
                    while (left > 0) {
                        if (job.optBoolean("stop", false)) break;
                        try {
                            job.put("wait", left);
                            job.put("step", "等待 " + left + " 秒 · 下一位");
                        } catch (Exception ignored) {
                        }
                        Dy.sleep(1000);
                        left--;
                    }
                }
            }
            try {
                job.put("results", jr);
                // done 只算真正发出去的那几条：以前不管成败一律写 total，
                // 结果是一条都没发也在界面上显示「5/5 完成」，看着像成功了
                int sentCount = okNames.size();
                int failCount = Math.max(0, targets.size() - sentCount);
                job.put("done", sentCount);
                job.put("failed", failCount);
            } catch (Exception ignored) {
            }
            if (!okNames.isEmpty()) markSent(okNames);
            markRan();
            try {
                int sentCount = okNames.size();
                int failCount = Math.max(0, targets.size() - sentCount);
                job.put("step", job.optBoolean("stop", false) ? "已停止"
                        : ("完成 · 成功 " + sentCount + " / 失败 " + failCount));
                log(sentCount > 0 ? "plan" : "error",
                        "本次结束 · 成功 " + sentCount + " 条 · 失败 " + failCount
                                + (failCount > 0 ? "（原因见各条结果）" : ""));
                // 只要有一条没发出去，就弹一条会响的通知：静默通知用户根本不会注意到
                if (failCount > 0) {
                    String why = firstFailReason(jr);
                    alertFail(sentCount == 0 ? "续火花这次一条都没发出去" : "续火花有 " + failCount + " 条没发出去",
                            "成功 " + sentCount + " 条 · 失败 " + failCount + " 条"
                                    + (why.isEmpty() ? "" : "\n第一条失败：" + why));
                }
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            fail("跑出错了：" + e);
        } finally {
            try {
                job.put("active", false);
            } catch (Exception ignored) {
            }
            kickJob();
            dy.destroy();
            Bridge.DY_LOCK.unlock();
        }
    }

    private List<JSONObject> enabledFriends(JSONObject cfg, List<String> only) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray fr = cfg.optJSONArray("friends");
        if (fr == null) return out;
        for (int i = 0; i < fr.length(); i++) {
            JSONObject f = fr.optJSONObject(i);
            if (f == null || !f.optBoolean("enabled", false)) continue;
            if (only != null && !only.isEmpty() && !only.contains(f.optString("name"))) continue;
            out.add(f);
        }
        return out;
    }

    private void addResult(JSONArray jr, String name, boolean ok, String msg, String text) {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("ok", ok);
            o.put("msg", msg == null ? "" : msg);
            if (text != null) o.put("text", text);
        } catch (Exception ignored) {
        }
        jr.put(o);
    }

    private void markRan() {
        try {
            JSONObject p = new JSONObject().put("last_run_date", today());
            store.patch(p);
        } catch (Exception ignored) {
        }
    }

    private void alertFail(String title, String body) {
        try {
            SparkService.alert(ctx, title, body);
        } catch (Exception ignored) {
        }
    }

    private String firstFailReason(JSONArray results) {
        if (results == null) return "";
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            if (!r.optBoolean("ok", false)) {
                String why = r.optString("msg", "");
                return r.optString("name", "") + " · " + why;
            }
        }
        return "";
    }

    private void fail(String msg) {
        log("error", msg);
        step(msg);
        // 失败必须让人看见：只写日志等于没提醒，用户压根不会翻记录页
        alertFail("续火花这次没跑成", msg + "（打开 App 看「记录」页）");
        // 记一次「今天试过了」。以前失败不记账，tick 每分钟又会重新拉一次页面，
        // 结果就是一天里每秒都在打开失败、反复刷新，耗电耗流量还把界面卡住。
        try {
            JSONObject cfg = store.copy();
            int n = today().equals(cfg.optString("last_try_date", ""))
                    ? cfg.optInt("last_try_count", 0) : 0;
            store.patch(new JSONObject()
                    .put("last_try_date", today())
                    .put("last_try_count", n + 1)
                    .put("last_try_ts", System.currentTimeMillis() / 1000));
            if (n + 1 >= 2) {
                // 连续两次没成，今天就不再自动重试，等用户处理完手动点一次
                store.patch(new JSONObject().put("last_run_date", today()));
                log("plan", "今天连着两次都没成，先不自动试了；处理好之后点「立即续一次」手动跑");
            }
        } catch (Exception ignored) {
        }
        try {
            job.put("active", false);
        } catch (Exception ignored) {
        }
        kickJob();
    }

    public void log(String kind, String text) {
        store.log(kind.toUpperCase(Locale.getDefault()), text);
    }

    /** 定时：到点就跑。由 Service 每分钟调用。 */
    public void tick() {
        JSONObject cfg = store.copy();
        if (!cfg.optBoolean("enabled", true)) return;
        JSONArray fr = cfg.optJSONArray("friends");
        boolean any = false;
        if (fr != null) for (int i = 0; i < fr.length(); i++) {
            if (fr.optJSONObject(i).optBoolean("enabled", false)) {
                any = true;
                break;
            }
        }
        if (!any && !cfg.optBoolean("auto_enroll", true)) return;
        if (today().equals(cfg.optString("last_run_date", ""))) return;
        // 失败过的自动任务不每分钟重来：至少隔 10 分钟，且当天最多试两次
        int tries = today().equals(cfg.optString("last_try_date", ""))
                ? cfg.optInt("last_try_count", 0) : 0;
        if (tries >= 2) return;
        Calendar target = dailyTarget();
        Calendar now = Calendar.getInstance();
        if (now.getTimeInMillis() < target.getTimeInMillis()) return;
        if (now.getTimeInMillis() - target.getTimeInMillis() > 6L * 3600 * 1000) return;
        if (jobActive()) return;
        long lastTry = cfg.optLong("last_try_ts", 0);
        if (tries > 0 && lastTry > 0
                && now.getTimeInMillis() / 1000 - lastTry < 600) return;
        log("plan", "到点了，开始续今天的火花");
        runJob("auto", null, false);
    }
}
