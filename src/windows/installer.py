# -*- coding: utf-8 -*-
"""
续火花 · Windows 安装包

把 SparkKeep.exe 装到 %LOCALAPPDATA%\\Programs\\SparkKeep，建桌面和开始菜单快捷方式，
再留一个卸载入口。不带参数双击就是向导；加 --silent 就是静默安装（自动化/测试用）。
"""
import os
import subprocess
import sys
import time

APP_NAME = "续火花"
PRODUCT = "SparkKeep"
VERSION = "1.0.9"

HERE = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
SRC_EXE = os.path.join(HERE, "SparkKeep.exe")
TARGET_DIR = os.environ.get("SPARK_INSTALL_DIR") or os.path.join(
    os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "Programs", PRODUCT)
TARGET_EXE = os.path.join(TARGET_DIR, "SparkKeep.exe")

PS_SHORTCUT = r"""
$ErrorActionPreference = 'Stop'
$W = New-Object -ComObject WScript.Shell
$items = @(
  @{ Path = '{DESKTOP}'; Name = '{LNK}' },
  @{ Path = '{START}';   Name = '{LNK}' }
)
foreach ($it in $items) {
  if (-not (Test-Path -LiteralPath $it.Path)) { continue }
  $p = Join-Path $it.Path $it.Name
  $s = $W.CreateShortcut($p)
  $s.TargetPath = '{EXE}'
  $s.WorkingDirectory = '{DIR}'
  $s.IconLocation = '{EXE}'
  $s.Description = '{APP}'
  $s.Save()
}
"""

UNINSTALL_PS = r"""$ErrorActionPreference = 'SilentlyContinue'
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
Get-Process SparkKeep -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 500
Remove-Item -LiteralPath (Join-Path $env:USERPROFILE 'Desktop\{LNK}') -Force
Remove-Item -LiteralPath (Join-Path ([Environment]::GetFolderPath('Programs')) '{APP}.lnk') -Force
Remove-Item -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'SparkKeep' -Force
Remove-Item -LiteralPath $dir -Recurse -Force
"""


def kill_running():
    try:
        subprocess.run(["taskkill", "/f", "/im", "SparkKeep.exe"],
                       capture_output=True, timeout=20)
    except Exception:
        pass
    time.sleep(0.7)


def make_shortcuts():
    if os.environ.get("SPARK_NO_SHORTCUT"):
        return
    ps = PS_SHORTCUT
    ps = ps.replace("{DESKTOP}", os.path.join(os.environ.get("USERPROFILE", ""), "Desktop"))
    ps = ps.replace("{START}", os.path.join(os.environ.get("APPDATA", ""),
                                            r"Microsoft\Windows\Start Menu\Programs"))
    ps = ps.replace("{LNK}", APP_NAME + ".lnk")
    ps = ps.replace("{APP}", APP_NAME)
    ps = ps.replace("{EXE}", TARGET_EXE)
    ps = ps.replace("{DIR}", TARGET_DIR)
    subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                   capture_output=True, timeout=90)


def write_uninstall():
    body = UNINSTALL_PS.replace("{LNK}", APP_NAME + ".lnk").replace("{APP}", APP_NAME)
    path = os.path.join(TARGET_DIR, "卸载续火花.ps1")
    with open(path, "w", encoding="utf-8") as f:
        f.write(body)
    cmd = os.path.join(TARGET_DIR, "卸载续火花.cmd")
    with open(cmd, "w", encoding="utf-8") as f:
        f.write('@echo off\r\npowershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0卸载续火花.ps1"\r\n')
    return cmd


def install():
    if not os.path.isfile(SRC_EXE):
        raise RuntimeError("安装包里找不到 SparkKeep.exe")
    kill_running()
    os.makedirs(TARGET_DIR, exist_ok=True)
    shutil_copy(SRC_EXE, TARGET_EXE)
    make_shortcuts()
    write_uninstall()
    return TARGET_EXE


def shutil_copy(src, dst):
    import shutil
    tmp = dst + ".new"
    shutil.copy2(src, tmp)
    if os.path.exists(dst):
        try:
            os.remove(dst)
        except Exception:
            pass
    os.rename(tmp, dst)


