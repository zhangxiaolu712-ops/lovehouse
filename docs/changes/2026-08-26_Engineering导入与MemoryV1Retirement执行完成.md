# Engineering 导入与 Memory V1 Retirement 执行完成

- 日期：2026-08-26
- 执行者：Claude Code
- 状态：**P0（Memory V1 Retirement 数据迁移）与 P1（unique code 保全）已完成并通过验收；Engineering 第二批正式导入已完成并通过 read-back；P2（本文档收口）进行中。未执行：任何删除、生产部署、PM2/Nginx 重启、OAuth 修改、GitHub push/PR。**

本记录承接 `docs/changes/2026-08-26_工程资料收口与规划边界.md`（GPT-5.6 Sol，规划与来源地图）和 `docs/engineering-import-2026-08-26.md`（第二批导入规划清单），记录规划之后**实际执行**的部分。

## 做了什么

### 1. Engineering 第二批正式导入（Supabase `memory_v2_entries`, `space_key='engineering'`）

- 直接读取 11 份原件（未凭摘要重写），逐份计算 SHA-256 核对：
  - `LoveHouse_工程错题集_V1.md`、`_待人工确认.md`、`2026-08-23_工程错题集V1.md`（worktree `lovehouse-deploy-df007fa5`）
  - `2026-08-24_CHAT_FOUNDATION_V1_HANDOFF.md`、`2026-08-23_原生App产品边界与工程问答.md`、`2026-08-25_MemoryV1_Retirement完整施工接班包.md`、`2026-08-25_功能施工清单前端V1.md`（`/root/lovehouse-deploy`）
  - `2026-08-20_CodexChat最小生产接入.md`、`2026-08-20_LoveHouse独立CodexChat原型.md`（`/root/lovehouse-bridge`）
  - `2026-08-24_Codex前端与ElevenLabs语音接线.md`（worktree `lovehouse-deploy-3f18558d`）、`2026-08-23_ChatGPT两按钮卡片实验.md`（worktree `lovehouse-deploy-40b0edc5`）
- 现场重新实测生产锚点（未直接采信历史对话数字）：Bridge `c894913e1548cc89b9ccf6868d400b51e62fa149`:3000、Codex sidecar `cd01151310de54e5c64fb3dbecc8aa00fd43ccf0`:3002（loopback）、Claude sidecar `6c8c47505dbc1f6d4483097dc2badb93dedfc265`:3003（loopback），Nginx 静态根 `/root/lovehouse-dist`，另发现一条平行的 Cloudflare Worker（`tight-heart-93aa`）代理链路，此前未记录。
- 新建 38 个 Engineering subject，2 个既有 subject（`lessons.engineering-mistakes.migration`、`feature.chat-natural-segments-v1`）追加 revision。工程错题集 16 条已验证错题按稳定 `subject_key`（`lessons.engineering-mistakes.<slug>`）逐条独立导入，content/quote_text 与原文逐字核对一致；6 条候选 + Worker IP→Cloudflare 1003 分支记录导入 `lessons.engineering-mistakes.pending-candidates`，`status=pending`，未升级为已验证事实。
- 完整 subject_key 清单、与原规划的偏离说明，见 `docs/00_工程边界与资料索引.md` 第 2.1、8.1 节。
- Engineering 空间现共 **45 subject / 48 revision / 54 source**。

### 2. P0：Memory V1 Retirement（`brain` / `memories` → Memory V2 `claude` 私有空间）

