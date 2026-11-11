# -*- coding: utf-8 -*-
"""给 assets/app.html 注入一个 mock 的 SparkBridge，用于在桌面浏览器里预览 Android 版 UI。
返回值严格按 Spark.state() 的真实结构构造，以此验证前端字段是否对得上。
"""
import io
import json
import os
import sys

AND = r"C:\Users\17098\Doubao\chats\2026-11-08\new-chat\android\assets\app.html"
OUT = r"C:\Users\17098\Doubao\chats\\2026-11-08\\new-chat\\android\\shots\\preview.html"

cfg = {
    "enabled": True, "wizard_done": True,
    "schedule": {"time": "00:30", "jitter_minutes": 30},
    "messages": ["续！！！！！！", "续火花", "hello", "我是天才", "晚上好"],
    "limits": {"min_gap_seconds": 2, "max_gap_seconds": 5},
    "check_today": True, "vary_messages": True, "auto_enroll": True,
    "keep_alive": True, "boot_start": True,
    "account": {"logged_in": True, "nickname": "", "checked_at": 1762700000},
    "appearance": {"veil": 58, "accent": "lime", "style": "glass", "blur": 0, "wallpaper": ""},
    "sent_today": {"date": "2026-11-09", "names": []},
    "last_run_date": "2026-11-09",
    "friends": [
        {"name": "˶>ᗜ<˶", "enabled": True, "streak": "62", "kind": "cooling"},
        {"name": "など", "enabled": True, "streak": "84", "kind": "alive"},
        {"name": "悲伤小猫️️️️꒦ິ^꒦", "enabled": True, "streak": "82", "kind": "alive"},
        {"name": "旋律", "enabled": True, "streak": "6", "kind": "alive"},
        {"name": "烬萱辞", "enabled": True, "streak": "24", "kind": "alive"},
        {"name": "黑奴赟", "enabled": True, "streak": "34", "kind": "alive"},
        {"name": "梁哥", "enabled": False, "streak": "65", "kind": "alive"},
    ],
}

friends = [
    {"name": "˶>ᗜ<˶", "icon": "gray_normal.png", "streak": "62", "time": "刚刚", "desc": "hello", "avatar": "", "group": False, "kind": "cooling"},
    {"name": "など", "icon": "normal_couple.png", "streak": "84", "time": "昨天", "desc": "续火花", "avatar": "", "group": False, "kind": "alive"},
    {"name": "悲伤小猫️️️️꒦ິ^꒦", "icon": "normal_normal.png", "streak": "82", "time": "2 天前", "desc": "在", "avatar": "", "group": False, "kind": "alive"},
    {"name": "旋律", "icon": "normal_couple.png", "streak": "6", "time": "3 天前", "desc": "hi", "avatar": "", "group": False, "kind": "alive"},
    {"name": "烬萱辞", "icon": "normal_normal.png", "streak": "24", "time": "4 天前", "desc": "好", "avatar": "", "group": False, "kind": "alive"},
    {"name": "黑奴赟", "icon": "gray_bosom.png", "streak": "34", "time": "5 天前", "desc": "?", "avatar": "", "group": False, "kind": "cooling"},
]

state = {
    "ok": True,
    "runtime": {
        "job": {"active": False, "step": "待机", "done": 0, "total": 0, "current": "", "wait": 0,
                "stop": False, "trigger": "", "results": []},
        "browser_alive": False,
        "wallpaper": "",
        "today": "2026-11-09",
        "metrics": {"mem_mb": 42.0, "cpu": 0.0},
        "service": True,
        "sdk": 34,
        "platform": "android",
        "keep_alive": True,
        "boot_start": True,
        "ignore_battery": False,
        "autostart": True,
    },
    "config": cfg,
    "friends": friends,
    "friends_at": 1762700000,
    "today_sent": ["˶>ᗜ<˶"],
    "next_run": {"at": "2026-11-10 00:06", "ts": 1762700000, "seconds": 3600,
                 "ran_today": True, "plan": "00:30 ±30 min"},
}

import datetime
_T = datetime.date.today()
_T0 = int(datetime.datetime(_T.year, _T.month, _T.day).timestamp())

logs = {"ok": True, "total": 5, "logs": [
    {"t": _T0 + 9 * 3600 + 6 * 60 + 12, "kind": "send", "text": "˶>ᗜ<˶ ← hello"},
    {"t": _T0 + 9 * 3600 + 6 * 60 + 15, "kind": "send", "text": "黑奴赟 ← 续火花"},
    {"t": _T0 + 9 * 3600 + 5 * 60 + 40, "kind": "plan", "text": "到点，开始今日续火花"},
    {"t": _T0 + 9 * 3600 + 4 * 60 + 2, "kind": "system", "text": "后台服务已就绪"},
    {"t": _T0 + 8 * 3600 + 50 * 60, "kind": "account", "text": "登录态检测通过"},
]}

MOCK = """
<script>
window.SparkBridge = {
  call: function(path, body){
    var S = window.__MOCK_STATE__;
    var p = {}; try { p = JSON.parse(body || "{}"); } catch(e){}
    if (path === "/api/state") return JSON.stringify(S.state);
    if (path === "/api/logs") return JSON.stringify(S.logs);
    if (path === "/api/ping") return JSON.stringify({ok:true, app:"sparkkeep"});
    if (path === "/api/friends") {
      if (p.__post) return JSON.stringify({ok:true, count:"7"});
      return JSON.stringify({ok:true, friends:S.state.friends, at:1762700000});
    }
    if (path === "/api/keep") return JSON.stringify({ok:true, service:true, running:true,
      boot_start:true, keep_alive:true, ignore_battery:false, sdk:34});
    return JSON.stringify({ok:true});
  }
};
</script>
"""

def main():
    page = "overview"
    for a in sys.argv[1:]:
        if a.startswith("--page="):
            page = a.split("=", 1)[1]
    with io.open(AND, "r", encoding="utf-8") as f:
        html = f.read()
    data = ("<script>window.__MOCK_STATE__=" +
            json.dumps({"state": state, "logs": logs}, ensure_ascii=False) + ";</script>")
    nav = ""
    if page != "overview":
        nav = ('<script>setTimeout(function(){var n=document.querySelector(\'[data-page="%s"]\');'
               'if(n) n.click();},900);</script>') % page
    head_end = html.find("</head>")
    # mock 必须在 api() 定义之后、页面初始化之前生效 → 插到 </head> 前即可（api 在 body 脚本里）
    html = html[:head_end] + data + MOCK + html[head_end:]
    if nav:
        html = html.replace("</body>", nav + "</body>")
    name = "preview.html" if page == "overview" else ("preview_%s.html" % page)
    out = os.path.join(os.path.dirname(OUT), name)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with io.open(out, "w", encoding="utf-8") as f:
        f.write(html)
    print("preview ->", out, len(html))


if __name__ == "__main__":
    main()
