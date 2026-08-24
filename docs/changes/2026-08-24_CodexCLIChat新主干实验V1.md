# Codex CLI Chat 新主干实验 v1

## 做了什么

- 在独立 `services/codex-chat/` package 纳入 sidecar、Owner auth、binding、session 与 Codex CLI
  runtime adapter 源码。
- 新增可复用 Runtime Adapter 七方法 contract；只真实实现 `codex_cli`。
- 将 Codex CLI JSONL 映射为统一 reasoning/tool/usage/quota/context 事件。
- Bridge Codex adapter 只转发 allowlist 后的安全字段；`/api/v1` 继续隐藏 provider session。
- 新增 `/#/codex-chat-v1` 最小实验页与 `/api/v1/chat` service。
- 新增 Thread 两轮 → 完全关闭 server → 新 server 第三轮的 restart 自动测试。

## 为什么

先用独立 Codex CLI 跑稳可替换 Chat 主干；以后 Claude/GPT API 只新增 adapter，不让 Android
或 Web 分别解析 provider 私有协议。

## 数据库、环境变量与部署

- 没有数据库、migration、RLS、Memory、LivingRoom、OAuth/MCP 或 Nginx 变化。
- `services/codex-chat/server.js` 延续现有 sidecar env 与
  `/root/lovehouse-codex-chat-state/thread-bindings.json` 文件；读写保持现有
  `codexThreadId/updatedAt` 磁盘格式，不需要生产数据迁移。
- 没有部署、重启 PM2 或修改生产。

## 验证

- Codex sidecar/runtime：12/12 PASS。
- Bridge Client API 定向（含独立 sidecar + Bridge restart）：16/16 PASS。
- Bridge 全量：203/203 PASS。
- 前端 service/state：4/4 PASS。
- frontend lint：PASS（仅仓库既有 warning）。
- frontend production build：PASS（保留既有大 chunk warning；新增动态 import 不拆包提示不影响产物）。
- JavaScript syntax、`git diff --check`：PASS。
- 旧 Claude Chat、Memory、LivingRoom、OAuth/MCP forbidden-path diff：0。

## 风险与回滚

- 本地 sidecar 尚未替换生产 sidecar；真实 HTTPS/PM2 E2E 仍待独立部署工单。
- Codex CLI 没有可靠 quota API，状态保持 unknown。
- 当前没有 Raw Archive；runtime session 真正丢失且 bounded recovery context 不存在时会明确失败。
- 回滚为撤销本提交；旧 Claude、旧 sidecar 和生产均未改变。

## 下一步

先审查并做 disposable/本地双进程 E2E。不要自动施工 Claude、Memory、Archive 或生产部署。
