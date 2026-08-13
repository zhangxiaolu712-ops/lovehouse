# 2026-08-13 Memory Source / Evidence Delta

- 基线：`9fe89935cc6bbecc912e01c09a8c5c1661bbbb14`。
- 新增 bounded summary、不可变 `memory_sources`、revision ↔ source 多对多与 fixed-actor `expand_source`。
- recall/list/starter pack/Memory Box/semantic recall 改为 summary-first；旧无 summary 记录只给明确标记的 bounded excerpt。
- `save_memory`、`save_to_memories`、`revise_memory` 的旧参数兼容；新增 summary/sources 均可选。
- Source 展开经独立 resolver/data-access contract；manual snapshot 可用，LoveHouse chat repository 未配置时明确 fail closed，绝不回退到 livingroom；Memory Repository 未获得任意表读取能力。
- Claude 精确白名单 13 → 14，其他内建工具仍关闭。
- 新增 disposable Supabase fresh apply、rollback、re-apply、隔离/RLS/audit/source 行为回归。
- 未触碰生产 Supabase、Bridge、PM2、Worker/Cloudflare、Gate B/C、Toy。
