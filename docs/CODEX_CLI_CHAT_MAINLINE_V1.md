# Codex CLI Chat 新主干实验 v1

> 状态：本地实现、未部署。依赖 Native Backend Foundation v1 commit
> `f6e0a95ca83b09a4b46398836d379925427824cd`。

## 1. 实验链与边界

```text
Web experiment / future Android
  → POST /api/v1/chat (Owner JWT, LoveHouse thread_id)
  → Bridge Persona Registry + Provider Router
  → Codex Adapter（只归一化协议）
  → independent services/codex-chat sidecar
  → CodexCliRuntimeAdapter
  → codex exec / codex exec resume
```

- Bridge 与 Codex sidecar 仍是两个独立进程、两个 package。
- 旧 Claude `/chat`、`claudeProcess`、Claude MCP/OAuth 没有修改。
- Thread/binding 不接 Memory、WorldBook、Archive 或 Context Composer。
- UI 只认识 `/api/v1` 事件，不解析 Codex CLI JSONL、session id 或内部端口。

## 2. Runtime Adapter 契约

正式 JavaScript 方法：

| 方法 | 唯一职责 |
|---|---|
| `startOrResume()` | 根据可空 provider session 生成 runtime 启动描述 |
| `sendMessage()` | 启动 provider 进程、发送 prompt、逐行交出原始 JSON event |
| `streamEvents()` | 把 provider JSONL 归一化为 Chat runtime events，处理确定的 session recovery |
| `getUsage()` | 区分 estimate 与 provider actual usage |
| `getQuota()` | 返回可靠 quota；拿不到必须是 `unknown` |
| `getCapabilities()` | 返回 `runtime_type/adapter_id/enabled/capabilities` |
| `resetRuntime()` | 清理可替换 runtime 状态，不删除 LoveHouse Thread |

预留 runtime type：`codex_cli`、`codex_api`、`claude_cli`、`claude_api`、`openai_api`。
本阶段只有 `codex_cli` 是新主干真实实现；其余只是枚举，不存在假 adapter。

当前 Codex descriptor：

```json
{
  "runtime_type": "codex_cli",
  "adapter_id": "codex-cli-v1",
  "enabled": true,
  "capabilities": {
    "streaming_text": true,
    "reasoning_summary": "detailed",
    "tool_events": true,
    "actual_usage": true,
    "quota": false,
    "context_breakdown": "basic"
  }
}
```

## 3. 统一 Chat Stream

允许事件顺序按实际 runtime 行为交错，但一轮必须以 `message_start` 开始，以
`message_end` 结束：

| event | 关键字段 |
|---|---|
| `message_start` | `thread_id/persona_id/runtime/adapter_id/scene/reply_policy` |
| `text_delta` | `delta` |
| `reasoning_status` | `available/status/summary/source` |
| `tool_call` | `call_id/tool_type/name/status=running` |
| `tool_result` | `call_id/name/status=success/summary` |
| `tool_error` | `call_id/name/status=failed/summary` |
| `usage` | estimate、actual、total、source |
| `quota` | status、remaining、unit、reset_at、source |
| `context_breakdown` | recent_chat/memory/worldbook/persona/current_message/estimated_tokens |
| `error` | `code/message/stage/request_id/retryable` |
| `message_end` | `ok` |

所有事件都带 LoveHouse `thread_id`。Bridge 丢弃 sidecar 的兼容 `session` 事件，不向
`/api/v1` 客户端暴露 Codex session id。

## 4. Reasoning / Tool / Usage / Quota / Context

### Reasoning

每次 `codex exec --json` 和 `codex exec resume --json` 都显式附加：

```text
-c model_reasoning_summary="detailed"
-c model_supports_reasoning_summaries=true
-c hide_agent_reasoning=false
```

只有 Codex JSONL 真正出现 user-visible `item.type=reasoning` 时才返回原生 summary。适配器不发
第二次模型请求、不把 assistant text 改写成旁白；只做长度边界与常见凭证模式裁剪。唯一的语气提示是
一条短句：自然、亲近、略带碎碎念，同时忠于实际推理且不补写步骤。没有 reasoning item 时固定：

```json
{"available":false,"status":"unavailable","summary":null,"source":"codex_cli"}
```

页面的「我的思路」只展示上述原生 summary。本轮仍使用 `exec --json` 的 item 级事件，不承诺逐字
reasoning delta。Codex 原生 thread 在 resume 时继续携带 reasoning item；它属于 runtime active
context，并由 Codex 原生 compaction 管理。LoveHouse 不把 summary 再次拼进 prompt。

### Tool

CLI 工具事件支持 `item.started/item.updated/item.completed`，至少覆盖 `command_execution`、
`file_change`、`mcp_tool_call`。adapter 统一增加 `lifecycle=started|updated|completed`；页面的
「正在做」只显示工具身份、生命周期、成功/失败与简短结果，不返回 command、arguments、stdout、
diff、环境变量或 Secret。
未知 JSONL event/item 继续忽略，stderr 只用于服务端错误分类，不进入前端事件。
Codex 子进程只继承运行所需的 HOME/CODEX_HOME/PATH/locale/proxy/certificate/temp allowlist，
不会继承 sidecar 的 Supabase 或其他业务凭证。

