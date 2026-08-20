# Memory V2 Phase 2B 迁移 canonical V1

- 日期：2026-08-20
- 执行者：Codex
- 状态：生产旁路数据迁移与核对已完成；未切换任何业务链路

## 做了什么

- 新增一次性、可重复执行且默认 dry-run 的 data migration script，只迁移 active canonical private 与 approved Shared。
- 先以 canonical 四表内容指纹锁定 dry-run 快照，再在单事务中执行；指纹变化会 fail closed。
- GPT private、Claude private 的完整 revision 历史按原序迁入；approved Shared 在其确定 private origin revision 存在后迁入。
- canonical source 与确定 revision 的 link 一并迁入；candidate Shared 不升级、不映射，继续原样保留在 V1。
- 用 revision metadata / source provenance 中的 legacy marker 实现幂等，不新增 mapping 表。

## 修改文件

- `supabase/data_migrations/20260820_migrate_canonical_v1_to_memory_v2.sql`：默认 dry-run、显式 apply、内容指纹保护、幂等迁移与结果报告。
- `docs/02_当前架构.md`：记录 Phase 2B 生产旁路数据状态。
- `docs/06_待开发列表.md`：标记 Phase 2B canonical 数据迁移已完成但仍未切流。
- `docs/changes/2026-08-20_MemoryV2Phase2B迁移CanonicalV1.md`：本记录。

## 数据库、环境变量与部署

- 生产 V2 新增 52 条 canonical 映射 entry：GPT private 28、Claude private 23、approved Shared 1。
- 新增 55 条 revision：GPT 30、Claude 24、approved Shared 1；15 个 source 全部有映射，18 个 eligible revision-source link 完整迁入。
- canonical V1 的 candidate Shared 1 条、其 revision 1 条及 link 1 条未迁为 Shared，仍只存在于 V1。因此 canonical V1 总量仍为 53 entry、56 revision、15 source、19 link。
- Phase 2A smoke 证据保持 7 entry / 8 revision；`brain` 380 行、旧 `memories` 21 行未变。
- 没有新增或修改 schema/RPC/RLS，没有修改环境变量，没有部署或重启 Bridge，也没有接入 MCP、Chat、frontend 或 Embedding。

## 验证结果

- dry-run：GPT 28、Claude 23、approved Shared 1、revision 55、source 15、eligible link 18；candidate Shared 1/1/1 明确跳过。
- 事务回滚演练：revision/content/created_at/current/source/link/Shared origin 全部 0 mismatch；跨 private 读取为 0；Starter Pack 非 current 为 0。
- 正式迁移后：revision 顺序与正文 0 mismatch，current revision 0 mismatch，history 无断号，普通 recall 与 Starter Pack 均未返回旧 revision。
- GPT/Claude private history 双向隔离通过，approved Shared 双方可读；private-only source 跨 actor `expand_source` 被拒绝。
- apply 模式二次事务回滚验证已存在 52/55/15/18，未产生重复映射。

## 已知风险或未完成事项

- 工单中的“56 个 revision、19 个 link 全部对应”与“candidate Shared 不迁成 Shared”在现有数据上互相冲突：第 56 个 revision 与第 19 个 link 正属于 candidate Shared。本次严格遵守 candidate 禁令，将它们保留在 V1，没有发明 private 身份。
- V2 仍是生产旁路；现有 V1/MCP/Chat 读写流量没有切换。

## 下一步计划

1. 停止并等待 Phase 2B 复审；不自行切流、双写、迁 Brain/旧 memories 或退休 V1。
