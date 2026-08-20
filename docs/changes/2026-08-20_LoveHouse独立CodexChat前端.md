# LoveHouse 独立 Codex Chat 前端

> 状态：独立本地分支，等待真实 VPS E2E；未部署。

## 范围与原因

VPS 旁路原型已经定义独立 `POST /api/codex/chat` 与 Codex 官方 thread/resume 主链，但正式后端尚未部署，Raw Chat Archive schema 也尚未确定。本次只完成不绑定数据库表的独立前端入口，不修改 Claude 旧 Chat、OAuth、MCP、Memory、LivingRoom 或生产基础设施。

## 实现

- 新增独立 `/codex-chat` 页面与首页第二屏 Codex 图标；Claude/小克入口保持不变。
- `codexChatService.js` 只负责从现有 Supabase session 读取 user access token、请求 `/api/codex/chat`，以及解析命名 SSE `session`、`text`、`error`、`done`。
- `window_id` 保存于当前浏览器窗口的 `sessionStorage`，刷新或在同一标签页重新进入时保持稳定；不同标签页互不共用。
- SSE 返回的 Codex `session_id` 仅保存在 React 页面运行状态，不写入 storage、不作为永久数据源，也不随请求回传为权威绑定。
- UI 与 fallback history 只保留最近 12 条、总计最多 16000 字符、单条最多 4000 字符；不直接访问任何 Supabase Chat 表。
- 页面支持发送、流式文本、loading、明确错误、重试以及同一 window 继续。

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
- `npm run lint`：通过；仅仓库既有 warning，新模块无 lint warning。
- `npm run build`：通过；有既有大 chunk 提示，以及 Supabase 同时被静态/动态导入的非阻断 bundler 提示。
- Claude `src/modules/chat/ChatPage.jsx` 与 `chatService.js` 相对 main diff：0。
- `git diff --check`：提交前执行并记录最终结果。

## 风险、回滚与下一步

- 后端原型尚未挂入 Nginx/PM2，因此只能完成 mock 合同验证；真实 JWT、SSE 网络分块和 Codex provider 必须等待单独 VPS E2E 授权。
- `sessionStorage` 只保证同一标签页刷新/重新进入时 window id 不变；浏览器关闭后不把旧 Codex thread 当作前端永久事实，未来由后端持久 binding 和 Raw Archive 恢复。
- 回滚方式：撤销本次单一前端提交；没有数据库或生产回滚动作。
- 下一步仅做真实 VPS E2E，不在本分支自行部署。
