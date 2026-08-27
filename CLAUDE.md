# LoveHouse Claude Code 施工入口

这是工程施工目录，不是聊天提示词目录。

施工前：
1. 完整阅读根目录 `PROJECT_RULES.md`。
2. 能访问 Engineering Workspace 时，先读取本任务相关的 current revision；不能访问时使用用户提供的简短接班信息。
3. 再核对本任务相关的当前代码、分支 / diff 与必要 runtime / 数据库事实。

不要把旧开发日志、旧 `docs/changes`、旧 handoff、旧 release 或生活记忆当作当前工程真相，也不要为了接班默认全仓库考古。

完整规则只维护在 `PROJECT_RULES.md`；本文件不保存项目历史、身份关系、生活记忆、当前 PID / SHA / release 快照，也不复制第二套开发规则。

自建 Claude Chat 应使用独立 cwd 和自己的聊天侧 `CLAUDE.md`，不要继承这里的施工指令。
