# 构建环境与迁移指南（在 PC 上编译 Android Code Studio）

> 目的：把 MCP 功能编译成可安装 APK。本机（Android 手机 + proot Ubuntu）已经跑通到
> 「依赖齐、arm64 工具链齐、能编译 649 个任务」，但受**可用内存 ~1.6 GB** 限制，Gradle 守护进程会被
> OOM 杀掉，因此改在 PC 上完成最后一步。本文记录全部版本、坑与解法，照抄即可在 PC 上出包。

---

## 0. 当前状态

| 项目 | 状态 |
|---|---|
| MCP 功能代码 | 完成（17 个新文件，7 个文件追加修改） |
| 协议层 / 文件工具验证 | **JVM 自检 44 项全通过**（真实启动 HTTP 服务） |
| 依赖获取 | 除 `io.github.rosemoe:editor:0.24.7-acs` 外全部可正常下载（该件见 §4） |
| APK 编译 | 未完成：编译到第 649 个 task（约 60%）时 daemon 被 OOM 杀掉 |
| 需要你做的 | 在 PC 上执行 §6 的命令，产出 APK 并跑 §7 的验收 |

---

## 1. 版本矩阵（务必对齐）

| 组件 | 版本 | 来源 / 说明 |
|---|---|---|
| Gradle | **9.8.2** | 仓库 `gradle/wrapper/gradle-wrapper.properties` 指定 |
| Android Gradle Plugin | **9.5.0** | `gradle/libs.versions.toml` |
| Kotlin | **2.4.0**（`:core:app`）/ 2.4.10（sora-editor 源码） | 同上 |
| JDK | **17+**（实测 17 可用；PC 建议 21） | AGP 9.x 最低 17 |
| compileSdk | **37** | `composite-builds/build-logic/common/.../BuildConfig.kt` |
| minSdk / targetSdk | 26 / 28 | 同上 |
| Build Tools | **37.0.0** 与 **36.0.0**（两个都要） | 36.0.0 被 `:external:logwire` 等模块钉死 |
| Android Platform | **android-37.0**（canary 通道）+ android-34（AGP 会自动装） | 公共 channel 0 只有到 36，37 要用 `--channel=3` |
| NDK | **27.1.12297006** | `termux/emulator`、`termux/shared` 用 ndkBuild |
| CMake | 不需要 | 原生构建走 `ndk-build` |
| 磁盘 | ≥ 30 GB 空闲 | Gradle 缓存 + 构建产物 |
| 内存 | **建议 ≥ 8 GB 可用**（手机上是 1.6 GB，死于这里） | 见 §5 |

---

## 2. PC 环境准备

```bash
# 1) JDK（macOS: brew install openjdk@21；Windows: 用 Temurin 21）
export JAVA_HOME=/path/to/jdk-21

# 2) Android SDK 命令行工具
#    https://developer.android.com/studio#command-line-tools-only
export ANDROID_HOME=$HOME/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

yes | sdkmanager --licenses
yes | sdkmanager --channel=3 "platforms;android-37.0" "build-tools;37.0.0" "build-tools;36.0.0" "platform-tools"
yes | sdkmanager "ndk;27.1.12297006"
```

> PC 是 x86_64，直接用官方 build-tools 即可，**不需要** §3 里的 arm64 替换和 aapt2 覆盖。

---

## 3. 仓库内必须先补的两处前置

**3.1 空目录**（Gradle 9 不允许 include 不存在的工程）：

Gradle 9 不允许 include 不存在的工程目录，而 `composite-builds/build-logic/settings.gradle.kts`
里 include 了 `:desugaring-core`，实际代码在 `composite-builds/build-deps-common/desugaring-core`：

```bash
mkdir -p composite-builds/build-logic/desugaring-core
```

（空目录即可，Gradle 只要求目录存在；该工程没有被任何脚本依赖。）

**3.2 框架桩用的 `android.jar`**：`utilities/framework-stubs` 会 `compileOnlyApi(files("libs/android.jar"))`，
而该文件被 `utilities/framework-stubs/.gitignore` 忽略，需要你从本机 SDK 复制一份：

```bash
mkdir -p utilities/framework-stubs/libs
cp "$ANDROID_HOME/platforms/android-37.0/android.jar" utilities/framework-stubs/libs/android.jar
```

（42 MB，属 Google SDK 二进制，仓库不收录；缺失时 `:utilities:framework-stubs` 编译会失败。）

