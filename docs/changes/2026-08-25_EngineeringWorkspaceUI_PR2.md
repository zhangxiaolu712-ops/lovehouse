# Engineering Workspace UI PR2

- 日期：2026-08-25
- 分支：`agent/engineering-workspace-ui-pr2-20260825`
- 基线：PR1 `de7e835`
- 状态：实现与本地验证完成；未 merge、未 deploy

## 做了什么

- 新增独立 `/engineering` 工程区和首页入口。
- 按 `category / component / subject_key` 分类浏览；未知 category 和分类缺失均正常展示。
- Owner 可新增、按稳定 subject 修订、归档与恢复；分类不完整不阻止保存。
- 详情展示 append-only revision history，并按需通过独立接口展开 source evidence 原文。
- 分类及组件建议集中在 `engineeringCategories.js`，允许以后直接追加模块，也允许未配置的新值先落盘。
- 新增前端 Bridge API service 与测试；所有请求从现有 Supabase session 取得 Owner bearer token，不直连数据库，不使用 service role。

## 为什么

PR1 已建立 Engineering space、Owner API 和权限边界。本 PR 只补齐可用的 Owner UI 闭环，保持数据库模型和 Bridge 权限设计不变。

## 修改文件

- `src/modules/engineering/EngineeringWorkspacePage.jsx`
- `src/modules/engineering/engineeringWorkspace.css`
- `src/modules/engineering/engineeringCategories.js`
- `src/modules/engineering/engineeringService.js`
- `src/modules/engineering/engineeringService.test.js`
- `src/core/router.jsx`
- `src/shared/Home.jsx`
- `docs/02_当前架构.md`
- `docs/06_待开发列表.md`
- 本记录

## 数据库、环境变量与部署

- 数据库表、字段、migration、RLS 均无变化；没有修改 PR1 数据库设计。
- 环境变量无变化；前端没有 service role。
- 未 merge、未 deploy。

## 验证

- `node --test src/modules/engineering/engineeringService.test.js`：通过。
- `npm run lint`：通过，只有仓库既有 warning。
- `npm run build`：通过，只有既有 dynamic-import/chunk warning。
- `git diff --check`：通过。

## 已知风险与下一步

- 本地未连接生产数据做浏览器 E2E；需在 PR review 环境用 Owner 登录态验收真实 Bridge API。
- 单次编辑器只提供一个新 source evidence 输入；历史版本可展示多个已有 source。批量来源编辑可在后续按真实需求扩展。
- 等 Draft PR CI 与人工审阅；本 PR 不 merge、不 deploy。
