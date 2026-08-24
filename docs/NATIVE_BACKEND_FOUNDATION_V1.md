# LoveHouse Native Backend Foundation v1

> 状态：本地实现，尚未部署。基线 commit：`43d975728e00ba5c48babef7e144277e5d0bf1e8`。
> 本文只描述本工单核实和新增的稳定 Client Contract；生产行为仍以实际运行版本为准。

## 1. 当前真实后端线路图

```text
Web / future Android
  │ Supabase Owner JWT
  ▼
https://b612.fyi/api/*
  │ Nginx：TLS + /api 前缀转发
  ▼
lovehouse Bridge :3000
  ├─ /chat, /reset ───────────────► claudeProcess ─► Claude CLI child
  ├─ /livingroom* ────────────────► LivingRoom fence ─► Supabase REST
  ├─ /mcp/gpt, /mcp/claude ───────► OAuth/MCP ─► Memory V2 + LivingRoom
  └─ /v1/*（本工单新增）
       └─ Persona Registry
           └─ Provider Router
               ├─ Claude Adapter ─► 既有 claudeProcess
               └─ Codex Adapter ──► loopback :3002/api/codex
                                         ▼
                                  lovehouse-codex-chat sidecar
                                         ▼
                              Codex exec/resume + 文件绑定仓库
```

Nginx 对外保留 `/api`，转给 Bridge 后为内部 `/...`；因此公开
`/api/v1/chat` 对应 Bridge 内部 `/v1/chat`。本工单没有修改 Nginx。

### 1.1 已核实的公开入口

| 公开入口 | 内部处理 | 身份 | 本工单 |
|---|---|---|---|
| `POST /api/chat` | Bridge `/chat` → Claude | Supabase Owner JWT | 保留不变 |
| `POST /api/reset` | Bridge `/reset` | Supabase Owner JWT | 保留不变 |
| `GET/POST /api/livingroom` | Bridge LivingRoom fence | MCP key/固定 sender | 保留不变 |
| `/api/mcp/gpt`, `/api/mcp/claude` | Bridge MCP transports | GPT key / Claude OAuth | 保留不变 |
| `/oauth/*` | Bridge OAuth/DCR/token | OAuth client + Owner approval | 保留不变 |
| `GET /api/codex/health` | Codex sidecar | health | 保留不变 |
| `POST /api/codex/chat` | Codex sidecar | Supabase Owner JWT | 保留不变 |
| `/api/v1/*` | Bridge Client API | Supabase Owner JWT | 新增 |

### 1.2 当前状态与持久化

| 状态 | 当前位置 | 重启影响 |
|---|---|---|
| Claude `window_id → session_id` 活跃映射 | Bridge 进程内存 | Bridge 重启会丢；v1 新绑定文件可恢复已知 session |
| v1 LoveHouse thread → Claude provider session | `CLIENT_RUNTIME_BINDINGS_PATH` JSON | 原子写入、`0600`，Bridge 重启保留 |
| Codex owner + window/thread → Codex thread | sidecar 文件仓库 | sidecar/Bridge 重启保留 |
| Codex recent UI history | sidecar 进程内存 | 可丢，不是 thread 权威来源 |
| OAuth authorization code | Bridge 进程内存 | 重启失效，短期一次性状态 |
| OAuth DCR client registry | 服务器文件 | Bridge 重启保留 |
| OAuth refresh token store | 服务器文件 | Bridge 重启保留 |
| rate limit / MCP SSE session / worker 状态 | Bridge 进程内存 | 重启重置 |
| Memory V2 / LivingRoom 正文 | Supabase | Bridge 重启不丢 |

Web 旧 Claude Chat 仍把窗口和有限历史放在浏览器 storage；这不是 Native
Client Contract 的权威 Thread/Archive。当前没有正式 Raw Chat Archive，本工单也没有伪造一个。

## 2. 稳定 Client Contract

所有 `/api/v1/*` 当前都要求：

```http
Authorization: Bearer <Supabase owner JWT>
```

Bridge 在服务端向 Supabase Auth 校验 JWT，并继续核对固定 Owner user id。Android
以后可换成独立设备门卡，但 v1 response 已明确报告当前 auth mode；业务模块不得各自发明登录。

### `GET /api/v1/health`

返回 Client API 本身和各 persona runtime 的轻量状态。它不是数据库完整健康证明。

### `GET /api/v1/bootstrap`

返回 `api_version`、可证明的 release/start identity、当前认证模式、feature discovery 与 Persona
Registry。不返回 secret、service-role、Provider key、VPS IP、内部端口或 MCP URL。无法从实际
release 路径证明 deployment SHA 时返回 `null`，不会拿可能漂移的 env 假装真实版本。

### `GET /api/v1/personas`

| id | 当前状态 | 默认 scene | 说明 |
|---|---:|---|---|
| `gpt` | disabled | `casual` | 当前只有 GPT MCP actor，没有一对一 GPT chat runtime |
| `claude` | enabled | `casual` | 复用既有 Claude session path |
| `codex` | enabled | `work` | 转发到独立 Codex sidecar |

### `POST /api/v1/chat`

```json
{
  "persona_id": "codex",
  "thread_id": "11bc3aa8-5eb4-4ec6-a931-25e5139d6483",
  "window_id": "android_window_01",
  "scene": "work",
  "message": {
    "type": "text",
    "text": "你好"
  }
}
```

