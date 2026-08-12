# 2026-08-12 Cloudflare 到 Bridge 的 HTTPS 域名修复

## 做了什么

- 将 Cloudflare Worker 的 Bridge origin 从 VPS IP literal 改为现有 HTTPS DuckDNS hostname。
- 保留既有 `/api/*` rewrite 语义：例如入站 `/api/health` 仍转发到公网 Bridge 路径 `/api/health`。
- 本轮只创建分支/preview 版本，不切换 Cloudflare production。

## 为什么

Worker 使用 IP literal 发起子请求时，Cloudflare 返回 `403 error code: 1003`，请求没有到达 nginx/Bridge。改用现有 HTTPS hostname 后，Host 与 TLS SNI 由 URL 自然生成，并继续沿用现有 nginx `/api/*` 入口。

## 修改文件

- `src/proxy.js`
- `docs/changes/2026-08-12_Cloudflare到Bridge的HTTPS域名修复.md`

## 数据库、环境变量与部署影响

- 不修改 VPS、nginx、DNS、UFW、PM2、Bridge、Supabase、RLS、anon、Memory、Toy 或任何密钥。
- 不新增或修改环境变量。
- 不部署 Cloudflare production；preview 验证通过后仍需单独取得生产发布授权。

## 验证

- Worker 代理路径测试：通过；`/api/health` 与 `/api/chat` 均重写到 HTTPS hostname 的单一 `/api/*` 路径，无 `/api/api`，未手工设置 Host。
- `npm run lint`：通过；仅有项目既有 warning，无 error。
- `npm run build`：通过；仅有既有 chunk-size warning。
- Wrangler `4.120.1 deploy --config wrangler.json --dry-run`：通过；读取 9 个静态资产并在上传前退出。
- 当前 Workers 类型包 `@cloudflare/workers-types@5.20260811.1` 已核对；本次使用标准 Fetch API，无新增 binding。
- 待完成：Cloudflare preview 的 health、chat 应用层、OAuth、MCP、小客厅、错误透明化与无递归 smoke。

## 风险与回滚

- 该 origin 影响所有经 Worker `/api/*` 的请求；路径或 DNS/SNI 异常会统一影响 chat、OAuth、MCP 和小客厅入口。
- 生产回滚方案是将 Cloudflare 流量保持或切回当前已知 production version；不动 VPS/Bridge/PM2。

## 下一步

- 仅发布分支 preview 并执行批准的七项 smoke。
- 全部通过后回小客厅交付，等待单独的 Cloudflare production deploy 授权。
