# Claude Code DCR 与 Refresh Token 兼容

- 日期：2026-08-13
- 执行者：Codex
- 状态：已完成本地实现与验证，等待 Draft PR 复审

## 做了什么

- 按 Claude Code 实际 DCR 契约，允许既有 `authorization_code`，以及严格的 `authorization_code + refresh_token` 组合；其他 grant、response type、redirect、application type 和 token auth 组合继续拒绝。
- OAuth 元数据同步声明 refresh grant；授权码流程继续强制精确 redirect、resource、scope 和 PKCE S256。
- 新增服务端 refresh token 状态仓库。原始 refresh token 只返回给客户端，落盘仅保存 HMAC 摘要、token family、代数、状态和 owner/client/resource/scope 绑定。
- refresh token 使用后立即轮换；旧 token 重放会撤销整个 family；无效、撤销、过期或绑定不匹配均 fail closed。
- #137 只读边界复审复现了两个文件 store 实例可同时读取 active token 并各自轮换的并发缺陷；以同目录原子 lock directory 包住完整 read-modify-write，修复同主机多进程双签发窗口。遗留 lock 会使操作超时失败，必须在确认无实例持有后由运维处理，不会被自动删除或绕过。
- 同次复审确认可选相对路径会随 PM2 release cwd 漂移；`OAUTH_REFRESH_STORE_PATH` 现仅接受绝对路径，默认 HOME 外置路径保持不变。
- 保留 `client_secret_post` 的 confidential web client 边界；native client 只能使用 `none`，不新增 OAuth 绕过。

## 为什么这样做

真实 VPS Claude Code 在打开浏览器前发起 DCR，并同时申请 `authorization_code` 与 `refresh_token`。旧 Bridge 只接受单一 `authorization_code`，因此返回 `only authorization_code is supported`。手工只测授权码 DCR 无法覆盖这项真实客户端契约。

## 修改文件

- `bridge/oauth.js`：严格 DCR/metadata、授权码与 refresh grant、绑定校验及轮换响应。
- `bridge/oauthRefreshStore.js`：服务端摘要持久化、原子更新、过期、撤销、轮换与重放处理。
- `bridge/server.js`：注入文件型 refresh token store。
- `bridge/oauth.test.js`：真实客户端等价 DCR、PKCE、refresh、安全绑定、重放、过期、撤销和重启复用测试。
- `docs/ARCHITECTURE_CURRENT.md`：记录 OAuth/refresh 当前设计与可选服务端路径。
- `docs/06_待开发列表.md`：记录此兼容修复处于 Draft 复审、尚未部署。

## 数据库、环境变量与部署

- 不需要 Supabase migration；未连接或修改任何生产 Supabase。
- 新增可选服务端变量 `OAUTH_REFRESH_STORE_PATH`；未修改任何生产环境变量或凭证。
- 未部署 Bridge、未重启 PM2、未修改 Worker/Cloudflare、未启动 Gate B/C，也未重跑工单 05 生产闭环。

## 验证结果

- OAuth 定向测试：15/15 通过，新增文件权限、双实例并发、缺失/损坏 fail-closed 与签名密钥轮换覆盖。
- Bridge 全量测试：158/158 通过。
- 前端 lint：通过，仅有既有非阻断 warning；build：通过，仅有既有 chunk-size warning。
- `git diff --check`：通过。
- 真实 Claude Code 到浏览器的 preview 验证：未执行。当前没有不触碰生产且具备公网 HTTPS OAuth issuer/callback 的 sidecar；本地等价契约测试已走到真实授权页、授权码、PKCE、access token 与 refresh token。

## 已知风险或未完成事项

- 动态客户端注册与授权码仍沿用既有进程内存存储；本工单只为 refresh token 增加跨重启持久化，不扩大为完整 OAuth 数据库。
- 生产真实 Claude Code Connect 必须等 Draft PR 审核、合并并取得单独生产授权后，回到工单 05 执行。

## 下一步计划

1. 提交新的 Draft PR，等待 GPT/小婷复审。
2. 不 merge、不 deploy，等待后续明确授权。
