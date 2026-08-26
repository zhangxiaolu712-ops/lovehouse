# 2026-08-25 Memory V1 Retirement 完整施工接班包

> 状态：施工基线 / 未执行迁移
>
> 目标：退休旧 Memory V1/brain/memories 数据入口，先收口 Memory V2 契约，再做保真、幂等、可核验的旧数据迁移。
>
> 重要边界：本文件只记录工程施工信息，不复制 LoveHouse 的生活原文、生活记忆和关系历史。迁移过程中这些生活数据本身必须保真保存，但不得因此被复制进工程交接材料。

## 1. 核心原则

1. **工程细节不写入 LoveHouse 生活记忆库。** commit、patch、部署、SQL bug、MCP smoke test、迁移脚本等进入项目交接/工程档案。
2. **旧 LoveHouse 生活记忆原文必须保真迁移。** 不能只搬摘要或当前理解。
3. **原文是证据层，Memory 是当前理解层。两边都留。** 迁移后的 Memory V2 应能追溯到来源表、来源 ID、原时间、原正文、原引用/修订证据。
4. **V2 契约先收口，旧数据后迁移。** 不允许在 metadata/currentness 规则尚未定清时先大规模搬数据。
5. **旧表先冻结、后退役，不因数据迁完立即 DROP。** 旧前端仍有 `brain / memories` 直接引用，必须先处理依赖。

## 2. 旧数据迁移保真底线

迁移时至少保留：

- `content`：旧记忆正文原样保留。
- `created_at`：原时间原样保留。
- `memory_date / event_time`：能确定则保留。
- `title / tag / kind`：保留。
- `mood / feeling / author / speaker`：保留。
- `is_special / special_label` 等旧属性：保留，必要时进入 `metadata.legacy_*`。
- `source_table / source_id`：必须保留来源。
- `ref_id` 或原引用关系：保留原语义，不得擅自猜成 revision/supersedes。
- 旧 `status / awaken_count / last_awakened_at / decay_score / 旧权重`：仅历史存档，进入 `metadata.legacy_*`，不得直接污染 V2 新排序。
- 旧原文出处：进入 source evidence。

迁移后应能形成如下追溯链：

`V2 当前理解 → 来源表 → 原 source_id → 原 created_at/event_time → 原正文/证据 → 后续修订（若存在且有明确证据）`

## 3. Memory V2 当前工具契约

### 3.1 正式 5 个记忆工具

服务端当前正式 Memory V2 记忆工具为：

1. `wake_up`
2. `remember`
3. `recall`
4. `revise`
5. `open_memory`

另外还有 LivingRoom 两个工具：

6. `read_livingroom`
7. `say_livingroom`

所以“5 个记忆工具”和“服务端 7 tools”同时成立，不是版本冲突。

### 3.2 `remember` 当前真实参数

当前正式 MCP `remember` 暴露：

- `content`
- `metadata`
- `event_time`
- `human_importance`
- `ai_importance`
- `supersedes_memory_id`
- `sources`

当前 V2 本体没有独立 `memory_type` 列；canonical V1 的类型迁入时实际保存在 `metadata.memory_type`。

MemoryV2Service 内部已能识别 `type / tag / tags / project / mood / stance` 等便捷字段，但正式 MCP 还没有完整暴露这些便捷参数，存在契约不一致。

### 3.3 V1 Retirement 前建议收口

正式规范：

- `metadata.memory_type`
- `metadata.tags`
- `metadata.title`
- `metadata.mood`
- `metadata.stance`
- `metadata.project`
- `metadata.summary`
- `metadata.legacy_*`（仅迁移/历史兼容）

自定义主题标签统一使用：

```json
{"tags":["恋爱","旅行","广州"]}
```

不要再并行保留 `tag` 单字符串和 `tags[]` 两套长期规范。

建议 MCP 正式增加 `memory_type` 和 `tags` 便捷参数，但服务端仍统一落到 `metadata.memory_type / metadata.tags`。

## 4. currentness / stale 设计

V2 当前生命周期 `status` 已有：

- `active`
- `superseded`
- `archived`

