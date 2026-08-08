# Memory System Phase 3：Runtime Integration

> 状态：独立 Draft PR；未部署、未连接生产 Supabase、未开启 `MEMORY_SYSTEM_ENABLED`。

## 1. 运行链

```text
GPT SSE MCP ── 固定 actor=gpt ───┐
                                  ├─ AccessPolicy ─ MemoryService ─ Repository
Claude HTTP MCP ─ 固定 actor=claude ┘                              │
                                                                   ▼
                                                     固定 actor 事务 RPC
                                                                   │
                                                                   ▼
                memory_entries / revisions / provenance / audit / idempotency
```

- actor 只来自服务端创建的 MCP channel；body、query、header 和 tool args 都不能指定 actor。
- `OWNER_USER_ID` 只由 Bridge 注入。AI 不传 owner、space、namespace、revision、hash、request id、权限或 Shared 状态。
- Bridge 的 service role 不再拥有新记忆表的直接权限，也不能执行 Phase 2 的原始读门、hash、幂等或 Curator RPC；它只获得固定 GPT/Claude Runtime RPC。
- 普通查询在 SQL 的第一层就只选择“actor 自己的 private + approved Shared”。Legacy Pending 与未批准 Shared 不进入候选集合。

## 2. AI-facing 工具

原有 9 个工具名称和基本参数保持兼容：

- 小客厅：`read_livingroom_messages`、`send_livingroom_message`、`get_livingroom_context`
- 记忆：`get_starter_pack`、`save_memory`、`recall`
- 旧名兼容：`load_memories`、`search_memories`、`save_to_memories`

新增 3 个必要工具：

- `get_memory(memory_id)`：读取一条有权访问的记忆。
- `revise_memory(memory_id, reason, ...changes)`：修订自己的 private memory；revision/provenance/audit 自动生成。
- `propose_shared_candidate(memory_id, reason)`：Curator 推荐当前确定 revision；AI 无批准权。

普通保存只要求 `content`。AI 不需要也不能传 revision/hash/owner/actor/space/idempotency 字段。默认 recall 5 条、硬上限 10；默认 list 20 条、硬上限 50；starter pack 默认 10 条。分页只使用上一页最后一条的 `id` 作为 cursor，不增加权限选择能力。

## 3. 事务与幂等

`remember`、`revise`、`propose_shared` 均由数据库事务 RPC 完成：

1. 以 `(owner_id, actor, operation, request_id)` 领取幂等声明；request hash 由数据库从规范 JSONB 计算。
2. 同 request id + 同 hash 返回原资源；同 request id + 不同 hash 以冲突失败。
3. 写入正文；Phase 2 历史触发器同步生成 revision 与 provenance。
4. 在同一事务写 append-only audit。
5. audit 插入失败时，正文、revision、provenance 与幂等声明全部回滚。

`request_id` 由已认证 transport 身份、JSON-RPC id、固定 actor 和工具名在 Bridge 内生成，不属于 tool args。SSE 会话内和签名 OAuth token 内的同一请求重试可得到同一个 id。

读取也要求持久化审计。固定读取 RPC 在返回结果前写 audit；SQL 范围拒绝会先写 denied audit 再返回错误信封。Bridge 预检查若在进数据库前失败，则通过固定 actor audit RPC 记录；该审计失败时操作保持 fail closed。

## 4. Shared 与权限

| 通道 | GPT private | Claude private | approved Shared | 其他 Shared | Legacy Pending |
|---|---|---|---|---|---|
| GPT MCP | 读写/修订/推荐 | 拒绝 | 只读 | 拒绝 | 拒绝 |
| Claude MCP | 拒绝 | 读写/修订/推荐 | 只读 | 拒绝 | 拒绝 |
| Curator 推荐门 | 选择自己 private 的当前 revision | 选择自己 private 的当前 revision | 无审批权 | 只创建 candidate | 无日常读取 |
| Owner 认证门 | 不代替 AI 写入 | 不代替 AI 写入 | approve/revoke | reject | 后续人工整理 |

推荐 Shared 时，Runtime 在数据库事务内锁定 private row，并解析它的当前 `memory_revisions.id`。candidate 保存 `source_memory_id + source_revision_id + source_revision_hash` 和稳定正文快照。private 后续修订不会让 candidate/approved Shared 漂移；要分享新版必须创建新 candidate。Owner 的状态机仍由 Phase 2 独立认证 RPC 控制。

## 5. 数据库对象

- Migration：`20260808215808_memory_runtime_phase3.sql`
- Rollback：`20260808215808_memory_runtime_phase3_rollback.sql`
- SQL tests：`memory_runtime_phase3.sql`
- 固定 RPC（GPT 与 Claude 对称）：`get`、`list`、`recall`、`remember`、`revise`、`propose_shared`、`audit`
- 内部函数没有授予 `public`、`anon`、`authenticated` 或 `service_role`；仅由 SECURITY DEFINER 固定 wrapper 调用，且 `search_path=''`。

Rollback 只用于空的临时验证库：删除 Phase 3 函数并恢复 Phase 2 的权限面。任何已有真实 Runtime 数据的未来环境都不得直接执行 DROP rollback，必须先停写、备份、核对并单独审批。

## 6. 验证矩阵

免费 CI 使用 GitHub runner 上的一次性本地 Supabase，不创建付费 Branch，不连接云项目：

- Phase 2 migration + 冻结测试
- Phase 3 fresh apply + SQL contract tests + database lint
- Phase 3 rollback，证明 Runtime 对象清除而 Phase 2 schema 保留
- Phase 3 re-apply + 复测
- V3 → V2 完整 rollback，证明清空；V2 → V3 fresh reinstall + 复测
- GPT/Claude private remember、recall、get、revision、provenance、audit
- 双向跨 private 拒绝及 denied audit
- approved/unapproved/revoked Shared、candidate 精确 revision 与快照不漂移
- Legacy 专属关键词返回 0，且不影响普通结果集合
- 同/异 payload 幂等、硬上限、cursor
- 强制 audit 失败后的完整事务回滚
- service role 原始表/RPC 绕过路径被撤销
- Bridge 单元/隔离/兼容测试，以及真实 GPT SSE + Claude HTTP transport 测试
- 前端 lint/build（只记录既有 warning，不在本 PR 顺手修复）

## 7. 当前风险与未解决事项

- 当前 recall 是大小写不敏感子串匹配，不是 embedding/语义检索；向量能力必须以后独立设计，并沿用同一 SQL 空间边界。
- GPT SSE 的幂等身份以单个服务端会话为边界；跨断线重连的重复请求不能保证复用旧 request id。不得为解决它而让 AI 自传 idempotency 字段。
- 生产 Supabase 尚无 V2/V3 schema；本 PR 不能部署或打开开关。
- Owner 审批 UI、Legacy 整理/导入、自动权重/潮汐/遗忘、情绪聚类都不在本阶段。
- 旧表 P0 RLS/public exposure 继续使用独立 PR；Toy/ADB 不在范围内。

## 8. 后续建议

1. Draft PR 通过工程与安全审阅，并等待免费 CI 全绿。
2. 单独制定非生产环境的 V2 + V3 应用演练与 Bridge 环境变量验证，不接生产正文。
3. 单独设计 Owner Shared 审批 UI；保持 Curator 只能推荐。
4. 生产启用必须另开变更：备份、迁移、密钥/权限复核、灰度与回滚预案齐备后，才讨论 `MEMORY_SYSTEM_ENABLED=true`。
5. Legacy 内容仍冻结；后续只做可审计、逐批、人工决定归属的整理流程。