def launch():
    try:
        subprocess.Popen([TARGET_EXE], cwd=TARGET_DIR, close_fds=True)
    except Exception:
        pass


def gui():
    import tkinter as tk
    from tkinter import ttk

    C_BG = "#0B0D0F"
    C_FG = "#E8ECEF"
    C_DIM = "#8B9096"
    C_ACC = "#D9F24C"

    root = tk.Tk()
    root.title("续火花 · 安装")
    root.configure(bg=C_BG)
    root.resizable(False, False)
    w, h = 560, 300
    sw = root.winfo_screenwidth()
    sh = root.winfo_screenheight()
    root.geometry("%dx%d+%d+%d" % (w, h, (sw - w) // 2, (sh - h) // 3))
    try:
        root.iconbitmap(default=os.path.join(HERE, "spark.ico"))
    except Exception:
        pass

    pad = 32
    tk.Label(root, text=APP_NAME, bg=C_BG, fg=C_FG,
             font=("Microsoft YaHei UI", 20, "bold")).place(x=pad, y=34)
    tk.Label(root, text="v" + VERSION, bg=C_BG, fg=C_ACC,
             font=("Consolas", 10)).place(x=pad + 96, y=44)
    tk.Label(root, text="定时抖音续火花 · Windows", bg=C_BG, fg=C_DIM,
             font=("Microsoft YaHei UI", 10)).place(x=pad, y=72)
    tk.Frame(root, bg="#1E2226", height=1, width=w - pad * 2).place(x=pad, y=104)

    info = tk.Label(root, text="安装到 " + TARGET_DIR, bg=C_BG, fg=C_DIM,
                    font=("Consolas", 9), anchor="w", justify="left", wraplength=w - pad * 2)
    info.place(x=pad, y=126)

    bar = ttk.Progressbar(root, length=w - pad * 2, mode="determinate", maximum=100)
    bar.place(x=pad, y=172)
    status = tk.Label(root, text="桌面和开始菜单会各放一个快捷方式", bg=C_BG, fg=C_DIM,
                      font=("Microsoft YaHei UI", 9))
    status.place(x=pad, y=196)

    def btn(text, x, w_, primary):
        b = tk.Button(root, text=text, bd=0, relief="flat",
                      bg=C_ACC if primary else "#1A1E21",
                      fg="#0B0D0F" if primary else C_FG,
                      activebackground=C_ACC if primary else "#24292D",
                      activeforeground="#0B0D0F" if primary else C_FG,
                      font=("Microsoft YaHei UI", 10, "bold"), cursor="hand2",
                      command=None)
        b.place(x=x, y=230, width=w_, height=40)
        return b

    start_btn = btn("安装", pad, 150, True)
    close_btn = btn("关闭", pad + 160, 110, False)
    run_btn = btn("启动", w - pad - 120, 120, False)
    run_btn.place_forget()

    def on_install():
        start_btn.configure(state="disabled", text="正在安装…")
        root.update_idletasks()
        for v in (18, 42, 66):
            bar["value"] = v
            root.update()
            time.sleep(0.18)
        try:
            install()
        except Exception as e:
            bar["value"] = 0
            status.configure(text="安装失败：%s" % e, fg="#FF8A76")
            start_btn.configure(state="normal", text="重试")
            return
        bar["value"] = 100
        status.configure(text="装好了。桌面和开始菜单都有「%s」。" % APP_NAME, fg=C_FG)
        start_btn.place_forget()
        close_btn.configure(text="完成")
        run_btn.place(x=w - pad - 120, y=230, width=120, height=40)
        run_btn.configure(command=lambda: (launch(), root.destroy()))

    start_btn.configure(command=on_install)
    close_btn.configure(command=root.destroy)

    note = tk.Label(root, text="卸载：程序目录里的「卸载续火花.cmd」", bg=C_BG, fg="#5F646A",
                    font=("Microsoft YaHei UI", 8))
    note.place(x=pad, y=278)

    root.mainloop()


def main():
    silent = "--silent" in sys.argv
    if silent:
        try:
            install()
            if "--launch" in sys.argv:
                launch()
            print("OK " + TARGET_EXE)
            return 0
        except Exception as e:
            print("FAIL " + str(e))
            return 1
    gui()
    return 0


if __name__ == "__main__":
    sys.exit(main())
