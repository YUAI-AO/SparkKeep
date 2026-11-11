package com.spark.keep;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

/**
 * 常驻后台：前台服务 + 每分钟 tick + AlarmManager 兜底唤醒。
 * 关掉界面（Activity 销毁）时本服务不受影响，续火花继续跑。
 */
public class SparkService extends Service {

    public static final String CH = "spark_keep";
    public static final String CH_ALERT = "spark_alert";
    public static final int NID = 1001;
    public static final int NID_ALERT = 1002;
    /** 正在跑任务时的那条进度通知：单独一条，跑完就撤掉 */
    public static final int NID_JOB = 1003;
    private static final long TICK_MS = 60_000L;
    /** 上一次真正投出去的常驻通知内容；没变就不再重投（见 refreshNotification） */
    private String lastNotiKey = "";
    /** 失败提醒的防抖：同一条标题 10 分钟只响一次（见 alert） */
    private static String lastAlertTitle = "";
    private static long lastAlertTs = 0L;

    private Handler handler;
    private Runnable ticker;
    private Runnable jobWatch;
    private boolean jobNotiOn = false;
    private String lastJobKey = "";
    private Spark spark;

    @Override
    public void onCreate() {
        super.onCreate();
        spark = Spark.get(this);
        spark.serviceRunning = true;
        handler = new Handler(Looper.getMainLooper());
        createChannel();
        startForeground(NID, buildNotification());
        scheduleAlarm();
        ticker = new Runnable() {
            @Override
            public void run() {
                try {
                    spark.tick();
                } catch (Exception ignored) {
                }
                refreshNotification();
                handler.postDelayed(this, TICK_MS);
            }
        };
        handler.post(ticker);
        // 任务状态一变就立刻推一次通知，不依赖巡检周期
        spark.jobHook = new Runnable() {
            @Override
            public void run() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            syncJobNotification();
                        } catch (Exception ignored) {
                        }
                    }
                });
            }
        };
        startJobWatch();
    }

    /**
     * 任务通知的巡检节奏：跑任务时 1.2 秒（进度要跟得上），空闲时 8 秒。
     * 任务一开始由 spark.jobHook 立刻踢一脚，所以空闲降频不会让转圈通知迟到。
     */
    private void startJobWatch() {
        if (jobWatch != null) return;
        jobWatch = new Runnable() {
            @Override
            public void run() {
                boolean active = false;
                try {
                    active = syncJobNotification();
                } catch (Exception ignored) {
                }
                handler.postDelayed(this, active ? 1200 : 8000);
            }
        };
        handler.postDelayed(jobWatch, 600);
    }

    /**
     * 任务进行中的那条通知：标题「正在惊醒中 · 名字」，正文带进度，图标转圈；
     * 任务一结束就 cancel 掉，不留残条。
     * 返回当前是否真的在跑任务 —— 调用方据此决定下一次多久再看一眼。
     */
    private boolean syncJobNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return false;
        JSONObject job = spark.job;
        boolean active = job != null && job.optBoolean("active", false);
        if (!active) {
            // 不看内存里的 jobNotiOn：服务被 onTaskRemoved 重建、或进程被系统回收过之后，
            // 这个标记是 false，但通知栏里可能还留着上一条「正在惊醒中」，
            // 而它是 setOngoing 的划不掉 —— 不无条件撤就会一直挂在那儿。
            nm.cancel(NID_JOB);
            jobNotiOn = false;
            lastJobKey = "";
            return false;
        }
        String who = job.optString("current", "");
        int done = job.optInt("done", 0);
        int total = job.optInt("total", 0);
        String step = job.optString("step", "");
        String title = who.isEmpty() ? "正在惊醒中" : ("正在惊醒中 · " + who);
        String body = total > 0 ? (step.isEmpty() ? "" : step + " · ") + done + "/" + total
                : (step.isEmpty() ? "准备中" : step);
        String key = title + "|" + body;
        if (key.equals(lastJobKey)) return true;
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 3, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .setShowWhen(false)
                .setProgress(0, 0, true);
        if (Build.VERSION.SDK_INT >= 21) b.setColor(0xFFD9F24C);
        nm.notify(NID_JOB, b.build());
        jobNotiOn = true;
        lastJobKey = key;
        return true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (spark == null) spark = Spark.get(this);
        spark.serviceRunning = true;
        if (intent != null && "boot".equals(intent.getStringExtra("from"))) {
            spark.log("sys", "开机自启，后台已就绪");
        }
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "续火花后台", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            c.setSound(null, null);
            c.enableVibration(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(c);
        }
    }

    /**
     * 「没发出去」这种必须要让人知道的事，走一条独立的、会响会震的通知。
     * 静默的常驻通知只在通知栏里躺着，用户根本不会注意到失败。
     */
    public static void alert(android.content.Context ctx, String title, String body) {
        try {
            /*
             * 防抖：同一件事 10 分钟内只响一次。
             * 未登录这类持续性故障会让每分钟的 tick 都失败，不防抖就是一天上千条，
             * 系统会把它降噪，真正该看的那条反而被淹掉。
             */
            long now = System.currentTimeMillis();
            if (title.equals(lastAlertTitle) && now - lastAlertTs < 600_000L) return;
            lastAlertTitle = title;
            lastAlertTs = now;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel c = new NotificationChannel(CH_ALERT, "续火花提醒",
                        NotificationManager.IMPORTANCE_HIGH);
                c.setShowBadge(true);
                c.enableVibration(true);
                nm.createNotificationChannel(c);
            }
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, 2, open,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, CH_ALERT)
                    : new Notification.Builder(ctx);
            b.setContentTitle(title)
                    .setContentText(body)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            if (Build.VERSION.SDK_INT >= 21) b.setColor(0xFFF0A868);
            if (Build.VERSION.SDK_INT >= 16) b.setStyle(new Notification.BigTextStyle().bigText(body));
            nm.notify(NID_ALERT, b.build());
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        b.setContentTitle(textTitle())
                .setContentText(textBody())
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT >= 21) b.setColor(0xFFD9F24C);
        return b.build();
    }

    private String textTitle() {
        try {
            JSONObject job = spark.job;
            if (job.optBoolean("active", false)) {
                return "续火花中 · " + job.optString("current", "");
            }
        } catch (Exception ignored) {
        }
        return "续火花后台待机";
    }

    private String textBody() {
        String base;
        try {
            JSONObject nr = spark.nextRunInfo();
            boolean ran = nr.optBoolean("ran_today", false);
            base = (ran ? "今日已完成 · 下次 " : "下次 ") + nr.optString("at", "");
        } catch (Exception ignored) {
            base = "运行中";
        }
        String mem = memLine();
        return mem.isEmpty() ? base : (base + " · " + mem);
    }

    /* ---------- 后台占用（显示在通知第二行，随通知一起更新） ---------- */

    private String memText = "";
    private long memAt = 0L;
    private int memMbLast = 0;

    /**
     * 内存数值放在通知正文里，但更新得很克制：
     * 15 分钟最多重算一次，且变化不到 8 MB 就不改内容 ——
     * 否则每分钟一条新内容会把通知刷成「降噪」，失败提醒也跟着收不到。
     */
    private String memLine() {
        long now = System.currentTimeMillis();
        if (!memText.isEmpty() && now - memAt < 15 * 60 * 1000L) return memText;
        int mb;
        try {
            android.os.Debug.MemoryInfo mi = new android.os.Debug.MemoryInfo();
            android.os.Debug.getMemoryInfo(mi);
            mb = mi.getTotalPss() / 1024;
        } catch (Throwable t) {
            mb = 0;
        }
        if (mb <= 0) {
            try {
                Runtime rt = Runtime.getRuntime();
                mb = (int) ((rt.totalMemory() - rt.freeMemory()) / 1048576L);
            } catch (Throwable t) {
                mb = 0;
            }
        }
        memAt = now;
        if (mb <= 0) return memText;
        if (!memText.isEmpty() && Math.abs(mb - memMbLast) < 8) return memText;
        memMbLast = mb;
        memText = "后台占用 " + mb + " MB";
        return memText;
    }

    private void refreshNotification() {
        try {
            /*
             * 以前这里每 60 秒无条件 notify 一次同一个 id，一天 1440 次。
             * 系统会把这样反复重投的通知标记成「降噪」（dumpsys 里 demoted 计数），
             * 连失败提醒都会被一起降级 —— 也就是「失败没有提醒」的真正原因之一。
             * 现在只有内容真的变了才重投。
             */
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            String key = textTitle() + "|" + textBody();
            /*
             * 通知被清掉就补回来。
             * 有的系统在划掉最近任务、或「一键清理」时会连前台通知一起抹掉，
             * 服务其实还活着，但通知栏空了 —— 用户看到的就是「退出应用后通知没了」。
             * 这里一分钟查一次自己这条还在不在，不在就重投一次，不算高频刷屏。
             */
            boolean missing = !ownsNotification(nm);
            if (!missing && key.equals(lastNotiKey)) return;
            nm.notify(NID, buildNotification());
            lastNotiKey = key;
        } catch (Exception ignored) {
        }
    }

    /** 当前通知栏里是否还挂着我们自己的那条常驻通知（API 23 以下无法判断，默认认为在）。 */
    private boolean ownsNotification(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            for (android.service.notification.StatusBarNotification s : nm.getActiveNotifications()) {
                if (s != null && s.getId() == NID) return true;
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Doze 下 Handler 会被冻结，用精确闹钟每 15 分钟兜一次。 */
    private void scheduleAlarm() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(this, SparkService.class);
            PendingIntent pi = PendingIntent.getService(this, 42, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            long at = System.currentTimeMillis() + 15 * 60 * 1000L;
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户从最近任务里划掉 App：界面内存已经释放，后台要立刻续上。
        // 先走系统 STICKY 重启，再自己抢一次启动（有的 ROM 划掉就杀进程，
        // 只靠 15 分钟的闹钟兜底，通知会在通知栏里空一大截）。
        spark.log("sys", "界面已退出，后台继续运行");
        SparkService.start(this, false);
        scheduleAlarm();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        spark.serviceRunning = false;
        spark.jobHook = null;
        try {
            if (handler != null && ticker != null) handler.removeCallbacks(ticker);
            if (handler != null && jobWatch != null) handler.removeCallbacks(jobWatch);
        } catch (Exception ignored) {
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NID_JOB);
        } catch (Exception ignored) {
        }
        spark.log("sys", "后台服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context c, boolean boot) {
        Intent i = new Intent(c, SparkService.class);
        if (boot) i.putExtra("from", "boot");
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, SparkService.class));
        } catch (Exception ignored) {
        }
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NID);
        } catch (Exception ignored) {
        }
    }

    public static boolean ignoringBattery(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }
}
