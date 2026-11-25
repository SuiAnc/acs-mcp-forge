# MCP 服务器使用说明

Android Code Studio 内置一个 MCP（Model Context Protocol）服务器，让 Claude Desktop、Cline、
Cherry Studio 等 AI 客户端直接读项目、改文件、建项目、跑 Gradle 构建。

- 传输：MCP Streamable HTTP（`POST /mcp`），单一 JSON 响应
- 默认地址：`http://127.0.0.1:6688/mcp`
- 健康检查：`GET http://127.0.0.1:6688/health`
- 协议版本：`2024-11-05`（同时接受 `2025-03-26`、`2025-06-18`，协商时回显客户端版本）

---

## 1. 开启

1. 打开 App → 主页面 → **设置** → **开发者选项** → **MCP 服务器**
2. 打开 **启用 MCP 服务器**（默认关闭）
3. 按需调整：

| 设置项 | 默认 | 说明 |
|---|---|---|
| 监听地址 | `127.0.0.1` | 仅在允许远程访问时生效 |
| 端口 | `6688` | 允许 1024–65535；被占用时状态项会显示原因 |
| 访问令牌 | 空 | 留空不校验；可点「生成令牌」随机生成 |
| 允许远程访问 | 关 | 关闭时强制绑定回环地址；开启后可监听 `0.0.0.0`，请配合令牌使用 |
| 允许写操作 | 关 | 打开后才有 `file_create`/`file_write`/`file_edit`/`file_rename`/`file_move`/`file_delete`/`project_create`/`project_open` |
| 允许构建 | 关 | 打开后才有 `build_*` 工具 |
| 允许网络操作 | 关 | 打开后才有 `git_fetch` / `git_pull` / `git_push` |
| 服务器状态 | — | 点一下刷新并显示当前监听地址、鉴权、请求数 |
| 查看审计日志 | — | 最近 100 条工具调用，按「时间+工具 / 类型·耗时·结果」分行显示，可复制（TSV）与清空 |

> 开关一变，服务器立即按新配置重启。全部关闭后端口会被释放。

4. 自检：

```bash
curl http://127.0.0.1:6688/health
# {"status":"ok","name":"android-code-studio","version":"...","transport":"streamable-http","tools":22,...}

./docs/mcp/selftest.sh http://127.0.0.1:6688            # 无令牌
./docs/mcp/selftest.sh http://127.0.0.1:6688 <令牌>      # 已设置令牌
```

> 手机上跑 curl 可以用 Termux；也可以直接在 App 的终端里执行。

---

## 2. 客户端配置

### Claude Desktop（`claude_desktop_config.json`）

```json
{
  "mcpServers": {
    "android-code-studio": {
      "type": "http",
      "url": "http://127.0.0.1:6688/mcp",
      "headers": { "Authorization": "Bearer <可选令牌>" }
    }
  }
}
```

### Cline / Roo Code（`cline_mcp_settings.json`）

```json
{
  "mcpServers": {
    "android-code-studio": {
      "type": "streamableHttp",
      "url": "http://127.0.0.1:6688/mcp",
      "headers": { "Authorization": "Bearer <可选令牌>" },
      "disabled": false,
      "autoApprove": ["file_list", "file_read", "file_search", "workspace_list_projects"]
    }
  }
}
```

### RikkaHub 等使用 mcp-kotlin SDK 的客户端

- 类型选 **Streamable HTTP**
- URL 必须是完整端点：`http://127.0.0.1:6688/mcp`（不要只填 `127.0.0.1:6688`，也不要漏掉 `/mcp`）
- 设了访问令牌时，加自定义 Header：`Authorization: Bearer <令牌>`
- 该 SDK 只接受 `text/event-stream` 时，服务器会自动把单条响应用 `event: message` + `data: {...}` 的 SSE 格式返回

### Cherry Studio / 其他客户端

选择「HTTP」或「Streamable HTTP」传输，URL 填 `http://127.0.0.1:6688/mcp`，需要时在自定义
Header 里加 `Authorization: Bearer <令牌>`。

> **关于「127.0.0.1」**：如果客户端跑在 PC 上、IDE 跑在手机上，`127.0.0.1` 在两端不是同一台设备。
> 二选一：
> - 打开「允许远程访问」，把监听地址设为 `0.0.0.0`，客户端填手机局域网 IP（**务必同时设置令牌**）；
> - 或者用 `adb forward tcp:6688 tcp:6688` 把手机的 6688 映射到 PC 的 6688，客户端继续用 `127.0.0.1`。

---

## 3. 工具清单

### 只读（服务器开启即可用）

