"""Edge 浏览器进程管理：定位、启动、等待调试端口、开标签页、回收。

设计要点：
- 使用独立 profile（不碰用户日常浏览器的登录态与数据）
- 平时不启动浏览器；只有"登录"或"到点发消息"时才拉起，用完即关，常态占用接近 0
- 单实例互斥：同一个 profile 同时只能被一个浏览器实例占用
"""
import os
import shutil
import socket
import subprocess
import threading
import time
import urllib.parse
import zlib

from cdp import CDPSession, http_json

EDGE_PATHS = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
]

DEFAULT_PORT = 9411


def derive_port(profile_dir):
    """调试端口跟着 profile 目录走，而不是全机共用一个 9411。

    端口是全局的：只要本机有第二个实例（测试、便携版、两份安装），
    它探测到 9411 通就会直接复用**别人的**浏览器，进而拿别人已登录的账号
    去发消息。按 profile 派生端口后，各装各的、互不越界。
    """
    key = os.path.abspath(profile_dir).lower().encode("utf-8", "replace")
    return DEFAULT_PORT + abs(zlib.crc32(key) % 180)


UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0")


def find_edge():
    for p in EDGE_PATHS:
        if os.path.exists(p):
            return p
    try:
        import winreg
        for root in (winreg.HKEY_LOCAL_MACHINE, winreg.HKEY_CURRENT_USER):
            try:
                k = winreg.OpenKey(root, r"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe")
                v = winreg.QueryValue(k, None)
                if v and os.path.exists(v):
                    return v
            except OSError:
                continue
    except Exception:
        pass
    for name in ("msedge.exe",):
        found = shutil.which(name)
        if found:
            return found
    return None


