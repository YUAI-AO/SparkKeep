"""本地服务：127.0.0.1 上的控制台后端 + 每日定时调度。

只用标准库：ThreadingHTTPServer 常驻约 30MB，调度线程 10 秒轮询一次，
不发消息时完全不碰浏览器，所以空闲开销接近于零。
"""
import json
import os
import re
import threading
import time

# 进程自身内存占用（Windows psapi，取 WorkingSet）
try:
    import ctypes
    from ctypes import wintypes

    class _PMC(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD),
                    ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
                    ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                    ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]

    _k32 = ctypes.WinDLL("kernel32.dll")
    _k32.GetCurrentProcess.restype = ctypes.c_void_p
    _psapi = ctypes.WinDLL("psapi.dll")
    _HAVE_PS = True
except Exception:
    _HAVE_PS = False
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import store as store_mod
import webui


_AVA_EXT = (("webp", "image/webp"), ("jpg", "image/jpeg"), ("png", "image/png"),
            ("gif", "image/gif"), ("avif", "image/avif"))
_AVA_CTYPE = {"image/webp": "webp", "image/jpeg": "jpg", "image/png": "png",
              "image/gif": "gif", "image/avif": "avif"}


def _today():
    import datetime
    return datetime.date.today().isoformat()


# 标签页图标：白色火苗剪影
FAVICON_SVG = (
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
    '<path fill="#FFFFFF" d="M12 1.2C12.6 5.2 15.9 7.4 16.9 11.4C17.3 13 17.5 14.4 17.5 15.6'
    'C17.5 19.6 15 22.8 12 22.8C9 22.8 6.5 19.6 6.5 15.6C6.5 13.2 7.7 11 9.2 8.9'
    'C9.5 10.3 10.2 11.2 11.2 11.5C10.7 7.8 11.1 4.2 12 1.2Z"/></svg>'
)


def _parse_hhmm(text, default=(9, 30)):
    try:
        m = re.match(r"^(\d{1,2}):(\d{2})$", str(text).strip())
        if not m:
            return default
        h, mi = int(m.group(1)), int(m.group(2))
        if 0 <= h <= 23 and 0 <= mi <= 59:
            return h, mi
    except Exception:
        pass
    return default


