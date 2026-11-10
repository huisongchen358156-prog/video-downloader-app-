# BUILD_NOTES.md — 构建与验证记录（2026-11-10）

给接手的人看的：每一步实际跑了什么、结果是什么、没跑通的是为什么。

## 一、版本核对（有来源，不是猜的）

| 组件 | 采用版本 | 依据 |
|---|---|---|
| Chaquopy Gradle 插件 | `com.chaquo.python` **16.0.0** | chaquo.com 官方文档 16.0 版：AGP 7.0.x–8.8.x，Python 3.8–3.13 |
| Android Gradle Plugin | **8.5.2** | 在 Chaquopy 16.0.0 支持范围内；要求 Gradle 8.7+、JDK 17 |
| Gradle | **8.7** | `gradle/wrapper/gradle-wrapper.properties`；本机 `~/gradle-8.7` |
| Kotlin | **1.9.24** | 与 AGP 8.5.2 兼容 |
| Python（Chaquopy 内） | **3.11** | yt-dlp 2026.10.26 起最低要求 Python 3.11 |
| yt-dlp | **2026.10.26** | 2026-10-26 发布的稳定版（PyPI / newreleases.io 可查）；本地 `pip install` 已验证存在 |
| JDK | Temurin **17.0.21** | 本机 `~/jdk17/bin/java -version` |
| Android SDK | API 34（platforms/android-34，build-tools 34.0.0） | 本机 `~/Android/Sdk` |

## 二、本地构建尝试（均失败，环境限制）

本机有 JDK 17、Gradle 8.7、Android SDK，`curl` 到 Maven Central / PyPI / chaquo.com / services.gradle.org 均返回 200，网络是通的。但：

1. `gradle wrapper --gradle-version 8.7 --no-daemon` → `Could not receive a message from the daemon.`
2. `gradle --no-daemon --info -q help` → 同上。
3. `gradle --no-daemon --stacktrace help` → 同上，堆栈为 `DaemonConnectionException`（`SingleUseDaemonClient`）。
4. `gradle --no-daemon -Dorg.gradle.jvmargs= help` 和 `-Dorg.gradle.daemon=false` → 同上。
5. 检查 `~/.gradle/daemon/8.7/`：**目录不存在**，守护进程从未写出日志；本机 Python 的 loopback bind 测试正常。

结论：沙箱里 Gradle 守护进程 fork 后无法与客户端通信，本地无法完成任何 Gradle 构建（哪怕只是 `help`）。**不是代码问题。** 出路：GitHub Actions 云端构建（已配好 `.github/workflows/build-apk.yml`）。

Gradle Wrapper 文件（`gradlew` + `gradle/wrapper/gradle-wrapper.jar`，43KB）因本地 `gradle wrapper` 任务跑不起来，改为从 `github.com/gradle/gradle/raw/v8.7.0/` 直接下载官方文件，已验证 jar 为合法 zip。

## 三、通过的本地验证（都有实际输出）

### 1. Python 解析模块语法
- `python3 -m py_compile app/src/main/python/downloader.py` → `PY_SYNTAX_OK`

### 2. yt-dlp 真实解析（联网实测）
- 本机 `pip install yt-dlp==2026.10.26` 成功，`yt_dlp.version` 输出 `2026.10.26`。
- `extract_info("https://www.youtube.com/watch?v=dQw4w9WgXcQ")` → `ok: True`，标题 `Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)`，作者 `Rick Astley`，时长 213 秒。
- **重要发现**：该次解析只返回 1 条音频格式（format 249），`max_combined_height: 0`，`has_higher_separate: True`。即 YouTube 在无 JS 运行环境时拿不到合并视频格式——V1 在 YouTube 上可能只能下音频。已如实写进 README 和 App 内帮助文案。抖音 / 快手 / B 站等平台的源文件一般是单个合并文件，不受此影响（未实测到真实分享链接，待真机验证）。
- 错误路径：`extract_info("https://example.com/not-a-video")` → `{"ok": false, "code": "network", ...}`，形状正确。

### 3. yt-dlp 真实下载（联网实测）
- `download(url, "249", tmpdir, "test_audio", progress.json)` → `ok: True`，落盘 `test_audio.webm`，1,231,355 字节；进度文件最终内容 `{"status": "finished", "percent": 100}`。下载代码路径真实跑通。

### 4. Kotlin 纯逻辑单测（35 项，全过）
- 用下载的 kotlin-compiler 1.9.24 编译 `LinkUtils.kt` + 测试 main（`/tmp/LinkUtilsTest.kt`，测试文件在 /tmp，未进仓库），`java -jar` 运行：`ALL LINKUTILS TESTS PASSED`。
- 覆盖：10 个平台识别、5 条链接提取（含中文标点/括号）、6 条文件名清洗、4 条时长、4 条大小、5 条中文错误提示。
- 途中修正 1 处**测试用例**的期望错误（全角 `：？` 是合法文件名字符，应保留；函数本身没改）。

### 5. Python 格式筛选逻辑离线单测（全过）
- `/tmp/test_downloader_filter.py`：用假的 yt-dlp 返回值（1080p mp4/webm 合并、720p 合并、4K 纯视频、两条音频），断言：只留 1080p mp4 + 720p + 1 条音频，同清晰度去重正确，`has_higher_separate: True`。输出 `ALL DOWNLOADER FILTER TESTS PASSED`。

### 6. 资源 ID / 引用逐项核对（全过）
- Kotlin 里用到的 18 个 `R.id.*` 在两个布局文件里全部存在，无缺失、无多余引用。
- `R.layout.activity_main` / `activity_help` 均存在；`R.string.app_name` / `help_title` 均存在。
- Manifest 里的 `.MainActivity`、`.HelpActivity`、`.DownloadService` 与源码文件一一对应。
- `grep androidx|jetpack|compose`：零命中（刻意零第三方 Android 依赖）。
- Python 模块在 `app/src/main/python/downloader.py`（Chaquopy 默认位置）。

### 7. 写完后的复查修正（未编译，仅靠 API 知识）
- `MainActivity` 注册下载进度广播时，改为按 Android 14 要求声明 `RECEIVER_NOT_EXPORTED`（targetSdk 34 下不声明会在真机上抛 SecurityException）；API 33 以下走旧的两参数注册。**此项改动未经编译验证**，云端首次构建时如报错请优先检查此处。

## 四、未验证（必须在真机上补）

- Gradle 完整编译（本地编不出，等 GitHub Actions 首次运行结果）。
- APK 安装、启动、剪贴板自动填入、ACTION_SEND 接分享。
- 真机解析各平台真实分享链接（尤其抖音 / 快手 / 小红书 / B 站）。
- 前台服务通知、下载进度广播、MediaStore 落盘、历史记录打开 / 分享 / 删除。
- Android 8/9（API 26–28）的旧版存储分支（本机无真机）。

## 五、文件清单

```
settings.gradle.kts / build.gradle.kts / gradle.properties
gradlew / gradle/wrapper/gradle-wrapper.{jar,properties}
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/noah/videodownloader/
  MainActivity.kt  DownloadService.kt  YtDlpBridge.kt
  HistoryStore.kt  LinkUtils.kt  HelpActivity.kt
app/src/main/python/downloader.py
app/src/main/res/layout/activity_main.xml  activity_help.xml
app/src/main/res/values/strings.xml
.github/workflows/build-apk.yml
README.md  BUILD_NOTES.md  .gitignore
```

没有 APK 产物（`find -name "*.apk"` 结果为 0）。拿到 APK 的唯一路径：按 README“云端构建”走 GitHub Actions。
