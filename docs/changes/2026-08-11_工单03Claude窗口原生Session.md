# 工单 03：Claude 一窗口一原生 Session

> 状态：独立 Draft 分支，等待 GPT 复审；未合并、未部署。

## 范围

本工单只处理 #59：让每个 LoveHouse 浏览器窗口绑定一个独立的 Claude CLI 原生 session。没有修改 Gate B、Memory、Toy、Cloudflare→VPS 拓扑、Supabase/RLS/anon 或生产环境。

## 根因

旧 Bridge 只有一个全局运行中 Claude 进程和一份全局 `chatContext`，每轮把最近 30 条消息手工拼进 prompt。多个窗口会共享历史、busy/abort/reset 状态，既可能串话，也不能把 `/reset` 限定到当前窗口。

## 实现

- 前端在每个标签页的 `sessionStorage` 中生成 UUID `window_id`，聊天历史和原生 session 标识也按标签页保存。
- Bridge 进程内维护 `window_id → {session_id, active process, usage}`；首轮通过 `--session-id <uuid>` 创建并绑定，后续只通过 `--resume <session_id>` 继续。
- 不使用 `--continue`，删除全局最近 30 条消息的手工重放。
- `/reset`、断开连接和 busy 状态只作用于当前 `window_id`；不同窗口可以并发。
- 只有 CLI 明确报告 session 不存在/不可恢复，或 Bridge 重启后前端仍持有旧绑定而服务端已丢失状态时，才创建新 session。fallback 原因会同时写入 Bridge 日志并通过 SSE `session` 事件显式通知前端。
- 其他 Claude/上游错误原样失败，不自动新建会话；CLI 返回与绑定不符的 session id 时也会失败，避免静默错绑。
- `stream-json` 的 result usage 会回传前端用于后续真实工作负载观察；健康检查仅返回聚合计数，不泄露 session id。

主要变更文件：

- `bridge/claudeProcess.js`
- `bridge/claudeProcess.test.js`
- `bridge/server.js`
- `src/modules/chat/chatService.js`
- `src/modules/chat/ChatPage.jsx`
- `src/modules/stats/StatsPage.jsx`
- 删除 `bridge/chatContext.js`

## 验证

- Claude session 定向测试：9/9 通过。
- Bridge 全量测试：120/120 通过。
- 前端 lint：通过，只有仓库既有告警。
- 前端 production build：通过，只有既有的大 chunk 提示。
- `git diff --check`：通过。

十轮确定性测试中，旧 Bridge 手工重放的 prompt 字符数为 2707，新 Bridge 只转发当前用户输入，共 181，Bridge 层转发文本减少 93.31%。这是字符代理值，不等于供应商计费 token，也不能单独证明额度节省。

另在 VPS 上使用 Haiku、禁用工具、每次调用最高 $0.01，做了五轮受控 CLI 对比；没有部署或重启服务：

| 模式 | input tokens | output tokens | total cost |
|------|-------------:|--------------:|-----------:|
| 旧式手工全文重放 | 1280 | 454 | $0.006735 |
| 原生 session + resume | 1957 | 211 | $0.003591 |

这次小样本中，原生 session 报告的 input token 分量高 52.89%，总成本低 46.68%；输出长度差异也影响成本，因此不能把它外推为生产节省承诺。结构性收益是消除 Bridge 自己的全局跨窗口重放，并开始暴露真实 usage 供后续观察。

## 已知边界与风险

- 窗口映射仍是进程内状态；Bridge 重启后不会信任客户端提供的 session id 直接恢复，而会显式 fallback 到新 session。
- 关闭但未 reset 的标签页会在 Bridge 进程内留下轻量窗口记录，当前没有 TTL；Bridge 重启会清除。
- `sessionStorage` 随标签页生命周期结束，符合“一窗口一会话”，但不提供跨标签页/跨浏览器恢复。

## 审后部署与回滚计划

只有 GPT 复审通过、PR 合并且用户另行批准生产按钮后，才把前端与 Bridge 以同一个明确 main commit 成对部署，因为新协议要求 `window_id`。部署 smoke 应覆盖两个并发窗口互不串话、只 reset 其中一个、缺失 session 的显式 fallback，以及 PM2/health 状态。

若回滚，前端与 Bridge 一起回到上一个明确生产 commit；本工单没有数据库、RLS 或环境变量变更，不需要数据库回滚。
