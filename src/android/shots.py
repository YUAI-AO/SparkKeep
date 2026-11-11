# -*- coding: utf-8 -*-
"""用 CDP + 手机设备模拟批量截图（--screenshot 模式不认 viewport meta，测出来的排版不是真机行为）。"""
import base64
import os
import subprocess
import sys
import time

sys.path.insert(0, r"C:\Users\17098\Doubao\chats\2026-11-08\new-chat\spark-src")
from cdp import CDPSession, http_json

ROOT = r"C:\Users\17098\Doubao\chats\2026-11-08\new-chat\android"
OUT = os.path.join(ROOT, "shots")
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
PAGES = ["overview", "account", "friends", "plan", "log", "settings"]
PORT = 9591
W, H = 390, 844

proc = subprocess.Popen([EDGE, "--headless=new", "--disable-gpu", "--no-first-run",
                         "--remote-debugging-port=%d" % PORT,
                         "--user-data-dir=" + os.path.join(os.environ["TEMP"], "spark_shots"),
                         "--window-size=%d,%d" % (W, H), "about:blank"],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
for _ in range(100):
    try:
        http_json("http://127.0.0.1:%d/json/version" % PORT, timeout=2)
        break
    except Exception:
        time.sleep(0.3)

page = next(p for p in http_json("http://127.0.0.1:%d/json/list" % PORT) if p.get("type") == "page")
s = CDPSession(page["webSocketDebuggerUrl"], timeout=45)
s.call("Runtime.enable")
s.call("Page.enable")
s.call("Emulation.setDeviceMetricsOverride",
       {"width": W, "height": H, "deviceScaleFactor": 2, "mobile": True})

report = []
for p in PAGES:
    subprocess.run([sys.executable, os.path.join(ROOT, "make_preview.py"), "--page=" + p],
                   cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
    name = "preview.html" if p == "overview" else "preview_%s.html" % p
    url = "file:///" + os.path.join(OUT, name).replace("\\", "/")
    s.call("Page.navigate", {"url": url})
    time.sleep(4.5)
    # 入场转场（黄幕擦除）会占满整屏，截图前先等它走完再兜底隐藏
    s.eval("(function(){var b=document.getElementById('boot');if(b)b.style.display='none';"
           "var o=document.getElementById('onboard');if(o&&o.classList.contains('on'))o.classList.remove('on')})()")
    time.sleep(1.2)
    if p != "overview":
        s.eval("(function(){var n=document.querySelector('[data-page=\"%s\"]');if(n)n.click()})()" % p)
        time.sleep(1.8)
    over = s.eval("(function(){var v=document.getElementById('view');"
                  "return v ? (v.scrollWidth > v.clientWidth + 1) : false})()")
    d = s.call("Page.captureScreenshot", {"format": "png"}, timeout=45)
    path = os.path.join(OUT, "m_%s.png" % p)
    open(path, "wb").write(base64.b64decode(d["data"]))
    report.append("%s(overflow=%s,dpr2=on)" % (p, over))

s.close()
subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
print(" | ".join(report))
