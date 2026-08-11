# 工单 02：小客厅认证与 Claude OAuth 发现链

## 状态

- 工单 01 已通过；本变更只处理工单 02。
- 代码与测试仅在 Draft 分支，尚未合并、尚未部署。
- PR #40 继续保持 Draft；本工单没有改动其分支、Cloudflare Worker 或 Cloudflare→VPS 拓扑。
- 没有改动 Gate B、Memory、Toy、Supabase schema/RLS/anon 权限或生产密钥。

## 补充证据

小客厅工单 #64/#65 报告 Claude 连接器注册失败：`Couldn't register with lovehouse/小客厅's sign-in service`，关联参考 `ofid_49badb1ce83d0554`。`focus_check_failed` 只来自本地密钥输入窗口的焦点保护，不能作为 OAuth 根因。

对当前生产环境做只读检查后确认：

1. `GET /.well-known/oauth-authorization-server` 正确返回授权服务器元数据并声明动态客户端注册。
2. `/api/mcp/claude` 的未认证响应为 `401`，但 `WWW-Authenticate` 指向 `/.well-known/oauth-protected-resource/mcp/claude`。
3. nginx 只把授权服务器的根 well-known 路径单独代理给 Bridge；上述受保护资源元数据根路径实际返回前端 HTML `200`，Claude 因而无法继续发现/注册。
4. 同一元数据在现有 `/api/.well-known/oauth-protected-resource/mcp/claude` 路径可正确返回 JSON，因此无需修改 nginx 或 Cloudflare 拓扑。
5. 当前 PM2 环境缺少 `OAUTH_TOKEN_SECRET`；即使修复发现链，令牌签发也不能安全工作。这是部署前的明确阻断项。

## 根因与修复

- `bridge/server.js`：受保护资源元数据公网地址默认放到现有 `/api/*` Bridge 代理下，并允许以 `MCP_RESOURCE_METADATA_URL` 显式覆盖。
- `bridge/oauth.js`：401 的 `WWW-Authenticate` 使用上述地址并声明 `mcp:tools` scope；缺失/过短的 `OAUTH_TOKEN_SECRET` 或非安全 HTTPS 元数据地址在启动阶段直接失败。
- `bridge/oauth.test.js`：覆盖可达元数据、Claude 托管回调的动态注册、弱/缺失签名密钥和不安全元数据 URL。

服务端 sender 固定、livingroom fence、server-side Supabase key、错误透明化和其他 P0 表隔离保持不变。没有放宽 RLS 或 anon。

## 验证

- 定向 Bridge：32/32 通过（OAuth、security、livingroom、MCP tools/channel/transports）。
- Bridge 全量：115/115 通过。
- 动态注册测试使用 Claude 托管回调 `https://claude.ai/api/mcp/auth_callback`、authorization code flow 和 public client `token_endpoint_auth_method=none`。
- 生产检查均为只读；没有重启 PM2、写入环境变量或发送测试消息。

## 剩余风险

- 动态注册的客户端表当前保存在 Bridge 进程内存中，重启后会丢失。客户端可按动态注册协议重新注册，但持久化客户端注册表不在本工单范围内。
- Claude Connect 的最终真实授权、工具列表和工具调用按工单 02/04 的既定阶段执行；本变更不借机展开 03/04。

## 审核后部署计划

1. 独立审阅并合并本 Draft PR，锁定合并后的明确 main commit。
2. 在 VPS 服务端安全生成并配置至少 32 字符的随机 `OAUTH_TOKEN_SECRET`；密钥不经过 git、前端或聊天正文。
3. 对部署配置做预检，确认 resource、metadata URL、issuer 与 Claude 中填写的 MCP URL 一致。
4. 只部署锁定的 Bridge commit，检查 PM2 与 `/health`。
5. 依次 smoke：授权服务器发现、受保护资源发现、DCR、authorize/token、Claude Connect，以及小客厅读取/发送/回读与错误透明化。

## 回滚

- 将 Bridge 切回部署前的明确 release commit 并重启 PM2；不回滚或修改数据库，因为本工单没有 schema/RLS/data 变更。
- 服务端 OAuth 签名密钥保留在 VPS 环境文件中；若确认泄露则单独轮换，不能写入日志或仓库。