| 工具 | 参数 | 说明 |
|---|---|---|
| `workspace_list_projects` | — | 当前项目、已登记项目、最近项目 |
| `workspace_current_project` | — | 当前打开的项目 |
| `project_templates` | — | 可用模板与参数（`project_create` 的 `template` 取值） |
| `project_info` | `projectPath?` | 模块、包名、compileSdk/minSdk/targetSdk、wrapper |
| `gradle_tasks` | `projectPath?` | 构建服务状态与常用任务 |
| `file_list` | `path?`, `recursive?`, `maxDepth?`, `includeIgnored?` | 目录列表 |
| `file_tree` | `path?`, `maxDepth?`, `includeIgnored?` | 嵌套树 |
| `file_read` | `path`, `startLine?`, `endLine?` | 读文件；**优先返回编辑器未保存内容** |
| `file_search` | `query`, `path?`, `glob?`, `regex?`, `caseSensitive?`, `maxResults?` | 内容检索 |

### 写（需「允许写操作」）

| 工具 | 参数 | 说明 |
|---|---|---|
| `file_create` | `path`, `content?`, `overwrite?` | 新建文件（自动建父目录） |
| `file_create_dir` | `path` | 新建目录 |
| `file_write` | `path`, `content`, `createBackup?` | 覆盖写（旧内容备份） |
| `file_edit` | `path`, `oldText`, `newText`, `replaceAll?` | 精确替换；多命中会报错，要求更具体的片段 |
| `file_rename` | `path`, `newName` | 同目录重命名 |
| `file_move` | `source`, `destination`, `overwrite?` | 移动；目标是目录则移入其中 |
| `file_delete` | `path`, `recursive?`, `purge?` | 默认进回收站 `filesDir/mcp/trash/<时间戳>/` |
| `project_create` | `template`, `projectName`, `packageName?`, `destination?`, `language?`, `minSdk?`, `useKts?`, `openAfterCreate?` | 从模板生成项目 |
| `project_open` | `projectPath` | 在 IDE 中打开 |

### 构建（需「允许构建」）

| 工具 | 参数 | 说明 |
|---|---|---|
| `build_start` | `projectPath?`, `task?`, `tasks?`, `variant?` | 返回 `buildId`；必要时自动绑定构建服务并初始化项目 |
| `build_status` | `buildId?` | 状态、任务、起止时间、耗时 |
| `build_logs` | `buildId?`, `tail?` | 构建输出尾部（最多 2000 行缓冲） |
| `build_cancel` | `buildId?` | 取消当前构建 |
| `build_apk` | `projectPath?`, `variant?` | 定位最近产出的 APK |

### Git（`git_*` 只读部分随时可用；写操作需「允许写操作」；网络操作需「允许网络操作」）

| 工具 | 参数 | 说明 |
|---|---|---|
| `git_status` | `projectPath?` | 当前分支、工作区是否干净、变更文件（含是否已暂存）、远端列表 |
| `git_diff` | `projectPath?`, `path?` | 单文件 diff；省略 `path` 时汇总全部变更（最多 10 个文件、8 KB） |
| `git_log` | `projectPath?`, `count?` | 最近提交（hash / 作者 / 时间 / 标题） |
| `git_branches` | `projectPath?` | 本地分支与当前分支 |
| `git_stage` | `projectPath?`, `paths?`, `all?`, `unstage?` | 暂存 / 取消暂存 |
| `git_commit` | `projectPath?`, `message`, `author?`, `email?` | 提交已暂存内容 |
| `git_checkout` | `projectPath?`, `branch`, `force?` | 切分支；工作区有改动且未 `force` 时拒绝 |
| `git_discard` | `projectPath?`, `path` | 丢弃单个文件的本地改动（**不可恢复**） |
| `git_fetch` | `projectPath?`, `remote?` | 需要「允许网络操作」与已配置的 remote |
| `git_pull` | `projectPath?`, `remote?`, `branch?` | 同上 |
| `git_push` | `projectPath?`, `remote?`, `branch?` | 同上 |

> 凭据：MCP **不接受**把 token 作为工具参数。`fetch/pull/push` 使用 remote URL 里内嵌的凭据（或匿名），
> 失败时会把 JGit 的错误原样返回（例如 `Authentication is required`），按提示配置远端即可。

### 返回格式

```json
{ "content": [ { "type": "text", "text": "{\"path\":\"...\",\"count\":3}" } ], "isError": false }
```

业务失败也是 200，但 `isError: true`，`text` 里是可读原因；协议级错误用 JSON-RPC 错误码
（`-32700` 解析失败、`-32600` 请求非法、`-32601` 方法不存在、`-32602` 参数非法、`-32603` 内部错误）。

---

## 4. 典型调用

