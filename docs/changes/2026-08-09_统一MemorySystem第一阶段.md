# 统一 Memory System 第一阶段

日期：2026-08-09

状态：`READY_FOR_REVIEW` / `NO_DATABASE_CHANGE` / `NO_PROD_CHANGE` / `NO_DEPLOY`

分支：`agent/memory-system-foundation-20260809`

## 做了什么

- 从最新 `main` 新建独立分支，没有直接合并 CC 的 32 个历史提交。
- 选择性复用 CC 已完成的 GPT SSE MCP、Claude Streamable HTTP MCP、OAuth/PKCE/签名令牌、小客厅工具和 Supabase REST wrapper。
- 将 CC 原来集中在 `bridge/server.js` 的 OAuth 与 MCP 工具拆成可测试模块。
- 新建唯一的 `MemoryAccessPolicy`、`MemoryService` 与 `MemoryRepository`。
- 建立 GPT、Claude、Shared、Legacy Pending 四个空间常量和固定 actor 权限矩阵。
- 旧 MCP 工具名继续保留，但 `save_memory`、`recall`、`load_memories`、`search_memories`、`save_to_memories` 全部转调同一个 `MemoryService`，不再直接读写 `brain` 或 `memories`。
- Repository 只面向未来唯一规范表 `memory_entries`；当前生产没有该表，因此使用 `MEMORY_SYSTEM_ENABLED=false` 默认关闭并 fail closed。
- 将日记、文章、感受、小事记、备忘录、问心、语录、总结、观点等旧结构提炼成稳定的 `memory_type`；统一入口同时规范 tag、emotion、importance、retention、decay、source 与 revision 基础字段。
- 新增不记录正文的审计事件边界；第一阶段使用空 sink，后续 migration 接入 append-only 审计表。
- 新增 26 项 Node 自动测试，覆盖所有第一阶段权限边界。

## 为什么这样做

GPT 与 Claude 需要独立记忆体，但不能维护两套逐渐漂移的记忆规则。MCP 只负责确认入口身份，所有 namespace、Shared approval 与 Legacy Pending 规则必须集中在服务端唯一 Memory System 中。

结构迁移与历史正文迁移必须分开。现有 `brain`、`memories` 以及其他旧表保留重要结构与来源，但旧正文不能根据语义猜测归属，也不能默认成为 Shared。本阶段先把权限和代码边界搭正确，不建立生产表、不搬正文。

## 新架构图

```text
GPT SSE MCP ───── actor=gpt ────┐
                                ├→ AccessPolicy → MemoryService → MemoryRepository → Supabase
Claude HTTP MCP ─ actor=claude ─┘

MemoryService（唯一规则层）
├── GPT Memory
├── Claude Memory
├── Shared Memory（仅 approved 可被双方读取）
└── Legacy Pending（默认不进入日常 recall）
```

MCP schema 不暴露 namespace 参数；AccessPolicy 还会递归拒绝伪造的 `space_key`、`spaceKey`、`namespace`、`space`、`actor` 等字段。Repository 即使错误返回所有空间，MemoryService 仍会二次过滤。

## 权限矩阵

| Actor | 读/写 GPT | 读/写 Claude | 读 approved Shared | 读 pending/revoked Shared | 写 Shared | 读 Legacy Pending |
|-------|------------|---------------|----------------------|----------------------------|-------------|---------------------|
| GPT | ✅ / ✅ | ❌ / ❌ | ✅ | ❌ | ❌ | ❌ |
| Claude | ❌ / ❌ | ✅ / ✅ | ✅ | ❌ | ❌ | ❌ |

Shared 的提出、批准、撤销和 Legacy 整理将由后续主人/Curator 工具处理，普通 GPT/Claude MCP 不获得直接写 Shared 的权限。

## 复用与废弃

### 选择性复用

- CC 的 GPT SSE MCP transport 与 JSON-RPC 处理方式。
- CC 的 Claude Streamable HTTP MCP endpoint。
- CC 的 OAuth 动态注册、授权码、PKCE S256、HMAC 签名令牌与验证思路。
- CC 的三个小客厅工具和固定发送者身份。
- CC 的 Supabase REST 服务端连接方式，以及新旧 Supabase server key 兼容处理。
- CC 的安全测试思路，并扩充为权限边界测试。

### 停止继续使用

- MCP handler 直接查询、PATCH 或 INSERT `brain`。
- MCP handler 直接查询或 INSERT `memories`。
- 让 `brain` 与 `memories` 各自发展独立的加载、搜索、写入规则。
- 根据调用参数决定 actor 或 namespace。
- 将旧正文默认设为 Shared，或由 Codex 根据语义猜 GPT/Claude 归属。

旧工具名只是兼容门面，不代表保留旧的双引擎实现。

## Diff 摘要与涉及文件

### Bridge 与统一记忆层

