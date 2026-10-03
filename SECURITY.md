# Security / 安全问题

LifeBuddy is currently an alpha project. Security fixes target the latest available alpha; older builds do not have a separate support branch.

Please report credential exposure, unauthorized tool execution, archive path traversal, or unintended access to private phone data through [GitHub private vulnerability reporting](https://github.com/RytterMohn/LifeBuddy/security/advisories/new). Include the affected version, a minimal reproduction and the impact. Do not include real credentials or private conversations.

请通过上述 GitHub 私密漏洞报告入口反馈凭据泄漏、越权执行、导入路径穿越等问题。提供受影响版本、最小复现方法和影响范围，不要发送真实 Key 或私人对话。普通功能问题可提交公开 Issue。

Relevant implementation boundaries:

- Phone tasks are explicitly started by the user; the accessibility service reads allowed, accessible foreground content.
- Requested actions, including messages, can execute directly in Operate mode. There is no extra confirmation dialog for each send.
- Model and MCP keys are encrypted on the phone. Configured providers receive the context or arguments necessary for their work.
- Imported skill descriptions and remote tool output are reference data, not new user authorization.
- Bundled scripts are not executed. MCP supports configured HTTPS endpoints; redirects and automatic tool-call retries are disabled.
- An unknown external outcome stays unknown until verified. Cancelling a task does not undo a completed operation.