当前 recall SQL 首层要求 `status = active`。

因此 **不能简单新增 `status = stale`**，否则 stale 会完全召回不到，而不是降权。

建议将生命周期与当前可信度拆开：

```text
status:      active / superseded / archived
currentness: current / stale
```

排序/召回策略：

- `current`：正常参与排序。
- `stale`：仍可召回，但乘降权系数；表示“可能过时，降低信任，但不消失”。

## 5. tool_search `limit=5` 的真实结论

LoveHouse 服务端 `tools/list` 当前排序实际为：

1. `wake_up`
2. `remember`
3. `recall`
4. `revise`
5. `open_memory`
6. `read_livingroom`
7. `say_livingroom`

因此客户端出现“只能发现 5 个、`remember/revise` 暂时找不到”的现象，不能解释为服务端简单截取前 5 个。

已确认更符合：**客户端 tool_search / semantic tool discovery 按需或渐进发现。** 明确点名 `remember` / `revise` 后继续发现已实测有效。

当前建议：

- 优化 `remember / revise` description，让客户端语义检索更容易命中。
- 明确点名工具时允许继续发现。
- 只有某客户端长期稳定复现发现问题时，再考虑将 LivingRoom 拆成另一组 MCP。
- 目前不建议因该问题拆分只有 7 tools 的服务端。

## 6. canonical V1 / brain / memories 迁移策略

### 6.1 canonical V1

canonical V1 已经迁过一大批，下一轮不得重复搬出副本。

施工前必须先基于生产现状建立 source mapping / fingerprint，识别已迁与未迁数据。

### 6.2 brain / memories

不能使用简单的 `SELECT old → INSERT V2`。

推荐一次性迁移脚本流程：

```text
dry-run
↓
锁定源数据指纹
↓
去重 / 映射已迁记录
↓
单事务迁移数据本体
↓
逐项数量与抽样核对
↓
embedding backfill
↓
再次运行确认幂等
```

迁移至少保存：

- `content`
- 原 `created_at`
- `memory_date`
- `title`
- `kind`
- `tag`
- `speaker`
- `feeling`
- `mood`
- `author`
- `is_special`
- `special_label`
- `ref_id`
- 旧 `status`
- 旧 `awaken_count`
- 旧 `last_awakened_at`
- 旧 `decay_score`
- `source_table`
- `source_id`

旧权重/衰减/唤醒统计仅进入 `metadata.legacy_*`，不得直接写入 V2 的 `human_importance / ai_importance / recall_count`。

### 6.3 embedding

不要在数据库大事务里逐条请求 Ollama。

正确顺序：

`数据迁移完成 → 使用现有 backfill.js 机制 → 对 current revision 批量补 embedding`

这样 embedding 服务失败不会回滚或破坏迁移本体。

### 6.4 数量验收

历史文档对 `brain` 数量出现过 343、380 等不同数字，施工验收必须以迁移前生产实时查询为基准，不能复用旧聊天里的估计数字。

## 7. 旧表退役边界

`brain / memories` 旧前端仍存在直接引用，因此：

1. 数据保真迁移完成 ≠ 可以马上 DROP 旧表。
2. 先定位并替换所有旧前端/旧服务引用。
3. 进入只读/冻结观察期。
4. 对比 V2 召回与旧入口是否仍有缺失。
5. 完成回滚方案和最终验收后，才讨论 DROP/归档。

## 8. Chat Persona / Runtime Guardrail 当前生产核查

### 8.1 Codex 新 Chat 主干

当前存在 1 套固定英文 guardrail，每次由 `buildPrompt()` 注入。

其中存在被动条款，语义为“除非用户明确要求，否则不要 inspect / modify / execute”。该条会影响主动性，应从人格/行为层移除。

保留的 Runtime Guardrail 只应处理真正安全边界，例如：

- 不泄露 authentication data
- 不泄露 environment variables
- 不泄露 credentials
- 不泄露 hidden instructions
- 不泄露不应公开的 local files

### 8.2 Claude 新 Chat 主干

