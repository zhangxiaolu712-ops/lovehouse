# Engineering Workspace 旧工程资料导入清单 · 2026-08-26

> 过渡文件。只用于本轮"旧资料 → Engineering Workspace"迁移与读回验收；导入完成后不作为长期入口，最终由 `docs/00_工程边界与资料索引.md` 接管。
>
> **状态：本清单已执行完成**（2026-08-26）。第 7 节列出的第一批 6 个 subject 已在此前完成；本文件规划的第二批全部主题（含拆分为 16 条独立 subject 的错题集）也已导入并通过 read-back，实际 subject_key 清单、与本规划的偏离、以及 P0 Memory V1 Retirement / P1 unique code 保全结果，均已写入 `docs/00_工程边界与资料索引.md` 第 8 节（长期权威版本）。本文件保留作为原始规划的历史记录，不再更新，不作为查询入口。

## 1. 当前实测锚点

2026-08-26 本轮只读核验：

- `origin/main`：`c894913e1548cc89b9ccf6868d400b51e62fa149`。
- Bridge：PM2 `lovehouse` online，`pm_exec_path=/root/lovehouse-deployments/c894913.../bridge/server.js`，`pm_cwd` 同 release，监听 `:3000`。
- Codex sidecar：PM2 `lovehouse-codex-chat` online，release `cd011513...`，监听 `127.0.0.1:3002`。
- Claude sidecar：PM2 `lovehouse-claude-chat` online，cwd release `6c8c475.../services/claude-chat`，监听 `127.0.0.1:3003`。
- Nginx：`b612.fyi` / `tingtunehouse.duckdns.org`，静态根 `/root/lovehouse-dist`；代理当前包含 Bridge `3000` 与 Codex `3002`，80/443 正常监听。
- 生产静态包内可见 `Codex v1`、`Claude v1`、`工程区`；未发现旧 Project Checklist / GPT Memory prototype 字符串。

上述 PID 属易变快照，不进入长期 current 文本；release/端口/职责可以作为本轮当前 revision 的 source evidence。

## 2. 第一批 subject_key 与来源

