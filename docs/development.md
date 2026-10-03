# 开发说明

## 构建环境

- JDK 17；JDK 21 可传 `-PbuildJdk=21`，仍输出 JVM 17 字节码。
- Android SDK Platform 34、Build Tools 34.0.0。
- Gradle 8.9 Wrapper 已包含在仓库，分发包 SHA-256 已固定。
- 默认构建不需要 NDK、模型权重、API Key 或运行手机。

设置 `ANDROID_HOME`，或仅在本机创建 `local.properties`：

```properties
sdk.dir=/your/android/sdk
```

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

CI 还构建独立测试应用和 CLI：

```sh
./gradlew :fixture:assembleDebug :cli:installDist
```

`.github/workflows/android.yml` 在 push、PR 和手动触发时运行离线检查；构建与测试报告作为 artifacts 保存。API 集成测试不会默认运行。

## 真机开发

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

通过 App 设置配置模型并开启无障碍。不要通过脚本修改个人会话或直接测试真实收件人的发送任务。`fixture/` 是独立、无网络的测试信箱与系统页面，适合验证动作链。

测试 instrumentation 示例：

```sh
./gradlew -PtestRunner=dev.ondevice.gemma.app.ExtensionSmokeInstrumentation :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.ondevice.gemma.app.test/dev.ondevice.gemma.app.ExtensionSmokeInstrumentation
```

扩展测试使用隔离的文件和模拟 MCP 服务，不调用真实业务。它短暂切换界面语言并在结束后恢复。部分系统限制测试进程启动界面；看到 `ui_ready` 后，可在另一终端用 `adb shell am start -n dev.ondevice.gemma.app/.MainActivity` 将 App 带到前台。多台设备时为每条命令选择同一序列号。

## 品牌资源

`design/lifebuddy/lifebuddy.svg` 是 Logo 主文件。运行：

```sh
python3 scripts/generate_brand_assets.py
```

会更新 Android 矢量及透明/单色版本；脚本只需要 Python 标准库。

## 实验性本地模型

云端主流程不依赖本节。`native/` 包含 JNI 接口，`native/llama-revision.txt` 固定可选 llama.cpp revision；准备上游代码、NDK 和 CMake 后运行 `native/build_android.sh /path/to/llama.cpp`，再把自己获得的 GGUF 放到应用外部私有目录的 `models/` 子目录。

Android 原生推理产物尚未充分真机验证。模型权重、原生库和 third_party 下载目录不进入仓库。下载、使用或另行分发模型须遵循模型和量化仓库的适用条款。

## 发布

主源码保留包名 `dev.ondevice.gemma.app` 以兼容已安装数据。仓库不包含任何签名密钥。CI 自动生成的 debug 证书不能保证跨运行一致，不能用作长期升级签名。

当前公开 APK 为个人 alpha 测试包。准备稳定发行版时，需要另行建立受保护的 release 签名与版本流程。现有测试安装与未来不同签名的版本可能无法直接覆盖升级。