---

## 4. 唯一拿不到的依赖：`io.github.rosemoe:editor:0.24.7-acs`

**问题**：这是 nullij 的 sora-editor 私有分支构建产物。

- `https://jitpack.io/io/github/rosemoe/editor/0.24.7-acs/...` → **404**
- `https://jitpack.io/com/github/...` → **401 No access token**
- 阿里云 / 腾讯 / 华为 / Maven Central 镜像全部 **404/401**
- JitPack 上 `com.github.nullij/sora-editor` 的 0.24.4 / 0.24.7 构建状态都是 **Error**

**解法（二选一）**

### 方案 A：直接用已构建好的 AAR（推荐，已验证可编译）

本机已从 `nullij/sora-editor` 的 `0.24.7` 标签源码构建出 `editor-debug.aar`，已导出为：
`editor-0.24.7-acs.aar`（由本机从 sora-editor 源码构建，见本节说明）。

在 PC 上装进 mavenLocal（项目 `settings.gradle.kts` 的仓库列表第一项就是 `mavenLocal()`，所以不用改依赖坐标）：

```bash
mkdir -p ~/.m2/repository/io/github/rosemoe/editor/0.24.7-acs
cp editor-0.24.7-acs.aar ~/.m2/repository/io/github/rosemoe/editor/0.24.7-acs/
cat > ~/.m2/repository/io/github/rosemoe/editor/0.24.7-acs/editor-0.24.7-acs.pom <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.github.rosemoe</groupId>
  <artifactId>editor</artifactId>
  <version>0.24.7-acs</version>
  <packaging>aar</packaging>
  <dependencies>
    <dependency><groupId>androidx.annotation</groupId><artifactId>annotation</artifactId><version>1.11.0</version><scope>compile</scope></dependency>
    <dependency><groupId>androidx.collection</groupId><artifactId>collection</artifactId><version>1.6.0</version><scope>runtime</scope></dependency>
    <dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId><version>2.4.10</version><scope>runtime</scope></dependency>
  </dependencies>
</project>
POM
```

### 方案 B：自己从源码构建

```bash
git clone --depth 1 --branch 0.24.7 https://github.com/nullij/sora-editor.git
cd sora-editor
./gradlew :editor:assembleDebug      # 产物 editor/build/outputs/aar/editor-debug.aar
# 然后按方案 A 的目录结构放进去
```

> 如果之后能拿到真正的 `-acs` 工件（例如作者提供的 JitPack 令牌），直接替换 mavenLocal 里那份即可，
> 其它构建配置无需改动。

---

## 5. 内存与 JVM 参数（手机端失败的根本原因）

手机端实测：`MemTotal 7.5 GB`，但 `MemAvailable` 只有 **0.8–1.6 GB**（系统占用高），Gradle daemon
用 `-Xmx1600m` 时跑到 649 个 task 就被 OOM killer 干掉，日志是
`Gradle build daemon disappeared unexpectedly`。

PC 上建议：

```properties
# gradle.properties 或命令行 -D
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g
org.gradle.parallel=true
org.gradle.caching=true
kotlin.daemon.jvmargs=-Xmx2g
```

手机端当时用的保守参数（仅作参考，PC 不要用）：
`-Xmx1150m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process`

---

## 6. 构建命令

```bash
cd android-code-studio

# 只用仓库自带密钥（有口令时）
-DSIGNING_STORE_PASSWORD=xxx -DSIGNING_KEY_PASSWORD=xxx

# 或者用本地调试密钥（仓库 keystore 口令未知时；core/app/build.gradle.kts 已内置该回退）
keytool -genkeypair -keystore ~/debug.jks -alias androiddebugkey \
  -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"

./gradlew :core:app:assembleDebug \
  -PlocalDebugKeystore=$HOME/debug.jks \
  -PlocalDebugStorePassword=android \
  -PlocalDebugKeyAlias=androiddebugkey \
  -PlocalDebugKeyPassword=android
```

产物：

```
core/app/build/outputs/apk/debug/android-code-studio-arm64-v8a-debug-<version>.apk
core/app/build/outputs/apk/debug/android-code-studio-armeabi-v7a-debug-<version>.apk
```

> 仓库原生支持两个 ABI 变体（`android-code-studio/build.gradle.kts` 里按 ABI 拆分输出）。
> 如果只想先验证功能，可以先只跑 `./gradlew :core:app:compileDebugKotlin` 确认代码能编过。