- 只读核查生产真实状态：`brain` 393 行、`memories` 21 行、canonical V1（`memory_entries`）70 行（其中 52 行经既有 `legacy_source='canonical_v1'` 标记确认已在 Phase 2B 迁入 V2，18 行未迁——不在本轮范围，未处理）。历史文档中的 343/380/52/55 等数字仅作历史参考，验收全部使用本轮现场查询结果。
- 结构分析确定 actor：`brain`/`memories` 的 `author`/`speaker`/内容/时间范围（2026-07-28 起）全部早于 GPT 拆分，100% 属于 Claude（"小克"）私有生活记忆，无一行指向 GPT，因此全部迁入 `space_key='claude'`，未触碰 GPT/Shared 空间。
- Dedupe 映射：`brain.source_table='memories'` 的 18 行与 `memories` 表逐字重复（内容、`created_at` 完全一致），迁移时只在 `brain` 迁出的 revision/`source` 上同时记录两边 provenance（`legacy_memories_dedup_id` 等字段），未重复 INSERT。`memories` 中另外 3 行（id 107/108/109）内容是误存进生活表的工程记录（小客厅上线、聊天接口鉴权、proxy 修复），已按"原表保真迁移 + `content_classification_note` 标注为已过期/已被 Engineering Workspace 现场记录取代"处理——不丢弃原文，也不写进 Engineering（避免生活正文进工程区）。
- 正式迁移：单条可回滚的 SQL 事务（`WITH ... AS MATERIALIZED` + 链式 `INSERT ... RETURNING`），393（brain）+ 3（memories 补充）= 396 条新 `memory_v2_entries`。保真字段（`content`/`created_at`/`memory_date`→`event_time`/`title`/`kind`/`tag`/`speaker`/`feeling`/`mood`/`is_special`/`special_label`/`ref_id`/`source_table`/`source_id`/`author`/旧 `status`/`awaken_count`/`last_awakened_at`/`decay_score`/`last_accessed_at`）全部写入 `metadata.legacy_*`，未直接映射为 V2 的 `human_importance`/`ai_importance`/`recall_count` 或新排序权重。`human_importance`/`ai_importance` 全部留空。
- 该批量写入触发了 Auto Mode 权限分类器拦截（大规模生产数据写入），已停下向用户确认，用户批准后才执行。
- 验收（P0 §7 全部完成）：迁移前后 count 对照（`claude` 空间 60→456，精确匹配 +396）；6 条随机 id 逐字 `content`/`created_at`/`author` 抽样对照全部一致；幂等复跑检查（"再跑一次是否会漏还是会重复"）确认 0 条缺失、0 条重复；`open`/`read-back` 端到端验证（entry→current_revision→content→source.quote_text）正常；GPT（84 active + 1 superseded）、Shared（2 active）空间行数未变化；candidate Shared 未被升级。
- **未完成**：embedding backfill。现有 `bridge/memory-v2/backfill.js` 硬编码 `ACTORS=['gpt','claude']` + `TARGET_LIMIT=50`（超过即抛 `MEMORY_V2_BACKFILL_TARGET_LIMIT` 并拒绝执行）；迁移后 `claude` 空间已有 456 条 active，远超该脚本设计容量，脚本会立即拒绝。本轮未修改脚本逻辑、未强行绕过，也未确认生产 Ollama embedding 服务从本 VPS 是否可达（`127.0.0.1:11434` 探测无响应，真实 endpoint 由未读取的环境变量指定，可能在另一台机器）。这是遗留给后续任务的真实阻塞项，不是本轮遗漏。
- **产品层重要发现（影响退休节奏）**：`brain`（路由 `/brain`，`src/modules/brain/brainService.js`，功能完整：增删改查、awaken/fade 状态、搜索、随机抽取、feeling 回复等）与 `memories`（路由 `/memory`，`src/modules/memory/memoryService.js`）**都仍在被当前生产前端实时读写**，均已在 `src/core/router.jsx` 注册路由，不是冻结表。`canonical V1`（`memory_entries`）只在 `bridge/server.js:175` 一行注释中被提及（"Fail closed until the future memory_entries migration has been reviewed"），没有实际调用路径，可归类为 legacy 但仍可访问。**本轮不建议、也未执行把 `brain`/`memories` 转入 freeze/只读观察期**——这会直接关闭用户正在使用的记忆页功能，是产品决策，不是数据迁移任务能单方面触发的动作。

### 3. P1：unique code 保全与去留判断