- `bridge/server.js`：接回双 MCP 与小客厅，固定 actor，接入唯一 MemoryService；默认 fail closed。
- `bridge/oauth.js`：从 CC 单体 Bridge 中提取 OAuth/PKCE 流程。
- `bridge/security.js`：签名令牌、PKCE、redirect URI 与常量时间比较。
- `bridge/security.test.js`：OAuth 安全单元测试。
- `bridge/mcp/tools.js`：9 个兼容工具的统一 adapter 与固定 actor。
- `bridge/mcp/tools.test.js`：MCP schema、actor 和兼容转调测试。
- `bridge/memory/model.js`：actor、四空间、Shared 状态、memory type。
- `bridge/memory/accessPolicy.js`：访问矩阵与伪造 namespace 拒绝。
- `bridge/memory/audit.js`：只记录访问元数据的审计 sink 合约，不记录记忆正文。
- `bridge/memory/service.js`：唯一写入、读取、检索与结构规范化入口。
- `bridge/memory/repository.js`：未来唯一 `memory_entries` 表的 Repository 合约。
- `bridge/memory/index.js`：统一导出。
- `bridge/memory/memory.test.js`、`repository.test.js`：跨空间与 Repository 测试。
- `bridge/package.json`：增加 Bridge 测试命令；未增加第三方依赖。

### 文档

- `docs/02_当前架构.md`：更新统一 Memory System、权限矩阵、Legacy Pending 与真实 P0 状态。
- `docs/ARCHITECTURE_CURRENT.md`：更新 Bridge/MCP 分层、兼容工具职责、环境开关与迁移禁令。
- `docs/06_待开发列表.md`：标记第一阶段代码地基完成，拆出 migration 与整理工具任务。
- `docs/changes/2026-08-09_统一MemorySystem第一阶段.md`：本记录。

## 数据库、环境变量、部署与现实设备

- 没有执行 Supabase SQL，没有调用 migration，没有修改任何表、数据、RLS 或正文。
- 没有迁移或读取历史记忆正文。
- 没有部署 VPS、Cloudflare 或 GitHub Pages。
- 没有修改生产密钥或生产环境变量。
- 新代码认识 `MEMORY_SYSTEM_ENABLED`，但生产未配置；默认 `false`，记忆工具会明确失败而不是回退到旧表。
- Toy/ADB 链路未读取、未修改、未测试。
- P0 RLS/public exposure 修复仍是独立 PR，不包含在本次 diff。

## 测试结果

- `cd bridge && npm.cmd test`：26 项通过，0 失败。
- `npm.cmd run lint`：通过；只有仓库原有 warning，本次没有新增 lint error。
- `npm.cmd run build`：通过；保留现有主包大于 500 kB 提示。
- `git diff --check`：通过。
- 未执行数据库集成测试：第一阶段明确不建表、不连接生产记忆正文。
- 未执行线上冒烟：本阶段明确不部署。

权限测试包括：GPT/Claude 写入自己的空间、双方读取 approved Shared、双方互读私有空间失败、pending Shared 失败、Legacy Pending 日常 recall 与直接读取失败、顶层及嵌套伪造 namespace 失败、Repository 返回越界数据时服务层二次过滤。

## 已知风险

- `memory_entries` 尚未建立，所以本分支的记忆 MCP 处于 fail-closed 状态；在 migration 完成前禁止开启 `MEMORY_SYSTEM_ENABLED`。
- 已合并但未执行的 `20260808174047_memory_namespace_v1.sql` 会把旧正文默认设为 Shared，与最终确认版冲突，禁止直接应用。
- 生产 `brain`、`livingroom` 与 Dreaming 表仍有已知 P0 RLS/public exposure 问题，必须由独立安全 PR 解决。
- Bridge 使用服务端 key 时可绕过 RLS，所以后续除了数据库策略，还必须保留服务层二次权限过滤与反向测试。
- OAuth 客户端注册仍保存在进程内存，令牌仍缺少撤销机制；第一阶段保持 CC 兼容实现，后续单独加固。
- 当前 Repository 的第一版搜索合约仍是文本检索；相关性排序、embedding、潮汐任务与审计落表属于后续阶段。

## 下一阶段 migration 建议

1. 不直接应用旧 namespace 草稿；新建后续 migration，保留旧文件作为已审阅历史。
2. 建立唯一规范表 `memory_entries`，包含 `space_key`、`memory_type`、tags、emotion、importance、decay、source、revision、shared_status 与审计所需字段。
3. 建立 `gpt`、`claude`、`shared`、`legacy_pending` 四个空间及必要约束；普通 MCP 不获得切换空间能力。
4. 结构迁移先完成，但不复制旧正文。
5. 单独建立 Legacy 导入/映射表或受控导入过程，保留：`original_table`、`original_id`、`original_created_at`、`legacy_source`、旧 `author/source_model`。
6. 旧正文进入 Legacy Pending 后默认不能 recall，禁止自动 Shared；由小婷或授权 Curator 分批整理。
7. Shared 使用 pending → approved/rejected/revoked 状态机，并记录批准者、时间、来源和修订历史。
8. 增加访问审计表或受控 RPC，记录 actor、动作、目标空间、允许/拒绝和请求来源。
9. 在临时 Supabase 分支完成正向/反向 RLS 与 service-layer 集成测试，再讨论生产 migration。
10. P0 owner-only RLS/public exposure 继续独立 PR，不能与上述 schema migration 合并。

## 回滚

本阶段没有数据库或部署变化。代码回滚只需撤销本分支提交；生产数据不受影响。由于默认开关为 false，即使误启动本分支 Bridge，也不会回退读取旧 `brain`/`memories` 内容。
