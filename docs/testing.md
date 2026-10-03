# 测试与覆盖范围

测试分成三类。真实模型调用、模拟界面和真实应用验证分别记录，不能互相替代。

## 默认离线检查

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

alpha18 基线：**199 项 core 测试＋7 项 app 单元测试**通过；lint 无错误，仍有已知警告。

覆盖上下文与工具配对、会话连续性、失效页面、取消与持久化检查点、消息核对、系统控件、自动技能、ZIP 校验、参考文件隔离、千工具目录、MCP 协商/分页/参数/错误，以及本地 HTTP 的 JSON / SSE 与断线不重试。

网络相关单测只连接临时 localhost 测试服务，不访问云端模型或真实业务。

## 可选真实模型测试

脚本支持 `learning`、`latency`、`apps`、`autoskills`、`system`、`extensions` 六套场景。提供环境变量，Key 不作为命令行参数：

```sh
export LIFEBUDDY_API_URL=https://your-provider.example/v1
export LIFEBUDDY_API_MODEL=your-model-id
# Set LIFEBUDDY_API_KEY through your local secret manager or shell environment.
python3 scripts/test_learning_api.py --suite extensions
```

这些测试会向配置的模型服务发起请求，使用合成任务、界面和工具返回，不操作真实手机应用。默认报告保存在忽略提交的 `build/reports/provider-tests/`。需要 Python 3，普通环境变量方式不需要额外 Python 依赖。JDK 21 可加 `--build-jdk 21`。

alpha18 开发验证中，真实模型完成了英文技能/参考文件读取，以及中英文 MCP 检索调用：从 1001 项合成工具目录选出目标、读取定义、只调用一次后按结果回答。业务工具结果为模拟值，并非真实第三方服务查询。

可显式传入 `--settings`、`--credentials` 使用既有 dsh 风格 YAML 配置；只有这一兼容入口需要自行安装 PyYAML。脚本不会默认读取其他工具的凭据文件。

## Android 设备检查

现有 instrumentation 覆盖历史存储、学习、手机工具与扩展存储。扩展烟雾测试验证 Android Keystore、重新加载、凭据回显处理、停用工具、聊天隔离和真实双语设置页面；设备结果来自 Android 12，尚不能代表其他 Android 厂商版本。

构建和运行方式见 [开发说明](development.md)。使用独立 `fixture/` 测试应用验证联系人搜索和模拟发送；它不联网、不发送运营商短信，不包含在 LifeBuddy 主 APK 内。

## 还没有证明的能力

- 所有微信、QQ、美团、网盘或系统设置版本的兼容性。
- 真实第三方 MCP 服务的完整互操作性、OAuth 或桌面脚本执行。
- 支付、外卖最终提交、视觉页面和长时间后台无人值守任务。
- Android NDK 本地推理的普遍性能或稳定性。

公开仓库不包含个人设备原始日志、会话、截图或凭据。反馈问题时请使用脱敏的最小复现案例。