安装与初步验证：

```bash
adb install -r core/app/build/outputs/apk/debug/android-code-studio-arm64-v8a-debug-*.apk
```

---

## 7. 功能验收（装机后）

1. 打开 App → 设置 → 开发者选项 → **MCP 服务器** → 启用（默认 `127.0.0.1:6688`，默认关闭）。
2. 自检脚本（在仓库根目录）：

   ```bash
   ./docs/mcp/selftest.sh http://127.0.0.1:6688
   # 设了令牌时： ./docs/mcp/selftest.sh http://127.0.0.1:6688 <token>
   ```

   - 手机与 PC 不是同一台设备时，先用 `adb forward tcp:6688 tcp:6688`，或在设置里打开
     「允许远程访问」+ 设置令牌。
3. 打开「允许写操作」「允许构建」后，`tools/list` 应包含 22 个工具；`build_start` 能跑通
   `assembleDebug`，`build_apk` 能给出 APK 路径。

---

## 8. 本次改动清单（便于 code review）

**新增（MCP 功能，17 个文件）**

```
core/app/src/main/java/com/tom/rv2ide/mcp/
├─ McpServerManager.kt      生命周期、偏好应用、状态摘要、审计日志
├─ McpToolRegistry.kt       沙箱根、项目上下文、编辑器缓冲读取
├─ McpBuildFacade.kt        绑定构建服务、初始化工具链、构建/取消/日志
├─ McpFileEvents.kt         文件事件 → 文件树/编辑器同步
├─ protocol/JsonRpc.kt      JSON-RPC 2.0
├─ protocol/McpProtocol.kt  initialize / ping / tools.list / tools.call、HTTP 路由
├─ server/HttpCodec.kt      HTTP/1.1 解析与响应
├─ server/McpHttpServer.kt  ServerSocket + 线程池
├─ security/PathSandbox.kt  路径沙箱
├─ security/BearerAuth.kt   令牌校验
├─ security/AuditLog.kt     审计日志
└─ tools/{McpTool,Schema,TextEdit,FileTools,WorkspaceTools,BuildTools}.kt
docs/mcp/{BUILD-ENVIRONMENT.md,MCP-USAGE.md,selftest.sh,devcheck/JvmSelfCheck.kt}
```

**修改（只在末尾追加，0 行删除）**

| 文件 | 改动 |
|---|---|
| `utilities/preferences/.../DevOpsPreferences.kt` | +63 行：10 个 `KEY_DEVOPTS_MCP_*` 键与属性 |
| `core/app/.../preferences/developerOptionsPrefExts.kt` | +235 行：`McpServerScreen` 子页 + 10 个设置项 |
| `core/resources/.../values/strings.xml`、`values-zh-rCN/strings.xml` | 各 +26 条中英文案 |
| `core/app/.../services/builder/GradleBuildService.kt` | +77 行：只读日志环 `BUILD_LOG_LIMIT=2000` 与状态快照（不改任何既有分支） |
| `core/app/.../app/IDEApplication.kt` | +6 行：`McpServerManager.init(this)`（后台线程、包 try/catch） |
| `core/app/build.gradle.kts` | +19 行：可选 `localDebug` 签名配置（只有传 `-PlocalDebugKeystore` 时才启用，默认行为不变） |

**仓库外的临时改动（仅本机，PC 不需要）**

- `/etc/hosts` 手工加了 `repo.maven.apache.org` 的解析（本机 DNS 故障）
- build-tools 目录被替换成 arm64 版本 + `-Pandroid.aapt2FromMavenOverride`（手机是 aarch64，官方 SDK 工具是 x86_64）
- `composite-builds/build-logic/desugaring-core/` 空目录（见 §3，PC 上也要建）

---

## 9. 环境排障速查（本机踩过的 8 个坑）

