# Engineering Memory Foundation V1

- 日期：2026-08-25
- 分支：`agent/engineering-memory-foundation-v1-20260825`
- 基线：`origin/main@6c8c47505dbc1f6d4483097dc2badb93dedfc265`
- 状态：PR 1 本地实现与 disposable Supabase 验证完成；未部署生产，未开始 Workspace UI

## 做了什么

- 在现有 5 张 Memory V2 表内增加独立 `engineering` space，没有创建第二套 Memory 表。
- `memory_v2_entries` 增加仅供 Engineering 使用的稳定 `subject_key`；同一 owner + subject 由 partial unique index 保证唯一。
- 增加 6 个 service-role-only、`security invoker` RPC：upsert、recall、open、expand source、archive、restore。
- upsert 以 subject 为核心：首次创建 Entry/revision 1；后续变化 append revision 并更新 current pointer；相同正文且无显式 metadata/source 变化时 no-op。
- GPT、Claude、Codex、Owner 共用独立 `EngineeringMemoryService` fixed-actor facade；普通 `MemoryV2Service` 仍只接受 GPT/Claude。
- Owner-authenticated `/api/v1/engineering-memory*` Client API 为后续 UI 提供 upsert/list/open/source/archive/restore；客户端不能选择 actor。
- Engineering 第一版只使用 bounded lexical 检索；未接 Ollama、worker、queue 或 backfill。
- 已核对现有 embedding backfill 只遍历 GPT/Claude ordinary recall；因为该 SQL 不包含 Engineering，现有生活 backfill 无需修改。

## 权限与隔离

- 5 张 Memory V2 表继续 FORCE RLS，anon/authenticated/public 没有表或 Engineering RPC 权限；只有现有 server-side `service_role` 获得新 RPC execute。
- Engineering source 与普通 GPT/Claude private source 继续按 `space_key` 隔离；history 只返回 descriptor，不含 `quote_text`。
- Codex 只被 Engineering facade/RPC 接受，不能调用普通 remember/history/recall 读取 GPT/Claude private。
- 普通 recall、semantic recall、Starter Pack、wake_up、approved Shared RPC 与 7-tool MCP definition 均未修改，因此 Engineering 不会进入生活记忆或日常 Chat。
- archive/restore 仅 Owner；没有物理删除入口，revision/source 历史保留。

## 修改文件

- `supabase/migrations/20260825100514_create_engineering_memory_space_v1.sql`
- `supabase/rollback/20260825100514_create_engineering_memory_space_v1_rollback.sql`
- `supabase/tests/memory_v2_engineering_space_v1.sql`
- `bridge/memory-v2/engineering.js`、`engineering.test.js`
- `bridge/memory-v2/repository.js`、`repository.test.js`、`index.js`
- `bridge/client-api/clientApi.js`、`clientApi.test.js`
- `bridge/server.js`
- `.github/workflows/memory-v2-phase1.yml`
- `docs/02_当前架构.md`、`docs/06_待开发列表.md` 与本记录

## 数据库、环境变量与部署

- 新增 delta migration；没有修改任何已上线历史 migration。
- 没有新增表、embedding、环境变量、MCP tool、Prompt 或 Runtime Adapter。
- rollback 会先拒绝存在 Engineering 数据的情况，避免静默删除；空空间时可恢复旧 CHECK/actor constraints 并移除新 RPC/index/column。
- 未连接或修改生产 Supabase，未部署 Bridge，未重启 PM2，未改 Nginx/Worker/Cloudflare。

## 验证

- Node 定向测试 21/21 通过，覆盖 Repository、Engineering Service、Owner Client API 与普通 actor boundary。
- fresh disposable Supabase 已执行 Phase 1 + approved Shared embedding delta + history descriptor delta + Engineering delta。
- DB 行为覆盖生活 recall/Starter Pack 隔离、四 actor 工程写入、Codex private 拒绝、subject revision/no-op、未知 category、history/source、archive/restore、approved Shared 回归与跨 owner 拒绝。
- Engineering delta rollback、完整 Memory V2 rollback、结构核验、re-apply 与 DB lint 已通过；DB lint 0 error。
- Bridge 全量 210/210 通过。
- Frontend lint 通过（仅既有 warning）；production build 通过（仅既有 chunk/dynamic-import warning）。
- `git diff --check` 通过。

## 风险与下一步

- PR 1 只建立 Foundation；没有前端分类页，也没有把 Engineering 暴露进 7-tool MCP。
- `subject_key` 是稳定身份，未来 UI 必须编辑同一 subject，而不是不断创建新 Entry。
- 等 PR 1 复审后才进入独立 PR 2 `Engineering Workspace UI`；不得在本分支顺手施工 UI。