class BrowserManager:
    def __init__(self, profile_dir, port=None, log=None):
        self.profile_dir = profile_dir
        # 不传端口就按 profile 派生：不同安装位置各有各的端口，不会互相劫持浏览器
        self.port = port or derive_port(profile_dir)
        self.proc = None
        self._lock = threading.RLock()
        self._log = log or (lambda msg: None)
        self.edge = find_edge()
        # 当前实例是不是"有窗口"的那一种。定时发送用 --headless，没有窗口；
        # 用户点登录时要的是能看见、能扫码的窗口，两者不能混用。
        self._visible = False

    # ---------- 基础 ----------
    def is_alive(self):
        # 用端口探测而不是 HTTP 请求：HTTP 走系统代理时未开端口会白等满 timeout，
        # 会让 /api/state 每次卡 2 秒（首屏"卡顿一下"的根因）。这里 0.35 秒封顶 + 1 秒缓存。
        now = time.monotonic()
        if now - getattr(self, "_alive_at", 0.0) < 1.0:
            return getattr(self, "_alive", False)
        ok = False
        try:
            s = socket.create_connection(("127.0.0.1", self.port), timeout=0.35)
            s.close()
            ok = True
        except Exception:
            ok = False
        self._alive = ok
        self._alive_at = now
        return ok

    def wait_ready(self, timeout=25):
        end = time.time() + timeout
        while time.time() < end:
            if self.is_alive():
                return True
            if self.proc is not None and self.proc.poll() is not None:
                return False
            time.sleep(0.25)
        return self.is_alive()

    def _profile_pids(self):
        """命令行里带本程序 profile 目录的 msedge 进程号。"""
        ps = ("Get-CimInstance Win32_Process -Filter \"Name='msedge.exe'\" | "
              "Where-Object { $_.CommandLine -and $_.CommandLine.Contains('%s') } | "
              "Select-Object -ExpandProperty ProcessId"
              % self.profile_dir.replace("'", "''"))
        try:
            out = subprocess.run(
                ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, universal_newlines=True,
                timeout=20, creationflags=0x08000000,
            ).stdout or ""
        except Exception:
            return []
        return [int(x) for x in out.split() if x.strip().isdigit()]

    def _reap_profile(self):
        """清掉仍占着本 profile 的残留实例。

        不清的话，新起的 msedge 会把命令行转交给旧实例然后自己退出：
        --remote-debugging-port 永远不会生效，用户那边表现为"点了登录没有任何窗口"。
        """
        pids = self._profile_pids()
        if not pids:
            return 0
        self._log("清理残留浏览器实例：%s" % ",".join(str(p) for p in pids))
        for pid in pids:
            try:
                subprocess.run(
                    ["taskkill", "/PID", str(pid), "/T", "/F"],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    timeout=12, creationflags=0x08000000,
                )
            except Exception:
                pass
        time.sleep(0.6)  # 等 Windows 释放 profile 目录
        return len(pids)

    def _focus(self):
        """把抖音窗口提到前台。窗口确实起来了却躲在后台，是"没弹出"的另一半原因。"""
        ps = ("$ErrorActionPreference='SilentlyContinue';"
              "$p=Get-Process msedge | Where-Object { $_.MainWindowHandle -ne 0 -and "
              "$_.MainWindowTitle -match 'douyin|抖音' } | Sort-Object StartTime -Descending | "
              "Select-Object -First 1;"
              "if($p){$sh=New-Object -ComObject WScript.Shell;$sh.AppActivate($p.Id)|Out-Null}")
        try:
            subprocess.run(
                ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                timeout=15, creationflags=0x08000000,
            )
        except Exception:
            pass

    def start(self, visible=False, url=None, timeout=25):
        """拉起 Edge 实例（若已在运行则复用）。返回是否成功。"""
        with self._lock:
            if self.is_alive():
                if visible and not self._visible:
                    # 端口活着，但那是之前定时发送留下的无窗口实例。
                    # 直接复用它，用户点完登录看不到任何窗口 —— 必须换成可见实例。
                    self._reap_profile()
                else:
                    if visible:
                        self._focus()
                    return True
            if not self.edge:
                self._log("未找到 Edge 浏览器，无法启动")
                return False
            os.makedirs(self.profile_dir, exist_ok=True)

            args = [
                self.edge,
                "--remote-debugging-port=%d" % self.port,
                "--user-data-dir=%s" % self.profile_dir,
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-extensions",
                "--disable-component-update",
                "--disable-background-networking",
                "--disable-sync",
                "--disable-breakpad",
                "--disable-crash-reporter",
                "--metrics-recording-only",
                "--mute-audio",
                "--disable-features=Translate,OptimizationHints,MediaRouter",
                "--window-size=1280,860",
                "--disable-blink-features=AutomationControlled",
                "--lang=zh-CN",
            ]
            if not visible:
                args.append("--headless=new")
            else:
                # 窗口直接从最大化状态出现，别缩在任务栏里
                args.append("--start-maximized")
                args.append("--hide-crash-restore-bubble")
            if url:
                args.append(url)

            creationflags = 0
            if os.name == "nt":
                creationflags = 0x00000008  # DETACHED_PROCESS：父进程不等待、不共享控制台

            def spawn(t):
                try:
                    self.proc = subprocess.Popen(
                        args,
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                        stdin=subprocess.DEVNULL,
                        creationflags=creationflags,
                        close_fds=True,
                    )
                except Exception as e:
                    self._log("启动浏览器失败: %s" % e)
                    return False
                return self.wait_ready(t)

            ok = spawn(timeout)
            if not ok:
                self._log("浏览器调试端口未就绪，清理残留实例后重试")
                self._reap_profile()
                ok = spawn(max(12, timeout // 2))
            self._visible = bool(ok and visible)
            if ok and visible:
                self._focus()
            if not ok:
                self._log("浏览器启动失败：调试端口始终没有就绪")
            return ok

    def list_pages(self):
        try:
            data = http_json("http://127.0.0.1:%d/json/list" % self.port, timeout=5)
            return [d for d in data if d.get("type") == "page"]
        except Exception:
            return []

    def _new_target(self, url):
        enc = urllib.parse.quote(url, safe="")
        try:
            return http_json("http://127.0.0.1:%d/json/new?%s" % (self.port, enc), method="PUT", timeout=8)
        except Exception:
            return http_json("http://127.0.0.1:%d/json/new?%s" % (self.port, enc), timeout=8)

    def _prepare(self, sess):
        """在导航前设置指纹，避免 headless 默认标识被风控拦截。"""
        try:
            sess.call("Page.enable")
            sess.call("Runtime.enable")
        except Exception:
            pass
        try:
            sess.call("Emulation.setUserAgentOverride", {
                "userAgent": UA, "acceptLanguage": "zh-CN", "platform": "Win32",
            })
        except Exception:
            pass
        try:
            sess.call("Emulation.setFocusEmulationEnabled", {"enabled": True})
        except Exception:
            pass
        try:
            sess.call("Emulation.setDeviceMetricsOverride", {
                "width": 1280, "height": 800, "deviceScaleFactor": 1, "mobile": False,
            })
        except Exception:
            pass

    def goto(self, sess, url, wait=3.0):
        try:
            sess.call("Page.navigate", {"url": url}, timeout=30)
        except Exception as e:
            self._log("导航失败: %s" % e)
            return False
        end = time.time() + wait
        while time.time() < end:
            try:
                if sess.eval("document.readyState") == "complete":
                    break
            except Exception:
                pass
            time.sleep(0.2)
        return True

    def open_page(self, url, timeout=20, visible=True):
        """打开（或复用）一个抖音页面并返回 CDP 会话。"""
        with self._lock:
            if not self.start(visible=visible, timeout=timeout):
                return None
            target = None
            for p in self.list_pages():
                u = p.get("url") or ""
                if u.startswith("http") and "douyin.com" in u:
                    target = p
                    break
            if target is None:
                for p in self.list_pages():
                    if (p.get("url") or "").startswith("about:"):
                        target = p
                        break
            if target is None:
                try:
                    target = self._new_target("about:blank")
                except Exception as e:
                    self._log("创建标签页失败: %s" % e)
                    return None
            if not target or not target.get("webSocketDebuggerUrl"):
                self._log("无法创建浏览器标签页")
                return None
            sess = CDPSession(target["webSocketDebuggerUrl"], timeout=30)
            self._prepare(sess)
            if not (target.get("url") or "").startswith("https://www.douyin.com"):
                self.goto(sess, url)
            return sess

    def close_page(self, sess):
        try:
            sess.close()
        except Exception:
            pass

    def stop(self):
        """关闭本程序拉起的浏览器实例。"""
        with self._lock:
            pid = self.proc.pid if self.proc is not None else None
            try:
                for p in self.list_pages():
                    if p.get("webSocketDebuggerUrl"):
                        try:
                            CDPSession(p["webSocketDebuggerUrl"], timeout=5).close()
                        except Exception:
                            pass
            except Exception:
                pass
            if os.name == "nt":
                if pid:
                    try:
                        subprocess.run(
                            ["taskkill", "/PID", str(pid), "/T", "/F"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            creationflags=0x08000000,
                        )
                    except Exception:
                        pass
                # 兜底：把还占着本程序 profile 的残留进程扫掉（只认这个 profile 目录）
                try:
                    q = ("Get-CimInstance Win32_Process -Filter \"Name='msedge.exe'\" | "
                         "Where-Object { $_.CommandLine -like '*%s*' } | "
                         "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
                         % self.profile_dir.replace("'", "''"))
                    subprocess.run(
                        ["powershell", "-NoProfile", "-NonInteractive", "-Command", q],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                        creationflags=0x08000000,
                    )
                except Exception:
                    pass
            self.proc = None