| 现象 | 原因 | 解法 |
|---|---|---|
| `./gradlew` 卡在下载并抛 `SocksSocketImpl.connect` | 发行版 307 重定向到 GitHub release 资源，JDK 的下载器处理失败 | 用 `curl -L` 手动下 `gradle-9.8.2-bin.zip`，解压后直接用 `bin/gradle` |
| `Configuring project ':desugaring-core' without an existing directory` | Gradle 9 新限制 | `mkdir -p composite-builds/build-logic/desugaring-core` |
| `Temporary failure in name resolution: repo.maven.apache.org` | 本机 DNS 对 maven 的 CNAME 解析失败 | 写 `/etc/hosts` 指向 `repo1.maven.org` 的 IP，或加镜像仓库 |
| `io.github.rosemoe:editor:0.24.7-acs` 401/404 | 私有 fork 构建，公共渠道无此件 | 见 §4（本地构建 AAR 或源码自建） |
| `A problem occurred starting process '.../build-tools/36.0.0/aidl'` | 官方 build-tools 是 x86_64，设备是 aarch64 | 换 `AndroidIDEOfficial/androidide-tools` 的 `build-tools-34.0.4-aarch64`（PC 不需要） |
| `platforms/android-37/android.jar does not exist` | 公共通道只有 `android-37.0` | `platforms;android-37.0` 安装后软链/复制成 `android-37`（PC 上 AGP 会按需自动装） |
| `Gradle build daemon disappeared unexpectedly` | 内存不足被 OOM killer 杀掉 | 见 §5，PC 上给 4 GB 堆 |
| `keystore password was incorrect`（签名阶段） | 仓库 `signing/signing-key.jks` 口令未知 | 用 `-PlocalDebugKeystore` 回退本地调试密钥，或设置 `SIGNING_STORE_PASSWORD` |

---

## 9.1 全局兜底：让**已有**项目也能解析 Maven Central（不用重新编译 App）

在 IDE 的终端里（`$HOME` 就是 App 的 home，Gradle 用户目录是 `$HOME/.gradle`）：

```bash
mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/acs-repos.gradle" <<'EOF'
// repo.maven.apache.org 在部分移动网络不可达，优先使用 Maven Central 官方别名与 Google 镜像
def extra = { handler ->
    handler.maven { url 'https://repo1.maven.org/maven2' }
    handler.maven { url 'https://maven-central.storage-download.googleapis.com/maven2' }
}
beforeSettings { settings ->
    extra(settings.pluginManagement.repositories)
    extra(settings.dependencyResolutionManagement.repositories)
}
allprojects {
    buildscript { extra(repositories) }
    extra(repositories)
}
EOF
```

`beforeSettings` 需要 Gradle 8.1+（本仓库 9.8.2、新项目模板 9.0.0 都满足）。它只**追加**仓库，不改任何现有配置，
删掉文件即恢复原状。

## 10. 可选：JVM 自检（不装 APK 也能验证协议层）

自检程序在 `docs/mcp/devcheck/JvmSelfCheck.kt`，它不依赖 Android，会真实起一个 HTTP 服务并跑 44 项断言。

```bash
# 1) 下载编译器与序列化运行时
curl -L -o kotlin.zip https://github.com/JetBrains/kotlin/releases/download/v2.4.0/kotlin-compiler-2.4.0.zip
unzip -q kotlin.zip
curl -L -o kotlinx-serialization-json.jar https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.6.0/kotlinx-serialization-json-jvm-1.6.0.jar
curl -L -o kotlinx-serialization-core.jar https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.6.0/kotlinx-serialization-core-jvm-1.6.0.jar

# 2) 编译（12 个 Kotlin 无关 Android 的文件 + 自检）
kotlinc/bin/kotlinc -nowarn -cp kotlinx-serialization-json.jar:kotlinx-serialization-core.jar -d /tmp/mcpout \
  core/app/src/main/java/com/tom/rv2ide/mcp/protocol/*.kt \
  core/app/src/main/java/com/tom/rv2ide/mcp/server/*.kt \
  core/app/src/main/java/com/tom/rv2ide/mcp/security/*.kt \
  core/app/src/main/java/com/tom/rv2ide/mcp/tools/{McpTool,Schema,TextEdit,FileTools}.kt \
  docs/mcp/devcheck/JvmSelfCheck.kt

# 3) 运行
kotlinc/bin/kotlin -cp /tmp/mcpout:kotlinx-serialization-json.jar:kotlinx-serialization-core.jar \
  com.tom.rv2ide.mcp.devcheck.JvmSelfCheckKt
```

本机最后一次运行结果：`44 passed, 0 failed`。

---

## 11. 回滚

代码改动前的完整备份（80 MB，9990 项）在工作目录上一级：

```bash
tar xzf android-code-studio-backup-20261123-1509.tar.gz   # 覆盖还原整个 android-code-studio/
```

只撤 MCP 功能：`rm -rf core/app/src/main/java/com/tom/rv2ide/mcp docs/mcp` + `git checkout --` 上述 7 个文件。
