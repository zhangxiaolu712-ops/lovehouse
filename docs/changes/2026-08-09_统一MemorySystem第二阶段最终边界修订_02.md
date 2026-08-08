# 统一 Memory System 第二阶段最终边界修订

- 日期：2026-08-09
- 执行者：Codex
- 状态：本地验证完成，待免费临时数据库最终验证

## 做了什么

- 新增最小可信 Curator 数据库入口：只允许 Bridge 服务角色从确定的 private revision 创建 Shared candidate。
- 新增最小可信 Owner 数据库入口：Owner 身份只取认证 JWT 的 `auth.uid()`，服务角色不能代替 Owner 审批。
- Curator/Owner 入口均不接受 actor、space、状态伪造字段、正文或客户端 hash；数据库触发器拒绝绕过 RPC 的直接表写。
- 收紧 `memory_revisions`：Bridge 服务角色只有读取权限，revision 只能由 `memory_entries` 历史触发器生成。
- 扩展 Bridge 保留字段检查，拒绝 owner、permission、revision/hash、source revision/hash、request hash 等 AI 工具参数。
- 将旧 V1 migration 移出 active migrations，放入 `supabase/retired_migrations/` 并增加手工执行也会失败的保护。
- 更新 SQL 权限测试、GitHub 免费临时 Supabase 工作流与架构文档。

## 为什么这样做

- Curator 推荐共享与 Owner 最终决定必须是两种不同的可信身份，不能只依赖调用方自报 actor。
- private revision 是记忆历史的事实依据，不能允许 Bridge 或 AI 直接伪造。
- 旧 V1 包含“旧正文默认 Shared”的冲突逻辑，继续留在 active migration 目录存在误执行风险。
- 上述安全边界必须隐藏在 Bridge/数据库内部，不能增加 GPT/Claude 日常记忆的字段、调用次数或 token 成本。

## 修改文件

- `supabase/migrations/20260808191311_create_unified_memory_system_v2.sql`：可信身份入口、revision 写保护与函数授权。
- `supabase/rollback/20260808191311_create_unified_memory_system_v2_rollback.sql`：对应回滚对象。
- `supabase/tests/memory_system_phase2.sql`：身份冒充、revision 伪造、状态机与权限矩阵测试。
- `supabase/retired_migrations/20260808174047_memory_namespace_v1.sql`：退役历史副本与失败保护。
- `.github/workflows/memory-system-schema.yml`：断言 V1 不得回到 active migrations。
- `bridge/memory/accessPolicy.js`、`bridge/memory/memory.test.js`、`bridge/mcp/tools.test.js`：内部字段拒绝与 AI-facing 接口不变测试。
- `docs/MEMORY_SYSTEM_PHASE2_SCHEMA.md`、`docs/02_当前架构.md`、`docs/ARCHITECTURE_CURRENT.md`、`docs/06_待开发列表.md`：同步当前契约。

## 数据库、环境变量与部署

- 未连接、读取或修改生产 Supabase；未迁移任何历史正文。
- 未修改环境变量、生产密钥或支付方式，未创建付费 Supabase Branch。
- 未部署 VPS/Cloudflare，未切生产流量，未开启 `MEMORY_SYSTEM_ENABLED`。
- 未触碰 Toy/ADB。

## 验证结果

- 已执行：Bridge 50 项测试通过，0 失败；frontend lint 通过（只有仓库既有 warning）；frontend build 通过（只有既有 bundle size warning）；`git diff --check` 通过。
- 待执行：免费 GitHub Actions 临时 Supabase 的 fresh migration、SQL 测试、lint、rollback、reinstall。

## 已知风险或未完成事项

- 数据库对象所有者仍是迁移管理的可信根；应用角色不能获得该身份。
- Curator RPC 的 owner/revision 参数仅供未来 Bridge 内部注入，不得暴露为 AI-facing 参数。
- Phase 3 Runtime、AI 主动保存/召回、Shared UI、persistent audit sink 均未实现。

## 下一步计划

1. 完成零费用临时 Supabase 全量验证并修复本 PR 的数据库契约问题。
2. 保持 PR #25 不合并、不部署，交回最终工程审阅。
3. 仅在 #25 审阅通过后，才从最新 main 单独启动 Phase 3。
