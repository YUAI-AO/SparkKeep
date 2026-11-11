package com.spark.keep;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 单文件 JSON 配置，字段与桌面版 config.json 对齐，方便两端共用同一套心智模型。
 */
public class Store {

    private final Context ctx;
    public final File dir;
    public final File avatars;
    private final File cfgFile;
    private final File logFile;
    private final Object lock = new Object();
    private JSONObject cfg = new JSONObject();

    public Store(Context c) {
        ctx = c.getApplicationContext();
        dir = new File(ctx.getFilesDir(), "data");
        if (!dir.exists()) dir.mkdirs();
        avatars = new File(dir, "avatars");
        if (!avatars.exists()) avatars.mkdirs();
        cfgFile = new File(dir, "config.json");
        logFile = new File(dir, "runtime.log");
        load();
    }

    public static JSONObject defaults() {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", true);
            o.put("wizard_done", false);
            JSONObject sch = new JSONObject();
            sch.put("time", "09:30");
            sch.put("jitter_minutes", 30);
            o.put("schedule", sch);
            JSONArray msgs = new JSONArray();
            msgs.put("续火花").put("hello").put("晚上好").put("在呢");
            o.put("messages", msgs);
            JSONObject lim = new JSONObject();
            lim.put("min_gap_seconds", 2);
            lim.put("max_gap_seconds", 5);
            o.put("limits", lim);
            o.put("friends", new JSONArray());
            o.put("check_today", true);
            o.put("vary_messages", true);
            o.put("auto_enroll", true);
            o.put("keep_alive", true);
            o.put("boot_start", true);
            JSONObject acc = new JSONObject();
            acc.put("logged_in", false);
            acc.put("nickname", "");
            acc.put("checked_at", 0);
            acc.put("reason", "");
            o.put("account", acc);
            JSONObject look = new JSONObject();
            look.put("veil", 58);
            look.put("accent", "lime");
            look.put("style", "glass");
            look.put("blur", 0);
            look.put("blur_card", 0);
            look.put("wallpaper", "");
            o.put("appearance", look);
            o.put("sent_today", new JSONObject().put("date", "").put("names", new JSONArray()));
            o.put("last_run_date", "");
            o.put("disclaimer_ok", false);
        } catch (Exception ignored) {
        }
        return o;
    }

    private void load() {
        synchronized (lock) {
            if (cfgFile.exists()) {
                try {
                    String s = readAll(cfgFile);
                    cfg = new JSONObject(s);
                } catch (Exception e) {
                    cfg = defaults();
                }
            } else {
                cfg = defaults();
                save();
            }
            // 补齐缺失键
            JSONObject d = defaults();
            for (String k : new String[]{"enabled", "wizard_done", "schedule", "messages", "limits",
                    "friends", "check_today", "vary_messages", "auto_enroll", "keep_alive",
                    "boot_start", "account", "appearance", "sent_today", "last_run_date", "disclaimer_ok"}) {
                if (!cfg.has(k)) {
                    try {
                        cfg.put(k, d.get(k));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    public JSONObject all() {
        synchronized (lock) {
            return cfg;
        }
    }

    public JSONObject copy() {
        synchronized (lock) {
            try {
                return new JSONObject(cfg.toString());
            } catch (Exception e) {
                return defaults();
            }
        }
    }

    public void save() {
        synchronized (lock) {
            try {
                String tmp = cfg.toString();
                File t = new File(cfgFile.getAbsolutePath() + ".tmp");
                writeAll(t, tmp);
                if (cfgFile.exists()) cfgFile.delete();
                t.renameTo(cfgFile);
            } catch (Exception ignored) {
            }
        }
    }

    /** 深层合并 patch 到配置里并落盘。 */
    public void patch(JSONObject p) {
        if (p == null) return;
        synchronized (lock) {
            mergeInto(cfg, p);
            save();
        }
    }

    @SuppressWarnings("unchecked")
    private static void mergeInto(JSONObject dst, JSONObject src) {
        java.util.Iterator<String> it = src.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = src.opt(k);
            if (v instanceof JSONObject) {
                JSONObject cur = dst.optJSONObject(k);
                if (cur == null) {
                    cur = new JSONObject();
                    try {
                        dst.put(k, cur);
                    } catch (Exception ignored) {
                    }
                }
                mergeInto(cur, (JSONObject) v);
            } else {
                try {
                    dst.put(k, v);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void log(String kind, String text) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = ts + "\t" + kind + "\t" + text.replace("\n", " ") + "\n";
        synchronized (lock) {
            try {
                FileOutputStream fos = new FileOutputStream(logFile, true);
                fos.write(line.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.close();
                if (logFile.length() > 400 * 1024) trimLog();
            } catch (Exception ignored) {
            }
        }
    }

    private void trimLog() {
        try {
            String all = readAll(logFile);
            String[] lines = all.split("\n");
            if (lines.length <= 1200) return;
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 1200; i < lines.length; i++) sb.append(lines[i]).append('\n');
            writeAll(logFile, sb.toString());
        } catch (Exception ignored) {
        }
    }

    public List<String[]> readLogs() {
        List<String[]> out = new ArrayList<>();
        synchronized (lock) {
            try {
                String all = readAll(logFile);
                for (String ln : all.split("\n")) {
                    if (ln.trim().isEmpty()) continue;
                    String[] p = ln.split("\t", 3);
                    if (p.length < 3) continue;
                    out.add(new String[]{p[0], p[1], p[2]});
                }
            } catch (Exception ignored) {
            }
            if (out.isEmpty()) out.add(new String[]{"--:--:--", "SYS", "暂无记录"});
            return out;
        }
    }

    public static String readAll(File f) throws IOException {
        if (!f.exists()) return "";
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            byte[] b = new byte[(int) raf.length()];
            raf.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        } finally {
            try {
                raf.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static byte[] readBytes(File f) throws IOException {
        if (!f.exists()) return new byte[0];
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            byte[] b = new byte[(int) raf.length()];
            raf.readFully(b);
            return b;
        } finally {
            try {
                raf.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static void writeAll(File f, String s) throws IOException {
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(s.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } finally {
            try {
                fos.close();
            } catch (Exception ignored) {
            }
        }
    }

    public File cfgFile() {
        return cfgFile;
    }
}
