# 2026-08-24 Native Backend Foundation v1

## 改了什么

- 新增 `/api/v1/bootstrap`、`/api/v1/health`、`/api/v1/personas`、`/api/v1/chat`、
  `/api/v1/chat/reset`。
- 新增最小 Persona Registry、Provider Router、Claude/Codex adapter。
- 新增 LoveHouse thread → Claude provider session 的文件 binding store，原子写入并限制为 `0600`。
- 将 Claude 与 Codex 的回复统一成 `message_start/text_delta/usage/error/message_end` SSE。
- 新增定向 API、adapter、持久化和兼容回归测试。
- 增补 Client Contract、线路图、状态地图、新旧入口映射和明确未实现项。

## 为什么

Android 以后只应依赖稳定 LoveHouse contract，不应知道 Claude route、Codex sidecar、provider
session 或 VPS 内部地址。本次只加薄合同与可恢复 runtime binding，不重写已有业务。

## 未改什么

- 没有修改 Memory V2、LivingRoom、OAuth/MCP、Supabase schema/RLS、Nginx、Worker、前端。
- 没有合并 Codex sidecar，也没有删除或改变旧 `/chat`、`/reset`、`/api/codex/*`。
- 没有实现 Archive、Voice、WorldBook、Context Composer、设备配对或 GPT chat runtime。
- 没有连接或修改生产环境。

## 新配置接口

- `CLIENT_RUNTIME_BINDINGS_PATH`：可选，默认
  `/root/lovehouse-client-state/runtime-bindings.json`。
- `CODEX_CHAT_INTERNAL_URL`：可选，默认
  `http://127.0.0.1:3002/api/codex`；不会返回给客户端。

两个默认值匹配当前双 PM2/loopback 结构，因此部署不要求改现有生产 env。

## 风险与回滚

- 新 `/api/v1` 尚未经过真实 Nginx/HTTPS/Android E2E；生产部署前必须验证路径前缀与 SSE buffering。
- Claude session 可由 binding 文件恢复，但正式 Raw Chat Archive 仍未实现；不要把 binding 当聊天正文。
- 回滚无需数据库操作：将 Bridge release 指回上一 commit 即可，sidecar 和旧 API 不受影响。

## 验证

- Client API 定向测试：14/14 PASS。
- Bridge 全量：201/201 PASS。
- 新增/修改 JavaScript syntax check：PASS。
- frontend lint：PASS（仅仓库既有 warning，无新增 error）。
- frontend production build：PASS。
- `git diff --check`：PASS。
- 生产环境：未触碰。