class App:
    def __init__(self, base_dir, port=80, open_console=True):
        self.base_dir = base_dir
        self.port = port
        self.alive = True
        self.job_lock = threading.RLock()
        self.store = store_mod.Store(base_dir)
        # 浏览器与抖音适配层按需加载：不做任何事时，连 ssl / urllib 都不进内存
        self._bm = None
        self._dy = None
        self.job = {"active": False, "step": "", "done": 0, "total": 0, "trigger": "", "results": []}
        # 会话列表落盘，启动直接读上次结果；只有用户点「重新读取」才重新抓
        self.friends_cache = self._load_convs()
        self.friends_at = 0
        self._login_proc = False
        self._met = {"mem_mb": 0.0, "cpu": 0.0}
        self._met_at = 0.0
        self._cpu_prev = (time.process_time(), time.monotonic())
        self._last_req = time.time()
        self._last_trim = time.time()
        self._auto = None
        self._auto_at = 0.0
        self._ava_idx = None
        # 浏览器"热身保留"退场时刻：0 表示不保留。
        # 之前每次读列表/发送完都直接 taskkill，下次又要从头冷启动（Edge 进程 + 页面骨架要 15–22 秒），
        # 用户点两下就得干等两遍。改成用完先留着，空闲到点再回收。
        self._bm_keep = 0.0

    # ---------------- 开机自启（HKCU Run） ----------------
    _RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
    _RUN_NAME = "SparkKeep"

    def autostart(self, enable=None, cached=True):
        """enable=None 只读；True/False 写入或删除自启项。返回当前是否在自启列表里。"""
        if enable is None and cached and self._auto is not None and time.time() - self._auto_at < 15:
            return self._auto
        import winreg
        cur = ""
        try:
            k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, self._RUN_KEY, 0, winreg.KEY_READ)
            try:
                cur, _t = winreg.QueryValueEx(k, self._RUN_NAME)
            finally:
                winreg.CloseKey(k)
        except OSError:
            cur = ""
        if enable is None:
            self._auto = bool(cur)
            self._auto_at = time.time()
            return self._auto
        import sys
        try:
            k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, self._RUN_KEY, 0, winreg.KEY_SET_VALUE)
            try:
                if enable:
                    if getattr(sys, "frozen", False):
                        cmd = '"%s" --boot' % os.path.abspath(sys.executable)
                    else:
                        cmd = '"%s" "%s" --boot' % (sys.executable, os.path.abspath(sys.argv[0]))
                    winreg.SetValueEx(k, self._RUN_NAME, 0, winreg.REG_SZ, cmd)
                else:
                    try:
                        winreg.DeleteValue(k, self._RUN_NAME)
                    except FileNotFoundError:
                        pass
            finally:
                winreg.CloseKey(k)
        except OSError:
            pass
        self._auto = None
        return self.autostart(cached=False)

    # ---------------- 会话列表本地留存 ----------------
    def _conv_path(self):
        return os.path.join(self.store.data_dir, "conversations.json")

    def _load_convs(self):
        try:
            with open(self._conv_path(), "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, list):
                return data
        except Exception:
            pass
        return []

    def save_convs(self, convs):
        try:
            os.makedirs(self.store.data_dir, exist_ok=True)
            with open(self._conv_path(), "w", encoding="utf-8") as f:
                json.dump(convs, f, ensure_ascii=False)
        except Exception:
            pass

    # ---------------- 空闲时把工作集还给系统 --------------
    def touch(self):
        """记录一次网页访问，用来判断是否已经闲置。"""
        self._last_req = time.time()

    def trim_if_idle(self):
        """没人打开网页超过 90 秒，就把物理内存换出去，任务管理器里只留几 MB。
        下次访问会自动缺页换回，首屏最多慢几十毫秒。"""
        if not _HAVE_PS:
            return False
        now = time.time()
        if now - self._last_req < 90 or now - self._last_trim < 120:
            return False
        if self.job.get("active"):
            return False
        try:
            if _psapi.EmptyWorkingSet(ctypes.c_void_p(_k32.GetCurrentProcess())):
                self._last_trim = now
                self._met_at = 0.0
                return True
        except Exception:
            pass
        return False

    @property
    def bm(self):
        if self._bm is None:
            import browser
            # 浏览器层的诊断（清理残留、端口没就绪）也写进历史，出问题时能直接看记录页
            # 端口不写死：由 profile 目录派生，避免第二个实例连到别人的浏览器
            self._bm = browser.BrowserManager(
                os.path.join(self.base_dir, "profile"),
                log=lambda m: self.log("browser", m),
            )
        return self._bm

    @property
    def dy(self):
        if self._dy is None:
            import douyin
            self._dy = douyin.Douyin(self.bm, self.store, self.log)
        return self._dy

    # ---------------- 自身占用 --------------
    def metrics(self):
        """返回本进程的内存(MB)与 CPU 占用百分比，0.5 秒内复用上次结果，不增加开销。"""
        now = time.monotonic()
        if now - self._met_at < 0.5:
            return self._met
        cpu = self._met.get("cpu", 0.0)
        try:
            pt, pw = self._cpu_prev
            cur_t, cur_w = time.process_time(), time.monotonic()
            dt = cur_w - pw
            if dt > 0.05:
                cpu = (cur_t - pt) / dt * 100.0 / (os.cpu_count() or 1)
                cpu = max(0.0, min(100.0, cpu))
                self._cpu_prev = (cur_t, cur_w)
        except Exception:
            pass
        mem = self._met.get("mem_mb", 0.0)
        if _HAVE_PS:
            try:
                pmc = _PMC()
                pmc.cb = ctypes.sizeof(_PMC)
                h = _k32.GetCurrentProcess()
                if _psapi.GetProcessMemoryInfo(ctypes.c_void_p(h), ctypes.byref(pmc), pmc.cb):
                    mem = pmc.WorkingSetSize / 1048576.0
            except Exception:
                pass
        self._met = {"mem_mb": round(mem, 1), "cpu": round(cpu, 1)}
        self._met_at = now
        return self._met

    # ---------------- 浏览器热身保留 --------------
    def browser_keep(self, seconds=150):
        """用完先别关浏览器：紧接着的第二次操作能直接复用，省掉 15–22 秒冷启动。"""
        if seconds <= 0:
            self._bm_keep = 0.0
            return
        self._bm_keep = time.time() + seconds

    def browser_maybe_close(self):
        """空闲到点就回收浏览器，把内存还给系统。有任务在跑时不动它。"""
        if not self._bm_keep or self._bm is None:
            return False
        if time.time() < self._bm_keep:
            return False
        if self.job.get("active"):
            self._bm_keep = time.time() + 60      # 任务在跑就再宽限一会
            return False
        self._bm_keep = 0.0
        try:
            if self._bm.is_alive():
                self._bm.stop()
                self.log("browser", "浏览器空闲超时，已回收内存")
        except Exception:
            pass
        return True

    # ---------------- 头像本地缓存 ----------------
    # 规则：平时一律读本地文件；每天「自动发送」那一次把当前目录降级为 avatars.old
    #（同时删除上上次），新建空的 avatars 目录，随后按抖音新链接重新落盘。
    def _avatar_dirs(self):
        base = self.store.data_dir
        return os.path.join(base, "avatars"), os.path.join(base, "avatars.old")

    def rotate_avatars(self, force=False):
        import hashlib, shutil
        cur, old = self._avatar_dirs()
        today = _today()
        if not force and (self.store.get() or {}).get("last_avatar_rotate") == today:
            return False
        try:
            shutil.rmtree(old, ignore_errors=True)      # 删掉上上次
            if os.path.isdir(cur):
                os.rename(cur, old)                     # 上一次留存
            os.makedirs(cur, exist_ok=True)             # 这一批空目录
        except Exception:
            try:
                os.makedirs(cur, exist_ok=True)
            except Exception:
                pass
        self.store.patch({"last_avatar_rotate": today})
        self._ava_idx = None      # 目录换过，索引作废
        self.log("info", "头像缓存已轮换：上一批保留，上上批已删除")
        return True

    def avatar_get(self, url):
        import hashlib
        if not url.startswith("http"):
            return None
        key = hashlib.sha1(url.encode("utf-8")).hexdigest()[:20]
        # 先查内存索引：页面一开就是几十个头像，别每次都去 stat 十个路径
        idx = self._ava_idx
        if idx is None:
            idx = {}
            for d in self._avatar_dirs():
                try:
                    for fn in os.listdir(d):
                        stem, _, ext = fn.rpartition(".")
                        if stem and ext in ("webp", "jpg", "png", "gif", "avif"):
                            idx.setdefault(stem, os.path.join(d, fn))
                except Exception:
                    pass
            self._ava_idx = idx
        p = idx.get(key)
        if p:
            try:
                with open(p, "rb") as f:
                    body = f.read()
                if body:
                    ctype = {"webp": "image/webp", "jpg": "image/jpeg", "png": "image/png",
                             "gif": "image/gif", "avif": "image/avif"}.get(p.rpartition(".")[2], "image/webp")
                    return body, ctype
            except Exception:
                idx.pop(key, None)
        # 索引里没有时再老实扫一遍（新下载的、或索引被轮换清掉的）
        for d in self._avatar_dirs():
            for ext, ctype in _AVA_EXT:
                p2 = os.path.join(d, key + "." + ext)
                if os.path.exists(p2):
                    try:
                        with open(p2, "rb") as f:
                            body = f.read()
                        if body:
                            idx[key] = p2
                            return body, ctype
                    except Exception:
                        pass
        return None

    def avatar_put(self, url, body, ctype):
        import hashlib
        if not url.startswith("http") or not body:
            return
        ext = _AVA_CTYPE.get((ctype or "").split(";")[0].strip().lower(), "webp")
        key = hashlib.sha1(url.encode("utf-8")).hexdigest()[:20]
        cur, _old = self._avatar_dirs()
        try:
            os.makedirs(cur, exist_ok=True)
            p = os.path.join(cur, key + "." + ext)
            with open(p, "wb") as f:
                f.write(body)
            if self._ava_idx is not None:
                self._ava_idx[key] = p
        except Exception:
            pass

    # ---------------- 日志 --------------
    def log(self, kind, text, extra=None):
        return self.store.log(kind, text, extra)

    # ---------------- 计划 --------------
    def _daily_target(self):
        import datetime, hashlib
        cfg = self.store.get()
        sch = cfg.get("schedule", {})
        h, m = _parse_hhmm(sch.get("time"), (9, 30))
        window = max(0, int(sch.get("jitter_minutes") or 0))
        date_s = _today()
        if window:
            hh = int(hashlib.md5((date_s + "|spark").encode()).hexdigest()[:6], 16)
            offset = hh % (window * 2 + 1) - window
        else:
            offset = 0
        base = datetime.datetime.combine(datetime.date.today(), datetime.time(h, m))
        return base + datetime.timedelta(minutes=offset)

    def next_run_info(self, cfg=None):
        import datetime
        cfg = cfg or self.store.get()
        target = self._daily_target()
        now = datetime.datetime.now()
        today = _today()
        ran = (cfg.get("last_run_date") or "") == today
        if ran:
            target = target + datetime.timedelta(days=1)
        elif target <= now:
            # 错过时间点后留 6 小时补发窗口，再晚就顺延到明天
            if (now - target) > datetime.timedelta(hours=6):
                target = target + datetime.timedelta(days=1)
        if target < now:
            target = now
        return {
            "at": target.strftime("%Y-%m-%d %H:%M"),
            "ts": int(target.timestamp()),
            "seconds": max(0, int((target - now).total_seconds())),
            "ran_today": ran,
            "plan": "%s ±%d min" % (cfg["schedule"].get("time", "09:30"), int(cfg["schedule"].get("jitter_minutes") or 0)),
        }

    def tick(self):
        import datetime
        cfg = self.store.get()
        if not cfg.get("enabled"):
            return
        if not any(f.get("enabled") for f in cfg.get("friends", [])):
            return
        if (cfg.get("last_run_date") or "") == _today():
            return
        target = self._daily_target()
        now = datetime.datetime.now()
        if now < target:
            return
        if (now - target) > datetime.timedelta(hours=6):
            return
        self.log("plan", "到点，开始今日续火花")
        self.run_job(trigger="auto")

    # ---------------- 任务 --------------
    def run_job(self, trigger="manual", only_names=None, force_all=False):
        with self.job_lock:
            if self.job.get("active"):
                return False, "已经有一个任务在跑"
            self.job = {"active": True, "step": "启动浏览器", "done": 0, "total": 0,
                        "current": "", "wait": 0, "stop": False, "trigger": trigger,
                        "results": []}
        t = threading.Thread(target=self._worker, args=(trigger, only_names, force_all), daemon=True)
        t.start()
        return True, "已开始"

    def _worker(self, trigger, only_names, force_all):
        cfg = self.store.get()
        sel = [f for f in cfg.get("friends", []) if f.get("enabled")]
        if only_names:
            sel = [f for f in sel if f.get("name") in only_names]
        sent_today = self._sent_today()
        # 自动续的时候要不要先看"今天是否已经续过"；关掉就是名单里所有人一律再发一遍
        check_today = cfg.get("check_today", True)
        try:
            import douyin
            self.job["step"] = "打开聊天页"
            # force=False：浏览器还开着、页面会话还有效就直接复用，别每次都重开一个 tab
            sess = self.dy.open_chat(visible=False, force=False)
            if not sess:
                self._fail("浏览器没有启动起来：%s" % self.dy.last_error)
                return
            state = self.dy._wait(sess, douyin.STATE_JS, timeout=18)
            if state != "user":
                self._fail("未登录，请先在网页里绑定账号")
                self.dy.close(shutdown_browser=True)
                return

            self.job["step"] = "读取会话列表"
            convs = self.dy.list_conversations(sess)
            if convs:
                self.friends_cache = convs
                self.friends_at = int(time.time())
                self.save_convs(convs)
                # 只在「自动发送」这一次轮换头像缓存；手动触发不轮换，只补缺
                if trigger == "auto":
                    self.rotate_avatars()
            by_name = {c["name"]: c for c in convs}

            # 自动纳入：有火花（alive / cooling）、非群聊的人，自动并进名单
            if convs and cfg.get("auto_enroll", True):
                have = {f.get("name") for f in (cfg.get("friends") or [])}
                added = []
                for c in convs:
                    if c.get("group") or c.get("kind") not in ("alive", "cooling"):
                        continue
                    n = c.get("name")
                    if not n or n in have:
                        continue
                    added.append({"name": n, "enabled": True, "avatar": c.get("avatar", ""),
                                  "streak": c.get("streak", ""), "kind": c.get("kind")})
                    have.add(n)
                if added:
                    fr = (cfg.get("friends") or []) + added
                    self.store.patch({"friends": fr})
                    cfg = self.store.get()
                    sel = [f for f in cfg.get("friends", []) if f.get("enabled")]
                    if only_names:
                        sel = [f for f in sel if f.get("name") in only_names]
                    self.log("plan", "自动纳入 %d 位有火花的人" % len(added))

            targets = []
            for f in sel:
                name = f.get("name")
                c = by_name.get(name)
                if not c:
                    self.job["results"].append({"name": name, "ok": False, "msg": "会话列表里没找到"})
                    continue
                if c.get("group"):
                    self.job["results"].append({"name": name, "ok": True, "msg": "群聊，跳过"})
                    continue
                if check_today and not force_all and name in sent_today:
                    self.job["results"].append({"name": name, "ok": True, "msg": "今日已续，跳过"})
                    continue
                if check_today and not force_all and c.get("kind") == "alive":
                    self.job["results"].append({"name": name, "ok": True, "msg": "今日已互动，跳过"})
                    continue
                targets.append({"name": name})

            if not targets:
                if not sel:
                    self.log("error", "名单为空，请先在好友页勾选需要续的人")
                    self.job["step"] = "名单为空"
                else:
                    self.log("plan", "名单里的人都没在会话列表里找到", {"count": 0})
                    self.job["step"] = "无可发送对象"
                self.store.patch({"last_run_date": _today()})
                self.job["active"] = False
                # 没有可发对象也别把浏览器杀掉：用户很可能马上补勾名单再点一次
                self.dy.close(shutdown_browser=False)
                self.browser_keep(150)
                return

            self.job["total"] = len(targets)
            self.job["step"] = "发送中"

            def progress(i, total, name):
                self.job["done"] = i
                self.job["total"] = total
                self.job["current"] = name
                self.job["wait"] = 0
                self.job["step"] = "正在发送 · %s" % name

            def wait_hook(left):
                self.job["wait"] = left
                self.job["step"] = "等待 %d 秒 · 下一位" % left

            def should_stop():
                return bool(self.job.get("stop"))

            messages = cfg.get("messages") or ["续火花"]
            limits = cfg.get("limits", {})
            gap = (int(limits.get("min_gap_seconds") or 25), int(limits.get("max_gap_seconds") or 60))
            results = self.dy.run_batch(targets, messages, gap=gap, progress=progress,
                                        sess=sess, wait_hook=wait_hook, should_stop=should_stop,
                                        unique=bool(cfg.get("vary_messages", True)))
            self.job["results"].extend(results)
            self.job["done"] = self.job["total"]

            ok_names = [r["name"] for r in results if r.get("ok") and r.get("text")]
            for r in results:
                self.log("send" if r.get("ok") else "error",
                         ("%s ← %s" % (r["name"], r.get("text", ""))) if r.get("ok") else ("%s 发送失败：%s" % (r["name"], r.get("msg", "")))
                         , {"name": r.get("name")})
            if ok_names:
                self._mark_sent(ok_names)
            self.store.patch({"last_run_date": _today()})
            self.job["step"] = ("已停止" if self.job.get("stop") else "完成")
            self.job["active"] = False
        except Exception as e:
            self._fail("任务异常：%s" % e)
        finally:
            try:
                # 关掉页面会话但不杀浏览器：手动续完再点一次、或立刻读好友列表都不用再从零启动。
                # 真正的回收交给 browser_maybe_close（空闲 150 秒后）。
                self.dy.close(shutdown_browser=False)
                self.browser_keep(150)
            except Exception:
                pass
            self.job["active"] = False

    def _fail(self, msg):
        self.log("error", msg)
        self.job["step"] = msg
        self.job["active"] = False

    def stop_job(self):
        if not self.job.get("active"):
            return False
        self.job["stop"] = True
        self.job["wait"] = 0
        self.job["step"] = "正在停止…"
        return True

    def _sent_today(self, cfg=None):
        cfg = cfg or self.store.get()
        rec = cfg.get("sent_today") or {}
        if rec.get("date") == _today():
            return set(rec.get("names") or [])
        return set()

    def _mark_sent(self, names):
        cfg = self.store.get()
        rec = cfg.get("sent_today") or {}
        names_set = set(rec.get("names") or []) if rec.get("date") == _today() else set()
        names_set.update(names)
        self.store.patch({"sent_today": {"date": _today(), "names": sorted(names_set)}})

    # ---------------- 状态 --------------
    def state(self):
        # 一次深拷贝供全流程复用：这接口每 4 秒被轮询一次，别重复解析配置
        cfg = self.store.get()
        sent = self._sent_today(cfg)
        wall = os.path.join(self.store.data_dir, "wallpaper.img")
        # job 由任务线程在写，拷一份再序列化，避免"list changed size during iteration"
        job = dict(self.job)
        job["results"] = list(job.get("results") or [])
        return {
            "runtime": {
                "job": job,
                "browser_alive": (self._bm.is_alive() if self._bm is not None else False),
                "browser_keep": (max(0, int(self._bm_keep - time.time())) if self._bm_keep else 0),
                "data_dir": self.store.data_dir,
                "wallpaper": ("/wallpaper?v=%d" % int(os.path.getmtime(wall))) if os.path.exists(wall) else "",
                "today": _today(),
                "metrics": self.metrics(),
                "autostart": self.autostart(),
            },
            "config": cfg,
            "friends": self.friends_cache,
            "friends_at": self.friends_at,
            "today_sent": sorted(sent),
            "next_run": self.next_run_info(cfg),
        }

    def idle_delay(self):
        """调度线程的休眠时长：离目标时间还早就少醒几次，空闲时一分钟一次就够。"""
        import datetime
        try:
            cfg = self.store.get()
            if not cfg.get("enabled"):
                return 60
            if (cfg.get("last_run_date") or "") == _today():
                return 60
            target = self._daily_target()
            left = (target - datetime.datetime.now()).total_seconds()
            if left > 600:
                return 60
            if left > 120:
                return 20
            return 5
        except Exception:
            return 15


# ---------------- HTTP --------------

class Handler(BaseHTTPRequestHandler):
    server_version = "SparkKeep/1.0"
    protocol_version = "HTTP/1.1"
    app = None

    def log_message(self, fmt, *args):
        pass

    # ---- 工具 ----
    def _send(self, body, code=200, ctype="application/json; charset=utf-8", cache="no-store"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        elif isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", cache)
        self.end_headers()
        try:
            self.wfile.write(body)
        except Exception:
            pass

    def _body(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
            if n <= 0:
                return {}
            return json.loads(self.rfile.read(n).decode("utf-8", "replace") or "{}")
        except Exception:
            return {}

    def _host_ok(self):
        host = (self.headers.get("Host") or "").split(":")[0]
        return host in ("127.0.0.1", "localhost", "")

    # ---- GET ----
    def do_GET(self):
        if not self._host_ok():
            return self._send({"ok": False, "msg": "forbidden"}, 403)
        path = self.path.split("?")[0]
        query = self.path.split("?", 1)[1] if "?" in self.path else ""
        app = self.app
        try:
            app.touch()
        except Exception:
            pass

        if path in ("/", "/index.html"):
            return self._send(webui.HTML, ctype="text/html; charset=utf-8")

        if path == "/api/ping":
            return self._send({"ok": True, "app": "sparkkeep"})

        if path == "/api/state":
            return self._send({"ok": True, **app.state()})

        if path == "/api/logs":
            return self._send({"ok": True, "logs": app.store.logs(120)})

        if path == "/api/account/check":
            info = app.dy.check_account(visible=False)
            app.browser_keep(150)
            if info.get("logged_in"):
                app.store.patch({"account": info, "wizard_account": True})
            else:
                acc = app.store.get()["account"]
                acc["logged_in"] = False
                app.store.patch({"account": acc})
            return self._send({"ok": True, "account": info})

        if path == "/api/friends":
            import douyin
            # 十分钟内刚确认过「没登录」就直接回话：下面要拉浏览器抓会话，实测要 22 秒，
            # 用户点一下「重新读取」干等二十多秒才看到同一句"还没登录"毫无意义。
            acc = (app.store.get().get("account") or {})
            never = int(acc.get("checked_at") or 0) == 0
            lately = (int(time.time()) - int(acc.get("checked_at") or 0)) < 600
            if acc.get("logged_in") is False and (never or lately):
                return self._send({"ok": False, "msg": "还没登录，先去「账号」页扫码登录"})
            # 这里不要写 app.job["step"]：那会把"读取会话列表"塞进任务进度里，
            # 概览页就会显示成一个正在跑的任务，而实际上只是读了一次名单。
            sess = app.dy.open_chat(visible=False, force=False)
            if not sess:
                return self._send({"ok": False, "msg": "浏览器没有启动起来"})
            convs = app.dy.list_conversations(sess)
            state = app.dy._wait(sess, douyin.STATE_JS, timeout=15)
            app.dy.close(shutdown_browser=False)
            app.browser_keep(150)
            if state != "user" and not convs:
                return self._send({"ok": False, "msg": "还没登录"})
            old = app.friends_cache or []
            if not convs:
                if old:
                    return self._send({"ok": True, "friends": old, "msg": "这次没读到会话，沿用上次结果"})
                return self._send({"ok": False, "msg": "没有读到会话，稍后再试"})
            # 质量门：明显比上次少，多半是页面还没渲染完，别用残缺结果覆盖
            if len(old) >= 10 and len(convs) < len(old) * 0.5:
                return self._send({"ok": True, "friends": old, "msg": "这次没读全，沿用上次结果"})
            app.friends_cache = convs
            app.friends_at = int(time.time())
            app.save_convs(convs)
            return self._send({"ok": True, "friends": convs})

        if path in ("/favicon.svg", "/favicon.ico"):
            return self._send(FAVICON_SVG, ctype="image/svg+xml; charset=utf-8", cache="max-age=86400")

        if path == "/avatar":
            # 头像：优先读本地缓存（avatars → avatars.old），没有再补下载并落盘
            import urllib.parse
            qs = urllib.parse.parse_qs(query)
            u = (qs.get("u") or [""])[0]
            if not u.startswith("http"):
                return self._send({"ok": False}, 404)
            hit = app.avatar_get(u)
            if hit:
                return self._send(hit[0], ctype=hit[1], cache="max-age=86400")
            try:
                import urllib.request
                req = urllib.request.Request(u, headers={
                    "User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                   "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"),
                    "Referer": "https://www.douyin.com/",
                    "Accept": "image/avif,image/webp,image/*,*/*;q=0.8",
                })
                with urllib.request.urlopen(req, timeout=12) as r:
                    body = r.read()
                    ctype = (r.headers.get("Content-Type") or "image/jpeg").split(";")[0]
                if not body or len(body) > 4 * 1024 * 1024:
                    return self._send({"ok": False}, 404)
                app.avatar_put(u, body, ctype)
                return self._send(body, ctype=ctype, cache="max-age=86400")
            except Exception:
                return self._send({"ok": False}, 404)

        if path == "/wallpaper":
            wall = os.path.join(app.store.data_dir, "wallpaper.img")
            if not os.path.exists(wall):
                return self._send({"ok": False}, 404)
            ctype = "image/jpeg"
            try:
                with open(os.path.join(app.store.data_dir, "wallpaper.meta"), "r") as f:
                    ctype = f.read().strip() or ctype
            except Exception:
                pass
            try:
                with open(wall, "rb") as f:
                    data = f.read()
                return self._send(data, ctype=ctype, cache="max-age=60")
            except Exception:
                return self._send({"ok": False}, 404)

        return self._send({"ok": False, "msg": "not found"}, 404)

    # ---- POST ----
    def do_POST(self):
        if not self._host_ok():
            return self._send({"ok": False, "msg": "forbidden"}, 403)
        path = self.path.split("?")[0]
        data = self._body()
        app = self.app
        try:
            app.touch()
        except Exception:
            pass

        if path == "/api/login":
            # 拉起可见浏览器，用户自己扫码，登录态写进独立 profile
            def worker():
                try:
                    sess = app.dy.open_chat(visible=True, force=True)
                    if not sess:
                        app.log("error", "打开登录窗口失败：%s" % (app.dy.last_error or "浏览器没起来"))
                except Exception as e:
                    app.log("error", "打开登录窗口失败：%s" % e)
            threading.Thread(target=worker, daemon=True).start()
            app.log("account", "已打开登录窗口，等待扫码")
            return self._send({"ok": True, "msg": "登录窗口已打开，请用手机抖音扫码"})

        if path == "/api/settings":
            patch = {}
            if "schedule" in data:
                patch["schedule"] = data["schedule"]
            if "messages" in data:
                patch["messages"] = data["messages"]
            if "limits" in data:
                patch["limits"] = data["limits"]
            if "enabled" in data:
                patch["enabled"] = bool(data["enabled"])
            if "check_today" in data:
                patch["check_today"] = bool(data["check_today"])
            if "vary_messages" in data:
                patch["vary_messages"] = bool(data["vary_messages"])
            if "auto_enroll" in data:
                patch["auto_enroll"] = bool(data["auto_enroll"])
            if "autostart" in data:
                on = bool(data["autostart"])
                patch["autostart"] = app.autostart(on)
            if "appearance" in data:
                patch["appearance"] = data["appearance"]
            if "wizard_done" in data:
                patch["wizard_done"] = bool(data["wizard_done"])
            app.store.patch(patch)
            return self._send({"ok": True, "config": app.store.get()})

        if path == "/api/friends":
            names = [str(n) for n in (data.get("names") or []) if str(n).strip()]
            old = app.store.get().get("friends", [])
            # 空提交会把名单清成 0 人，静默毁掉监控；除非明确要求清空，否则忽略
            if not names and old and not data.get("allow_empty"):
                return self._send({"ok": True, "config": app.store.get(),
                                   "msg": "名单为空，已保留原有 %d 位好友" % len(old)})
            cache = {c["name"]: c for c in app.friends_cache}
            old_map = {f["name"]: f for f in old}
            new = []
            for n in names:
                item = dict(old_map.get(n) or {})
                item["name"] = n
                c = cache.get(n)
                if c:
                    item["avatar"] = c.get("avatar", "")
                    item["streak"] = c.get("streak", "")
                    item["kind"] = c.get("kind", "none")
                item["enabled"] = True
                new.append(item)
            app.store.patch({"friends": new})
            return self._send({"ok": True, "config": app.store.get()})

        if path == "/api/send-now":
            names = data.get("names")
            # 手动执行默认全量：不管今天是否已发过、是否已互动，名单内每个人都发一条
            force = data.get("force")
            if force is None:
                force = True
            ok, msg = app.run_job(trigger="manual", only_names=names, force_all=bool(force))
            return self._send({"ok": ok, "msg": msg})

        if path == "/api/stop":
            stopped = app.stop_job()
            return self._send({"ok": stopped, "msg": "正在停止" if stopped else "当前没有任务"})

        if path == "/api/appearance":
            patch = {"appearance": data.get("appearance") or {}}
            app.store.patch(patch)
            return self._send({"ok": True, "config": app.store.get()})

        if path == "/api/wallpaper":
            import base64
            raw = (data.get("data") or "")
            if "," in raw:
                head, b64 = raw.split(",", 1)
                ctype = "image/jpeg"
                if head.startswith("data:") and ";" in head:
                    ctype = head[5:].split(";", 1)[0] or "image/jpeg"
                try:
                    b = base64.b64decode(b64)
                    if len(b) > 12 * 1024 * 1024:
                        return self._send({"ok": False, "msg": "图片太大了，换一张小一点的"})
                    with open(os.path.join(app.store.data_dir, "wallpaper.img"), "wb") as f:
                        f.write(b)
                    with open(os.path.join(app.store.data_dir, "wallpaper.meta"), "w") as f:
                        f.write(ctype)
                    return self._send({"ok": True})
                except Exception as e:
                    return self._send({"ok": False, "msg": "保存失败: %s" % e})
            # 传空表示清除壁纸
            try:
                for fn in ("wallpaper.img", "wallpaper.meta"):
                    fp = os.path.join(app.store.data_dir, fn)
                    if os.path.exists(fp):
                        os.remove(fp)
            except Exception:
                pass
            return self._send({"ok": True})

        if path == "/api/wizard":
            app.store.patch({"wizard_done": bool(data.get("done", True))})
            return self._send({"ok": True})

        if path == "/api/quit":
            try:
                app.log("system", "服务被手动停止")
            except Exception:
                pass
            # 先把自己的浏览器收掉，再退出：否则 Edge 会变成没有主人的孤儿进程，
            # 下次启动还要先清理一遍残留，等于把冷启动再拖长一次。
            try:
                if app._bm is not None:
                    app._bm.stop()
            except Exception:
                pass
            threading.Timer(0.6, lambda: os._exit(0)).start()
            return self._send({"ok": True})

        return self._send({"ok": False, "msg": "not found"}, 404)


def make_server(app, port):
    handler = type("H", (Handler,), {"app": app})
    httpd = ThreadingHTTPServer(("127.0.0.1", port), handler)
    httpd.daemon_threads = True
    return httpd


def start_scheduler(app):
    def loop():
        while app.alive:
            try:
                app.tick()
            except Exception as e:
                try:
                    app.log("error", "调度异常：%s" % e)
                except Exception:
                    pass
            try:
                app.trim_if_idle()
            except Exception:
                pass
            try:
                app.browser_maybe_close()
            except Exception:
                pass
            try:
                delay = app.idle_delay()
            except Exception:
                delay = 15
            time.sleep(max(3, delay))

    t = threading.Thread(target=loop, daemon=True, name="spark-scheduler")
    t.start()
    return t
