# Memory V2 Phase 2A 生产旁路验活

- 日期：2026-08-20
- 执行者：Codex
- 状态：生产旁路 schema 与一次性 smoke 已完成；未切换任何现有业务链路

## 做了什么

- 从最新 `main` 建立独立 Phase 2A 分支，先只读确认生产项目、V2 空白状态、V1/Brain/canonical Memory 资产及生产 Bridge 旧链路。
- 将已合并的 `20260820110600_create_memory_v2_phase1.sql` 原文应用到预期生产 Supabase；Supabase 实际 migration 记录为 `20260820125305_create_memory_v2_phase1`。
- 通过一次性、已删除的 VPS 临时 harness，使用生产 server-side Supabase 凭据直接调用 `MemoryV2Service` 与 `SupabaseMemoryV2Repository`，保留前缀 `PHASE2A_SMOKE_20260820_1787230600605` 的少量验活数据。
- 验证 GPT/Claude private、approved Shared、revision/history、currentness、superseded 排除、Starter Pack、manual quote source/expand 与 lexical fallback。
- 真实 smoke 发现并修复 Repository 将 PostgREST 顶层 JSON 数组误拆成首项的问题；修复只保留 RPC payload 原形，不改变 schema、Service contract 或现有业务链。

## 修改文件

- `bridge/memory-v2/repository.js`：不再误拆 JSON 数组 RPC payload。
- `bridge/memory-v2/repository.test.js`：覆盖 JSON 数组与对象两种 RPC 返回形状。
- `docs/02_当前架构.md`：记录 Phase 2A 生产旁路状态。
- `docs/06_待开发列表.md`：标记 Phase 1 / 2A 已完成且未切流。
- `docs/changes/2026-08-20_MemoryV2Phase2A生产旁路验活.md`：本记录。

## 数据库、环境变量与部署

- 生产新增 5 张 `memory_v2_*` 表与 11 个 `memory_v2_*` RPC；全部来自已验收 migration，没有现场修改 SQL。
- 5 张表均启用并强制 RLS；`public`/`anon`/`authenticated` 无表权限，11 个 RPC 仅授予 `service_role` 执行。
- 保留 7 条 V2 entry、8 条 revision、1 条 source 与 2 条 revision-source link 作为首次生产验活证据；没有 embedding 行。
- V1 `memories`、`brain`、canonical `memory_entries` / `memory_revisions` 行数前后不变。
- 没有修改任何生产环境变量，没有重启 PM2，没有部署或修改 Bridge/MCP/Chat/frontend。

## 验证结果

- 生产 smoke：remember、recall、revise、history、Starter Pack、source/expand、GPT/Claude private 双向隔离、approved Shared 双方可见、current revision、superseded 排除全部通过。
- Embedding 未注入旁路 Service；recall 返回 `mode=lexical_fallback` 与 `semantic_error=embedding_not_configured`，remember/recall 不受影响。
- 强制 Starter Pack 失败后，错误显式返回，普通 recall 仍成功。
- 生产 Bridge 检查：PID 与启动时间不变，`/health` HTTP 200，实际 release 仍为 `3d4da7d8b788f62d3fac269e698d05a031199a51`，运行代码没有 Memory V2 注册。
- Repository/Memory V2 定向测试 14/14、Bridge 全量测试 175/175、syntax 与 `git diff --check` 全部通过。

## 已知风险或未完成事项

- Phase 2A 只证明生产 V2 旁路独立可用；当前 GPT/Claude MCP、Chat、前端与 Starter Pack 启动链仍使用原链路。
- `bridge/memory-v2/repository.js` 的返回形状修复需先合并，未来才能安全接入任何常驻 Runtime；本轮未部署它。
- Embedding 未作为本阶段阻塞项，生产旁路当前只验证 lexical fallback。

## 下一步计划

1. 停止并等待 Phase 2A 审核；不自行开始 Phase 2B、数据迁移、双写或 MCP 切换。
