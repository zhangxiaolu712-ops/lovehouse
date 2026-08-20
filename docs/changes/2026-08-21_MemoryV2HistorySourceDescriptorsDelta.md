# Memory V2 history source descriptors delta

- 日期：2026-08-21
- 执行者：Codex
- 状态：本地待审；未 push、未 PR、未 merge、未部署

## 做了什么

- 新增极小 delta migration，以同名、同参数、同 `jsonb` 返回类型替换 `memory_v2_history`。
- 每个原 revision JSON 新增按 `ordinal, source_id` 排序的 `sources` 数组；descriptor 仅包含 ID、kind、locator、provenance、ordinal，最多 101 条。
- history 不返回 `quote_text`，真实原文仍只由既有 `memory_v2_expand_source(source_id)` 显式展开。
- 新增精确 rollback，恢复 Phase 1 原始的 revision-only history JSON。
- 新增数据库行为/权限测试、SQL contract 测试，并让既有 disposable Supabase workflow 覆盖 fresh apply、delta rollback/re-apply 与完整 Phase 1 rollback/reinstall。

## 修改文件

- `supabase/migrations/20260820163417_memory_v2_history_source_descriptors.sql`
- `supabase/rollback/20260820163417_memory_v2_history_source_descriptors_rollback.sql`
- `supabase/tests/memory_v2_history_source_descriptors.sql`
- `.github/workflows/memory-v2-phase1.yml`
- `bridge/memory-v2/historyMigration.test.js`
- `bridge/memory-v2/repository.test.js`
- `bridge/memory-v2/service.test.js`
- `docs/02_当前架构.md`、`docs/06_待开发列表.md` 与本记录

## 数据库、权限与部署

- 没有修改历史 migration；没有新增表、RPC、字段、index、trigger、policy、RLS、service、queue 或 worker。
- `memory_v2_history` 继续是 `security invoker`、`stable`、`search_path=pg_catalog, public`；anon/authenticated/PUBLIC 继续无执行权，service_role grant 不变。
- actor/owner/private/approved Shared predicate 保持原样。descriptor 来源还要求 source owner 与调用 owner 一致。
- 未连接或修改生产 Supabase；未部署 Bridge、未重启 PM2、未修改 env/MCP/OAuth/Claude session/LivingRoom。

## 验证

- 数据库测试覆盖：空 source、单 source、多 source ordinal、多 revision 独立关联、原字段保留、quote 不泄露、GPT/Claude cross-private、approved Shared、history source ID 显式 expand。
- 本机无 Supabase CLI、Docker 或 PostgreSQL client，账号也没有现成 disposable branch；因此 disposable DB workflow 在本地 commit 前无法实际执行，禁止写成已通过。未来 branch push 后才可由既有 CI 运行。
- SQL contract + Repository/Service 定向测试 18/18 通过；Bridge 全量 185/185 通过。
- frontend lint/build 通过，仅有既有 warning；`git diff --check` 通过。
- Bridge npm 下载受限网络阻断；全量测试复用了同仓库上一 worktree 已安装的相同 `bridge/package.json` 依赖，没有修改依赖声明或 lockfile。

## 风险与下一步

- SQL 的真实 PostgreSQL 执行仍须 disposable Supabase CI 终态验证；验证前不得应用生产。
- delta 审过并合并后，才返回独立 MCP 分支继续 7-tool 收窄。