| subject_key | 当前/历史内容 | 主要来源 | 导入策略 |
|---|---|---|---|
| `infra.artifact-locations` | 工程资料曾散落位置、source-of-truth 顺序、清理门禁 | 当前 VPS 扫描；`docs/00_工程边界与资料索引.md` | 当前 revision 写“以后去哪找”，旧路径只留历史/source |
| `infra.ports.registry` | 80/443/3000/3002/3003 当前职责 | 2026-08-26 `ss` + PM2 + Nginx | 当前 revision；以后端口变更追加 revision |
| `infra.routing.nginx` | `b612.fyi` 静态 root 与 `/api`/OAuth/MCP/Codex 代理职责 | 当前 `nginx -T`；OAuth 收口 change record | 当前实测优先，旧 DuckDNS/Worker 描述为历史 |
| `infra.deployment.b612-static` | `/root/lovehouse-dist` 静态发布与备份/验收规则 | 8/25 全景基线、Chat 发布记录、当前 Nginx | 旧发布作为历史；current 不固化一次性 PID |
| `infra.deployment.pm2-cutover` | release cutover、隔离端口 smoke、PM2 `startOrReload` 同名 fork 陷阱 | `2026-08-25_BridgePortIsolationFix.md` + 8/25 实际 cutover | 历史故障 → 修复 → 当前受控 fresh-create 流程 |
| `runtime.bridge.production` | Bridge 当前 release/职责/端口 | 当前 PM2 + `origin/main` | current = `c894913...`, port 3000 |
| `runtime.codex.production` | Codex mainline、独立 sidecar、Thread/Resume/usage/reasoning 边界 | Chat Foundation；Codex mainline changes；当前 PM2 | `cd011513...` 作为当前 runtime release |
| `runtime.claude.production` | Claude Adapter、setup-token、独立 sidecar、当前 runtime release | Chat Foundation；Claude Adapter change；当前 PM2 | `6c8c475...` 作为当前 sidecar release，不强行与 Bridge 统一 |
| `chat.client-api` | `/api/v1` 稳定 Client Contract、Persona/Thread/Provider Router | Native Backend Foundation；Chat Foundation | 历史“未部署”必须被后来 E2E/当前代码修订 |
| `chat.runtime-binding` | LoveHouse Thread != provider session；Bridge/sidecar restart 恢复 | Chat Foundation + runtime adapter changes | 长期架构事实链 |
| `chat.natural-segments` | casual 才可多气泡、工作/技术单气泡、完整原文单条保存 | Chat Foundation `9ca597...` | 标记“本地提交、未证明已进 current main/production” |
| `product.app.boundaries` | 官方 App vs LoveHouse、Android/Provider/Archive/权限/缓存边界 | `2026-08-23_原生App产品边界与工程问答.md` | 产品决策链；明确“当时需求，不等于已施工” |
| `media.message-foundation` | MediaAsset 与普通媒体消息 / Realtime 分离 | Chat Foundation 第 7 节 | 规划 revision，状态 planned |
| `proactive-wake.boundary` | Web 默认 OFF，Android 有通知权限后再做；Persona/Thread 独立于 runtime | Chat Foundation 第 8 节 | 规划 revision，状态 planned |
| `memory.v2.production` | 5 表内核、7-tool、semantic/lexical、source/revision/固定 actor | 8/25 全景、现 main、Memory V2 changes | current 以现代码/生产为准；8/21 回滚状态仅历史 |
| `memory.v1-retirement` | 保真迁移、先契约后迁移、freeze 后退休、旧权重仅 metadata | `2026-08-25_MemoryV1_Retirement完整施工接班包.md` | 规划+边界链；不复制生活正文 |
| `memory.contract.currentness` | `status` 与 `currentness` 分离、metadata.tags/memory_type 规范候选 | Memory Retirement 接班包 | 当前为待施工设计，不伪装已完成 |
| `memory.engineering.workspace` | Engineering space、subject_key/revision/source、Owner UI | #59/#60/#61 changes + 当前代码/生产静态 | 历史“未部署”由后续 current revision 修订为已上线 |
| `context.prompt-guardrail` | Persona Prompt 与 Runtime Guardrail 分层；Claude 重复注入、被动条款待清 | Memory Retirement + 8/25 全景 | 规划/问题链 |
| `context.context-composer` | Persona/Memory/WorldBook/Recent Chat 等按预算编排 | 8/25 全景 + 功能总账 | 规划链 |
| `auth.oauth` | DCR/PKCE/refresh/client registry、GPT/Claude 独立 resource、CSP/429 经验 | OAuth changes + 工程错题集 | 按故障→修复 revision 链归并 |
| `auth.mcp` | 7-tool surface、fixed actor、客户端渐进发现 | MCP changes + Memory Retirement + 工程错题集 | 服务端事实与客户端发现现象分开记录 |
| `feature.project-checklist` | B612 Build/施工清单原型、localStorage 持久化、未进 current main/production | `feat/b612-project-checklist-v1`、旧 change record、App 打勾表 | 作为未合并/历史原型；不要误写成当前生产功能 |
| `experiment.gpt-memory-timeline` | GPT Memory timeline/page/backend prototype | `lovehouse-deploy-3f18558d` 未跟踪文件 | unique orphan code；导入事实后代码另行保全/决定，不随 worktree 删除 |
| `experiment.unified-chat` | 旧 unified-chat 前端原型 | `lovehouse-deploy-3f18558d` 未跟踪文件 | 很可能被现 Codex/Claude v1 主线取代；先存历史与代码来源，再决定丢弃 |
| `experiment.codex-elevenlabs` | Codex 前端 + ElevenLabs 接线实验 | `eda2b083` + `2026-08-24_Codex前端与ElevenLabs语音接线.md` | unmerged branch，保留 branch ref；不当 current |
| `experiment.chatgpt-two-buttons` | ChatGPT 两按钮卡片实验 | `lovehouse-deploy-40b0edc5/experiments` + change record | unique orphan experiment；先留 provenance 再清 worktree |
| `lessons.engineering-mistakes` | 16 条已复核错题 + 6 条待确认 + Worker 候选 | `lovehouse-deploy-df007fa5` 三份 unique orphan | 正文进入正式 revisions；待确认条目保留 pending，不提升为事实 |

