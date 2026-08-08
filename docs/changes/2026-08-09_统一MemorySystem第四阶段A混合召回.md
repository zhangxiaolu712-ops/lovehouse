# 2026-08-09 · Memory System Phase 4A 混合召回

## 做了什么

- 新增 exact-revision embeddings schema、领取/完成/失败的固定 actor RPC。
- 新增不可修改的 `ranking_v1` 与关键词 + 语义 RRF 混合排序。
- 原 `recall_memory` 工具和参数完全不变；Embedding、profile、actor、space 都由 Bridge 内部注入。
- provider 不可用时先持久化审计，再降级 Phase 3 关键词召回；权限或审计错误 fail closed。
- 新增 Phase 2/3/4A disposable Supabase 全回归、rollback/reinstall、Bridge 与前端验证工作流。

## 为什么

先让记忆“找得准”，并保持失败时仍能用旧的关键词能力；避免同时加入 Anchor、Dream 等后台行为后难以定位风险。

## 没有做什么

- 未实现 Phase 4B（Anchor、Dream Queue、可替换 Curator）。
- 未迁移 Legacy，未读取生产正文。
- 未修改生产数据库、VPS、Cloudflare、密钥或 Toy。
- 未开启 `MEMORY_SYSTEM_ENABLED` / `MEMORY_SEMANTIC_ENABLED`，未部署。

## 下一步

保持 Draft，等待 GPT/工程二审与免费 CI 全绿。任何生产启用或 Phase 4B 都必须另开工单与分支。
