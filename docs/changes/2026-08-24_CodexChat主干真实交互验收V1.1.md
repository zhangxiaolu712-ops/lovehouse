# Codex Chat 主干真实交互验收 V1.1

## 结论

核心链路通过真实 `codex exec/resume --json` 验收。原生 reasoning item、工具 lifecycle、四项
Token 差分、Thread resume 与 Bridge restart 恢复均成立；没有生成第二份思考旁白。

## Reasoning 根因与证据

- 原参数已启用 `model_reasoning_summary="detailed"` 与 `hide_agent_reasoning=false`，但运行时同时使用
  `--ignore-user-config`，模型 capability metadata 被隔离。
- 增加 `model_supports_reasoning_summaries=true` 后，真实 CLI 第一轮输出
  `item.completed(type=reasoning)`，summary 为两条实际分析标题；统一 SSE 随后输出
  `reasoning_status(available=true, status=completed, source=codex_cli)`。
- 后续短轮次有 `reasoning_output_tokens`、但 CLI 没有 reasoning item 时，页面保持 unavailable；没有
  用 Token 数量或额外模型调用伪造摘要。

第一轮原始事件顺序：

```text
thread.started → turn.started
→ agent_message
→ command_execution started/completed
→ agent_message
→ command_execution started/completed
→ reasoning completed
→ agent_message
→ turn.completed
```

## 真实交互结果

- Thread：五轮均为同一个 LoveHouse `thread_id`；Codex 原生 thread 也保持不变。
- Resume：第二轮正确回答测试标记 `AURORA-31`。
- Command：同一 `call_id` 从 started 更新为 completed，没有生成重复卡片；stderr 未进入工具正文。
- Bridge restart：只重启临时 Bridge，Codex sidecar PID 未变化；第三轮继续回答标记与此前分析主题。
- Usage：第二轮累计值减第一轮累计值，得到本轮 input `58943`、cached input `55808`、output
  `213`、reasoning output `12`。Bridge restart 后第三轮仍沿用旧基线。
- 旧 binding：移除测试 binding 的 `lastUsage` 后，第四轮明确为 `establishing`，四项本轮值均为
  `null`；第五轮恢复正常差分：input `30004`、cached input `29440`、output `12`、reasoning
  output `0`。
- Error cleanup：无效凭据得到 `message_start → AUTH_FAILED → message_end(ok=false)`；原 Thread 与
  binding 未删除。
- File change：未测试。运行时固定 `--sandbox read-only`，没有为测试扩大权限或污染工作区。
- MCP tool：阻塞。`--ignore-user-config` 下没有可安全调用的 MCP，没有临时开放权限。
- Compaction：未强制制造长上下文。实现继续依赖 Codex 原生 resume/compaction，不保存并重复注入
  reasoning summary，也没有第二套 compaction。

## 回归

- Codex sidecar/runtime：16/16 PASS。
- Bridge：203/203 PASS。
- 前端 targeted：6/6 PASS。
- frontend lint：PASS（0 error，20 个仓库既有 warning）。
- frontend build：PASS。
- `git diff --check`：PASS。

## 边界

未修改 Claude、Memory、LivingRoom、OAuth/MCP 业务逻辑；未 push、未部署、未操作生产服务。
