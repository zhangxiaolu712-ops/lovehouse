# Claude Adapter 重建 v1

## 结论

- 新 `claude_cli` Adapter 已接入 Codex Chat Mainline v1.1 的同一套 Thread、SSE、错误与可观察性契约。
- 旧 `/chat` handler、`claudeProcess.js`、Claude MCP/OAuth、Memory V2、LivingRoom 均未修改。
- 本地自动化闭环通过；真实 Claude 2.1.229 启动已到达 provider，但现有 CLI 登录状态返回 `AUTH_FAILED`，因此真实正文/Resume 验收仍被外部认证状态阻断。
- 未部署、未重启生产进程、未修改生产配置。

## 实际实现

- 独立 `services/claude-chat` sidecar，默认内部路由 `/api/claude/*`、端口 `3003`。
- 原子持久化 `Owner + LoveHouse thread_id -> Claude runtime session id`，provider session 不经过 `/api/v1` 或前端。
- Bridge 的 `/api/v1/chat persona_id=claude` 改走 Claude sidecar；旧 `/chat` 仍走冻结的 legacy 实现。
- 新实验页：`/#/claude-chat-v1`。
- Claude CLI 使用 `stream-json`、安全模式、空的严格 MCP 配置；MCP/OAuth 初始化不再是普通 Chat 的前置条件。
- 只接受 CLI 原生 reasoning-summary 事件；raw thinking 不展示，不发起第二次模型调用。
- usage 映射真实 input/cache/output/thinking token；quota 拿不到时为 `unknown`。

## 真实 CLI 证据

VPS 安装版本：Claude Code `2.1.229`。

真机 smoke 验证了：

- 空 MCP 配置必须是 `{"mcpServers":{}}`，裸 `{}` 会被 CLI 拒绝。
- init 事件包含 `session_id`、`tools=[]`、`mcp_servers=[]`。
- usage 含 `input_tokens`、`cache_read_input_tokens`、`output_tokens` 与 `output_tokens_details.thinking_tokens`。
- 当前凭据在 provider 调用时返回 `authentication_failed: OAuth session expired and could not be refreshed`；Adapter 将其归一化为 `AUTH_FAILED`，且不会先把错误文本当作 assistant 正文发给前端。

未在本工单重新认证或修改 OAuth。

## 验证

- Claude/Codex Runtime 与 Sidecar 定向测试：PASS。
- Bridge 全量测试：PASS。
- Claude/Codex 前端统一流测试：PASS。
- Bridge restart（Claude sidecar 不重启）恢复：PASS。
- frontend lint：PASS（仅既有 warnings）。
- frontend build：PASS。
- `git diff --check`：PASS。

## 当前验收状态

| 项目 | 状态 |
|---|---|
| 架构/统一协议 | PASS |
| Thread 与 provider session 分离 | PASS |
| Sidecar restart 持久恢复 | PASS |
| Bridge restart 连续性 | PASS（自动化） |
| Reasoning | `unavailable` 为合法结果；仅原生 summary 可展示 |
| Tool | Adapter 映射 PASS；v1 空 MCP 真机调用未开放 |
| Usage | PASS |
| Quota | `unknown` |
| 真实 Claude 正文/Resume | BLOCKED：现有 Claude CLI OAuth session expired |

在真实 Claude 登录恢复前，不进入 push/部署或生产 E2E。
