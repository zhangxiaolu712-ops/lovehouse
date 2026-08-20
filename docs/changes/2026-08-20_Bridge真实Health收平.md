# Bridge 真实 Health 收平

- 日期：2026-08-20
- 执行者：Codex
- 状态：本地实现与验证完成，等待复审；未部署生产

## 做了什么

- 将 `/health` 响应组装提取为小型纯函数，便于验证每个字段都来自当前进程实际状态。
- 新增当前 Bridge 进程的初始化时间 `bridge_started_at`。
- 暴露实际生效的 Memory 总闸、写入、Semantic、Dream 开关，以及当前运行时已经选择的 Memory Repository 类型与选择状态。
- 删除把 Memory 总闸冒充数据库 migration 状态的 `database_migration`，并删除不表示运行事实的 `memory_system: foundation`。
- 不再读取可能漂移的 `LOVEHOUSE_DEPLOY_COMMIT` 作为发布身份；在没有可信发布注入前明确返回 `deployment_release.status: unavailable`。
- 没有增加 `schema_ready` 或 `memory_ready`：当前代码没有可直接复用的低成本真实 schema/连接探针。

## 修改文件

- `bridge/server.js`
- `bridge/health.js`
- `bridge/health.test.js`
- `docs/changes/2026-08-20_Bridge真实Health收平.md`

## 数据库、环境变量与部署

- 未连接或修改 Supabase；无 schema、migration、RPC、RLS 或数据变更。
- 未新增或修改环境变量，也未改变 Memory Repository 选择与任何业务开关。
- 未修改 Chat、Codex Chat、Claude、OAuth/MCP、LivingRoom、Worker、Nginx 或前端。
- 未部署 Bridge、未重启 PM2、未合并 main。

## 验证结果

- Health 定向测试：3/3 通过。
- Bridge 全量测试：164/164 通过。
- `node --check`：通过。
- `git diff --check`：通过。

## 风险与下一步

- `memory_repository.status: selected` 只证明当前运行时选中了该实现，不声称数据库可达或 schema 已准备完成。
- 当前无法从 Bridge 进程可靠证明实际部署 commit，因此发布身份保持 `unavailable`。后续如需显示 SHA，应由发布流程提供不可漂移且可验证的 release identity，再另行审查。
- schema readiness、migration history 和数据库连通性仍不由本次 health 证明；若需要，应另开窄工单评估真实探针，不能从 feature flag 推导。
- 本次回滚只需 revert 单个提交；当前尚未部署，因此没有生产回滚动作。
