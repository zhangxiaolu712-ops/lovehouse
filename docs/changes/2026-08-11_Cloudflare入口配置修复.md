# 2026-08-11 Cloudflare Worker 入口配置修复

## 做了什么

- 在 Cloudflare 部署步骤中固定使用 Wrangler `4.120.1`。
- 显式指定 `wrangler.json` 作为部署配置文件。
- 保持 Worker 入口为现有的 `src/proxy.js`，未修改 Worker、VPS 或业务代码。

## 为什么

GitHub Actions 的 `cloudflare/wrangler-action@v3` 在项目没有安装 Wrangler 时回退安装了 Wrangler `3.90.0`。该版本不会自动读取 `wrangler.json`，因此忽略了其中的 `main: src/proxy.js`，最终报错 `Missing entry-point`。

## 修改文件

- `.github/workflows/deploy.yml`
- `docs/changes/2026-08-11_Cloudflare入口配置修复.md`

## 数据库、环境变量与生产影响

- 不修改 VPS、Memory、Supabase、RLS、Toy 或生产密钥。
- 不新增或更改 GitHub Secrets。
- 仅修正 Cloudflare 部署工具版本与配置文件路径。

## 验证

- `npm run lint` 通过（仅有项目原有 warnings，无 error）。
- `npm run build` 通过。
- Wrangler `4.120.1` 执行 `deploy --config wrangler.json --dry-run` 通过；成功识别 `src/proxy.js` 和 `dist` 中的 9 个静态文件，未上传部署。
- GitHub Actions 的 Cloudflare 部署 job 只在 `main` push 时触发；分支阶段不修改触发条件，合并后由现有 workflow 自动验证。

## 风险与回滚

- 风险限于 Cloudflare 部署 job 使用的 Wrangler 版本发生变化。
- 回滚方式：撤销 `.github/workflows/deploy.yml` 中新增的 `wranglerVersion` 与 `command` 两行。

## 下一步

- 只观察本次 CI 是否通过；不继续扩展部署链。
