# Codex CLI Chat 原生 Reasoning 与 Token 差分

## 做了什么

- `codex exec/resume --json` 显式设置 `model_reasoning_summary="detailed"`、
  `model_supports_reasoning_summaries=true` 与 `hide_agent_reasoning=false`。真实 CLI A/B 证明，在
  `--ignore-user-config` 下缺少 capability override 会产生 reasoning tokens、却不输出 reasoning item。
- 原生 reasoning item 映射到「我的思路」；没有 item 时保持 unavailable，不生成第二份旁白。
- command/file/MCP 工具事件保留 started/updated/completed lifecycle，前端显示为「正在做」。
- 将 `turn.completed.usage` 视为 thread 累计值，持久保存上一轮累计基线；网页用当前减上一轮显示
  本轮 input/output/cached input/reasoning output Token。旧 binding 首轮没有基线时明确
  establishing，不冒充准确本轮值。
- Context 增加 reasoning active context，标明由 Codex thread resume 续传并参与原生 compaction。

## 为什么

Codex CLI 的 reasoning、工具和 usage 已经是原生运行事件。新主干只归一化真实事件，不通过额外
prompt/调用伪造可见思考，也不能把线程累计 Token 误显示成本轮消耗。

## 修改范围

- `services/codex-chat/` 的 runtime adapter、context、binding、sidecar 与测试。
- `bridge/client-api/` 的安全事件 allowlist 与测试。
- `src/modules/codex-chat-v1/` 的实验页、Token 差分及测试。
- Codex Chat 主干文档、当前架构与待开发列表。

旧 Claude Chat、Memory、LivingRoom、OAuth/MCP 未修改。

## 数据库、环境变量与部署

- 无数据库、migration、RLS、环境变量或 Nginx 变化。
- binding 文件保持 version 1 与既有 `codexThreadId/updatedAt` 字段，只增加可选 `lastUsage`；旧文件
  可直接读取，旧 sidecar 回滚后也可忽略该字段。
- 未 push、未部署、未重启生产。

## 验证

- Codex sidecar/runtime：16/16 PASS。
- Bridge Client API 定向：14/14 PASS。
- Bridge 全量：203/203 PASS。
- 前端 service/state：6/6 PASS。
- frontend lint：PASS（仅仓库既有 warning）。
- frontend production build：PASS。
- `git diff --check`：PASS。
- 旧 Claude Chat、Memory、LivingRoom、OAuth/MCP frozen-path diff：0。

## 风险与下一步

- `exec --json` 当前是 item 级 reasoning summary，不是逐字 delta；逐字流留给以后 app-server 方案。
- 已有旧 binding 部署后的第一轮只能建立 usage 基线，这是避免显示错误累计值的刻意降级。
- 下一步仅在审查通过后决定 push/PR；本工单不部署。
