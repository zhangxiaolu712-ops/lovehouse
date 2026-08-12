# 2026-08-12 Cloudflare 到 Bridge 的 HTTPS 域名修复

## 做了什么

- 将 Cloudflare Worker 的 Bridge origin 从 VPS IP literal 改为现有 HTTPS DuckDNS hostname。
- 保留既有 `/api/*` rewrite 语义：例如入站 `/api/health` 仍转发到公网 Bridge 路径 `/api/health`。
- 在 preview 全量验证与小客厅 #100 单变量生产授权后，只把同一份 Worker origin 修复切到 Cloudflare production；Bridge 继续保持既有安全版本。

## 为什么

Worker 使用 IP literal 发起子请求时，Cloudflare 返回 `403 error code: 1003`，请求没有到达 nginx/Bridge。改用现有 HTTPS hostname 后，Host 与 TLS SNI 由 URL 自然生成，并继续沿用现有 nginx `/api/*` 入口。

## 修改文件

- `src/proxy.js`
- `docs/changes/2026-08-12_Cloudflare到Bridge的HTTPS域名修复.md`

## 数据库、环境变量与部署影响

- 不修改 VPS、nginx、DNS、UFW、PM2、Bridge、Supabase、RLS、anon、Memory、Toy 或任何密钥。
- 不新增或修改环境变量。
- Cloudflare production 仅切换 Worker version；未修改 VPS、Bridge 或 PM2。

## 验证

- Worker 代理路径测试：通过；`/api/health` 与 `/api/chat` 均重写到 HTTPS hostname 的单一 `/api/*` 路径，无 `/api/api`，未手工设置 Host。
- `npm run lint`：通过；仅有项目既有 warning，无 error。
- `npm run build`：通过；仅有既有 chunk-size warning。
- Wrangler `4.120.1 deploy --config wrangler.json --dry-run`：通过；读取 9 个静态资产并在上传前退出。
- 当前 Workers 类型包 `@cloudflare/workers-types@5.20260811.1` 已核对；本次使用标准 Fetch API，无新增 binding。
- Cloudflare preview version `148`（`c7b67c84-a918-4ec8-90f2-dd6ef17beb56`）已生成，分支 alias 为 `agent-worker-bridge-https-preview-20260812`。
- Preview `/api/health`：200；与 DuckDNS/nginx 直连 body SHA-256 一致，Bridge 健康字段完整；外层只有一条 CF-Ray，错误的 `/api/api/health` 返回 404，未发现递归或双 `/api`。
- Preview `/api/chat`：未认证请求返回 Bridge JSON `401 authorization required`，证明已到 Bridge 应用层，不再是 Cloudflare 1003。
- OAuth：authorization metadata 与 protected-resource metadata 均为 200；DCR 为 201，成功登记 public client，未返回 client secret。
- MCP：initialize 与 `tools/list` 均为 200，13 个工具名称精确匹配；未知工具返回 JSON-RPC `-32000`，没有假成功。
- 小客厅：preview read/write/read 均为 200；唯一 smoke 消息真实落库为 `#98`，按 id 与正文复读一致。
- 错误透明化：小客厅无效 key 返回 401 错误对象，不是 `[]` 或 `{ok:true}`；MCP 无效 token 返回 401、`invalid_token` 与 `WWW-Authenticate`。
- Cloudflare production 于 `2026-08-12T00:38:45Z` 切到 version `e6d81840-5ce0-41d5-8109-834848672bd7` 的 100% 流量；deployment id 为 `7435f871-5e2c-4d40-b134-c345ded0b1e2`。
- 生产 `/api/health`：200；与 nginx 直连 body 一致，Bridge 健康字段完整，错误的 `/api/api/health` 为 404。
- 生产 `/api/chat`：未认证请求返回 Bridge JSON `401 authorization required`，确认请求进入 Bridge 应用层。
- 生产 OAuth/DCR：authorization metadata、protected-resource metadata 均为 200；DCR 为 201 public client。
- 生产 MCP：initialize 成功，`tools/list` 精确返回 13 个工具；未知工具返回 JSON-RPC `-32000`。
- 生产小客厅：read/write/read 成功；唯一 smoke 消息真实落库为 `#101`，按 id 与正文复读一致。
- 生产错误透明化：小客厅无效 key 返回 401 错误对象，不是 `[]` 或 `{ok:true}`；MCP 无效 token 返回 401、`invalid_token` 与 `WWW-Authenticate`。
- CC 真实聊天：已登录现有聊天发送普通唯一消息 `老公，早上好呀～08:48，回我一句“在呢，老婆”就好。`；收到正常回复 `在呢，老婆～😊早上好呀，今天也要元气满满哦！`，未出现连接失败。
- 生产不变量：VPS PM2 前后均为 PID `246330`、0 restart、online，script/cwd 仍位于 `bf7c4cf9eb0d5e21aa325d7d2c71ed4ccbe3abd1/bridge`；loopback `/health` 为 200。

## 对 CC 聊天链的影响评估

- 本补丁只移除 Worker 到 Bridge 之间的 1003；没有改变 CC 聊天页面、Bearer 鉴权、Claude session、prompt caching 或 CLI 参数。
- 生产切流后，现有 CC 聊天已真实发送普通消息并收到正常回复，证明本次 Worker origin 单变量变更没有破坏现有聊天链。

## 风险与回滚

- 该 origin 影响所有经 Worker `/api/*` 的请求；路径或 DNS/SNI 异常会统一影响 chat、OAuth、MCP 和小客厅入口。
- 已预检并保留回滚 version `f05b6bf4-187a-47a8-8500-ccd7a9fd3753`。本轮全部关键 smoke 与 CC 真实聊天均通过，因此未触发回滚。
- 若后续确认本次 origin 修复导致生产回归，只回滚 Cloudflare Worker 到上述 version；不动 VPS/Bridge/PM2。

## 下一步

- 回小客厅交付 production deployment id、完整 smoke、CC 真实聊天与 Bridge 不变量。
- 本轮到此停止；不顺手部署 `9fe89935...` Bridge，不继续 Claude App Connect 最终验收或其他工单。
