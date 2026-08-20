# Memory V2 Phase 2E Ollama Semantic 旁路

- 日期：2026-08-20
- 执行者：Codex
- 状态：Approved Shared embedding delta 已上线；58/58 current revision backfill 与 semantic/降级/恢复验收完成；尚未接入 Bridge/MCP/Chat

## 做了什么

- 从包含 Phase 2B squash merge 的 `main` `519592035bd00859f3c0a70f8ae5d27fdd0af4f7` 建立独立分支。
- 以同签名 `CREATE OR REPLACE` 仅扩展 `memory_v2_store_embedding` eligibility：fixed actor 可写自己的 private 或 approved Shared；candidate/revoked Shared 与交叉 private 仍拒绝。
- 新增薄 `OllamaEmbeddingAdapter`，只调用 `/api/embed`、校验 HTTP/1536 维/finite vector，并提供内存中的最近一次状态。
- 新增一次性、可续跑 backfill：只枚举 current active revision、同模型已存在即跳过、逐条经现有 Repository/RPC 写入，首次错误立即停止。
- Memory V2 Service 只增加可选 `embeddingStatus()` 结果；没有注册 MCP、HTTP endpoint、监控或后台任务。

## 修改文件

- `supabase/migrations/20260820141805_allow_approved_shared_memory_v2_embedding.sql`
- `supabase/rollback/20260820141805_allow_approved_shared_memory_v2_embedding_rollback.sql`
- `supabase/tests/memory_v2_embedding_shared_delta.sql`
- `bridge/memory-v2/ollamaEmbedding.js`、`ollamaEmbedding.test.js`
- `bridge/memory-v2/backfill.js`、`backfill.test.js`
- `bridge/memory-v2/service.js`、`service.test.js`、`index.js`
- `docs/02_当前架构.md`、`docs/06_待开发列表.md` 与本记录

## 数据库、环境变量与部署

- 生产 Supabase migration `20260820142029_allow_approved_shared_memory_v2_embedding` 已应用；函数签名和 grants 不变，没有新增表/RPC/RLS/policy。
- 配置项仅为 `MEMORY_V2_EMBEDDING_URL`、`MEMORY_V2_EMBEDDING_MODEL`、`MEMORY_V2_EMBEDDING_DIMENSIONS`，可选短 timeout 为 `MEMORY_V2_EMBEDDING_TIMEOUT_MS`。
- 没有修改 PM2 env、没有重启或部署 Bridge。生产 backfill 由 VPS 临时目录的一次性 SSH 子进程执行。
- Backfill 首轮目标 58，成功 46 后在一条 1910 字符的 current Claude revision 上按 30 秒上限安全停止。获批续跑时已有 46 条按同模型跳过；一次性 harness 仅把该次请求上限临时提高到 120 秒，剩余 12 条全部成功。最终 GPT 32/32、Shared 2/2、Claude 24/24 均有 `qwen3-embedding:4b` embedding。
- 120 秒只存在于一次性 SSH 子进程；仓库 adapter 的正常 30 秒硬上限、PM2 env 与 live Bridge 配置均未修改。

## 验证结果

- Delta 先在生产同构 schema 单事务演练并整体 rollback，再正式应用；GPT/Claude own private、approved Shared 正向写入与交叉 private、revoked/candidate 反向测试通过。
- 生产定义核对：同签名、approved Shared predicate 存在、没有 current-revision predicate；service_role 可执行，anon/authenticated 不可执行；5 张 V2 表继续 FORCE RLS。
- VPS → Tailscale → Ollama 单条请求：HTTP 200、模型 `qwen3-embedding:4b`、1536 维、全 finite。
- Adapter/backfill/Service 定向测试 21/21；Bridge 全量 182/182；frontend lint/build 与 `git diff --check` 通过。Lint/build 只有既有 warning。
- 中文近义 query 实测返回 `mode=semantic`、`semantic_error=null`；GPT/Claude private 隔离、approved Shared 双方可见、旧 revision 不进入普通 recall 均通过。
- 同一 Service 与同一 adapter 将请求临时改投不可达本机端口后，recall 返回 `mode=lexical_fallback`、`semantic_error=MEMORY_V2_EMBEDDING_NETWORK_ERROR` 且仍有 lexical 结果；恢复真实 Ollama URL 后下一次 recall 无需重启即回到 `semantic`。

## 已知风险或未完成事项

- 当前 58 条 current revision 已完整 embedding；以后新增/修订记录仍需由未来获批的实际接入路径 best-effort 写 sidecar，本阶段没有新增 worker/queue/daemon。
- 本机没有 Docker，delta DB 测试采用生产 schema 单事务 rollback 演练及应用后事务测试；没有伪装为 disposable Supabase。
- Supabase advisors 仍报告仓库既有 INFO/WARN；本 delta 没有新增表、policy 或 grant，也没有在本工单处理基线告警。

## 下一步计划

1. 等待复审；不自行切 MCP/Chat、部署 Bridge、进入 Phase 2C 或清理 V1。
