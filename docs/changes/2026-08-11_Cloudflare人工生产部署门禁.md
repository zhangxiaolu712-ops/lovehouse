# 2026-08-11 Cloudflare 人工生产部署门禁

## 做了什么

- 为现有 GitHub Actions workflow 增加 `workflow_dispatch` 手动入口。
- `pull_request` 仅运行 build job，用于在合并前验证改动，不部署 Pages 或 Cloudflare。
- 普通 `main` push 继续自动构建并部署 GitHub Pages，但不再执行 Cloudflare 生产部署。
- Cloudflare job 仅在手动运行、ref 为 `main` 且 `ENABLE_CLOUDFLARE` 未显式关闭时执行。
- 将不同事件和 ref 分到不同并发组，避免 PR、Pages 与人工 Cloudflare 发布相互取消。

## 为什么

PR #38 已修复 Worker entry-point/config，但其合并仍触发旧 workflow 自动执行 `wrangler deploy`。Cloudflare 官方文档说明该命令会创建新版本并立即把它部署到 100% 流量，因此普通 merge 不能继续隐式修改生产。

## 修改文件

- `.github/workflows/deploy.yml`
- `docs/changes/2026-08-11_Cloudflare人工生产部署门禁.md`

## 数据库、环境变量与生产影响

- 不修改 Worker 业务代码、Cloudflare 路由或 Cloudflare 当前生产版本。
- 不修改 VPS、Bridge、Memory、Supabase、RLS、Toy 或任何密钥。
- 不新增或更改 GitHub Secrets。
- 本次 PR 合并后，Cloudflare 发布方式由自动变为人工触发；合并本身不再部署 Cloudflare 生产。

## 验证

- 已只读确认 PR #38 的 `main` push workflow run `31473025529` 全绿，并由旧流程自动发布 Worker version `f05b6bf4-187a-47a8-8500-ccd7a9fd3753`。
- `npm ci`：通过。
- `npm run lint`：通过（仅保留既有 warning，无 error）。
- `npm run build`：通过（仅保留既有 chunk-size warning）。
- `wrangler@4.120.1 deploy --config wrangler.json --dry-run`：通过；读取 9 个静态资产并在上传前退出，未部署。
- 事件矩阵断言：通过；PR 仅 build、Pages 仅 push、Cloudflare 仅 `workflow_dispatch` + `main`，并继续尊重关闭开关。
- Draft PR #40 的 Actions run `31486599959`：`build` 成功，`deploy-pages` 与 `deploy-cloudflare` 均按门禁跳过。
- Cloudflare Git 集成仅生成 commit/branch preview；preview 根路径返回 200，`/api/health` 能经 Worker 入口到达 Bridge 并按未认证请求返回 403。
- Cloudflare 面板核对：生产 active version 仍为 `f05b6bf4`、100% 流量，未被本分支预览替换。
- `workflow_dispatch` 只能在本 workflow 进入默认分支后做真实 Actions 验证；当前分支不会部署生产。

## 风险与回滚

- 风险限于 Cloudflare 发布操作从自动改为人工，后续发布需要有仓库写权限的人明确运行 workflow。
- 手动选择非 `main` ref 时 Cloudflare job 会跳过，不修改生产。
- 回滚可撤销本次 workflow 变更，但会恢复普通 merge 自动部署生产的风险；除非已有等价 gate，否则不建议回滚。

## 下一步

- 审阅窄 Draft PR #40 的 workflow diff，不部署。
- 合并后先验证 `main` push 只执行 build/Pages；再单独确认是否从 `main` 手动发布 Cloudflare。
- 工单 01 验收前不开始工单 02。