- `lovehouse-deploy-3f18558d`：与当前 `origin/main`/本地真实 HEAD（`f14e134294858ed167894f6a91d086f0f3cb088f`，见下方"更正"）做代码级 diff。确认 `bridge/client-api/{clientApi.js,memoryTimeline.js,memoryTimeline.test.js}`、`src/modules/gpt-memory/*`、`src/modules/unified-chat/*`、`src/modules/project/*` 及 `router.jsx`/`AppShell.jsx`/`Home.jsx` 的未提交改动此前从未出现在任何分支历史中（`git log --all` 零命中）。逐项去留判断：
  - `memoryTimeline.js`（`GET /v1/memory/:actor/timeline`，直接查询 `memory_v2_entries`+`memory_v2_revisions`）与 `gpt-memory` 前端：**生产完全没有等价功能，功能独特**，需要接线才能用。
  - `unified-chat`：UI 设计（reasoning/tool-events/usage/quota 面板）有参考价值，但依赖已被主线淘汰的 `codex-chat-v1`/`claude-chat-v1` 服务，**需要重新适配当前后端才能复用**，不能直接部署。
  - `src/modules/project/*`：与已上线的 `feat/b612-project-checklist-v1` 施工清单功能逻辑完全相同（`STATUS_META`、同一组件结构），只是压缩格式的早期草稿，**已被主线完整取代**。
  - `clientApi.js`（`/v1/bootstrap`、`/v1/health`、`/v1/chat` SSE 等）：production 的 `/api/v1/*` 契约由别处实现（未找到与此文件同构的代码），这部分判定为**独立草稿，功能上已被主线用不同实现取代**，只有其中的 memory timeline 端点是novel的。
  - 全部内容按原样（不重构）提交到新分支 `archive/3f18558d-unique-code-20260826`（该 worktree 当前 HEAD 之上一个 commit），provenance 写在 commit message 里。未合并进 main，未 push，未部署。
- `lovehouse-deploy-40b0edc5`：`experiments/chatgpt-choice-card/`（含未提交的 `server.mjs`/`package.json`）按原样提交到新分支 `archive/40b0edc5-chatgpt-two-buttons-20260826`，`node_modules` 未入历史。
- `agent/codex-voice-frontend-20260824`（commit `eda2b083`，worktree `lovehouse-deploy-4e562e9c`）：核实为干净、已具名、`git merge-base --is-ancestor` 确认**未合并**进 `origin/main` 或 `f14e134`。已经是安全的具名分支，本轮无需额外操作，继续保留。
- `lovehouse-deploy-854bacea`（`agent/bridge-port-isolation-fix-20260825`，commit `96791c6f`）：用 `git merge-base --is-ancestor 96791c6f origin/main` 核实，**结果为真——该 commit 已经是 `origin/main` 的祖先**，即已通过 PR #59/#60 合入主线，不是独立未合并代码。这更正了上一轮对话中对该 worktree 的误判（曾错误归类为"unique_orphan 待确认"）；`docs/engineering-import-2026-08-26.md` 原本的分类（"Port fix branch 已合并进 c894913…，clean"）其实是对的。
- `c894913e1548…-dc37b53c`（本工程文档 worktree，4 处未提交改动）：按要求作为本轮 in-progress 工作区保留，未清理。

### 4. 发现并更正：本地 Git HEAD 与 `origin/main` 的真实关系

- `/root/lovehouse-deploy` 的本地 `HEAD` 实际是 `f14e134294858ed167894f6a91d086f0f3cb088f`（分支 `feat/b612-project-checklist-v1`），比 `origin/main`（`c894913e1548cc89b9ccf6868d400b51e62fa149`）多两个**未推送到 GitHub** 的本地 commit：`400dd5c`（"feat: add B612 project checklist"）、`f14e134`（"chore: add B612 static release script"）。
- 上一轮 Engineering 导入对话中曾错误汇报"lovehouse-deploy 的 HEAD 与 origin/main 完全一致"，这是错的，本记录予以更正。
- 进一步确认：**生产静态前端** `/root/lovehouse-dist` 是从这个未推送的 `f14e134` 构建部署的（`src/modules/project/*` 只存在于这个本地 commit，`origin/main` 的 git tree 里没有），而**生产 Bridge 后端**部署的是 `origin/main` 本身的 `c894913`。即当前生产前后端并非同一 commit 构建，且前端源码尚未同步回 GitHub。

## 为什么这样做

- P0 严格按用户给定的 8 步流程执行（现场盘点 → dedupe mapping → dry-run → 正式迁移 → actor/space 边界 → transaction/embedding 分离 → 验收 → 依赖扫描），核心目的是避免"凭猜测归类 actor"或"重复生成副本"。
- 大规模生产数据写入（396 条个人生活记忆）触发 Auto Mode 分类器拦截后，选择停下汇报 dry-run 结果、由用户显式批准后再执行，而不是尝试绕过分类器。
- P1 对 unique code 一律"保全优先于清理"：先原样提交到独立归档分支保留 provenance，不重构、不直接改 main、不部署；对"看起来重复"的 worktree（854bacea）用 `merge-base --is-ancestor` 而不是"commit SHA 不同就当作独立"这种粗糙判断来复核，避免把已合并代码误判为待保全资产，也避免把真正独有的代码误判为重复。

