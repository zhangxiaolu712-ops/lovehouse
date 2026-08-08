# 统一 Memory System 第二阶段数据库结构

日期：2026-08-09

状态：`DRAFT` / `ZERO_COST_VALIDATED` / `NO_PROD_CHANGE` / `NO_DEPLOY`

分支：`agent/memory-system-schema-phase2-20260809`

## 做了什么

- 新建唯一规范 schema：GPT、Claude、Shared、Legacy Pending 四个空间共用 `memory_entries` 与统一规则。
- 新增 type 目录、不可变 revision、provenance、Shared 状态流水、持久化 audit、Dreaming/整理 candidate 表。
- 新增数据库触发器，阻止静默覆盖、旧来源丢失、直接 approved Shared 与非法 Shared 状态跳转。
- 新增固定 actor 的 GPT/Claude 数据库 RPC；Repository 读取不再依赖手写 `WHERE space_key`。
- Repository 强制服务端注入 owner；新增持久化审计 sink 合约，但没有在生产 Bridge 启用。
- 增加 SQL 权限/生命周期测试、显式 rollback 和零付费 GitHub Actions 临时 Supabase 验证流程。
- 完整设计、映射、权限和风险见 `docs/MEMORY_SYSTEM_PHASE2_SCHEMA.md`。

## 为什么

第一阶段证明了 MCP actor 与应用层权限边界；第二阶段需要让数据库本身也具备规范模型、来源链和不可变审计。service key 会绕过 RLS，因此读取通过固定 actor RPC 再过滤，不能仅依赖开发者每次记得手写空间条件。

## 费用与生产边界

- 没有创建 Supabase Branch，没有绑定/修改支付方式，预计 Supabase Branch 费用为 0。
- 没有连接或修改生产数据库，没有读取/迁移旧正文。
- 没有部署 VPS/Cloudflare，没有切生产流量，没有修改密钥。
- `MEMORY_SYSTEM_ENABLED` 未开启；生产写入仍为 false。
- Toy/ADB 未读取、未修改、未测试。

## 当前验证

- `cd bridge && npm.cmd test`：43 项通过，0 失败。
- `npm.cmd run lint`：无 error；仅仓库既有 warning。
- `npm.cmd run build`：通过；保留既有 bundle size warning。
- `git diff --check`：通过。
- 免费临时 Supabase：[run 31274614880](https://github.com/zhangxiaolu712-ops/lovehouse/actions/runs/31274614880) 通过，fresh install、SQL 权限测试、`db lint`（No schema errors）、rollback、清空断言、re-apply 与复测全部成功；临时容器已停止并删除。

## 下一步

1. 保持 Draft，不 merge、不 deploy。
2. 由工程师审阅 schema、权限矩阵、旧字段映射和 rollback。
3. P0 RLS 修复继续独立 PR；Legacy 正文整理继续后置。
