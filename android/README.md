# Agents Anywhere Android

Native Android client for Agents Anywhere.

## Stack

- Kotlin
- Jetpack Compose
- Material 3
- Android Gradle Plugin

## Open In Android Studio

1. Install Android Studio.
2. Open this `android/` directory.
3. Let Android Studio install the requested Android SDK and sync Gradle.
4. Connect an Android phone with USB debugging enabled.
5. Run the `app` configuration.

## Install 2.0.0

Download the Android APK from the [release download table](../README.md#下载与入口).
The client supports Android 8.0+ (API 26), sessions, devices, runtime configuration,
approvals, files and terminals through the v2 API. Cloud is the default service;
a self-hosted address can be selected in the login flow.

## Build from the command line

Use JDK 17 and the Android SDK matching `compileSdk` in `app/build.gradle.kts`
(currently 36). Set `ANDROID_HOME` or the local SDK path in ignored
`local.properties`, then run from this directory:

```bash
./gradlew testDebugUnitTest assembleDebug
./gradlew assembleRelease
```

On Windows use `gradlew.bat`. Outputs are under `app/build/outputs/apk/`.
The checked-in release build type does not define a signing configuration;
`assembleRelease` alone does not produce a distributable signed update. Apply the
release signing process with the existing keystore and keep that identity for
future updates. Do not commit passwords or keystores. Changing the signature
prevents an in-place update of an installed app signed with another key.

`local.properties` remains machine-specific and ignored. The manifest allows
HTTP for local/self-hosted addresses; use HTTPS for public service endpoints.

## 数学公式

聊天消息在 Android 本地排版数学公式，支持行内 `$...$`、`\(...\)`，以及块级 `$$...$$`、`\[...\]`。
公式字体随消息字号缩放，颜色跟随明暗主题；长公式可横向滑动，选中复制时保留 LaTeX 源码。
代码示例保持原文，流式回复在公式闭合后排版；不支持或有语法错误的公式回退为文本，其他消息内容继续显示。

这个功能需要重新构建并安装新版 Android APK，仅同步服务器代码不会改变已安装 App 的渲染能力。
更新 App 后，已有聊天记录也会按新规则显示，无需修改服务端消息或重新生成回复。

公式解析回归测试位于 `app/src/test/java/com/agentsanywhere/app/ui/screens/sessiondetail/MarkdownMathParserTest.kt`。
`MarkdownScrollTest.kt` 使用 Robolectric 原生图形环境，覆盖倒序聊天列表中的普通引用、嵌套引用、公式、深色主题及大字号的往返滚动，并验证公式实际绘制。
引用边框根据内容测量后的高度绘制，不能向公式或代码布局请求固有高度；普通文本段落保持原有的文本布局。
多行公式不能直接作为行内占位符的 alternate text：跨段落的占位符在 Android 上不会获得显示位置。
块级公式使用不含换行的内部标记布局，通过选择复制映射还原 LaTeX 原文；无障碍标签仍保留公式源码。
公式占位符按 Android 的线性像素换算计算尺寸，避免大字号下非线性字体换算造成二次放大和偏移。
块级长公式由文本外层的滚动区域处理横向拖动，文本和公式一起移动，避免行内子视图的选择手势拦截滑动。
发布前需在手机上验证长公式滑动、明暗主题、系统字体缩放和选择复制；单元测试与 APK 编译不替代真机验证。

在已连接的 Android 模拟器或手机上运行消息组件的画面检查：

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.agentsanywhere.app.ui.screens.sessiondetail.MathPresentationTest
```

该测试检查聊天组件实际显示的像素，并生成分式、单行公式、积分、矩阵、多行推导、长公式以及明暗主题和大字号聊天截图。
截图保存在设备的 `/sdcard/Android/data/com.agentsanywhere.app/files/math-qa/current/`，可使用 `adb pull` 导出后检查。

## Application updates

After entering the signed-in app, Android reads the saved server's
`/api/v2/health` and compares its `version` with `BuildConfig.VERSION_NAME`
numerically. This also runs on launch with an existing login and when the saved
server changes. Login screens and sessions without a saved server never check
for updates; no default server is substituted. Signing out cancels pending
checks and downloads and clears the visible update state.
`AppConfig.UPDATE_DOWNLOAD_URL` is the fixed APK address and currently contains
an explicit `.invalid` placeholder that must be replaced before distribution.

**Ignore this version** saves the target version per server in the private
`app-updates` SharedPreferences file. The same version stays quiet on later
launches; a newer version prompts again. Settings keeps a download icon with a
red dot beside **Check for updates** whenever a newer version exists. Tapping it
reopens the dialog even for an ignored version. Outside taps and Back do not
close the prompt. Downloads show progress and continue through the existing APK
installer flow.

### 2.1 共享工作区和消息时间

服务端显式启用共享模式后，填写地址即可进入；已保存地址在启动时自动获取共享会话。首页设备筛选同时用于项目和时间视图，每台手机单独记住选择。消息使用 Timeline.createdAt，按手机时区显示到秒；旧消息无有效时间显示“时间未记录”。

## 额度错误与远程回答（2.1.1）

额度耗尽、限流和运行错误使用错误提示，不再统一显示为“待你回应”。有可提交动作的真实问题优先展示；需开启接管且设备在线，手机才能选择并提交。暂不支持的问题格式会提示到电脑端查看，避免发送无效回答。接口保持兼容，只需更新 Android App。

`RuntimeNoticePresentationTest` 的单元测试覆盖分类与展示优先级；同名模拟器测试使用正式卡片和回应 API，向设备本地隔离服务器提交单选、多选和自定义回答，并核对实际请求。运行方式与上面的消息显示测试一致，将测试类改为 `com.agentsanywhere.app.ui.screens.sessiondetail.RuntimeNoticePresentationTest`。详见 [2.1.1 发布说明](../docs/releases/2.1.1.md)。

## 服务器发布 App 更新（2.1.2）

首次手动安装 2.1.2 后，App 在启动、回到前台以及前台每 5 分钟检查已连接服务器的 Android 更新清单；也可在设置中手动检查。发布 APK 无需改 Server 版本。点击更新会显示下载进度，校验文件长度、SHA-256、版本与签名，再打开系统确认安装。忽略版本后不会反复提示同一个构建；更高版本会再次提示。

自托管配置与后续发布见 [更新部署说明](../deploy/ANDROID-UPDATES.md)。旧版下载地址为占位地址，仍需手动安装一次支持更新的新版本。

## “未分组”散聊文件夹（2.1.3）

首页项目视图将散聊的自动工作目录和没有项目的对话合并到“未分组”，可展开收起、按设备和 Agent 筛选。正常目录项目和手动创建、改名、置顶的项目保留原样。仅调整列表展示，不创建服务器项目或改动会话工作目录。测试位于 `UngroupedProjectsTest` 和模拟器中的 `UngroupedFolderTest`。详见 [2.1.3 发布说明](../docs/releases/2.1.3.md)。

## 项目对话更新时间（2.1.4）

项目、“未分组”和置顶列表的每条对话右侧显示最后活动时间。手机本地当天固定显示 24 小时制 `HH:mm`，其他日期显示 `MM/dd`，不显示年份；缺少有效记录时显示 `—`。列表回到前台或前台每分钟更新日期判断，跨天自动切换格式。详见 [2.1.4 发布说明](../docs/releases/2.1.4.md)。

## 安装兼容与安装包清理（2.1.5）

覆盖更新要求包名和签名一致、版本递增，与安装包文件名无关。Android 2.1.5 保持之前分发包的签名和包名；包中包含 arm64-v8a、armeabi-v7a、x86 和 x86_64 架构。不同手机的系统安装器、未知来源权限、工作空间及安全策略可能造成不同结果，不能绕过 Android 的签名检查。不要为解决冲突默认卸载，卸载可能丢失本地配置。

App 内下载的 APK 在系统安装会话接管文件后自动删除；安装成功、失败或取消后，系统负责释放其安装会话副本。复制或提交异常时会主动放弃会话。启动时清理历史遗留的更新 APK，取消授权或安装准备失败时也清理原下载文件。只处理本 App 私有更新缓存，不删除聊天附件，也不能自动删除微信、浏览器或 Downloads 中用户自行保存的安装包。

更新包签名不同、版本过旧，以及系统返回的应用冲突、空间不足、权限限制、设备不兼容、取消和无效包有独立提示。后台收到的失败结果会保留到下次打开 App，即使尚未加载更新清单也能显示。详见 [2.1.5 发布说明](../docs/releases/2.1.5.md)。
