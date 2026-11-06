# Kujira — 产品规格（v1）

> Android 原生 AI 助手客户端。布局学 ChatGPT，气质学 Claude。
> 通过 MCP 支持自定义插件，并原生适配 SentinelX。

## 一句话

一个装在 Android 手机上的「大肥鱼」：多模型对话、会话历史、Markdown 流式渲染、
可插拔的 MCP 工具、以及开箱可用的 SentinelX 服务器控制。

## 设计基调

- **深色为默认**：墨黑底（#0D0D0F）+ 陶土橙强调色（#E07A5F）。
- **浅色为暖纸**：#F7F4EF 米白 + 深棕文字，Claude 式的暖。
- 标题使用衬线体（Serif），正文用系统无衬线体；正文行高 1.55。
- 界面对话优先：尽量少的边框与阴影，用层次（surfaceContainer*）分层。
- 动效克制：消息出现 180ms 淡入上移，流式光标呼吸，工具卡片展开用 spring。
- 触觉反馈：发送、工具确认、停止生成时轻微 haptic。

## 功能清单

### 1. 聊天（P0）
- 流式回复（SSE），可随时停止（停止后保留已生成内容）。
- Markdown 渲染：段落、标题、粗体/斜体/删除线、行内代码、代码块（横向滚动 + 语言标签 + 复制）、
  有序/无序列表、引用、分割线、表格、链接（点击外部浏览器打开）。
- 用户消息气泡靠右（surfaceContainerHigh），AI 消息通栏无气泡（ChatGPT 风格）。
- 消息操作：长按 → 复制 / 重新生成 / 编辑（用户消息） / 删除。
- 代码块一键复制 + 「复制全文」。
- 空状态：品牌字标 + 今日建议提示词（可点击填入）。
- 输入区：多行自适应（最高 6 行）、发送/停止按钮、模型选择 chip、插件状态指示。
- 自动滚动：贴底时随流式内容滚动；用户上滑则暂停并显示「回到底部」。
- 错误态：网络错误、401、429、超时，显示可重试的错误条。
- **上下文预算**：输入区显示当前估算用量 / 模型窗口；≥75% 警告，≥85% 发送前自动压缩。
  压缩是非破坏性的：原始消息保留，后续请求只发送「摘要 + 最近消息」；可手动压缩 / 清除摘要。
- **每条回复用量**：输入 / 输出 / 缓存 / 推理 tokens、Provider 实际费用与 billing source。
- **用量面板**：按本月 / 全部 / Provider / 模型汇总 tokens、缓存命中与费用；
  无实际费用时按同步到的模型价格估算并标注 ≈。

### 2. 会话（P0）
- 抽屉式会话列表，按 今天 / 昨天 / 近 7 天 / 更早 分组。
- 新建、重命名、置顶、删除（滑动删除 + 撤销 Snackbar）、搜索。
- 会话标题自动取首条用户消息前 30 字。
- 持久化到本地 SQLite，冷启动恢复上次会话。

### 3. 模型与 Provider（P0）
- 内置 provider：DeepSeek、OpenAI、Anthropic、OpenRouter、**OpenCode Zen**、
  **OpenCode Go**、自定义（OpenAI 兼容）。
- Provider 预设目录另外提供 Gemini、xAI、Groq、Mistral、Moonshot / Kimi、智谱 GLM；
  添加时预填 Base URL、文档、取 Key 地址和精选模型。
- 每个 provider 可配置：API Key（加密存储）、Base URL、模型列表（多行文本，
  `id|显示名` 格式）、可选模型目录 URL、启用开关。
- **模型同步**：从服务端 `GET /models` 拉取 ID、显示名、上下文 / 输出上限、
  工具 / 视觉 / 思考 / temperature 能力；合并时保留用户改过的显示名和手动模型。
- Provider 管理页：列表、Key 状态、模型数、启用开关、添加预设、删除与连接测试。
- OpenCode 两个网关按官方文档发送稳定的 `x-opencode-session`（会话 ID），
  Go 的路由与 prompt cache 依赖该头。
- 会话级模型绑定：切换模型后新消息用新模型；模型多时用可搜索的 Bottom Sheet 选择。
- 全局生成参数：temperature、max_output_tokens、top_p、系统提示词。
- API Key 用 Android Keystore（AES-GCM）加密后存 SharedPreferences，不落盘明文。

