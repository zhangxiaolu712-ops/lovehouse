# 统一 Memory System 第三阶段 Runtime

- 日期：2026-08-09
- 执行者：Codex
- 状态：Draft PR #26 已创建并通过免费验证；未合并、未部署

## 做了什么

- 从合并 PR #25 后的最新 `main` 新建独立 Phase 3 分支。
- 新增固定 GPT/Claude actor 的 Runtime 事务 RPC；Bridge service role 不再直接访问规范记忆表或 Phase 2 原始内部 RPC。
- 把主动保存、读取/检索、修订、来源链、持久化审计和幂等接入同一个 `MemoryService` / Repository。
- 原有 9 个 MCP 工具保持兼容，新增单条读取、修订、Shared 推荐 3 个低字段工具。
- GPT SSE 与 Claude Streamable HTTP 使用相同 Runtime；actor 与 request id 均由已认证服务端 transport 注入。
- 新增免费一次性 Supabase 的 fresh/rollback/reinstall/SQL 测试流程，并覆盖审计失败整笔回滚。

## 为什么

- 让 GPT 与 Claude 可以主动记住、想起和修订自己的记忆，但不能跨越私有边界。
- 把“开发者记得加 WHERE”和“先写正文再补审计”改成数据库可验证的硬约束。
- 不增加 AI 的日常调用步骤：AI 只传内容、查询、memory id 和理由，revision/hash/owner/space/权限/幂等字段全部隐藏在 Bridge 与数据库内。

## 主要文件

- `supabase/migrations/20260808215808_memory_runtime_phase3.sql`
- `supabase/rollback/20260808215808_memory_runtime_phase3_rollback.sql`
- `supabase/tests/memory_runtime_phase3.sql`
- `.github/workflows/memory-runtime-phase3.yml`
- `bridge/memory/*`
- `bridge/mcp/*`
- `bridge/server.js`
- `docs/MEMORY_SYSTEM_PHASE3_RUNTIME.md`

## 验证

- Bridge：63 项测试通过，包含真实 GPT SSE 与 Claude HTTP transport。
- 前端：lint 通过（仅既有 warning）；build 通过（仅既有 bundle-size warning）。
- `git diff --check`：通过。
- 免费临时 Supabase：GitHub Actions `runtime` 全绿；fresh install、Phase 2 冻结契约、Phase 3 SQL、rollback/re-apply、完整卸载重装均通过，任务结束后临时栈已删除。
- 回归检查：既有 `Memory System schema` 的 `validate` 全绿；Cloudflare 仅完成既有的非生产分支预览构建，未推广版本、未切生产流量。
- 验证记录：`runtime` run `31281943239`（2m01s），`validate` run `31281943233`（2m22s）。

## 数据、环境与部署

- 未连接、读取或修改生产 Supabase；未导入任何历史正文。
- 未修改生产密钥、VPS、Cloudflare、支付方式或 Supabase Branch。
- 未部署，未开启 `MEMORY_SYSTEM_ENABLED`。
- 未读取或修改 Toy/ADB 链路。

## 下一步

1. 保持 Draft PR #26，等待工程审阅。
2. 只修本 PR 的 Runtime、SQL、权限、测试问题，不顺手加潮汐/向量检索/Legacy 整理等功能。
3. 未经新的生产变更授权，不合并、不部署、不打开开关。
