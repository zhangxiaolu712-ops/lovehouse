# LoveHouse Codex 入口

本项目的完整共同开发规则位于 `PROJECT_RULES.md`。

任何 Codex 或其他读取 `AGENTS.md` 的代理在修改文件前，必须：

1. 完整阅读 `PROJECT_RULES.md`。
2. 以当前任务相关的实际代码和配置为准核对现状，不重复实现已有功能。

`CLAUDE.md`、`docs/00_工程边界与资料索引.md`、`docs/changes/` 和 Engineering Workspace 历史资料均按任务需要查询，不作为每次施工的默认必读项。涉及对应历史、工程资料迁移、来源追溯或现状冲突时，再读取相关部分。

只要修改了项目文件，结束前必须在 `docs/changes/` 新建本次任务记录，写明：

- 做了什么
- 为什么这样做
- 修改了哪些文件
- 数据库、环境变量或部署是否变化
- 实际验证结果
- 已知风险或未完成事项
- 下一步计划

如果旧说明与 `PROJECT_RULES.md` 冲突，以 `PROJECT_RULES.md` 为准。
