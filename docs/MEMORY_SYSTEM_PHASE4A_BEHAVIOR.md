# Memory System Phase 4A：Hybrid Recall

> 状态：独立 Draft PR；未合并、未部署、未连接生产 Supabase，`MEMORY_SYSTEM_ENABLED` 与 `MEMORY_SEMANTIC_ENABLED` 均保持关闭。

## 本阶段边界

Phase 4A 只实现四项能力：

1. 当前 revision 的 embedding 生命周期；
2. 不可修改、可新增版本的 ranking profile；
3. 关键词候选与语义候选的 Hybrid Recall；
4. 语义服务不可用时，经持久化审计后降级到 Phase 3 关键词召回。

明确不包含 Anchor/Pin、Dream Queue、Curator provider、潮汐整理、Legacy 迁移，也不新增 MCP 工具或 AI-facing 参数。

## 调用链

```text
原有 recall_memory(query, limit, cursor, tags)
  -> 固定 GPT / Claude MCP Adapter
  -> MemoryService
     -> Embedding Provider
     -> 固定 actor Hybrid RPC
     -> SQL eligible relation
        = actor 自己的 private + approved Shared
        != 对方 private / unapproved Shared / Legacy Pending
  -> AccessPolicy 最终复核

语义 provider 失败
  -> 持久化 recall_semantic_fallback 审计
  -> Phase 3 固定 actor keyword RPC

安全/权限/审计失败
  -> fail closed，不降级
```

## Schema

### `memory_ranking_profiles`

- `ranking_v1` 保存 RRF、关键词/语义/importance/recency/decay 权重、语义阈值和候选倍数。
- profile 行由 trigger 禁止更新和删除；调整实验参数必须新增 `ranking_v2` 等新版本。
- Bridge 只传服务端配置的 profile key，AI 不传权重或 embedding 参数。

### `memory_embeddings`

- vector 绑定 `owner_id + memory_id + revision_id + embedding_profile_key`，不绑定“当前可变正文”。
- revision trigger 仅为 GPT/Claude private 当前 revision 排队；approved Shared 状态 trigger 为稳定 Shared 快照排队。
- Legacy Pending 与 unapproved Shared 在入队阶段就被排除。
- 旧 revision 的 vector 可以保留供审计/重建，但 Hybrid SQL 只 join 当前 revision，因此不会参与召回。
- 状态仅为 `pending -> processing -> ready/failed`，单次领取最多 8 条、租约 2 分钟、最多 3 次尝试。

## Ranking v1

`ranking_v1` 是首个可替换实验参数，不是架构真理：

| 组成 | 默认权重 |
|---|---:|
| keyword RRF | 0.31 |
| semantic RRF | 0.31 |
| importance | 0.18 |
| recency | 0.12 |
| decay | 0.08 |

最终排序固定为 `score DESC, created_at DESC, id DESC`。每次 hybrid recall 的 audit metadata 会记录 ranking/embedding profile，便于以后比较 v1/v2。

## 安全边界

- 只有 8 个固定 GPT/Claude 的 Phase 4A RPC 对 `service_role` 开放；内部函数、原表、sequence 均撤销访问。
- actor 不存在于 AI tool schema，也不能通过 body/query/header/namespace/space 选择。
- SQL 的 `eligible MATERIALIZED` 从第一步只包含 actor private 与 approved Shared；Legacy 不是“查出后过滤”。
- MemoryService 仍执行 AccessPolicy 最终复核。
- provider 超时、限流、不可用、无效 vector 可降级；跨空间、安全拒绝与审计故障不能降级。

## 配置（默认全部关闭）

| 变量 | 默认/用途 |
|---|---|
| `MEMORY_SEMANTIC_ENABLED` | `false`；必须同时满足 `MEMORY_SYSTEM_ENABLED=true` 才生效 |
| `MEMORY_RANKING_PROFILE` | `ranking_v1` |
| `MEMORY_EMBEDDING_PROFILE` | `semantic-1536-v1` |
| `MEMORY_EMBEDDING_DIMENSIONS` | `1536` |
| `MEMORY_EMBEDDING_API_URL` | OpenAI-compatible embedding endpoint；未配置时不启动 indexer |
| `MEMORY_EMBEDDING_API_KEY` | 仅 Bridge 环境变量，绝不进前端或数据库 |
| `MEMORY_EMBEDDING_MODEL` | provider model，由服务端固定 |

## 验证与回滚

- GitHub Actions 使用免费的 disposable local Supabase，不创建付费 Branch。
- 顺序验证 Phase 2、Phase 3、Phase 4A，执行 db lint、Phase 4A rollback/reinstall、全量 rollback/reinstall、Bridge 全测、前端 lint/build。
- 回滚先移除 Phase 4A triggers/RPC/functions/tables，再保留 Phase 2/3。`vector` extension 不自动删除，避免误伤可能共享该扩展的其他组件。

## 已知风险

- embedding provider 仍是外部依赖，所以默认关闭，并有审计后的关键词 fallback。
- `ranking_v1` 需要真实匿名化测试数据验证权重；当前只证明可版本化与确定排序，不声称权重已经最优。
- 当前后台 indexer 是 Bridge 内的轻量有界轮询，只在显式开启语义配置后运行；更复杂的调度属于后续独立阶段。
