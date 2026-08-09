# 统一 Memory System 第四阶段 B：Dream 基础层

## 做了什么

- 新增默认关闭的小批量 Dream Queue。
- 新增最多 12 条的内部 Anchor 能力与释放历史。
- 新增可替换 Curator provider 接口，不绑定 GPT 或 DeepSeek。
- Dream 输出只写 pending ingest candidate，并保存 exact revision、dream actor、source actor、perspective、provider/model provenance。
- 新增 disposable Supabase fresh/rollback/reinstall 和 Phase 2/3/4A/4B 全回归工作流。

## 为什么

让记忆系统能在后台提出整理建议，同时保证任何 AI、来源 revision 和视角都不会从叙事里消失；失败或模型幻觉也不能静默覆盖原始记忆。

## 安全边界

- 未增加 MCP 工具或 recall 参数。
- 未开启 `MEMORY_DREAM_ENABLED`。
- 未迁移 Legacy、未部署、未修改生产数据库/密钥/VPS/Toy。
- Curator 无数据库权限；Shared 建议不会自动批准。

## 下一步

等待 Draft PR 工程审阅。候选审批/转换、Anchor UI、情感/日记/盲盒/潮汐均不在本阶段实现。

