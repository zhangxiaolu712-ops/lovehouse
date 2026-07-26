# LoveHouse Codex 入口

本项目的完整共同开发规则位于 `PROJECT_RULES.md`。

任何 Codex 或其他读取 `AGENTS.md` 的代理在修改文件前，必须：

1. 完整阅读 `PROJECT_RULES.md`。
2. 阅读 `CLAUDE.md` 中与任务有关的项目状态和历史。
3. 查看 `docs/changes/` 中最近的变更记录。
4. 以当前实际代码为准核对文档，不重复实现已有功能。

只要修改了项目文件，结束前必须在 `docs/changes/` 新建本次任务记录，写明：

- 做了什么
- 为什么这样做
- 修改了哪些文件
- 数据库、环境变量或部署是否变化
- 实际验证结果
- 已知风险或未完成事项
- 下一步计划

如果旧说明与 `PROJECT_RULES.md` 冲突，以 `PROJECT_RULES.md` 为准。