## 修改文件

**代码/分支（均为归档提交，未改动 main，未部署）：**

- worktree `lovehouse-deploy-3f18558d`：新分支 `archive/3f18558d-unique-code-20260826`（1 个内容提交 + 说明性 commit message）。
- worktree `lovehouse-deploy-40b0edc5`：新分支 `archive/40b0edc5-chatgpt-two-buttons-20260826`（1 个内容提交 + 1 个 `.gitignore` 清理提交）。

**数据库：**

- Supabase `memory_v2_entries`/`memory_v2_revisions`/`memory_v2_sources`/`memory_v2_revision_sources`：新增 396 条 `space_key='claude'` 记录（P0）+ 38 个新 `space_key='engineering'` subject、2 个既有 subject 追加 revision（Engineering 第二批）。

**文档（本工程文档 worktree，尚未 commit）：**

- `docs/00_工程边界与资料索引.md`：更新第 2 节（新增 2.1 实际导入结果）、第 4 节（unique orphan 状态更新、Git HEAD 更正）、第 5 节（差异扫描标记为已导入完成并给出 subject 对应关系）、第 6 节（清理门禁逐项打勾）、新增第 8 节（P0/P1/第二批导入完成状态总结）。
- `docs/engineering-import-2026-08-26.md`：标注为"已执行完成"，冻结为历史记录，不再作为查询入口。
- 本文件：新增变更记录。

## 数据库、环境变量与部署

- 数据库：如上，仅 INSERT，无 UPDATE/DELETE/DROP/TRUNCATE；唯一的 UPDATE 是把 `lessons.engineering-mistakes.migration` 与 `feature.chat-natural-segments-v1` 两个既有 entry 的 `current_revision_id` 指向新 revision（标准的 Engineering revision 追加流程，非破坏性）。
- 环境变量：无变化。
- PM2 / Nginx / Cloudflare Worker / 生产 release：无变化，未重启、未切流、未部署。
- GitHub：本轮全部改动（archive 分支 + 本文档）目前只存在于对应 VPS worktree 本地，未 commit push、未开 PR、未合并。

## 实际验证

已执行：见"做了什么"第 1-3 节中每一步列出的验收方法（Engineering read-back、P0 §7 全部 9 项、P1 的 `merge-base --is-ancestor` 复核）。

未执行：

- Embedding backfill（原因见第 2 节"未完成"）。
- `brain`/`memories` 进入 freeze/只读观察期（原因见第 2 节"产品层重要发现"）。
- 归档分支 push 到 GitHub / 创建 PR。
- 本文档 commit。
- 任何删除操作（worktree、branch、release、数据库表/行）。

## 已知风险或未完成事项

- Embedding backfill 阻塞：`backfill.js` 的 `TARGET_LIMIT=50` 设计已经不适应当前数据规模（claude 空间 456 条），需要先重新设计该脚本（例如分页/按 actor 拆批），而不是简单调大常量了事——常量背后是"读取全部 current revision 并一次性处理"的实现方式，规模上去后本身就需要重新设计。
- `memories`/`brain` 两表仍在被生产读写，本轮的 396 条 V2 副本是"此刻快照"，之后用户在 `/brain`、`/memory` 页面新增/修改的内容不会自动同步进 V2，这是待设计的增量同步问题，不在本轮范围。
- `lessons.engineering-mistakes.pending-candidates` 与工程错题集里 Worker IP literal → Cloudflare 1003 分支记录的最终归属仍未确认（沿用原状态，本轮未做进一步核实）。
- canonical V1 剩余 18 条未迁入 V2 的记录，本轮未处理（用户未要求，不在授权范围）。
- `experiment.gpt-memory-timeline` 对应的 Engineering 事实记录未写入（代码已保全在归档分支，但对应的"这个功能存在过"的 Engineering subject 本轮未建），留作后续任务。

## 下一步计划

1. 视用户决定，是否需要把两个 `archive/*` 分支 push 到 GitHub 并开 Draft PR（本轮未做，等待明确指示）。
2. 重新设计 `backfill.js` 的批量/分页策略后再执行 embedding backfill。
3. 补齐 `experiment.gpt-memory-timeline` 的 Engineering 事实记录。
4. 待用户对 `brain`/`memories` 的产品去留（继续双写、迁移到新前端、还是维持现状）做出决定后，再规划下一阶段的旧表退役路径。
