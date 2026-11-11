# 续火花 · SparkKeep

抖音私信「续火花」自动化的两个客户端，同一套前端与接口口径：

- **Windows**（`src/windows/`）：PyInstaller 单文件 exe + 安装向导，本地起 HTTP 服务 + WebView 页，走 Edge + CDP 驱动抖音网页版。
- **Android**（`src/android/`）：整块 WebView 加载同一份 `app.html`，前台服务保活 + 开机自启 + 退出界面后延迟释放内存。

界面按「性冷淡、极度克制」的方向打磨：低饱和、少装饰、卡片带液态玻璃质感，一切以低占用为前提。

## 功能

- 定时到点自动给名单里的好友发一条消息，维持火花天数
- 读取抖音私信会话列表，按火花状态自动纳入名单
- 文案池随机且不重复；可跳过今天已经聊过的人
- 失败有独立告警通知；任务进行中有一条带转圈的进度通知（「正在惊醒中 · 人名」），跑完自动撤走
- 壁纸、卡片模糊、压暗、强调色、深/浅主题均可调，可切「玻璃 / 扁平」两种材质

## 目录约定

三种东西彻底分开放，互不混杂：

```
src/                  源代码（进仓库）
  windows/            Windows 端
  android/            Android 端
release/              打包产物（不进仓库）
  *.apk  *.exe
work/                 调试数据（不进仓库，我跑测试产生的）
  shots/              截图
  logs/               日志与校验输出
  probes/             探针脚本
```

路径全部基于脚本自身位置解析，整个 `SparkKeep` 目录可以整体搬走，不需要改任何绝对路径。

## Windows 构建

```powershell
cd src\windows
pip install pyinstaller
pyinstaller --noconfirm SparkKeep.spec                       # 主程序 → dist\SparkKeep.exe
pyinstaller --noconfirm --onefile --windowed --name "续火花安装包" `
  --icon spark.ico --add-data "dist\SparkKeep.exe;." --add-data "spark.ico;." installer.py
```

产物 `dist\续火花安装包.exe` 是安装向导：默认装到 `%LOCALAPPDATA%\Programs\SparkKeep`，
建桌面与开始菜单快捷方式，附卸载脚本。环境变量 `SPARK_INSTALL_DIR` 可改安装目录，
`SPARK_NO_SHORTCUT=1` 跳过快捷方式，`--silent` 走静默安装（自测用）。

## Android 构建

需要 JDK 17、Android SDK（build-tools 34、platforms;android-34），并设好 `JAVA_HOME` / `ANDROID_HOME`。

```powershell
cd src\android
python make_assets.py          # 由 ..\windows\app.html 生成 assets\app.html
python make_icon.py            # 生成图标资源
pwsh -NoProfile -File build.ps1
```

`build.ps1` 依次执行 aapt2 → javac → d8 → zipalign → apksigner，产物 `SparkKeep.apk`。

前端改动一律改 `src\android\make_assets.py` 里的常量，再重跑脚本，**不要直接改 `assets/app.html`**（会被覆盖）。

## 结构

```
src/windows/            Windows 端
  app.html              前端（唯一样式与页面来源，两个平台共用）
  main.py               进程入口 / 本地服务
  server.py             /api 路由
  store.py              配置与状态持久化
  browser.py            Edge 启动与 CDP 连接
  cdp.py                CDP 协议封装
  douyin.py             抖音私信页自动化
  webui.py              打开界面
  installer.py          Windows 安装向导
src/android/
  make_assets.py        把 app.html 转成 Android 版（注入 API 层、手机 CSS、文案）
  build.ps1             手写构建脚本（不依赖 Gradle）
  java/com/spark/keep   Bridge / Spark / SparkService / Dy / Store / MainActivity / BootReceiver
  AndroidManifest.xml
```

## 许可与免责

![License](https://img.shields.io/badge/license-GPL--3.0-D9F24C?style=flat-square)

- 许可证：**GNU GPL v3.0**（全文见 [`LICENSE`](LICENSE)）。你可以自由运行、研究、修改、再分发；衍生作品必须同样以 GPL-3.0 开源，且不得追加额外限制。
- 免责声明：完整版见 [`DISCLAIMER.md`](DISCLAIMER.md)。要点——软件按「现状」提供，无任何担保；账号被封、消息发错、数据丢失、设备损耗、平台规则风险全部由使用者承担；作者全部赔偿责任上限为零。
- 这是一份**个人自用工具**，非商业产品，与抖音及其关联公司无任何隶属或授权关系。请只在你自己的账号上、低频、拟人化地使用，不要用于批量、营销、骚扰。
- `DISCLAIMER.md` 挡的是正常使用中的意外与不可控风险；法律强制规定不能排除的责任（人身损害、故意或重大过失致财产损失）不受本免责声明影响。

## 说明

- 抖音的私信接口与页面结构会变；自动化走的是网页版 DOM，不碰私有接口。
- 本项目只做个人账号的日常维护，不做批量、不做营销。
- 作者 宇捱 · 邮箱 aolikeyou@outlook.com