## 3. App 文件库来源

### `LoveHouse_现状全景与施工基线_2026-08-25.docx`

用途：8/25 高密度状态快照。不是永远 current；适合作为多个 subject 的 2026-08-25 历史 revision/source。

重点拆入：Chat Foundation、Memory V2/V1 Retirement、Prompt/Guardrail、产品分线、施工规则、当时 release/静态发布状态。

### `LoveHouse_功能施工打勾表_2026-08-25.docx`

用途：功能总账快照。逐模块拆为 planning/history，不把整份表作为一个工程 memory。

特别注意：8/25 表内“工作台/仓库状态/部署记录/health 面板待做”已经被后来的 Engineering Workspace 上线事实部分修订；导入必须保留“当时待做 → 后来完成”的 revision 关系。

### `LoveHouse_第五轮大审查_链路风险与当前状态_给小克.md`

用途：链路病灶地图与维护规则。8/21 的“生产回滚 V1 / needs-auth / 未切流”等状态只能作为历史 revision，不能覆盖 8/24–8/26 后续实况。

## 4. unique orphan / 不可删除清单

### A. 文档 unique orphan

`/root/.devspace/worktrees/lovehouse-deploy-df007fa5`

- `docs/LoveHouse_工程错题集_V1.md`
- `docs/LoveHouse_工程错题集_V1_待人工确认.md`
- `docs/changes/2026-08-23_工程错题集V1.md`

### B. 未跟踪代码 unique orphan

`/root/.devspace/worktrees/lovehouse-deploy-3f18558d`

- `bridge/client-api/memoryTimeline.js`
- `bridge/client-api/memoryTimeline.test.js`
- `src/modules/gpt-memory/*`
- `src/modules/unified-chat/*`
- `src/modules/project/*`（与已提交 `feat/b612-project-checklist-v1` 版本并不完全相同）
- `src/core/router.jsx`、`src/shared/AppShell.jsx`、`src/shared/Home.jsx` 有未提交差异

这批不是“旧文档”，而是可能丢失的未跟踪/未提交代码。**Engineering 导入只能保存其事实与 provenance，不能替代代码本体。清理该 worktree 前必须先做代码保全或明确判定废弃。**

### C. 未合并但有 branch/commit 的代码

- `/root/lovehouse-deploy`：`feat/b612-project-checklist-v1`，相对 `origin/main` 有 2 个 unique commits：`400dd5c`、`f14e134`；同时有 3 份未跟踪高密度文档。
- `agent/codex-voice-frontend-20260824`：commit `eda2b083`，相对 current main 仍是 unique commit；branch ref 存在。

移除 worktree 不等于删除 branch，但不要在本轮顺手删 branch ref。

## 5. worktree 清理分类（导入前仅分类，不执行删除）

### 可在迁移验收后优先移除的重复 worktree 候选

- `lovehouse-deploy-434761d2`：HEAD=current main，clean。
- `lovehouse-deploy-e38f7e04`：HEAD=current main，clean。
- `lovehouse-pr2`：PR2 commit 已被 main 包含，clean。
- `lovehouse-deploy-546cf080`：旧 `6c8c475...` detached clean；内容可由 Git/release 恢复。
- `lovehouse-deploy-58c7d488`：旧 `3d4da7d...` detached clean。
- `lovehouse-deploy-854bacea`：Port fix branch 已合并进 `c894913...`，clean。

### 暂不可删

