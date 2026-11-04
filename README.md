# Friday Android

远程 [Friday](https://github.com/integemjack/friday) 的 Android 客户端：登录 SChat 账号，经 `schat-ws` 信令呼自己的机器人，
在 WebRTC DataChannel（`friday-work`）上跑 JSON-RPC。

- **会话**：看远程的全部会话、新建会话、发消息、停止；流式显示回复（含 thinking 折叠块、工具卡片、子 Agent 运行记录）
  - 长按会话：置顶、重命名、删掉；消息有多个分支时左右切换（`runner.selectBranch`）
  - 长按消息：复制文本 / 复制工具输出；上一轮失败时一键重试（`runner.retry`）
  - 排队消息列出来（暂停时点「接着发」）、每条单独删掉（`runner.resumeQueue` / `runner.removeQueued`）
  - 会话有多个根（多个分支）时只跟最早那条链，点分支切换器可切到其他根
  - 输入框左下角「＋」：附件、模型（每个下面写那家的余额 / 套餐用量，`services.usage`）、任务模式（不派 / 需要时派 / 工作流）、
    思考模式、工作目录（最近选过的 / 默认 / 浏览远程目录 `fs.list`），改的是这段对话的
    （`runner.setEngine` / `setTeamMode` / `setThinking` / `setWorkingDirectory`，换模型同时记成新对话默认）
  - 在跑时能**工作补充**：停止键一直在，打了字多一颗「补充给智能体」（做完当前这一步就交给它），补进去的消息标「工作中补充」
  - 框里空着时右边默认是**语音键**（手机自带的语音识别，边说边出字，说完看一眼再发）
- **助手**：和会话页是同一个输入框，也能带附件（`file.upload` 后 `assistant.say` 带路径）；「＋」里有**视频通话**：
  直接打给这台的助手（SChat 机器人通话 `callType: video`，Opus + H.264，和工作通道各用各的 PeerConnection）；
  模型 = 助手开会话 / 插话用的（`sessionEngineID`），任务模式 = 这台新对话的默认（`settings.update teamMode`），
  思考模式 = 助手大脑开不开深度思考（`brainThinking`）
- **媒体**：消息里的图片直接显示、点开全屏缩放看；看图 / 看视频 / 听录音的工具卡片内嵌媒体；
  音视频和文件点一下下载到本机用系统播放器打开。拉回来的按源缓存，第二次不再走网络（可在设置里清掉）
  - `file.read`（附件，按 `relativePath`）、`fs.read`（工具参数里的本地路径，相对路径按会话工作目录拼）
  - 每块 1 MB，超上限（图片 24 MB / 视频 64 MB / 音频 32 MB）不拉、给提示
- **附件**：聊天里能选相册图片 / 视频 / 任意文件，`file.upload` 分块上传，带进度，传完跟文字一起 `runner.submit`
- **通知**：App 在后台时有待确认的调用 / 提问发系统通知（带会话名和工具名），点通知直达活动页；
  新版本下载完也通知。Android 13+ 首次启动申请通知权限
- **活动**：所有会话的实时动静、待确认的调用批准 / 拒绝、ask_user 问题选项点选；点会话名直接进聊天
- **自动更新**：启动和每 6 小时查 GitHub 最新 Release，下载带进度，完成后台一键安装

对应桌面版：`friday-desktop-work`（Electron），两者连法是同一套：登录 SChat → `p2p_request{callType:"work"}`
→ offer / answer / ICE 经 `p2p_signaling` 互换 → DataChannel `friday-work` 上按 `WorkFramer` 分片跑
JSON-RPC 2.0（协议见 `Friday/qt/docs/REMOTE.md` §2）。

## 版本与发布（GitHub Actions 自动）

- **推 main → 自动发版**：CI 用最新 `v*` tag 判断版本——
  - 版本没动：`patch +1`（写回 `app/build.gradle.kts`，提交 `[skip ci]`），打 tag、构建、发 Release
  - 手动把 `APP_VERSION_NAME` 改大：按手动的版本发（`minor` / `major` 由你决定）
  - `versionCode` 每次发版必然 +1（手动改小了会被自动提到 `tag 的 code + 1`），保证覆盖安装
- **重跑保护**：同一版本 tag 已存在就不重复发
- 构建产物校验：APK 的 `versionName` / `versionCode` 必须与 tag 一致，否则任务失败
- 应用内自动更新读 `releases/latest`，所以「latest Release」永远对应最新构建

### 首次配置（一次性）

1. 生成签名密钥（本地，不入库）：

   ```bash
   keytool -genkeypair -v -keystore friday-release.jks -alias friday -keyalg RSA -keysize 2048 -validity 10950
   ```

2. 仓库 Settings → Secrets and variables → Actions 添加：

   | Secret | 值 |
   |---|---|
   | `FRIDAY_KEYSTORE_BASE64` | `base64 -w0 friday-release.jks`（Windows: `certutil -encode` 后去头尾，或 PowerShell `[Convert]::ToBase64String([IO.File]::ReadAllBytes(...))`） |
   | `FRIDAY_KEYSTORE_PASSWORD` | keystore 密码 |
   | `FRIDAY_KEY_ALIAS` | 别名（上例 `friday`） |
   | `FRIDAY_KEY_PASSWORD` | 密钥密码 |

3. 推一次 main，之后每次推 main 自动出新版本。

## 本地构建

```bash
./gradlew assembleDebug    # 调试包
./gradlew assembleRelease  # 正式包（需 FRIDAY_KEYSTORE_* 环境变量，缺省退化为未签名）
./gradlew testDebugUnitTest  # 单元 + 界面测试（92 个，JVM 上跑，不用设备）
./gradlew lintDebug        # lint（CI 闸门）
bash tools/test-bump-version.sh   # bump 逻辑测试
```

- 另有一个默认跳过的真连测试 `LiveDialTest`（真登录 chatserver、真呼一次机器人，验信令那一半；
  DataChannel 那半段要真机）：
  `SCHAT_SERVER=https://host:port SCHAT_TOKEN=… SCHAT_BOT_ID=100000057 ./gradlew :app:testDebugUnitTest --tests '*LiveDialTest*'`

## 演示数据（只 debug 包）

debug 包里带一份假数据（`app/src/debug`，release 包不编译），用来截图看排版：

```
adb shell am start -n com.integemjack.friday/.MainActivity --es demo sessions   # 会话列表
adb shell am start -n com.integemjack.friday/.MainActivity --es demo chat       # 聊天页
adb shell am start -n com.integemjack.friday/.MainActivity --es demo chat-running  # 生成中
adb shell am start -n com.integemjack.friday/.MainActivity --es demo activity --ei friday_tab 1
adb shell am start -n com.integemjack.friday/.MainActivity --es demo settings --ei friday_tab 2
```

不带 `--es demo` 就是正常启动。

- JDK 17（GitHub Actions 用 temurin 17）。AGP 8.7 不接受 JDK 21+，本机只有更高版本时用
  `-Dorg.gradle.java.home=<jdk17 路径>` 指过去
- Android SDK：platform 35、build-tools（AGP 自动下载）
- 界面测试用 Robolectric 真渲染（`ui-test-junit4` + `unitTests.isIncludeAndroidResources`），
  不需要模拟器；Dialog 里的输入框在 Robolectric 下会让 Compose 一直不 idle，测试里避开这一块

## 结构

```
app/src/main/java/com/integemjack/friday
  proto       协议层：JSON-RPC、WorkPeer（WebRTC）+ WorkSignaling（p2p_request/signaling 状态机）
              + WorkFramer（DataChannel 分片）、WorkTransport、消息补丁（messagePatch）、会话状态合并、
              文件分块传输（FileTransfer）、媒体取与缓存（MediaLoader）
  schat       SChat 那一头：schat-ws 连接（信令）、登录 / 凭据轮换 / 证书指纹钉住、机器人列表、ICE
  update      应用内自动更新（GitHub Releases 查询 / 进度下载 / 安装）
  data        SChat 会话与配置存储、Android Keystore 加密
  ui          Compose 界面：会话 / 聊天 / 活动 / 设置；媒体视图（MediaViews）、附件（Attachments）
app/src/test    协议纯逻辑（含 WorkFramer / WorkSignaling）+ 假 RPC 的服务端 + Robolectric 界面渲染测试
               + LiveDialTest（默认跳过，真连 chatserver）
tools         bump-version.sh（CI 与本地共用）+ 测试
```

版本号在 `app/build.gradle.kts` 顶部两个常量：`APP_VERSION_NAME` / `APP_VERSION_CODE`。
