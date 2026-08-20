# Memory V2 MCP 七工具收窄

- 基线：`4bc87ed26cc9bf08690bdaac281ec215500356dc`
- 分支：`agent/memory-v2-mcp-seven-tools-20260821`
- 状态：本地待审；未 push、未 merge、未部署、未切生产 MCP

## 本次改动

- 正式 MCP definitions 与 Claude 精确白名单由历史 14 项收为：`wake_up`、`remember`、`recall`、`revise`、`open_memory`、`read_livingroom`、`say_livingroom`。
- GPT/Claude channel 由服务端固定 actor 后调用同一个 `MemoryV2Service`；MCP schema 不提供 owner、actor、space、Shared approval 或 permissions 参数。
- `open_memory(memory_id)` 使用既有 V2 history/source descriptors；`open_memory(source_id)` 使用既有 `expandSource`。没有新增 schema、表或 RPC。
- `read_livingroom` 合并旧 read/context 的表面用途；`say_livingroom` 继续通过既有 LivingRoom fence 固定 sender，保留错误透明化。
- 旧工具名与 `propose_shared_candidate` 不再作为兼容 alias 暴露；Shared candidate/approval 保留在 Owner/管理路径，不属于日常 AI MCP。

## 职责边界

LoveHouse Core MCP 只做两件事：把自然语言工具参数整理后交给 Memory V2，以及把小客厅读写交给既有 fence。semantic/lexical fallback、ranking、Tide、revision、currentness、Shared/source 权限、Starter Pack 与 embedding 都不在 MCP 中实现。它也不负责 Chat、Claude session、数据库业务逻辑或未来其他产品能力。

## 风险与回滚

- 这是显式 breaking tool-surface change：部署后客户端必须重新读取 `tools/list`，不能继续调用旧 14 个名字。
- `open_memory` 依赖已合并的 history source descriptors delta；该 delta 未应用生产前不得部署本分支。
- 回滚只需撤销本提交即可恢复 V1 MemoryService 与历史 14-tool surface；数据库没有变化。

## 生产边界

没有修改生产 env、PM2、Bridge、Supabase schema、OAuth、Chat、frontend、Worker、Nginx 或 DNS；没有部署或切换线上 MCP。
