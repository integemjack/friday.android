# Friday Android

远程 [Friday](https://github.com/integemjack/friday) 的 Android 客户端：连接跑着 Friday 的机器（SSH 桥），

- **会话**：看远程的全部会话、新建会话、发消息、停止；流式显示回复（含 thinking 折叠块、工具卡片、子 Agent 运行记录）
- **活动**：所有会话的实时动静、待确认的调用批准 / 拒绝、ask_user 问题选项点选
- **自动更新**：启动和每 6 小时查 GitHub 最新 Release，下载带进度，完成后台一键安装

对应桌面版：`friday-desktop-work`（Electron）。协议按 `Friday/qt/docs/REMOTE.md`（JSON-RPC 2.0 over SSH 桥）实现。

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
bash tools/test-bump-version.sh   # bump 逻辑测试
```

- JDK 17+（GitHub Actions 用 temurin 17）
- Android SDK：platform 35、build-tools（AGP 自动下载）

## 结构

```
app/src/main/java/com/integemjack/friday
  proto       协议层：JSON-RPC、SSH 桥传输、消息补丁（messagePatch）、会话状态合并
  update      应用内自动更新（GitHub Releases 查询 / 进度下载 / 安装）
  data        服务器配置（Android Keystore 加密存储）
  ui          Compose 界面：会话 / 聊天 / 活动 / 设置
tools         bump-version.sh（CI 与本地共用）+ 测试
```

版本号在 `app/build.gradle.kts` 顶部两个常量：`APP_VERSION_NAME` / `APP_VERSION_CODE`。
