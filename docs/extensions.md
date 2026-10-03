# 技能与 MCP 扩展

版本：0.2.0-alpha18 / versionCode 19。仍然只有聊天、操作两个模式。扩展接入现有对话、任务历史和执行循环，不创建独立的“插件模式”。

## 使用入口

侧边栏 → 设置：

- **界面语言**：跟随系统、中文、English。非中文系统默认英文。切换立即更新界面，不重建 Activity、不创建或切换会话。首页、设置、历史管理、记忆与学习面板、工具名称和主要运行提示均支持两种语言。
- **扩展中心 → 技能**：选择 `SKILL.md` 或技能 ZIP，预览名称和说明后导入；可查看、启停、删除。同名技能更新内容并保留原启用状态。
- **扩展中心 → MCP**：填写服务名称、HTTPS 地址及可选 Bearer Token，或导入 `mcpServers` JSON。连接阶段只初始化并读取工具目录；成功后操作模式可以使用这些工具。可以搜索目录、刷新、停用和删除服务。

新回复和新任务的步骤说明优先使用用户明确要求的语言，其次跟随最新输入，模糊输入以界面语言作为默认。用户原文、已有历史、应用名称、页面证据、导入说明和服务返回保留原文，不批量翻译历史。

## 技能包

可直接导入示例：[daily-brief.zip](../examples/extensions/daily-brief.zip)。源文件在 [daily-brief](../examples/extensions/daily-brief/SKILL.md)。导入后可在聊天模式输入：

> 使用 daily-brief 技能，帮我安排一个包含专注工作和散步的简短计划。

或：

> Use the daily-brief skill to make a short plan for focused work and a walk. Answer in English.

最小格式：

```markdown
---
name: daily-brief
description: Plan a short day / 安排简短的日程
---
Read references/format.md and use its format for the plan.
```

ZIP 可包含多个技能目录及其相对参考文件。支持 `name`、`description`、`compatibility` 的常见 YAML 标量、引号和多行写法；其他元数据不作为授权或工具定义执行。名称使用小写字母、数字和连字符。支持读取 Markdown、TXT、JSON、YAML、CSV 文本资料；包含脚本的包会显示说明，**Python、Node、Shell、二进制资产不会在手机运行**。这不是完整桌面 Agent Skills 运行环境；依赖桌面文件、浏览器、命令行或未连接工具的技能只能部分使用。

导入在内存中校验，绝不把 ZIP 路径直接解压到文件系统。拒绝路径穿越、重名条目、非法 UTF-8 和超限内容。当前限制：单次导入 2 MiB、解压后总量 2 MiB、每文件 128 KiB、128 个 ZIP 条目、最多 20 份技能、每技能 32 个参考文件；本机最多保存 100 份导入技能。自动学习的应用技能仍在“学习与经验”管理，两者不互相覆盖。

## MCP 工具服务

配置模板：[mcp.example.json](../examples/extensions/mcp.example.json)。模板地址和令牌须替换为自己的真实服务；LifeBuddy 没有内置公共服务或凭据。

```json
{
  "mcpServers": {
    "My service": {
      "type": "streamable-http",
      "url": "https://your-server.example/mcp",
      "headers": { "Authorization": "Bearer REPLACE_WITH_YOUR_TOKEN" }
    }
  }
}
```

本版支持 **HTTPS Streamable HTTP** 的 JSON 及 SSE 响应，初始化协商、会话头、工具目录分页和 `tools/call`。请求协议版本为 `2025-11-25`，可协商 `2025-06-18`、`2025-03-26`。SSE 收到对应请求的完整结果就返回，不等待服务关闭长连接。

暂不支持 `stdio` / `npx` / 本地脚本进程、OAuth 登录、旧版独立 SSE 地址、服务端 sampling / roots / elicitation，以及图片或音频工具内容。桌面 MCP 配置里的 `command`、`args`、`env` 会被拒绝，不能直接导入后在 Android 运行。可先在自己的服务器运行工具，再提供兼容的 HTTPS MCP 地址。

最多 20 个服务，每服务 1000 个工具、32 页目录；工具参数定义最多 16000 字符。目录有本机搜索和分批展开，避免一次渲染上千行。单次响应最大 1 MiB，调用结果中的文本和结构化数据分别保留最多 4000 / 3000 字符并标记截断；连接、读取、整个调用超时分别为 12 / 45 / 60 秒。复杂嵌套 JSON Schema 的最终校验由服务端执行，本机先校验必填项和常见顶层类型。

## 上下文与执行机制

模型始终只增加三个固定入口：

1. `extension_search`：检索当前启用的技能和工具，每次最多 5 项摘要。
2. `extension_read`：读取选中的技能正文/参考文件，或一个工具的实际参数定义；技能文本按 6000 字符分页。
3. `extension_call`：操作模式下调用已读取的工具，使用确切工具 ID 和参数 JSON。聊天模式只开放前两个入口，且看不到 MCP 工具目录。

不把整套技能或所有工具 Schema 注入系统提示词。导入内容与服务返回作为参考数据，不能覆盖用户目标或扩大授权。调用前再次核对工具是否启用、定义是否变化、参数是否符合基本约束；定义变化后必须重新读取。

扩展搜索、读取、参数、结果和用时写入原任务历史。外部调用先保存执行意图，再发出请求；相同任务内相同工具和参数不会重复调用。连接断开、任务取消或结果丢失时显示“外部调用结果待确认”，不声称执行失败，不自动重试；HTTP 层也关闭重试和跳转。纯外部工具任务可以直接根据返回值回答；混合手机任务仍需页面证据核对。

认证头用 Android Keystore / AES-GCM 加密，存放在本机私有配置中，与工具目录分开。模型请求不包含认证头；工具返回里匹配到的凭据会被替换，目录若回显凭据会拒绝保存。调用参数和必要的技能文字会进入配置的模型上下文；调用参数也会发给选定的 MCP 服务。

## 验证与参考

离线、真实模型与真机验证覆盖范围见 [测试说明](testing.md)。提供模型及服务凭据前，普通构建与离线测试不会调用你的业务服务。

实现参考：[Agent Skills](https://agentskills.io/specification)、[MCP 传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[MCP 生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)、[MCP 工具](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)。
