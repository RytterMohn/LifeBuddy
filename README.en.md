<div align="center">
  <img src="design/lifebuddy/lifebuddy.svg" width="104" alt="LifeBuddy puppy" />
  <h1>LifeBuddy</h1>
  <p>A personal AI agent that runs on your Android phone.</p>
  <p>English · <a href="README.md">中文</a></p>
  <p><a href="https://github.com/RytterMohn/LifeBuddy/actions/workflows/android.yml"><img src="https://github.com/RytterMohn/LifeBuddy/actions/workflows/android.yml/badge.svg" alt="Android CI" /></a> <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84" alt="Android 8.0+" /> <img src="https://img.shields.io/badge/status-alpha-orange" alt="alpha" /></p>
</div>

LifeBuddy keeps the agent loop, tools, memory and history on Android. Your configured cloud model handles conversation and planning. Given a goal, the agent reads the accessible screen, chooses a tool, performs one step and checks the result.

**Current version: 0.2.0-alpha18, an early personal-testing build.** Chinese and English are supported. No local model download is required for the default experience. Support varies by device, app version and task.

## Features

- Streaming chat, Markdown, multi-turn context, calculator and time tools.
- Accessibility-based app navigation, search, tapping, typing, scrolling and cross-app tasks.
- System alarms, timers, settings shortcuts, and supported switches and sliders.
- Persistent conversation history, editable memory and confirmed preferences.
- Reusable trial skills learned from task traces with verifiable completion evidence.
- Imported `SKILL.md` / ZIP skills and remote HTTPS MCP tools, loaded on demand.
- System / Chinese / English UI selection without replacing the current conversation.

There are two modes: **Chat** for conversation and skill reading; **Operate** for model-selected apps and tools. Individual apps do not need separate modes.

## Try it

1. Download a test APK from [Releases](https://github.com/RytterMohn/LifeBuddy/releases), or build it below.
2. In sidebar → Settings, enter an HTTPS model endpoint, model name and API key. The provider must support Chat Completions, function tools and streaming chat.
3. Try Chat first. Enable the LifeBuddy accessibility service before using Operate.
4. Start with a small task, such as creating and verifying a test note.

Operate executes your requested actions directly, including sending messages, without an additional send-confirmation dialog. Pause or stop at any time; completed external actions are not undone. You can limit the allowed apps in Settings.

## Extensions

Settings → Extensions accepts [skill ZIPs](examples/extensions/daily-brief.zip) and [MCP configurations](examples/extensions/mcp.example.json). The model searches short summaries, loads the selected instructions or schema, then calls a tool. It does not receive every installed tool definition in each prompt.

MCP currently supports HTTPS Streamable HTTP with JSON or SSE responses. Desktop `stdio` / `npx`, local scripts, legacy SSE endpoints and OAuth login are not supported yet. Chat can read skills; external MCP calls require Operate. See the [extension guide](docs/extensions.md).

## Data and execution

History, memory, skills and task traces stay in private app storage. Model keys and MCP credential headers are encrypted using Android Keystore. Relevant conversation context is sent to your configured model; operation requests also include accessible screen text. Tool arguments go to the selected MCP service. LifeBuddy has no built-in cloud account or relay backend.

The app does not passively record your everyday phone usage or train a model on your records. Clearing app data or uninstalling deletes local records. Interrupted external calls remain unconfirmed and are not automatically retried.

## Build

Install **JDK 17**, **Android SDK Platform 34**, and **Build Tools 34.0.0**. Set `ANDROID_HOME` or configure `sdk.dir` in a local `local.properties` file.

```sh
git clone https://github.com/RytterMohn/LifeBuddy.git
cd LifeBuddy
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Use `-PbuildJdk=21` with JDK 21, or `gradlew.bat` on Windows. Normal builds need no API credentials, NDK or model weights. GitHub Actions runs the offline checks and produces a debug artifact; CI debug certificates may differ between runs and from release APKs.

## Current limits

Phone control depends on accessible text and controls. Visual-only pages, missing labels, app updates and system restrictions can block a task. CAPTCHA solving, payments, final food-order submission and unattended background operation are not supported. Screen verification is not a delivery or business-level receipt. Synthetic tests do not establish compatibility with every real app.

Local Gemma / llama.cpp support is experimental. The default APK has no model weights or native inference library; phone tasks currently use a cloud planner.

## Source map

`app/` contains Android UI, accessibility, storage and networking. `core/` contains the agent loop, context, memory, skills, MCP and tests. `fixture/` is a separate offline test app. `cli/` and `native/` provide optional local-inference experiments.

Read the [development guide](docs/development.md), [test coverage](docs/testing.md), [contribution guide](CONTRIBUTING.md), and [third-party notices](THIRD_PARTY_NOTICES.md). Most detailed guides currently use Chinese with command examples.

LifeBuddy draws ideas from Hermes, OpenCode and OpenClaw, but is an independent Android implementation, not a distribution of or a fully compatible runtime for those projects.

## License

[MIT](LICENSE). Dependencies and optional models retain their own licenses.
