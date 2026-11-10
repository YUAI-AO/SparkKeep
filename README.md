# 续火花 · SparkKeep

抖音私信「续火花」自动化的两个客户端，同一套前端与接口口径：

- **Windows**（`spark-src/`）：PyInstaller 单文件 exe，本地起 HTTP 服务 + WebView 页，走 Edge + CDP 驱动抖音网页版。
- **Android**（`android/`）：整块 WebView 加载同一份 `app.html`，前台服务保活 + 开机自启 + 退出界面后延迟释放内存。

界面按「性冷淡、极度克制」的方向打磨：低饱和、少装饰、卡片带液态玻璃质感，一切以低占用为前提。

## 功能

- 定时到点自动给名单里的好友发一条消息，维持火花天数
- 读取抖音私信会话列表，按火花状态自动纳入名单
- 文案池随机且不重复；可跳过今天已经聊过的人
- 失败有独立告警通知；任务进行中有一条带转圈的进度通知，跑完自动撤走
- 壁纸、卡片模糊、压暗、强调色、深/浅主题均可调，可切「玻璃 / 扁平」两种材质

## Windows 构建

```powershell
cd spark-src
pip install pyinstaller
pyinstaller --noconfirm SparkKeep.spec
# 产物 dist\SparkKeep.exe，双击即用（首次会要求登录抖音）
```

安装包（向导式）：

```powershell
pyinstaller --noconfirm --onefile --windowed --name "续火花安装包" --icon spark.ico --add-data "dist\SparkKeep.exe;." --add-data "spark.ico;." installer.py
```

## Android 构建

需要 JDK 17、Android SDK（build-tools 34、platforms;android-34）。

```powershell
cd android
python make_assets.py          # 由 spark-src\app.html 生成 assets\app.html
pwsh -NoProfile -File build.ps1
```

`build.ps1` 依次执行 aapt2 → javac → d8 → zipalign → apksigner，产物 `SparkKeep.apk`。

前端改动一律改 `android/make_assets.py` 里的常量，再重跑脚本，**不要直接改 `assets/app.html`**（会被覆盖）。

## 结构

```
spark-src/            Windows 端
  app.html            前端（唯一样式与页面来源）
  main.py             进程入口 / 本地服务
  server.py           /api 路由
  store.py            配置与状态持久化
  browser.py          Edge 启动与 CDP 连接
  cdp.py              CDP 协议封装
  douyin.py           抖音私信页自动化
  webui.py            打开界面
  installer.py        Windows 安装向导
android/
  make_assets.py      把 app.html 转成 Android 版（注入 API 层、手机 CSS、文案）
  build.ps1           手写构建脚本（不依赖 Gradle）
  java/com/spark/keep Bridge / Spark / SparkService / Dy / Store / MainActivity / BootReceiver
  AndroidManifest.xml
```

## 说明

- 抖音的私信接口与页面结构会变；自动化走的是网页版 DOM，不碰私有接口。
- 本项目只做个人账号的日常维护，不做批量、不做营销。
- 作者 宇捱 · 邮箱 aolikeyou@outlook.com
