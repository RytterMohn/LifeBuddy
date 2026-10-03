<div align="center">
  <img src="design/lifebuddy/lifebuddy.svg" width="104" alt="LifeBuddy 小布" />
  <h1>LifeBuddy</h1>
  <p>在手机上运行的个人 AI 助手。和你聊天，也帮你操作应用。</p>
  <p><a href="README.en.md">English</a> · 中文</p>
  <p><a href="https://github.com/RytterMohn/LifeBuddy/actions/workflows/android.yml"><img src="https://github.com/RytterMohn/LifeBuddy/actions/workflows/android.yml/badge.svg" alt="Android CI" /></a> <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84" alt="Android 8.0+" /> <img src="https://img.shields.io/badge/status-alpha-orange" alt="alpha" /></p>
</div>

LifeBuddy 把 Agent 的执行循环、工具、记忆和历史放在 Android 本机运行，由你配置的云端模型负责对话与规划。给它一句目标，它通过无障碍读取当前页面、选择工具、执行下一步并核对结果。

**当前版本：0.2.0-alpha18，个人测试阶段。** 支持中文和英文，默认无需下载本地大模型。它仍在开发中，并不保证所有 App 或每个任务都能成功。

## 能做什么

| 能力 | 当前实现 |
| --- | --- |
| 对话 | 流式回复、Markdown、多轮上下文、计算器和时间工具 |
| 操作手机 | 打开应用、查找控件、点击、输入、搜索、滚动、返回；可尝试跨 App 任务 |
| 系统工具 | 闹钟、计时器、常见设置入口，以及页面支持的开关与滑块 |
| 历史与记忆 | 本机会话历史、搜索和续聊；可编辑的长期记忆与习惯记录 |
| 从任务中学习 | 从有证据的成功流程生成试用技能，按需复用并管理失效版本 |
| 外部扩展 | 导入 `SKILL.md` / ZIP 技能包，连接 HTTPS MCP 工具服务 |
| 双语界面 | 跟随系统、中文、English；切换语言保留当前会话 |

只有两个模式：**聊天**用来问答、写作和读取技能资料；**操作**让模型根据目标选择应用及工具。无需选择“QQ 模式”或“外卖模式”。

## 开始使用

1. 从 [Releases](https://github.com/RytterMohn/LifeBuddy/releases) 下载测试 APK，或按下方步骤自行构建。
2. 打开侧边栏 → 设置，填写支持 Chat Completions / function tools 的 HTTPS API 地址、模型名称和 Key。
3. 先在聊天模式测试对话。要操作手机，再在设置中开启 LifeBuddy 无障碍服务。
4. 切换操作模式，先尝试短任务，例如：“打开备忘录，新建一条写着 LifeBuddy 测试的笔记并保存。”

操作模式按你的指令直接执行，发送消息也不再额外弹出确认。可随时暂停或停止，并在设置中限制可操作的应用范围。停止不会撤销已经发生的外部操作。

详细说明：[安装与使用](docs/getting-started.md) · [技能与 MCP](docs/extensions.md) · [数据与执行机制](docs/architecture.md)

## 技能和工具不会全部塞进上下文

LifeBuddy 先检索摘要，再读取相关技能或某一个工具的参数定义，最后执行调用。外部服务无论有多少工具，模型侧都使用固定的搜索、读取和调用入口。

试用 [每日计划技能包](examples/extensions/daily-brief.zip)，或从 [MCP JSON 模板](examples/extensions/mcp.example.json)开始配置自己的服务。当前支持 HTTPS Streamable HTTP；桌面 `stdio` / `npx`、本机脚本执行和 OAuth 登录尚不支持。

## 数据放在哪里

- 会话、记忆、技能和执行记录保存在手机私有目录；API Key 与 MCP 认证头通过 Android Keystore 加密保存。
- 云端对话会把相关上下文发送给你配置的模型；操作模式还会发送任务和可访问的页面文字。
- MCP 调用参数会发送给对应服务。LifeBuddy 不提供内置模型账户，也不把自己的后台作为中转。
- 不被动录制日常操作，不用你的手机记录训练模型。卸载或清除应用数据会删除本地记录。

## 从源码构建

需要 **JDK 17、Android SDK Platform 34、Build Tools 34.0.0**。仓库包含 Gradle 8.9 Wrapper；设置 `ANDROID_HOME`，或在本机 `local.properties` 配置 `sdk.dir`。

```sh
git clone https://github.com/RytterMohn/LifeBuddy.git
cd LifeBuddy
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。JDK 21 用户可加 `-PbuildJdk=21`；Windows 使用 `gradlew.bat`。普通构建不需要模型 Key、NDK 或 GGUF。

更多：[开发说明](docs/development.md) · [测试方法与覆盖范围](docs/testing.md) · [贡献指南](CONTRIBUTING.md)

## 当前边界

- 手机控制依赖可访问的界面文字和控件；无标签、自绘或纯视觉页面可能无法操作。
- 暂不支持验证码处理、支付、外卖最终下单、持续后台无人值守或完整桌面 Agent 插件环境。
- 页面变化不等于业务成功。消息发送后的核对不能替代送达回执；外部调用结果不明时会暂停，不自动重试。
- 真实 App 的布局、版本和系统权限会影响结果。模拟测试通过不代表已适配所有微信、QQ、美团或网盘版本。
- 本地 Gemma / llama.cpp 路径属于实验代码，默认 APK 不包含模型或原生推理库；手机操作目前使用云端规划。

## 项目结构

```text
app/       Android 界面、无障碍执行、存储、HTTP 与运行控制
core/      Agent 循环、页面协议、上下文、记忆、技能、MCP 与测试
fixture/   不联网的独立测试应用，不包含在主 APK 中
cli/       本地文本 Agent 的桌面调试入口
native/    可选 llama.cpp JNI
examples/  可导入的技能和 MCP 配置示例
docs/      使用、架构、开发与测试说明
```

项目借鉴了 Hermes、OpenCode、OpenClaw 等 Agent 的上下文、记忆和工具组织思路，针对 Android 重新实现；不属于这些项目，也不保证插件或运行时完全兼容。依赖和可选模型说明见 [第三方组件](THIRD_PARTY_NOTICES.md)。

## 许可

[MIT](LICENSE)。第三方依赖和可选模型保留各自许可。
