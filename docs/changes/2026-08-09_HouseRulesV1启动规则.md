# House Rules V1 启动规则

- 日期：2026-08-09
- 执行者：Codex
- 状态：独立分支开发中；未合并、未部署

## 做了什么

- 新增一份短小、版本化的 `house-rules.v1.json`，集中保存六条不能出错的记忆底线和四条最小工具使用提示。
- 新增 `FileHouseRulesProvider`，每次读取时校验版本、revision、唯一规则 id、数量与长度。
- 在不重写 `MemoryService` 的前提下扩展 `starterPack()`：保留原私有/Shared 记忆字段，新增 `schema_version` 与 `house_rules`。
- 更新 `get_starter_pack` 的 AI-facing 描述，使不了解 LoveHouse 历史的新 AI 也知道应在新对话开始时调用、会拿到什么以及不会拿到什么。
- 为未来 VPS `onSessionStart()` 保留直接复用 `MemoryService.starterPack()` 返回契约的接口，本次没有实现自动注入。

## 为什么

- 不应依赖小婷在每个新窗口重复提醒底线。
- 规则需要一个可审阅、可修改、不会散落进多份提示词的单一来源。
- 给 AI 的启动能力必须继续通过 AI-facing MCP 使用，不能再次错做成需要小婷点击的前端按钮。

## 修改文件

- `bridge/memory/house-rules.v1.json`
- `bridge/memory/houseRules.js`
- `bridge/memory/houseRules.test.js`
- `bridge/memory/index.js`
- `bridge/memory/service.js`
- `bridge/mcp/tools.js`
- `bridge/mcp/tools.test.js`
- `docs/ARCHITECTURE_CURRENT.md`
- `docs/06_待开发列表.md`
- 本变更记录

## 数据库、环境变量与部署

- 没有新增或修改 Supabase 表、migration、RLS、RPC 或生产数据。
- 没有新增生产环境变量；默认规则数据源随 Bridge 代码部署。
- 没有修改 VPS、Cloudflare、生产密钥或 Toy。
- 没有实现 Orchestrator、情绪检测、自动 AI 日记或 Phase 4B Dream 功能。

## 验证

- Bridge `npm.cmd test`：83/83 通过，其中新增 House Rules 校验、fail-closed、GPT/Claude service 与实际 MCP adapter 覆盖。
- 前端 `npm.cmd run lint`：通过，只有 18 条既有 warning；没有新增 lint error。
- 前端 `npm.cmd run build`：通过，只有既有 bundle-size warning。
- `node --check` 与 `git diff --check`：通过。
- 将本提交临时叠加到未修改的 #28 head 后，Phase 2/3/4A/4B Bridge 回归 90/90 通过；仅 `bridge/memory/index.js` 出现两项独立 export 的机械冲突，临时保留 `dream.js` 与 `houseRules.js` 两行后即通过。
- Phase 2/3/4A disposable Supabase SQL 回归将在 Draft PR 工作流继续验证。

## 风险与未完成事项

- 规则文件必须随 Bridge 一起部署；文件缺失或格式损坏时 starter pack 会 fail closed。
- 当前仍由 AI 主动调用 `get_starter_pack`；VPS 自动 `onSessionStart()` 不在本版本范围。
- 规则修改目前通过版本控制审阅，没有新增 Owner 前端编辑器。

## 下一步

1. 运行 Phase 2/3/4A、前端与 diff 回归。
2. 在不修改 #28 的临时组合工作树上运行 Phase 4B Bridge 回归。
3. 创建独立 Draft PR，等待小婷与工程审阅；不自动合并或部署。
