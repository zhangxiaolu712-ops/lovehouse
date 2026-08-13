# #113 Claude Session 恢复与受控 Fallback

> 状态：独立 Draft 分支，等待 GPT / 小婷复审；未合并、未部署。

## 范围与原因

工单 03 已实现“一窗口一 Claude 原生 session”，但 Bridge 进程重启后会丢失内存中的 `window_id → session_id` 映射。旧实现即使前端仍携带有效 `known_session_id`，也会直接新建 session，造成可恢复的上下文丢失。

本次只补齐 #125 / #113 指定的 session 恢复与受控 fallback，不恢复旧全局 `chatContext`，不新建聊天数据库，不改 Memory、Supabase、Cloudflare、PM2、Toy 或 Gate B/C。

## 实现

- `/chat` 新增明确的 `session_intent: "new" | "continue"`：
  - Bridge 有当前窗口映射时继续原有 `--resume`；
  - 映射丢失、`continue` 且 `known_session_id` 有效时，先以该 id 执行 `--resume`，状态为 `resumed_known_session`；
  - 只有 Claude 明确报告 session 不存在/不可恢复，或 `continue` 请求缺少/携带非法 known id 时，才创建新 id，状态为 `fallback_new_session`；
  - 真正首轮或 reset 后创建状态为 `new_session`；普通映射内续聊状态为 `resumed_session`。
- 非 session 类的上游、权限或 MCP 错误不会触发 fallback；失败后的再次尝试仍保持 resume 语义。
- `/reset` 继续只影响当前 `window_id`，并留下单次失效标记，阻止并发或陈旧请求把 reset 前的 known session 复活。
- fallback 可选接收 `recent_history`，只在新 session 的第一次 prompt 注入一次：最多 30 条、仅 `user/assistant` 文本角色、单条最多 2000 字符、总计最多 20000 字符和 32000 UTF-8 bytes；禁止尾部重复当前用户消息。后续轮次恢复正常 `--resume`，不重复注入。
- SSE `session` 事件明确返回 mode、fallback reason 和是否实际 bootstrap 历史；前端保存该状态并用不含内部错误的提示告知用户。
- Claude 启动策略和 MCP init 门禁不变：内建工具继续关闭，LoveHouse 白名单仍精确为 14 项；恢复与 fallback 两条路径都必须通过相同门禁。

## 历史来源盘点

当前仓库没有可靠的、持久化的 LoveHouse 单人聊天消息 repository。现有当前窗口历史只存在该标签页的 `sessionStorage`。

因此本次没有把浏览器存储定义为长期真相，也没有建立聊天 V1 数据库。前端只把当前窗口内的 bounded 文本记录作为可选的一次性 fallback input；服务端独立复验全部限制。未来接入稳定 message repository 后，可以替换该输入来源，不需要改变 session/fallback 状态契约。

## 修改文件

- `bridge/claudeProcess.js`
- `bridge/claudeProcess.test.js`
- `bridge/server.js`
- `src/modules/chat/chatService.js`
- `src/modules/chat/ChatPage.jsx`
- `docs/06_待开发列表.md`
- `docs/changes/2026-08-13_ClaudeSession恢复与受控Fallback.md`

## 数据库、环境变量与部署

- 数据库 / migration / RLS：无变化。
- 环境变量 / secret：无变化。
- Worker / Cloudflare / Bridge / PM2：未部署、未修改生产配置、未重启。
- Gate B / Gate C：未执行。

## 验证

- session / policy 定向测试：23/23 通过。
- Bridge 全量测试：147/147 通过。
- Claude 精确 MCP 白名单：14 项，恢复与 fallback 测试通过；MCP failed 继续 fail closed。
- 前端 `npm run lint`：通过；仅仓库既有 warnings。
- 前端 `npm run build`：通过；仅既有大 chunk 提示。
- `git diff --check`：提交前执行并记录最终结果。

## 风险、回滚与下一步

- 尚未在生产 Claude CLI 上做真实恢复 smoke，因为本工单明确禁止部署；生产效果仍待审阅、合并及单独生产授权后验证。
- 没有持久聊天库时，fallback 历史只能来自调用方可选输入；缺失时会明确创建无历史的新 session，不伪造上下文。
- 回滚方式是撤销本次单一提交；没有数据库或生产配置回滚动作。
- 下一步仅等待 Draft PR 复审，不 merge、不 deploy、不启动 Gate B/C。
