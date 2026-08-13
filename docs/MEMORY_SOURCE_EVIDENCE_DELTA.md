# Memory Source / Evidence Delta

## 边界

本次在既有 Memory Runtime 上增量加入 summary-first 读取与 revision-bound source/evidence；不迁移旧记忆、不改 tag taxonomy、不启用生产 Memory、Gate B/C、Dream 或 Semantic。

## Schema

- `memory_entries.summary`、`memory_revisions.summary`：可空、1–2000 字；`content` 仍是完整正文。旧记录不回填，读取时返回最多 320 字并标记 `summary_origin=excerpt_fallback`。
- `memory_sources`：不可变、owner + GPT/Claude private namespace 固定的 source identity。内置支持 `manual_quote`、`manual_summary`、`lovehouse_message`、`lovehouse_message_range`；旧 Draft 名称 `lovehouse_range` 作为兼容 alias 保留。`source_kind` 与 `source_channel` 是有界 provenance，不绑定具体数据库或前端。
- `memory_revision_sources`：不可变 revision ↔ source 多对多；单 revision 最多 8 个 source。修订默认继承，显式 sources 创建新 revision；Shared candidate 继承其确定 private revision 的 source。
- 两张新表均 FORCE RLS，public/anon/authenticated/service_role 均无表级直读；Bridge 只可执行 fixed-actor RPC。

旧 `quotes` 是前端语录墙，没有 Memory owner/private namespace、revision identity、多对多或 fixed-actor Runtime 门，不能作为 canonical source identity；本次没有修改或复制它的数据。Dream 的 candidate source 表仍只服务 Dream job，也未复用为通用证据表。

## AI-facing contract

`save_memory` / `save_to_memories` 保留全部旧参数，并可选接收：

```json
{
  "summary": "不超过 2000 字的总结",
  "sources": [
    { "source_id": 12 },
    {
      "source_channel": "chatgpt_app",
      "source_kind": "manual_quote",
      "locator": { "reference": "selected text" },
      "quote_text": "原文快照"
    }
  ]
}
```

`recall`、`list`、starter pack、Memory Box 与 semantic recall 默认 item 为：`summary`、`summary_origin`、revision/space/type/tags 等 metadata、`has_source`、`source_count`、`sources[{source_id,source_channel,source_kind,can_expand}]`。它们不返回 `content`、`quote_text` 或 raw evidence。`get_memory` 继续返回完整 Memory 正文，但不自动展开 sources。

`expand_source({source_id,cursor_message_id?,limit?})` 返回 source descriptor 与 evidence。MemoryService 只依赖 `SourceResolver.resolve(source, options)`；resolver 再按 kind/channel 分派 adapter。manual quote 返回保存时的 quote snapshot；manual summary 返回 provenance 与明确的 `available:false`。

持久聊天证据 adapter 只依赖稳定的 `ChatMessageRepository.getMessage/listMessages` 数据访问接口。仓库当前没有可确认的 LoveHouse 单人聊天持久库，因此默认 repository 明确返回 `MEMORY_CHAT_SOURCE_NOT_CONFIGURED`；不会把 livingroom 行伪装成普通 LoveHouse chat message。将来 PostgreSQL、API、WeChat 或其他 channel adapter 可以在不改变 Memory schema、MemoryService 或 `expand_source` contract 的情况下注册。范围最多 50 个 message id，单页最多 20 条，并返回 `has_more` / `next_cursor`。

locator 只保存稳定 message id/range 或 external reference；Memory 写入层拒绝 browser window、component state、local/session storage 与 Supabase REST path。

Claude MCP 白名单原子更新为 14 项；仍关闭 Bash/Edit/Read/Write/Web 和任意文件工具，MCP init 缺项或漂移继续 fail closed。
