"""程序入口：单实例守护 + 本地服务 + 每日调度。

双击 exe：确保后台在跑，并打开控制台网页。
开机自启（--boot）：只把后台拉起来，不弹任何窗口。
"""
import json
import os
import sys
import threading
import time
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import server


def base_dir():
    env = os.environ.get("SPARK_HOME")
    if env:
        return env
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


def log_line(text):
    try:
        path = os.path.join(base_dir(), "data", "runtime.log")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        stamp = time.strftime("%Y-%m-%d %H:%M:%S")
        with open(path, "a", encoding="utf-8") as f:
            f.write("[%s] %s\n" % (stamp, text))
        try:
            if os.path.getsize(path) > 256 * 1024:
                with open(path, "r", encoding="utf-8", errors="replace") as f:
                    tail = f.readlines()[-500:]
                with open(path, "w", encoding="utf-8") as f:
                    f.writelines(tail)
        except Exception:
            pass
    except Exception:
        pass


def is_running(port):
    # 端口探测用 socket，既不加载 urllib/ssl，也不会被系统代理拖满超时
    import socket
    try:
        s = socket.create_connection(("127.0.0.1", port), timeout=0.4)
        s.settimeout(1.2)
        s.sendall(b"GET /api/ping HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n")
        raw = s.recv(4096)
        s.close()
        return b'"app": "sparkkeep"' in raw or b'"app":"sparkkeep"' in raw
    except Exception:
        return False


def parse_args(argv):
    opts = {"port": 80, "boot": False, "open": True}
    for a in argv[1:]:
        if a == "--boot":
            opts["boot"] = True
            opts["open"] = False
        elif a in ("--no-open",):
            opts["open"] = False
        elif a.startswith("--port="):
            try:
                opts["port"] = int(a.split("=", 1)[1])
            except Exception:
                pass
    return opts


def open_console(port):
    url = "http://127.0.0.1:%d/" % port
    # 优先用系统外壳：走默认浏览器，且一定带窗口（webbrowser 在无控制台进程里可能静默失败）
    try:
        os.startfile(url)  # noqa: 仅 Windows
        return
    except Exception:
        pass
    try:
        import webbrowser
        webbrowser.open(url, new=2)
    except Exception as e:
        log_line("打开控制台失败: %s" % e)


def lower_priority():
    """后台常驻程序主动让出 CPU：BELOW_NORMAL，前台游戏/剪辑时不抢资源。
    发消息本身有 2-5 秒间隔，降优先级不影响任务完成时间。"""
    try:
        import ctypes
        k32 = ctypes.WinDLL("kernel32.dll")
        k32.GetCurrentProcess.restype = ctypes.c_void_p
        k32.SetPriorityClass(ctypes.c_void_p(k32.GetCurrentProcess()), 0x00004000)  # BELOW_NORMAL
    except Exception:
        pass


def main():
    opts = parse_args(sys.argv)
    port = opts["port"]

    if is_running(port):
        log_line("已在运行，跳过重复启动")
        if opts["open"]:
            open_console(port)
        return

    lower_priority()
    app = server.App(base_dir(), port=port)
    try:
        httpd = server.make_server(app, port)
    except OSError as e:
        log_line("端口 %d 绑定失败: %s" % (port, e))
        # 80 被占用时退到高位端口，保证程序仍然可用
        for alt in (8080, 8090, 8123):
            try:
                httpd = server.make_server(app, alt)
                port = alt
                app.port = alt
                break
            except OSError:
                continue
        else:
            log_line("没有可用端口，退出")
            return

    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    server.start_scheduler(app)
    app.log("system", "服务启动，端口 %d" % port)
    log_line("服务启动 http://127.0.0.1:%d" % port)

    if opts["open"]:
        threading.Timer(0.8, lambda: open_console(port)).start()

    try:
        # 常驻主线程：不解 1 秒一次的空转，直接长睡，几乎不再唤醒 CPU
        stop = threading.Event()
        while True:
            stop.wait(600)
    except KeyboardInterrupt:
        app.alive = False


if __name__ == "__main__":
    try:
        main()
    except Exception:
        log_line("启动失败:\n" + traceback.format_exc())
        raise
