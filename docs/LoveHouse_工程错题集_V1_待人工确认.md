# LoveHouse 工程错题集 V1：待人工确认条目

以下 6 项有复用价值，但本轮证据不足以写成正式错题：

1. **生产 smoke 曾把 Bridge 端口写成 3001**：当前保留脚本已是 3000，未找到失败版本或日志，无法证明具体误回滚过程。
2. **history smoke 曾混用 `revision_id` 与 `revisions[].id`**：现有 harness/test 只有修正后结构，未找到原失败断言的可追溯提交。
3. **Ollama 冷态约 6.9 秒、热态约 0.9 秒与生产 8 秒 timeout 的精确事件**：仓库能证明 embedding timeout/fallback 与 46/58 续跑，但没有保存这组三次测量原始输出。
4. **Claude 工具渐进式发现 5 → 6 → 7**：没有保存客户端 tools/list 回执或服务端对应 request trace。
5. **called_at 合并后，生产真实 GPT/Claude 客户端是否都返回**：代码、测试和合并证据存在；本轮没有可复核的生产 runtime 与两端真实响应，不能宣称生产部署或客户端验收完成。
6. **importance 接口接受小数而数据库字段为 smallint**：当前 MCP schema 与 migration 存在类型张力，但未找到实际失败请求、错误日志或已验证修复，因此暂不作为已发生 Bug。

另有 Worker IP literal → Cloudflare 1003 的分支记录 `d09a638`，但该记录只证明 preview 修复，当前 main/生产归属未在本轮复核；待确认其最终部署状态后再决定是否收入。