```bash
# 1) 初始化
curl -s -X POST http://127.0.0.1:6688/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'

# 2) 列工具
curl -s -X POST http://127.0.0.1:6688/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# 3) 读文件
curl -s -X POST http://127.0.0.1:6688/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"file_read","arguments":{"path":"README.md"}}}'

# 4) 精确改一行
curl -s -X POST http://127.0.0.1:6688/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"file_edit","arguments":{"path":"app/src/main/AndroidManifest.xml","oldText":"android:label=\"Old\"","newText":"android:label=\"New\""}}}'

# 5) 构建并取 APK
curl -s -X POST http://127.0.0.1:6688/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"build_start","arguments":{"task":"assembleDebug"}}}'
curl -s -X POST http://127.0.0.1:6688/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"build_apk","arguments":{"variant":"debug"}}}'
```

带令牌时每个请求加：`-H 'Authorization: Bearer <令牌>'`。

---

## 5. 常见问题

**`Unknown endpoint: /` 或 `{"error":"not_found"}`**
客户端把请求发到了根路径 `/`，而 MCP 端点是 `/mcp`。旧版把 URL 配成 `http://127.0.0.1:6688` 就会这样。
两种解法：
1. 客户端 URL 改成 **`http://127.0.0.1:6688/mcp`**（推荐，立即生效）；
2. 重新编译本仓库后，`/` 也会被当作 MCP 端点（本次已加入兼容），随意填 `http://127.0.0.1:6688` 也能用。

**`Fail to parse url: 127.0.0.1:6688` / `Illegal character in scheme`**
URL 少了协议头。必须写全：`http://127.0.0.1:6688/mcp`（`http://` 不能省）。

**连不上 / Connection refused**
服务器没开，或端口不是 6688。看「服务器状态」那一行；确认客户端 URL 是 `http://<设备>:<端口>/mcp`。

**返回 401**
设置里填了访问令牌但请求没带，或令牌不一致。去「MCP 服务器」页复制令牌，或点「生成令牌」重设。

**返回 405**
`/mcp` 只接受 `POST`（健康检查用 `GET /health`）。部分客户端会先尝试 `GET /mcp` 建立 SSE 流，
本实现不支持该模式，请在客户端里把它配置成 Streamable HTTP / HTTP JSON。

**工具列表里没有 `file_write` / `build_start`**
「允许写操作」「允许构建」「允许网络操作」默认关闭，打开后立即生效（服务器会重启）。

**新建项目后构建失败：`Could not resolve ... repo.maven.apache.org:443 failed to respond`**
不是项目代码问题，是 Maven Central 的规范主机 `repo.maven.apache.org` 在当前网络不可达（`mavenCentral()`
和 `gradlePluginPortal()` 都走它）。`repo1.maven.org`（Maven Central 官方别名）与
`maven-central.storage-download.googleapis.com`（Google 的 Central 镜像）通常可达。
在项目的 `settings.gradle(.kts)` 两个 `repositories {}` 里各加两行，放在 `mavenCentral()` 之前：

```groovy
maven { url 'https://repo1.maven.org/maven2' }
maven { url 'https://maven-central.storage-download.googleapis.com/maven2' }
```

Kotlin DSL 写法：`maven { url = uri("https://repo1.maven.org/maven2") }`。
本仓库已修正两套项目模板，重新编译 App 后新建的项目会自动带上这两行。

**`Path is outside of the allowed directories`**
路径不在沙箱内。沙箱只包含：当前打开的项目、模板中心登记过的项目、`PROJECTS_DIR`、
`AT_ACSHOME_PROJECTS` 和最近打开的项目。先 `project_open` 或把项目放进这些目录。

**`Project initialization failed` / 构建很慢**
首次构建需要启动 Gradle 工具链并同步项目，可能要几分钟；期间 `build_status` 会显示 `running`，
`build_logs` 能看到进度。若工具链启动失败，先在 IDE 里打开一次项目再重试。

**`A build is already running`**
同一时刻只允许一个构建；先 `build_cancel` 或等结束。

**`editorStarted` 为 false**
Android 限制了后台启动界面：项目已经登记，但需要你把 App 切到前台才会显示编辑器。

**MCP 客户端看不到文件改动**
`file_write` / `file_edit` 会请求刷新文件树；如果是已经在编辑器里打开的文件，重新打开标签页
（或 File → Reload）即可看到磁盘上的新内容。

**审计日志在哪**
设置页「查看审计日志」：每条记录分两行显示（时间 + 工具 / 类型 · 耗时 · 结果），条之间有分隔线，
底部「复制」可导出 TSV（时间、类型、工具、耗时、结果）。文件是 `filesDir/mcp/audit.log`
（1 MB 轮转，`audit.log.1` 为上一份），旧版本的纯文本日志仍能读取。
