# 2026-08-25 Bridge Port Isolation Fix

## 做了什么

- 将 Bridge 监听端口从硬编码 `3000` 改为读取 `PORT` 环境变量；未提供时仍默认 `3000`。
- 新增严格端口校验，只接受 `1..65535` 的整数。
- 新增独立单元测试，覆盖默认端口、显式测试端口和非法端口。

## 为什么

Engineering Workspace 生产发布前的隔离 smoke test 发现，Bridge 在 `server.js` 中硬编码监听 `3000`，导致即使临时进程配置 `PORT=3101` 仍会抢占生产端口，无法安全验证新 release。

## 修改文件

- `bridge/server.js`
- `bridge/runtimeConfig.js`
- `bridge/runtimeConfig.test.js`

## 数据库、环境变量与部署影响

- 不修改数据库 schema、RLS、Memory、Chat、Claude/Codex Adapter、Nginx 或生产配置。
- 新增可选 `PORT` 运行时配置；未设置时保持生产默认 `3000`，因此对现有生产行为向后兼容。
- 本次仅在隔离 worktree 修改代码，未部署生产、未重启 PM2、未切 release。

## 验证

- `cd bridge && npm install --omit=dev`：依赖安装成功，audit 0 vulnerability。
- `cd bridge && npm test`：213/213 通过，包含新增 `resolveBridgePort` 3 组测试。

## 已知风险 / 未完成事项

- PM2 同名 fork-mode 进程使用 `startOrReload` 无法可靠切换 `pm_exec_path/pm_cwd`；该问题与本次端口修复独立，仍需单独建立安全 release cutover 流程。
- 在真正生产切换前，应先用新 release + 非 3000 端口完成隔离 smoke test，再执行 PM2 canonical record 的受控重建。

## 下一步

1. 将本修复提交并合并到 main。
2. 创建包含本修复的新 release。
3. 使用 `PORT=3101`（或其他确认空闲端口）做隔离 smoke test。
4. 验证通过后，再按受控流程切换生产 `lovehouse` PM2 记录；失败则保留当前旧 release。