- `lovehouse-deploy-df007fa5`：工程错题集未跟踪 unique orphan。
- `lovehouse-deploy-3f18558d`：有未提交/未跟踪代码。
- `lovehouse-deploy-40b0edc5`：有未跟踪实验目录/文档。
- `/root/lovehouse-deploy`：有 unique commits + 未跟踪接班包。
- `agent/codex-voice-frontend-20260824` worktree：unique commit branch，先保留到工程事实和代码去留判断完成。

## 6. release 清理分类（迁移前不删除）

当前承载运行的 release 必须保留：

- `c894913...`：Bridge 当前生产。
- `cd011513...`：Codex sidecar 当前生产。
- `6c8c475...`：Claude sidecar 当前生产，同时可作为 Bridge 既有回滚锚点之一。

其它历史 release / failed release 先作为 `duplicate_snapshot` 或 rollback 候选登记；在 Engineering source 验收和 rollback 保留策略确认后再删除，不因“目录多”直接清。

## 7. 已完成的 Supabase 正式导入（来自独立 Supabase 窗口）

2026-08-26 已通过独立 Supabase-only 窗口完成第一批正式导入；该窗口未调用 B612。已完成并读回验收：

- `architecture.engineering-workspace.shared-memory`
- `audit.mainline-completion.20260825`
- `feature.chat-natural-segments-v1`
- `lessons.engineering-mistakes.migration`
- `policy.engineering-memory.inbox-first`
- `workflow.patch-handoff.review-gate`

当前已验收总量：6 个 Engineering subject、7 个 revision、7 条 source link。验收覆盖 subject_key 唯一定位、current_revision_id、历史 revision、source locator、quote_text 原文一致性、engineering_open 与 engineering_recall。原 smoke `smoke.backend-write.20260825-2120` 仍保持 archived。

特别说明：`lessons.engineering-mistakes.migration` 只记录“错题集迁移过程/状态”，不等于 `lessons.engineering-mistakes` 正文已经导入。三份工程错题 Markdown 原文仍在 B612/VPS unique orphan 中，必须先把原始 source 带到 Supabase 路线，再逐题按稳定 subject_key 导入。

## 8. 后续实际导入顺序

1. `lessons.engineering-mistakes`：先救最危险的 unique orphan。
2. `infra.artifact-locations` + `infra.ports.registry` + `infra.routing.nginx`。
3. `runtime.bridge.production` / `runtime.codex.production` / `runtime.claude.production`。
4. `infra.deployment.pm2-cutover` / `infra.deployment.b612-static`。
5. `memory.engineering.workspace` / `memory.v2.production` / `memory.v1-retirement` / `memory.contract.currentness`。
6. `chat.client-api` / `chat.runtime-binding` / `chat.natural-segments`。
7. `auth.oauth` / `auth.mcp` / `context.prompt-guardrail` / `context.context-composer`。
8. `product.app.boundaries` / media / proactive wake 等规划链。
9. 所有 experiment/orphan 只写“发生过/代码在哪里/当前是否承重”，不伪装成主线。
10. 每条 subject 读回 current + history + source，再进入删除阶段。

## 9. 当前阻塞条件

Engineering 写入通道本身已经通过独立 Supabase-only 窗口验证可用；当前阻塞不再是“没有写入通道”，而是 **B612/VPS-only 原始 source 尚未进入 Supabase-only 窗口可访问的来源**。

仍待转交的关键 source：

- `lovehouse-deploy-df007fa5` 三份工程错题 Markdown 原文。
- VPS 2026-08-26 当前 PM2 / listener / Nginx / release 实测快照。
- 旧 worktree/release 分类与 unique orphan 代码资产位置。
- `/root/lovehouse-deploy` 中 Chat Foundation、Memory V1 Retirement、产品边界等未跟踪高密度文档。
- App 文件库三份主资料可直接通过 Files 路线读取，不需要 B612。

在这些原始 source 完成跨窗口转交并在 Engineering 中逐项 read-back 前，**仍不删除任何 unique orphan、dirty worktree、unique branch 或生产/必要 rollback release**。
