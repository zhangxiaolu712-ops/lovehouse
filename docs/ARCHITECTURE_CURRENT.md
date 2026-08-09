# ARCHITECTURE_CURRENT.md

> 现状审计文档 | 2026-08-09 | 审计者：CC (Claude Code) / Codex
> 以实际运行中的代码和数据库为准，旧文档中与此冲突的内容视为 archived。

---

## 1. 技术栈

| 层 | 技术 | 版本 |
|----|------|------|
| 前端框架 | React | 19.2.7 |
| 路由 | react-router (HashRouter) | 8.3.0 |
| 构建 | Vite | 8.1.1 |
| 数据库 | Supabase (PostgreSQL) | 项目 cvyguanuaxcypsvoozeo, ap-southeast-1 |
| Bridge | Express (Node.js) | 4.21.0 |
| 部署 | GitHub Pages + Cloudflare Workers + VPS (Vultr) | — |
| 域名 | tingtunehouse.duckdns.org (DuckDNS + Let's Encrypt) | — |
| 进程管理 | pm2 (VPS) | — |

---

## 2. 部署架构

```
用户浏览器
  ├── GitHub Pages   zhangxiaolu712-ops.github.io/lovehouse/
  ├── Cloudflare Workers (proxy.js → VPS bridge)
  └── VPS 直连       tingtunehouse.duckdns.org

VPS (139.180.146.26)
  ├── nginx (443/SSL → localhost:3000)
  │   ├── /api/*         → bridge
  │   ├── /.well-known/* → bridge (OAuth)
  │   ├── /oauth/*       → bridge (OAuth)
  │   └── /*             → /root/lovehouse-dist/ (静态前端)
  └── pm2 → bridge/server.js (:3000)
        ├── /chat           (Claude CLI SSE)
        ├── /livingroom     (REST CRUD)
        ├── /mcp/sse        (GPT MCP SSE Transport)
        ├── /mcp/claude     (CC MCP Streamable HTTP)
        └── /oauth/*        (OAuth 2.0 + PKCE)
```

---

## 3. Supabase 数据库 — 全部 18 张表

### 核心活跃表（前端 + MCP 均使用）

| 表 | 行数 | RLS | 前端模块 | Bridge/MCP | 说明 |
|----|------|-----|----------|------------|------|
| **brain** | 343 | on | brainService.js | 第一阶段后不再由 MCP 直连 | 现有漪记忆结构与未来 Legacy Pending 来源 |
| **livingroom** | 29 | **off** | LivingroomPage.jsx | read/send/context MCP tools | 三人小客厅（小婷/CC/GPT） |
| **diary** | 60 | on | diaryService.js | get_starter_pack | 日记（部分已迁入 brain） |
| **notes** | 96 | on | notesService.js | get_starter_pack | 小纸条留言板 |
| **quotes** | 84 | on | quotesService.js | — | 语录墙 |
| **memories** | 21 | on | memoryService.js | 第一阶段后不再由 MCP 直连 | 旧记忆碎片与未来 Legacy Pending 来源 |
| **todo** | 5 | on | todoService.js | — | 待办事项 |
| **mood_log** | — | on | moodService.js | — | 心情日志 |
| **stream** | 24 | on | streamService.js | — | 私密记录（毛玻璃模糊） |
| **toy_commands** | 14 | on | ToyPage.jsx | — | 玩具设备控制 |

### 仅数据库存在、无前端模块

| 表 | 行数 | RLS | 说明 | 状态 |
|----|------|-----|------|------|
| **codebook** | 26 | on | 暗号密码本 | 已迁入 brain (tag='暗号')，可归档 |
| **api_config** | 11 | on | 聊天 API 配置键值对 | ChatPage 的 SetupPanel 用 localStorage，此表未被前端查询 |
| **mirror** | 8 | on | 小克的镜子（自我判断） | 已迁入 brain (kind='记感受')，可归档 |
| **reasoning** | 1 | on | 判断日志 | 已迁入 brain，可归档 |
| **messages** | 2 | on | 小克信箱（待送达 Telegram） | 功能未完成，保留 |

### Dreaming V1 新建表（2026-08-07 建好，尚未使用）

| 表 | RLS | 说明 |
|----|-----|------|
| **window_summaries** | on | 窗口摘要（每个对话窗口结束后的总结） |
| **memory_candidates** | on | 候选记忆（待审核，approved 后写入 brain） |
| **active_threads** | on | 活跃话题（跨窗口追踪未完成讨论） |
| **dream_runs** | on | Dreaming 运行审计日志 |

### 安全提醒

- 2026-08-08 实测生产库仍存在阻断项：`livingroom` 未启用 RLS；Dreaming 四表使用 `allow_all`；`brain` 虽启用 RLS，但主人没有可用策略。
- 修复分支已准备 `20260809000704_secure_bridge_and_memory_tables.sql`：六张表只允许主人账号直接访问，Bridge 使用只存在于 VPS 的服务端密钥访问。
- **迁移尚未应用到生产库**。必须先部署并验证 Bridge 服务端密钥，再执行迁移，避免小客厅和记忆工具被一起锁住。
- `toy_commands` 不在本次修复范围内，迁移明确不修改它。

### 统一 Memory System V2（仅 Draft，未进入上述生产表）

独立 migration `20260808191311_create_unified_memory_system_v2.sql` 设计了九张全新规范表、四空间、revision/provenance/audit、mutation idempotency、Shared 状态机和固定 GPT/Claude 数据库读 RPC。Shared candidate 绑定确定 private revision 并形成不可变快照：仅 Bridge 内部 Curator RPC 可创建，只有从认证 JWT `auth.uid()` 固定身份的 Owner RPC 可作最终决定。`memory_revisions` 对 Bridge 服务角色只读，只能由历史触发器生成。AI-facing 工具不接收 owner、revision/hash 或权限字段。它不查询旧正文，也未应用生产；生产表数量仍以本节上方实测清单为准。

Bridge 未来读取链为：`固定 MCP actor → AccessPolicy → MemoryService → owner-scoped Repository → 固定 actor RPC → memory_entries`。即使 service key 绕过 RLS，固定 RPC 与服务层二次过滤仍阻止跨空间正文返回。完整方案见 `MEMORY_SYSTEM_PHASE2_SCHEMA.md`。

---

## 4. Brain 表详细 Schema

brain 是整个记忆系统的核心，字段最多：

| 列 | 类型 | 默认值 | 约束 | 说明 |
|----|------|--------|------|------|
| id | BIGINT | auto | PK | — |
| content | TEXT | — | NOT NULL | 内容 |
| title | TEXT | NULL | — | 标题（日记/长文用） |
| kind | TEXT | '记事' | CHECK: 记事, 记感受 | 类型 |
| tag | TEXT | '日记' | CHECK: 语录/总结/日记/长文/观点/修订/暗号/杂集 | 内容标签 |
| speaker | TEXT | NULL | — | 说话人（语录用） |
| feeling | TEXT | NULL | — | 感受描述 |
| mood | TEXT | NULL | — | 情绪天气 |
| stance | TEXT | NULL | CHECK: 认/不认/修订/悬置 | 审视态度（漪系统） |
| is_special | BOOLEAN | false | — | 特殊标记 |
| special_label | TEXT | NULL | — | 特殊标签文字 |
| status | TEXT | 'active' | CHECK: active/faded/awakened/archived | 记忆状态 |
| decay_score | FLOAT | 1.0 | — | 淡忘曲线分数（1=鲜活, 0=遗忘） |
| awaken_count | INTEGER | 0 | — | 被唤醒次数 |
| last_awakened_at | TIMESTAMPTZ | NULL | — | 上次唤醒时间 |
| last_accessed_at | TIMESTAMPTZ | now() | — | 上次访问时间（decay 计算用） |
| author | TEXT | '小克' | — | 作者 |
| memory_date | DATE | CURRENT_DATE | — | 记忆日期 |
| ref_id | BIGINT | NULL | FK→brain(id) | 引用另一条记忆（修订链） |
| source_table | TEXT | NULL | — | 迁移来源表名 |
| source_id | BIGINT | NULL | — | 迁移来源 ID |
| created_at | TIMESTAMPTZ | now() | — | — |
| updated_at | TIMESTAMPTZ | now() | — | — |

---

## 5. 前端路由表

### 已实现路由（20 条）

| 路由 | 组件 | 所属中心 |
|------|------|----------|
| `/` | Home | 空间中心（首页） |
| `/chat` | ChatPage | AI 中心 |
| `/profile` | ProfilePage | 空间中心 |
| `/diary` | DiaryPage | 记忆中心 |
| `/memory` | MemoryPage | 记忆中心（碎碎念） |
| `/memory/search` | SearchPage | 记忆中心（搜索浏览器） |
| `/brain` | BrainPage | 记忆中心（大脑/漪系统） |
| `/quotes` | QuotesPage | 记忆中心（原句收集） |
| `/mood` | MoodPage | 记忆中心（心情日志） |
| `/stream` | StreamPage | 记忆中心（私密记录） |
| `/todo` | TodoPage | 空间中心（待办） |
| `/space/notes` | NotesPage | 空间中心（小纸条） |
| `/space/theme` | ThemePage | 空间中心（主题） |
| `/space/clawd` | ClawdPage | 空间中心（Clawd 宠物） |
| `/device/toy` | ToyPage | 设备中心 |
| `/changelog` | ChangelogPage | 项目中心 |
| `/stats` | StatsPage | AI 中心（用量统计） |
| `/settings` | ThemePage | 设置中心 |
| `/settings/status` | StatusPage | 设置中心（Config 状态页） |
| `/livingroom` | LivingroomPage | AI 中心（小客厅） |

### 占位路由（13 条，均渲染 PlaceholderPage）

`moments`, `interact`, `all`, `space/layout`, `space/games`, `memory/inbox`, `ai/api`, `ai/config`, `ai/apps`, `device/band`, `device/smart`, `project/updates`, `settings/backup`

---

## 6. 底部导航 + 首页结构

**底部导航栏**（药丸形悬浮，4 tabs）：
1. 聊天 → `/chat`
2. 记忆 → `/brain`
3. 朋友圈 → `/moments`（占位）
4. 设置 → `/settings`

底部导航在 `/chat` 和 `/livingroom` 路径下隐藏。

`/livingroom` 使用独立全屏聊天布局：保留小婷 / CC / GPT 原始消息与 `created_at`，按上海时区显示时间；右上角工单簿根据小客厅消息首行动态生成只读索引，可按状态、优先级查看并跳回原消息。它不新增或复制数据库表。

**首页** (Home.jsx)：两页左右滑动 (scroll-snap)
- 第 1 页：App 图标网格（聊天、日记、语录、心情、待办、搜索等）
- 第 2 页：Room (小客厅)、Config (状态页)、Profile 等
- 顶部 Hero 卡片：T/K 头像 + 心跳线 + 在一起天数
- 小组件：时钟、每日语录、天数

---

## 7. 主题系统

| ID | 名称 | 默认 | 文件 |
|----|------|------|------|
| prince | 星球玫瑰 | **是** | themes/prince/ (style.css + backgrounds.css + auth.css + rose-paper.jpg) |
| classic | 恋爱小屋 | — | themes/classic/style.css |
| cozy | 浪漫蓝 | — | themes/cozy/style.css |
| vintage | 复古手账 | — | themes/vintage/style.css |
| desktop | 夜空紫 | — | themes/desktop/style.css |

主题切换：core/theme.jsx，localStorage key `lovehouse-theme-v2`，`data-theme` 属性。

---

## 8. Bridge Server 端点清单

| Method | Path | Auth | 说明 |
|--------|------|------|------|
| POST | /chat | Supabase JWT | Claude CLI SSE 聊天 |
| POST | /reset | Supabase JWT | 重置会话 |
| GET | /livingroom | JWT 或 API Key | 读小客厅消息 |
| POST | /livingroom | JWT 或 API Key | 发小客厅消息 |
| GET | /livingroom/context | JWT 或 API Key | 读上下文（纯文本） |
| GET | /mcp/sse | API Key | GPT MCP SSE 连接 |
| POST | /mcp/message | clientId | GPT MCP JSON-RPC |
| POST | /mcp/claude | OAuth Token | CC MCP Streamable HTTP |
| GET | /mcp/claude | OAuth Token | CC MCP SSE keepalive |
| DELETE | /mcp/claude | OAuth Token | CC MCP 断开 |
| GET | /.well-known/oauth-authorization-server | — | OAuth 元数据 |
| POST | /oauth/register | — | 动态客户端注册 |
| GET/POST | /oauth/authorize | — | 授权页面/授权码 |
| POST | /oauth/token | — | Token 签发 |
| GET | /health | — | 健康检查 |

### MCP 工具（GPT + Claude 共享 13 个）

| 工具名 | 说明 |
|--------|------|
| read_livingroom_messages | 读小客厅消息 |
| send_livingroom_message | 发消息（sender 按通道固定） |
| get_livingroom_context | 纯文本上下文 |
| get_starter_pack | 新会话先调用；返回 House Rules V1 + 自己的私有记忆 + approved Shared |
| open_memory_box | AI 启动记忆盲盒；随机返回自己的私有记忆 + approved Shared，默认 3 条、最多 4 条 |
| save_memory | 通过固定 actor 写入自己的私有空间 |
| recall | 通过 AccessPolicy 检索自己的私有空间 + approved Shared |
| load_memories | 兼容旧工具名，转调统一 MemoryService，不再直连 memories |
| search_memories | 兼容旧工具名，转调统一 recall |
| save_to_memories | 兼容旧工具名，转调统一 write |
| get_memory | 按编号读取有权访问的规范记忆 |
| revise_memory | 修订自己的私有记忆并保留 revision/provenance |
| propose_shared_candidate | 把自己的确定私有 revision 提交为 Shared candidate，等待 Owner 审批 |

### House Rules V1 启动契约

- 唯一规则数据源：`bridge/memory/house-rules.v1.json`。规则不散落在 MCP 描述、系统提示词或前端按钮中；文件每次调用时重新读取并校验，修改规则不需要修改 `MemoryService`。
- `FileHouseRulesProvider` 校验 schema version、revision、规则数量、唯一 id、长度与基础工具说明；数据源缺失或损坏时 `get_starter_pack` fail closed，不会只返回记忆却漏掉底线规则。
- `get_starter_pack` 保持原有 `private_memories` / `shared_memories` 字段，并新增稳定的 `schema_version` 与 `house_rules`。GPT/Claude 得到同一份短规则，记忆正文仍按固定 actor 隔离。
- AI-facing 工具描述明确要求在新对话开始时调用，不需要调用方预先知道 LoveHouse 历史。
- 未来 VPS `onSessionStart()` 可直接调用现有 `memoryService.starterPack(fixedActor, options, trustedContext)` 并注入同一返回对象；本版本不实现 Orchestrator 或自动注入。
- 本版本没有新表、RLS、生产环境变量、前端按钮或部署变化。

### AI 启动记忆盲盒 V1

- 调用链固定为 `open_memory_box → MemoryService.memoryBox → SupabaseMemoryRepository.memoryBox → fixed actor RPC`；AI 只传可选 `limit`，不能传 actor、owner、space、namespace、revision 或权限字段。
- SQL 先物化当前 actor 可读的合法候选集（自己的 private + approved Shared），然后才在该集合内随机；另一 AI private、未批准 Shared、Legacy Pending 和其他 owner 从不进入随机候选集。
- 每条结果从 `memory_entries` 当前 `revision_number` 精确连接同一条 `memory_revisions`，因此 `revision_id`、`revision_number`、`revision_hash`、`content` 来自同一确定版本。
- Shared 结果保留真实的 private source memory/revision/hash、来源空间和 provenance events；不会把阅读视角、审批者或整理者伪装成单一作者。
- 默认 3 条、范围 1–4，同批按 memory id 自然去重；不保存抽取历史，也不跨调用去重。成功读取写入持久化 `memory_box` audit，不记录正文。
- `MemoryService.memoryBox(fixedActor, options, trustedContext)` 可供未来 VPS `onSessionStart()` 复用；V1 不实现 Orchestrator、自动注入或前端按钮。

---

## 9. 共享组件

| 文件 | 说明 |
|------|------|
| shared/AppShell.jsx | 布局壳 + 底部导航 |
| shared/Home.jsx | 首页（滑动页+图标网格+Hero+小组件） |
| shared/LineIcon.jsx | SVG 图标库（40+ 图标） |
| shared/MobileUI.jsx | 手机端通用页面头、卡片、分区、标签与空状态组件 |
| shared/Markdown.jsx | 轻量 Markdown 渲染器 |
| shared/WeatherCard.jsx | 天气组件（Open-Meteo + 定位） |
| shared/Clawd.jsx | Clawd 宠物动画 |
| shared/global.css | 全局样式（3100+ 行） |

---

## 10. 待清理/合并项

| 项目 | 建议 | 优先级 |
|------|------|--------|
| memories 表 (21行) | 已被 brain 替代，前端 MemoryPage 仍在查询。改为查 brain 后可归档 | 中 |
| codebook 表 (26行) | 已迁入 brain (tag='暗号')，无前端引用，可归档 | 低 |
| mirror 表 (8行) | 已迁入 brain (kind='记感受')，无前端引用，可归档 | 低 |
| reasoning 表 (1行) | 已迁入 brain，无前端引用，可归档 | 低 |
| api_config 表 (11行) | 未被前端查询（ChatPage 用 localStorage），确认无其他引用后可归档 | 低 |
| diary/quotes/stream 表 | 数据与 brain 有重叠（brain 有 source_table 引用），前端仍独立查询。保持现状或逐步迁移 | 后续 |

---

## 11. 漪记忆系统（已落地）

- **stance 列**：认/不认/修订/悬置（从 tag 分离）
- **decay_score**：淡忘曲线 `e^(-days/stability)`，stability = 30 × 1.5^awaken_count
- **盲盒**：水波纹动画，加权随机（偏向久未访问），四个审视按钮
- **漪面板**：衰减分布可视化（鲜活/温热/渐远/将忘）+ 审视统计
- **唤醒搜索**：搜索 + 自动 awaken_count++, decay_score→1.0

---

## 12. Dreaming V1（表已建好，逻辑待实现）

**表结构就绪**：window_summaries, memory_candidates, active_threads, dream_runs

**待 Codex 实现**：
1. dream_worker 脚本（读对话→生成摘要→提取候选→查重→审核）
2. new_window_init MCP 工具（组合 memory_profile + active_threads + 最近摘要 + 尾部原文）
3. close_window_summary MCP 工具（窗口结束时生成摘要）
4. GPT 定时任务对接（通过受限 MCP 调用 dream_worker）
5. 前 7 天仅 review-run，禁止自动写入 brain

### 12.1 统一 Memory System 第一阶段（本分支代码，未部署）

```text
GPT SSE MCP ───── actor=gpt ────┐
                                ├→ AccessPolicy → MemoryService → MemoryRepository → Supabase
Claude HTTP MCP ─ actor=claude ─┘

MemoryService 管理同一套规则
├── GPT Memory
├── Claude Memory
├── Shared Memory（approved 才可读取）
└── Legacy Pending（禁止日常 recall）
```

代码分层：

| 层 | 文件 | 职责 |
|----|------|------|
| MCP Channel | `bridge/mcp/channel.js` | 在认证后的 GPT/Claude 路由上用服务端常量闭包固定 actor；不信任 body/query/header/tool args 的身份字段 |
| MCP Adapter | `bridge/mcp/tools.js` | 保留 CC 的 9 个工具名，固定 actor；没有 namespace 参数 |
| AccessPolicy | `bridge/memory/accessPolicy.js` | 权限矩阵、Shared approval、Legacy 隔离、伪造 space 拒绝 |
| MemoryService | `bridge/memory/service.js` | 统一写入/读取/检索与结构标准化；二次过滤 Repository 返回值 |
| MemoryRepository | `bridge/memory/repository.js` | 唯一未来规范表 `memory_entries` 的 Supabase REST 合约 |
| Runtime Gate | `bridge/memory/runtimeRepository.js` | `MEMORY_SYSTEM_ENABLED=false` 时四种仓储操作 fail closed，禁止回退旧表 |
| OAuth/Bridge | `bridge/oauth.js`, `bridge/server.js`, `bridge/security.js` | 选择性复用 CC 的 OAuth/PKCE/签名令牌、GPT SSE、Claude HTTP、小客厅 |

权限矩阵：

| Actor | 自己私有空间 | 对方私有空间 | approved Shared | pending/revoked Shared | Legacy Pending |
|-------|--------------|--------------|-----------------|------------------------|----------------|
| GPT | 读写 | 拒绝 | 只读 | 拒绝 | 拒绝 |
| Claude | 读写 | 拒绝 | 只读 | 拒绝 | 拒绝 |

结构迁移与历史正文迁移完全分离：新系统继承 memory type、tag、emotion、importance、decay、source、revision 等结构能力，但本阶段没有建表、没有复制正文。未来迁移必须保留 `original_table`、`original_id`、`original_created_at`、`legacy_source` 及旧的 `author/source_model` 等追溯信息；全部旧正文先进入 Legacy Pending，禁止默认 Shared 或根据语义猜归属。

含“旧数据默认 Shared”逻辑的 `20260808174047_memory_namespace_v1.sql` 已退出 active migrations，仅在 `supabase/retired_migrations/` 保存带强制失败保护的历史副本，禁止应用。唯一规范结构由 V2 migration 承担；生产 RLS/public exposure 的 P0 修复继续独立 PR，不与本重构混合。

第一阶段 Shared 对 GPT/Claude 均为只读；普通 MCP 写入只能进入 actor 自己的私有空间。`NullMemoryAuditSink` 与 `InMemoryAuditSink` 均不是持久化审计，`MemoryService` 要求 `writeEnabled=true` 且 `auditSink.persistent=true` 才允许写入；当前 Bridge 明确使用 `writeEnabled=false`。因此 append-only 审计持久化上线并完成审阅前，生产记忆写入保持关闭。

---

## 13. 环境变量

| 变量 | 位置 | 说明 |
|------|------|------|
| VITE_SUPABASE_URL | .env.production (git) | Supabase API URL |
| VITE_SUPABASE_ANON_KEY | .env.production (git) | Supabase 公开 anon key |
| SUPABASE_URL | VPS pm2 env | bridge 用 |
| SUPABASE_ANON_KEY | VPS pm2 env | bridge 用 |
| SUPABASE_SECRET_KEY | VPS pm2 env | Bridge 服务端数据库密钥（推荐使用 `sb_secret_...`，绝不进入 Git 或浏览器） |
| SUPABASE_SERVICE_ROLE_KEY | VPS pm2 env | 旧版服务端 JWT，仅作兼容；配置新密钥后可不填 |
| OWNER_USER_ID | VPS pm2 env | 唯一主人账号的 Supabase user UUID |
| LIVINGROOM_KEY | VPS pm2 env | GPT MCP 认证密钥（不入 git） |
| OAUTH_BASE_URL | VPS pm2 env | OAuth issuer base URL |
| OAUTH_TOKEN_SECRET | VPS pm2 env | 签发 MCP 短期访问令牌的随机密钥（至少 32 字符，不入 git） |
| MCP_RESOURCE_URL | VPS pm2 env | MCP 对外资源地址；使用 Cloudflare 代理时填写代理后的完整地址 |
| MEMORY_SYSTEM_ENABLED | VPS pm2 env | 默认 false；仅在 `memory_entries` migration 已应用且权限测试通过后才能设为 true |

> 上述新增 Bridge 变量目前只在分支代码中生效，生产 VPS尚未配置。本阶段没有修改任何生产环境变量；Memory System 默认 fail closed。

---

## 14. Memory Runtime Phase 3（Draft，未部署）

- 两种真实 MCP transport 已统一进入固定 actor 的 `MemoryService`，客户端提供的 body/query/header/tool args 不能改变 actor。
- Repository 只调用 `memory_runtime_*_gpt|claude` 固定 RPC；service role 对规范表和旧内部 RPC 的直接路径已撤销。
- `remember/revise/propose_shared` 将正文、revision、provenance、audit、idempotency 放在一笔数据库事务中；读取审计失败同样 fail closed。
- Shared 推荐在可信事务内解析当前 private revision 并形成不可漂移 candidate；Owner approve/reject/revoke 仍走 Phase 2 独立认证门。
- Legacy Pending 在普通 SQL 查询条件中即被排除；不迁正文、不默认 Shared。
- 本分支只提供 migration、Bridge Runtime、测试与免费临时 Supabase CI；生产环境仍保持 V2/V3 未应用、开关关闭。详见 `docs/MEMORY_SYSTEM_PHASE3_RUNTIME.md`。
## 2026-08-09 · Memory System Phase 4A（Draft，未部署）

- Phase 3 已作为统一 Runtime 基线合并；Phase 4A 在独立 Draft 分支增加 derived embeddings 与 hybrid recall。
- `memory_entries` 仍是唯一 canonical store；`memory_embeddings` 只保存 exact revision 的派生索引。
- AI-facing recall 工具不变，Bridge 内部固定 actor、embedding provider、ranking profile，再调用固定 actor RPC。
- SQL 从候选集合入口排除对方 private、unapproved Shared 与 Legacy Pending；MemoryService 再做 AccessPolicy 复核。
- 语义故障只有在 fallback audit 成功后才调用 Phase 3 keyword recall；权限/审计故障 fail closed。
- query embedding 的 profile/model/dimensions 必须与 versioned ranking profile 的绑定完全一致；fallback 仅允许网络/超时、408/429/5xx 与无效向量/维度，配置、鉴权、权限及 identity mismatch 均 fail closed。
- Phase 4B 在独立 Draft 分支实现默认关闭的 bounded Dream Queue、exact-revision candidate provenance、内部 Anchor 历史和可替换 Curator provider；它不增加 MCP/recall 参数，且只能产生 pending candidate，不能修改 canonical memory/revision。