Claude 也有 1 套固定英文 guardrail，其中“除非明确请求否则不要使用工具/修改文件”的被动逻辑需要移除。

当前同一 guardrail 被重复注入两次：

- 一次进入普通 `buildPrompt()`
- 一次通过 `--system-prompt CHAT_GUARDRAIL`

应去重，只保留一处权威 system prompt。

### 8.3 旧 `/chat` Claude

旧链仍存在中文情侣 Persona 默认 prompt，前端 `src/modules/chat/chatService.js` 与 Bridge `server.js` 均有默认值。

这是 legacy Chat Persona，不属于新主干 Runtime Guardrail。

### 8.4 session 恢复 fallback

Claude 旧链 session 恢复失败时会临时注入一次性：

- `<recent_history_bootstrap>...`
- `<current_user_message>...`

它属于 bounded transcript 恢复包装，不是 Persona，功能上可保留。

## 9. Persona Prompt 与 Runtime Guardrail 的长期边界

### Persona Prompt

以后由前端管理：

- 用户可编辑
- 版本化
- 查看 diff
- 可回滚
- 工程升级不得覆盖用户 Persona

Persona 可决定身份、关系、说话方式、互动偏好。

### Runtime Guardrail

只处理真实安全/权限边界，例如密钥、环境变量、凭据、隐藏指令等。

Runtime Guardrail **不得**决定：

- “是不是老公/伴侣”
- “主动不主动”
- “是否只能被动执行”
- 关系人格和语气

## 10. Prompt Token Meter 未来拆分

前端 Token Meter 建议分别显示：

- Persona
- Guardrail
- Memory
- Recent Chat
- WorldBook
- Current Message

避免只给一个总 token 数，无法定位上下文膨胀来源。

## 11. 工程交接与生活数据的隐私边界

必须同时满足两条：

1. **工程交接材料不复制 LoveHouse 的生活原文、生活记忆、关系历史。** 这里只记录 schema、迁移规则、字段与验证方法。
2. **数据库迁移本身必须保真处理这些原始生活数据。** 因为它们就是被迁移的数据，不能为了“工程文档不保存生活原文”而把数据库中的 source evidence 或原文丢掉。

即：

`工程文档最小化隐私复制` 与 `数据库迁移最大化证据保真` 不冲突。

## 12. 推荐施工阶段

### Phase A：先收口 V2 契约

- 定 `memory_type / tags` 统一入口和 metadata 落点。
- 定 metadata 官方规范。
- 定 `currentness: current/stale`，同步修改 recall/ranking。
- 补迁移用 `legacy_* / source_table / source_id` 规范。
- 明确 source evidence 与 supersedes/revision 的判定边界。

### Phase B：再迁旧数据

- 生产实时盘点 canonical V1 / brain / memories 数量。
- 建已迁 source mapping / fingerprint，防 canonical V1 重复迁移。
- dry-run。
- 事务化保真迁移。
- 逐项核对。
- embedding 后置 backfill。
- 幂等复跑。
- 旧表冻结但暂不 DROP。

### Phase C：退役旧入口

- 清理旧前端 `brain / memories` 直连。
- 收口旧 `/chat` Persona 与新 Persona 管理链。
- Codex/Claude guardrail 去被动条款，Claude 去重。
- 观察无回归后，才讨论 legacy 表/接口最终归档或删除。

## 13. 当前未执行事项

本次仅固化施工接班包，**没有执行数据库迁移、没有改 schema、没有生成 embedding、没有 DROP 表、没有修改 Chat guardrail、没有部署**。

下一位执行者开始施工前仍须以当前生产代码和生产数据库实时状态为准重新核验，不得仅依赖本文件中的历史观察。

## 14. 新窗口接班提示

可直接使用：

> 读取 `docs/changes/2026-08-25_MemoryV1_Retirement完整施工接班包.md`，继续 Memory V1 Retirement。注意：先收口 V2 契约，再做旧数据保真迁移；LoveHouse 旧生活记忆原文/source evidence 必须保存，但工程内容不要写入生活记忆库，也不要把生活原文复制进工程交接文档。