### Usage

```json
{
  "estimated_input_tokens": 123,
  "actual_input_tokens": 456,
  "cached_input_tokens": 300,
  "actual_output_tokens": 78,
  "reasoning_output_tokens": 12,
  "total_tokens": 534,
  "usage_source": "codex_cli"
}
```

`turn.completed.usage` 是 Codex thread 累计值，不可直接冒充“本轮 Token”。sidecar 在同一
LoveHouse Thread binding 中持久保存上一轮累计基线；事件同时返回当前累计值与上一轮累计值，网页
明确执行 `current - previous` 后才显示本轮 input/output/cached input/reasoning output/total。
cached input 是 input 的子集，reasoning output 是 output 的明细，二者不重复加入 total。已有旧
binding 第一次没有基线时只标记 `baseline_status=establishing`，不显示错误的大累计数；下一轮起
正常做差。旧 binding 若只缺新增明细字段，则该字段首轮保持空值并建立自己的累计基线。CLI 没有
usage 时只保留 estimate，且 `usage_source=estimate`。

### Quota

Codex CLI 当前没有为 sidecar 提供可靠订阅余额接口：

```json
{"status":"unknown","remaining":null,"unit":null,"reset_at":null,"source":"codex_cli_unavailable"}
```

Token 与 quota 不互相推算。

### Context

当前报告 native runtime/recovery 所需的 `recent_chat`、`current_message` 与 `reasoning` active
context。reasoning context 明确标记 `resumes_with_thread=true`、`compaction=codex_native`，summary
仍只来自当前原生 reasoning item。`memory`、`worldbook`、`persona` 明确
`enabled=false/available=false`。本阶段不读取它们，也不伪造 token。

## 5. Thread 与 restart

```text
LoveHouse thread UUID
  └─ persisted owner + thread → runtime_session_id
       └─ Codex CLI thread/session UUID
```

- 浏览器只持久化 LoveHouse thread/window 与 bounded UI history，不保存 runtime session。
- sidecar binding 文件原子写入、权限 `0600`。
- 自动测试执行 Thread A 第 1、2 轮，完全关闭第一台 server，再用同一 binding 文件创建新
  server 跑第 3 轮；三轮 LoveHouse Thread 不变，第 2/3 轮均 resume 同一 Codex session。
- 另一项 HTTP 集成测试保持 sidecar 独立运行，完全关闭并重建 Bridge 后发送第 3 轮；客户端
  继续提交同一 Thread，sidecar 继续 resume，且 `/api/v1` 不泄露 session id。
- 确认 session 丢失时可用 bounded continuation 新建 runtime binding，但 LoveHouse Thread
  不变；没有 recovery context 时返回 `SESSION_RECOVERY_FAILED`，不静默换 Thread。
- quota/runtime 错误不会删除 binding。

## 6. 错误码

| code | stage | 含义 |
|---|---|---|
| `RUNTIME_UNAVAILABLE` | runtime | CLI/sidecar 无法启动或 runtime 正忙 |
| `AUTH_FAILED` | auth | Owner JWT 或 Codex CLI 登录失败 |
| `SESSION_RECOVERY_FAILED` | session/storage | provider session 无法恢复或 binding 不可用 |
| `TOOL_FAILED` | tool | 工具导致 turn 失败；单个工具失败也会先发 `tool_error` |
| `QUOTA_EXHAUSTED` | quota | CLI 明确报告额度/usage limit |
| `STREAM_INTERRUPTED` | runtime/transport | JSONL/HTTP/SSE 非正常结束 |
| `UNKNOWN_RUNTIME` | routing | runtime/sidecar route 不存在 |

Thread 在上述错误后继续存在。

## 7. 实验页

路由：`/#/codex-chat-v1`

页面显示 Persona、runtime/adapter、LoveHouse Thread、work/text policy，以及独立的「我的思路」和
「正在做」区块；本轮 Token 来自累计差值，另显示 quota、context breakdown 与错误 stage/code。
它不访问 sidecar 私有接口，不保存 JWT/session id，不接 Archive。

## 8. 真实实现与预留

已实现：Codex CLI start/resume、文本流、真实/不可用 reasoning 状态、工具生命周期、actual 与
estimate token、unknown quota、基础 context breakdown、持久 binding、restart、统一错误、测试 UI。

仅预留：其他 runtime type、Memory/WorldBook/persona context、Voice、Archive、API provider、完整
Context Composer、可查询 quota。

## 9. 下一阶段建议（不在本工单施工）

Claude 不再修旧 `/chat`。最小迁移应是：

1. 新建独立 `ClaudeRuntimeAdapter`，实现同一七方法 contract。
2. 只将 Claude CLI 原生事件映射为同一 Chat Stream，不改变 UI/parser。
3. 为 Claude 建独立持久 runtime binding，并做两轮 + restart + 第三轮。
4. 验收后只切 `persona=claude` 的新 `/api/v1` binding；旧 `/chat` 保留回滚窗口。

不要把 Claude MCP/OAuth、Memory 或 Archive 混入该迁移。
