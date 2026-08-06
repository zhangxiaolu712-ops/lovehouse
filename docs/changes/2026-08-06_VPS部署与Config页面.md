# VPS 部署调试 + Config 状态页面

- 日期：2026-08-06
- 执行者：Claude Code
- 状态：已完成

## 做了什么

- 调试并修复 VPS nginx 部署（防火墙、权限、配置）
- 修复 PIN 系统在 HTTP 下不可用的问题（添加 softHash fallback）
- 修复 bridge 聊天返回空内容的问题（绝对路径 + res.on('close')）
- 部署 Cloudflare Workers 代理版本
- 配置 DuckDNS 免费域名 + Let's Encrypt HTTPS 证书
- 新增 Config 状态页面，展示所有 URL、VPS 服务和基础设施信息

## 为什么这样做

- 用户需要 HTTPS 才能使用 PIN 门锁（crypto.subtle 需要安全上下文）
- bridge 因 pm2 不继承 PATH 和 req.on('close') 提前杀进程导致聊天为空
- 用户记不住各种网址和配置，需要一个集中查看的地方

## 修改文件

- `src/modules/settings/StatusPage.jsx`：新建 Config 状态页面（URL 列表、VPS 服务、基础设施总览）
- `src/core/router.jsx`：新增 StatusPage 路由 `/settings/status`
- `src/shared/Home.jsx`：桌面第 2 页新增 Config 图标入口
- `src/modules/auth/authService.js`：添加 softHash + derivePinFallback（HTTP 下 PIN 可用）
- `src/proxy.js`：修复 CORS、错误处理、OPTIONS 预检
- `bridge/server.js`：修复绝对路径 `/usr/bin/claude`、`res.on('close')` 替换 `req.on('close')`

## 数据库、环境变量与部署

- 无数据库变化
- VPS 已部署：nginx + HTTPS + bridge (pm2)
- Cloudflare Workers 已部署
- DuckDNS 域名 tingtunehouse.duckdns.org 已指向 VPS

## 验证结果

- 已执行：`npm run build` 构建通过
- 已执行：三个网址均可访问（Cloudflare Workers / DuckDNS HTTPS / GitHub Pages）
- 已执行：聊天功能端到端测试通过

## 已知风险或未完成事项

- Cloudflare Workers 暂需手动 `npx wrangler deploy` 更新
- VPS 暂需 SSH 手动拉代码重建
- Supabase RLS 策略尚未启用

## 下一步计划

1. Cloudflare 自动部署（GitHub Action）
2. 聊天上下文记忆（会话模式）
3. Bridge 接入 Supabase 记忆数据
