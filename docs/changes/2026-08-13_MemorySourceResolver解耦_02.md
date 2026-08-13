# 2026-08-13 Memory SourceResolver 解耦

## 做了什么

- 在 Draft PR #44 内新增稳定 `SourceResolver.resolve(source, options)` dispatch contract。
- manual quote 与 manual summary 拆为独立 evidence adapters。
- 新增 channel-neutral `ChatMessageRepository.getMessage/listMessages` contract；默认实现明确 `MEMORY_CHAT_SOURCE_NOT_CONFIGURED`。
- 移除 Memory source 模块与 `MemoryService` 对 livingroom fence、livingroom 表和 Supabase REST path 的依赖。
- `lovehouse_message` / `lovehouse_message_range` 只代表持久 LoveHouse chat source；绝不回退读取 livingroom。旧 Draft kind `lovehouse_range` 仅保留 alias。
- locator 只接受稳定 ID/range/reference，拒绝 browser state、local/session storage 与 Supabase REST path。

## 为什么

livingroom 是独立的小客厅通道，不是 canonical LoveHouse 单人聊天存储。把 Memory evidence 直接解析为 livingroom REST 行会让 source identity、数据访问与前端/当前实现耦合，并制造错误兼容。

## 修改文件

- `bridge/memory/source.js`、`service.js`、`source.test.js`、`memory.test.js`
- `bridge/server.js`
- `bridge/mcp/tools.js`
- Draft migration/test 与 Memory 架构文档

## 数据库、环境与部署

- 未连接或修改生产 Supabase；仅修正 Draft migration 内 source kind/channel 的便携约束。
- 无新增环境变量；未部署 Bridge、未重启 PM2、未执行 Gate B/C、未修改 Cloudflare/Toy。

## 验证与下一步

- Bridge、disposable Supabase、frontend lint/build 与 `git diff --check` 随本次改动重跑。
- 当前没有配置真实 LoveHouse chat message repository，因此相关 source 展开按设计 fail closed。未来由独立 chat storage 工单实现 adapter；本 PR 不实现 chat V1。
