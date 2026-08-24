# Claude Chat Adapter v1

## Scope

Claude Chat v1 adds one runtime implementation to the existing unified Chat mainline. It does not modify or migrate legacy `/chat`, legacy Claude sessions, or LoveHouse OAuth/MCP.

```text
LoveHouse thread_id
  -> POST /api/v1/chat (persona_id=claude)
  -> Bridge Claude sidecar adapter
  -> /api/claude/chat
  -> ClaudeCliRuntimeAdapter
  -> claude -p --output-format stream-json
```

The Claude sidecar is an independent process. Its file-backed binding is:

```text
Owner + LoveHouse thread_id -> Claude runtime session id
```

The provider session id never enters the Client API or browser state. A Bridge restart therefore does not change the LoveHouse thread or the sidecar-owned binding.

## Runtime Adapter contract

`ClaudeCliRuntimeAdapter` implements the existing shared contract:

- `startOrResume()` builds a new or resumed native Claude CLI turn.
- `sendMessage()` owns the child process and JSONL transport.
- `streamEvents()` maps native events to the unified Chat stream.
- `getUsage()` reports only counters actually returned by Claude CLI.
- `getQuota()` returns `unknown`; it never estimates subscription quota.
- `getCapabilities()` reports `runtime_type=claude_cli` and `adapter_id=claude-cli-v1`.
- `resetRuntime()` acknowledges a reset while the Client API rotates to a new LoveHouse thread identity; it does not delete provider history behind the caller's back.

`claude_api` remains a disabled runtime type only; no fake adapter exists.

## Event mapping

| Claude CLI input | Unified event | Notes |
|---|---|---|
| native text delta | `text_delta` | text only |
| native reasoning-summary block, if provided | `reasoning_status` | no second model call; raw thinking is not republished |
| tool use start | `tool_call` | inputs and commands are removed |
| tool result | `tool_result` / `tool_error` | result body is removed |
| CLI usage | `usage` | cached input and `output_tokens_details.thinking_tokens` are mapped when present |
| reliable quota failure | `quota` + `error` | otherwise quota is `unknown` |
| completion/failure | `message_end` / `error` | stable stage and code |

The v1 command starts with an empty strict MCP configuration and safe mode. Ordinary Claude Chat therefore does not depend on LoveHouse MCP initialization or OAuth state. The adapter can normalize tool events if the runtime emits them, but v1 does not enable or redesign MCP.

## Persistence and recovery

- Default binding file: `/root/lovehouse-claude-chat-state/thread-bindings.json`.
- Writes are atomic and permissioned `0600`; the containing directory is `0700`.
- A missing native session can be replaced only with bounded continuation history already owned by the LoveHouse thread.
- A runtime error does not delete the LoveHouse thread.
- Context and compaction remain native to the Claude session; LoveHouse does not store or reinject a second reasoning transcript.

## Experiment UI

`/#/claude-chat-v1` uses the same `/api/v1/chat` event contract as the Codex experiment. Browser storage contains only a LoveHouse thread id, a separate window id, and bounded UI history. It never stores a Claude session id or JWT.

## Explicitly out of scope

- Legacy `/chat` and its session manager
- Claude MCP/OAuth and tool registration
- Memory V2 and LivingRoom
- Claude API
- Voice, Android, Archive, Context Composer
- Nginx, PM2, or production deployment

## Future Claude API migration

Add a separate `claude_api` implementation of the same Runtime Adapter contract, then select it in the server-side persona registry. The Client API and experimental UI should not need a provider-specific parser.
