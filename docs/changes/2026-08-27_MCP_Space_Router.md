# MCP Space Router

## 做了什么

- 在现有七工具 MCP surface 内为 `remember`、`recall`、`revise`、`open_memory` 增加可选 string `space_key`。
- 为 Engineering 写入、修订和历史读取增加可选 `subject_key`；选择 Engineering 时必须显式提供，不从正文生成，不放入 metadata。
- 新增统一 space policy registry，当前注册 `gpt`、`claude`、`shared`、`engineering`，并集中决定路由与权限。
- Engineering 路由复用现有 `EngineeringMemoryService` 及 `memory_v2_engineering_*` RPC，不经过 GPT private remember。

## 为什么

让当前 Memory MCP 工具能选择受策略控制的 memory space，同时保持工具数量、fixed actor 边界、旧调用默认语义和 Engineering 稳定 subject revision 模型。

## 修改文件

- `bridge/mcp/spacePolicy.js`
- `bridge/mcp/tools.js`
- `bridge/mcp/channel.js`
- `bridge/server.js`
- `bridge/mcp/tools.test.js`
- `bridge/mcp/channel.test.js`
- `docs/02_当前架构.md`
- `docs/changes/2026-08-27_MCP_Space_Router.md`

## 数据库、环境变量与部署

- 数据库表、字段、RLS、migration 和 RPC 签名均未修改。
- 环境变量和部署流程未修改。
- 本轮未部署。

## 实际验证

- `node --test bridge/mcp/tools.test.js bridge/mcp/channel.test.js bridge/memory-v2/engineering.test.js`：通过。
- `node --test mcp/transports.test.js`（`bridge/` 目录，沙箱外临时 loopback 端口）：通过。
- `node --test mcp/*.test.js memory-v2/engineering.test.js`（`bridge/` 目录，允许 loopback）：21 个测试全部通过。
- `npm test`（`bridge/` 目录）：本轮相关测试通过；7 个需要本地端口的 client-api/OAuth/transport 文件在沙箱内因 `listen EPERM 127.0.0.1` 失败，直接依赖 `mcp/transports.test.js` 已在允许 loopback 的环境单独通过。
- `git diff --check`：待最终收口执行。

## 已知风险与未完成事项

- `shared` 仍只允许读取 approved Shared，MCP 不提供直接写入或批准能力。
- 未注册 `space_key` fail closed；未来新增 space 需先在 registry 定义权限与路由。

## 下一步

- 在 Draft PR 中审阅 MCP schema 与 fixed-actor/Engineering 权限边界。
- 合并与部署分开确认；若后续部署，再做 GPT/Claude channel 线上冒烟。