- `thread_id` 可省略；Bridge 会生成 LoveHouse UUID，并在首个 `message_start` 返回。
- `window_id` 是客户端窗口标识，不是 provider session，不作为长期 thread 权威来源。
- `scene` 可省略，使用 persona 默认值。
- 当前只执行 `text`。`audio/image/file/location/tool_result` 是保留类型，调用会明确返回
  `415 UNSUPPORTED_MESSAGE_TYPE`，不会伪装成功。
- `source_platform/source_conversation_id/source_message_id/imported_at` 可放在 thread 请求层或
  message 层，经验证后向 adapter 传递；当前没有 Archive Repository，因此不持久化。

统一 SSE 事件按顺序为：

```text
message_start { request_id, thread_id, persona_id, runtime, scene, message_type }
text_delta   { request_id, thread_id, persona_id, delta }
usage        { request_id, thread_id, persona_id, usage }  # 有真实 usage 时才出现
error        { ok:false, error:{ code, message, stage, request_id, retryable } }
message_end  { request_id, thread_id, persona_id, ok }
```

Claude/Codex 的 provider session、thinking 事件和内部 sidecar 协议均不暴露给客户端。
Claude 额度耗尽会成为 `PROVIDER_QUOTA_EXHAUSTED` provider error，不会冒充 Bridge 故障。

### `POST /api/v1/chat/reset`

请求 `persona_id + thread_id`，删除该 LoveHouse thread 的 Claude runtime binding，并返回一个
新的 `thread_id`。Codex sidecar 不被合并或直接改写；客户端使用新 thread 后由 sidecar 建立
新 binding。

### 非流式错误 envelope

```json
{
  "ok": false,
  "error": {
    "code": "UNKNOWN_PERSONA",
    "message": "Unknown persona_id",
    "stage": "routing",
    "request_id": "...",
    "retryable": false
  }
}
```

## 3. Persona / Thread / Runtime 数据关系

```text
Persona（用户选择的角色）
  └─ Thread（LoveHouse 长期 UUID；未来 Archive 主键）
       ├─ scene
       ├─ future archive/import metadata
       └─ Runtime Binding（可替换的技术映射）
            └─ Provider Session（Claude session / Codex thread）
```

核心不变量：

- `thread_id != provider_session_id`
- provider session 可以轮换、恢复或替换；LoveHouse thread 身份不随之改变。
- Android 只保存/提交 LoveHouse thread，不把 Claude/Codex session 当永久事实。
- Bridge 进程内存只容纳可丢的运行状态；新的 Claude binding 是服务器文件状态。
- Raw Chat Archive、Summary、Memory V2 仍是三层不同数据，本工单没有互相替代。

## 4. Provider Router / Adapter 边界

Router 只做 `persona_id → registry → default_runtime → adapter`。

- Claude Adapter：调用既有 `claudeProcess`，保存/读取确定 thread 的 provider session binding。
- Codex Adapter：以 loopback 调独立 sidecar，转发当前 Owner bearer，将 sidecar SSE 映射为统一 SSE。
- future GPT Adapter：只有真正的一对一 runtime 出现后再启用；当前不做假实现。

Router/Adapter 不实现 Memory ranking、MCP、LivingRoom、Archive、provider session 算法或长期业务状态。

## 5. 新旧 API 映射

| 稳定入口 | 当前下游 | 旧入口是否保留 |
|---|---|---:|
| `/api/v1/chat persona=claude` | 既有 Claude `claudeProcess` | 是：`/api/chat` |
| `/api/v1/chat persona=codex` | 独立 `/api/codex/chat` sidecar | 是 |
| `/api/v1/chat persona=gpt` | 无 runtime，明确 503 | GPT MCP 不受影响 |
| `/api/v1/chat/reset` | Claude binding/reset 或新 Codex thread | 是：`/api/reset` |
| `/api/v1/bootstrap` | registry/runtime capability | 新增 |
| `/api/v1/health` | Client API/runtime lightweight health | 旧 health 均保留 |

## 6. 明确预留但未实现

- message type：`audio`、`image`、`file`、`location`、`tool_result`
- scene：`casual`、`work`、`travel`、`livingroom`、`custom`
- Archive import metadata：`source_platform`、`source_conversation_id`、
  `source_message_id`、`imported_at`
- device：`device_id`、独立 credential、revoked state、feature capability
- future GPT one-to-one runtime
- Raw Chat Archive、Summary、external import writer
- Voice policy / TTS / STT / realtime voice

“预留”只表示 contract 不会把未来路线锁死，不表示功能已经上线。

## 7. 回滚与下一步

本工单只有 Bridge 源码增量、测试和文档，没有 migration。回滚只需把 Bridge release 指回
上一 commit；旧 `/chat`、`/reset`、`/api/codex/*` 没有改语义。

建议下一工单仅做 disposable/integration 环境的真实 HTTPS E2E：Owner login → Claude/Codex
各两轮 → Bridge restart → 同一 LoveHouse thread 恢复，并验证 Nginx `/api/v1/*` 路由。
通过后再决定生产部署；不要在同一工单开始 Archive、设备配对或 Android UI。