### 4. 插件系统（P0，MCP）
- 插件 = 一个 MCP server 连接。传输：Streamable HTTP（POST JSON-RPC），兼容 SSE 响应。
- 插件字段：名称、图标 emoji、URL、认证方式（OAuth 2.1 PKCE / Bearer / 无）、自定义 Header、启用开关。
- 添加方式：表单手动添加 / 粘贴 mcpServers JSON（兼容 Claude Desktop 格式）导入。
- 插件详情：连接状态、协议版本、serverInfo、工具列表（名称 + 描述 + 参数摘要）、
  「测试连接」「刷新工具」。
- **工具开关**：每个工具可单独启用/禁用；默认只启用少量安全工具（因为工具太多会吃 context）。
- 工具执行策略：`auto`（直接执行）/ `ask`（每次确认）/ `deny`。
  危险工具（名字含 exec/delete/restart/stop/edit/write/upload/destroy）默认 `ask`。
- 聊天中的工具调用卡片：工具名 + 服务器徽章 → 参数（折叠 JSON）→ 结果（Markdown/JSON，折叠）→ 状态。
- 工具调用循环上限（默认 12 轮），到达上限提示。
- **工具审计页**：记录每次 MCP 调用的参数、结果预览、耗时、成功/失败；
  支持只看失败、展开详情、一键清空。
- 会话级插件总开关：可临时关闭全部插件。

### 5. SentinelX 适配（P0，重点）
- **内置**预置插件「SentinelX」，端点 `https://mcp.sentinelx.app/mcp/mcp`，不可删除，可禁用。
- **OAuth 登录**：Authorization Code + PKCE(S256)，自定义 Tabs 打开 Keycloak，
  本地 loopback HTTP server 监听 `127.0.0.1:20573` 接收回调（与桌面 bridge 完全一致的
  redirect_uri `http://localhost:20573/oauth/callback`）。
  - client_id `416add6f-801f-4359-9bab-96b774e70ddf`
  - issuer `https://keycloak.pensa.ar/realms/mcp`
  - scope：`openid profile email offline_access sentinelx:exec sentinelx:state sentinelx:capabilities
    sentinelx:service sentinelx:restart sentinelx:edit sentinelx:script sentinelx:upload sentinelx:ping`
  - token 存 Keystore 加密存储；access_token 过期前 60s 自动 refresh；refresh 失败进入「需重新授权」。
- **主机适配**：`sentinel_list_hosts` 拉取主机列表，设置里选「当前主机」；
  之后所有工具调用自动注入该 `host_id`（工具参数本来就有 host_id 字段）。
- **工具分组**：95 个工具按前缀自动分组（sentinel_*、cloudflare_*、telegram_*、resend_*、compute_*），
  每组可一键全开/全关，默认只开 `sentinel_state`、`sentinel_capabilities`、`sentinel_list_hosts`、
  `sentinel_exec`、`sentinel_service_status` 等常用项。
- **能力总览页**：连接后展示 serverInfo、主机、ops 列表、允许命令、服务定义（来自
  `sentinel_capabilities detail=summary`），用于让用户知道自己能干什么。
- **快捷指令**：预置 6 个 one-tap 提示词卡片（查看服务器状态、磁盘占用、重启 nginx、
  列目录、拉日志、Cloudflare 记录）。
- **审计日志**：本地 SQLite 记录每次工具调用（时间、工具、参数摘要、耗时、结果状态），
  设置里可查看、清空。
- 认证失败 / upgrade_required / path_not_allowed 等错误给出人话解释。

### 6. 设置（P1）
- 外观：主题（跟随系统/深色/浅色）、字号（小/中/大/特大）、代码字体等宽开关。
- 数据：导出全部数据为 JSON、导入、清空会话、清空全部。
- 插件：插件管理入口、当前主机。
- 关于：版本、开源许可、致谢。

## 非目标（v1 不做）
- 语音输入/语音播报
- 图像生成
- 本地模型
- 多设备云同步
- iOS

## 验收标准
1. `./gradle.sh assembleDebug` 成功产出 APK。
2. 冷启动进入会话列表空状态，新建会话 → 发消息 → 能看到流式回复（模拟成功后）。
3. 断网/错误 key 有明确错误提示。
4. 能添加一个自定义 MCP 插件并拉到工具列表（用公开 MCP 服务或本地 fake 测试）。
5. SentinelX：点登录 → 浏览器授权 → 回到 app 显示已连接 → 拉到 95 个工具 →
   选择主机 → 让模型调用一个只读工具（sentinel_ping）能看到结果卡片。
6. 单元测试覆盖：SSE 解析、MCP JSON-RPC 编解码、Markdown 块解析、工具调用循环（fake client）。
