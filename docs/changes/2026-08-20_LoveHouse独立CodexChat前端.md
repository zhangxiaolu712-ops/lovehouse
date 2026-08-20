# LoveHouse 独立 Codex Chat 前端

> 状态：独立本地分支，真实 VPS E2E 已通过；等待正式前端部署授权。

## 范围与原因

VPS 已正式最小部署独立 `POST /api/codex/chat` 与 Codex 官方 thread/resume 主链，Raw Chat Archive schema 仍未确定。本次只完成不绑定数据库表的独立前端入口及真实联调，不修改 Claude 旧 Chat、OAuth、MCP、Memory、LivingRoom 或生产基础设施。

## 实现

- 新增独立 `/codex-chat` 页面与首页第二屏 Codex 图标；Claude/小克入口保持不变。
- `codexChatService.js` 只负责从现有 Supabase session 读取 user access token、请求 `/api/codex/chat`，以及解析命名 SSE `session`、`text`、`error`、`done`。
- `window_id` 保存于 `localStorage`，刷新、关闭标签页及浏览器/App 重启后保持稳定；它不是 secret，也不包含 Codex thread identity。
- SSE 返回的 Codex `session_id` 仅保存在 React 页面运行状态，不写入 storage、不作为永久数据源，也不随请求回传为权威绑定。
- UI 与 fallback history 只保留最近 12 条、总计最多 16000 字符、单条最多 4000 字符；不直接访问任何 Supabase Chat 表。
- 页面支持发送、流式文本、loading、明确错误、重试以及同一 window 继续。
- 本地开发仍请求相对 `/api/codex/chat`。Vite 开发代理指向正式 HTTPS 域名；当前 Node 开发环境经该 HTTPS 路径的 TLS 1.3 握手会 reset，TLS 1.2 实测为 200，因此只在开发代理内固定 TLS 1.2 并复用连接。业务代码、正式 build 和生产配置不受影响。

## 修改文件

- `src/modules/codex-chat/CodexChatPage.jsx`
- `src/modules/codex-chat/codexChatService.js`
- `src/modules/codex-chat/codexChatState.js`
- `src/modules/codex-chat/codexChatService.test.js`
- `src/modules/codex-chat/codexChatState.test.js`
- `src/core/router.jsx`
- `src/shared/Home.jsx`
- `src/shared/AppShell.jsx`
- `src/shared/global.css`
- `vite.config.js`
- `package.json`
- `docs/changes/2026-08-20_LoveHouse独立CodexChat前端.md`

## 数据库、配置与部署

- Supabase schema / migration / RLS：无变化。
- Raw Chat Archive：未定义、未访问、未创建。
- 环境变量 / secret：无变化；前端继续使用现有 Supabase user session。
- Claude Chat `/api/chat`、OAuth、MCP、Memory、LivingRoom、Worker、Nginx、PM2：无变化。
- 正式前端和 VPS：未部署。

## 验证

- Codex frontend mock tests：6/6 通过。
- 覆盖 JWT header、请求体、SSE session/text/error/done、HTTP 分类错误、刷新后稳定 window id、bounded history、router 与首页入口。
- 真实 owner JWT E2E：首轮创建 Codex thread 并返回唯一标记；同页第二轮状态为“已继续”且准确召回；刷新后第三轮准确召回；关闭页面/标签页并重新打开后，历史与 `window_id` 仍在，第四轮继续同一 thread 并准确召回。
- 真实 SSE 已观察 `session`、流式 `text`、`done`；真实代理故障时 loading、明确 HTTP 502 与“重试”按钮均正常。SSE `error` 分支未人为制造生产 provider 故障，继续由 mock contract test 覆盖。
- 本地开发代理健康检查：TLS 1.2 路径实测 200，连接建立后连续 5 次 200；未修改生产 Nginx/CORS/后端。
- `npm run lint`：通过；仅仓库既有 warning，新模块无 lint warning。
- `npm run build`：通过；有既有大 chunk 提示，以及 Supabase 同时被静态/动态导入的非阻断 bundler 提示。
- Claude `src/modules/chat/ChatPage.jsx` 与 `chatService.js` 相对 main diff：0。
- `git diff --check`：通过。

## 风险、回滚与下一步

- APP ↔ VPS 真实 E2E 已完成；正式前端仍未部署。
- `localStorage` 持久化的只有非敏感 `window_id` 与 bounded UI/fallback history；JWT 和 Codex thread/session id 均不落盘，后端 binding 继续是权威事实。
- 回滚方式：撤销本次单一前端提交；没有数据库或生产回滚动作。
- 下一步仅等待正式前端部署授权，不在本分支自行部署。
