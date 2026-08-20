# Memory V2 Phase 1 最小旁路

- 日期：2026-08-20
- 执行者：Codex
- 状态：本地实现、disposable Supabase 与回归完成；未部署、未合并，等待人工复审

## 做了什么

- 新增完全旁路的 Memory V2 schema：2 张核心表、2 张可选证据表、1 张可重建 embedding 表。
- 新增独立 `bridge/memory-v2/` Service/Repository；没有修改或注册现有 MCP、Bridge server、Chat 或前端入口。
- `remember` 支持仅传正文；owner、GPT/Claude actor 与 private scope 由服务端 facade 固定。事件时间、tag/project/type/mood/stance、人类/AI 重要性及 source 全部可选。
- remember/revise 成功后才 best-effort 写 embedding；provider 未配置、离线或失败不回滚正文。
- recall 优先使用 semantic candidates；provider 错误或没有可用向量时自动走 PostgreSQL lexical + `pg_trgm.word_similarity` candidates；非 ASCII 查询另以相邻双字覆盖率补足中文插词命中，至少覆盖一半才进入候选。候选再使用同一个动态 Ranker。降级返回 `mode=lexical_fallback` 与明确 `semantic_error`，恢复后返回 `mode=semantic`。
- Ranker 将相关性与权重分开：最终分为 `relevance × (0.75 + 0.15 × tide + 0.10 × importance)`；importance 使用可调实现常量 AI 70%、human 30%。Tide 只复用 created/event/last recalled 时间和有上限的 recall count，不新增 Tide 表。
- revision append-only；普通 recall 只查 active/current，旧事实用一个 `superseded_by_id` 关联保留历史。
- source 可省略；存在时绑定确定 revision，默认 recall 只返回 source count，原文由显式 expand 展开。
- Starter Pack 不使用 importance 排序：当前 revision 以 `metadata.commitment_status=active` 表达有效承诺（最多 4），其后最近记忆/变化（最多 8），最后从剩余合格池随机盲盒（最多 3）；三类去重，总数软上限 15，1600 estimated-token 为最终硬预算，长正文只给 bounded excerpt，不展开 source。
- 每次 remember/recall/revise/starter pack 在入口取得一次 `current_time`（默认 `+08:00`）；时间格式化失败明确返回 unavailable，但不阻止正文存入。

## 修改文件

- `bridge/memory-v2/index.js`
- `bridge/memory-v2/repository.js`
- `bridge/memory-v2/service.js`
- `bridge/memory-v2/service.test.js`
- `supabase/migrations/20260820110600_create_memory_v2_phase1.sql`
- `supabase/rollback/20260820110600_create_memory_v2_phase1_rollback.sql`
- `supabase/tests/memory_v2_phase1.sql`
- `.github/workflows/memory-v2-phase1.yml`
- `docs/02_当前架构.md`
- `docs/06_待开发列表.md`
- `docs/changes/2026-08-20_MemoryV2Phase1最小旁路.md`

## 数据库、环境变量与部署

- 新 migration 只创建 `memory_v2_*` 对象，不读取、修改或删除任何 V1/canonical/legacy 表、RPC 或数据。
- 5 张新表全部 FORCE RLS；public/anon/authenticated 无表权限或 RPC execute，旁路只预留 server-side `service_role` RPC 调用。
- 没有新增环境变量；embedding adapter 通过构造参数可选注入，未配置时直接 lexical。
- 未连接或修改生产 Supabase，未部署 Bridge，未重启 PM2，未修改 Worker/Nginx/OAuth/MCP。
- 本机没有 Supabase CLI/Docker；临时 `npx supabase` 未能完成下载，因此 migration 文件按仓库既有 UTC 时间命名规则创建。数据库 fresh/rollback/re-apply 由新增 disposable CI 执行。

## 验证

- Memory V2 定向测试：11/11 通过，覆盖 AI/human 权重、Starter Pack 三类选择、降级/恢复状态。
- Bridge 全量测试：172/172 通过。
- 前端 lint：通过，仅仓库既有 warnings。
- 前端 build：通过，仅既有大 chunk 提示。
- Node syntax 与 `git diff --check`：通过。
- Disposable Supabase：fresh apply、中文拆分短语召回/无关中文排除、行为测试、rollback、re-apply 与 DB lint 全部通过（Actions run `32367681966`）。

## 风险、回滚与下一步

- 本阶段没有接入现有 server/MCP，因此只是可测试旁路，不是用户可见上线。
- `current_time` 的 `+08:00` 是当前默认 server context；未来若 owner timezone 可配置，应只替换构造参数，不增加时间服务。
- Starter Pack 使用轻量 token 估算而不是 Token Meter；它是硬停止预算，但不是模型厂商账单计数器。
- rollback SQL 只删除 `memory_v2_*` 函数和五张旁路表，不触碰 V1。
- 下一步只等待人工复审；不自行接 MCP、迁移 V1、部署或开启生产切流。
