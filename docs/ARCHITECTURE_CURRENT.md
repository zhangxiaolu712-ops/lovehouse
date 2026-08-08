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
| **brain** | 343 | on | brainService.js | save_memory, recall, get_starter_pack | 统一记忆中枢（记事/记感受/观点/语录/日记/暗号） |
| **livingroom** | 29 | **off** | LivingroomPage.jsx | read/send/context MCP tools | 三人小客厅（小婷/CC/GPT） |
| **diary** | 60 | on | diaryService.js | get_starter_pack | 日记（部分已迁入 brain） |
| **notes** | 96 | on | notesService.js | get_starter_pack | 小纸条留言板 |
| **quotes** | 84 | on | quotesService.js | — | 语录墙 |
| **memories** | 21 | on | memoryService.js | — | 旧记忆碎片（已被 brain 替代） |
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

### MCP 工具（GPT + Claude 共享 9 个）

| 工具名 | 说明 |
|--------|------|
| read_livingroom_messages | 读小客厅消息 |
| send_livingroom_message | 发消息（sender 按通道固定） |
| get_livingroom_context | 纯文本上下文 |
| get_starter_pack | 开场加载（日记+修订+纸条+将忘记忆） |
| save_memory | 存记忆到 brain |
| recall | 搜索 + 唤醒记忆 |
| load_memories | 按 level/category 加载记忆库（memories 表） |
| search_memories | 关键词搜索记忆库 |
| save_to_memories | 写入新记忆到记忆库（校验 8 大类） |

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

### 12.1 MEMORY-NAMESPACE-V1（本地迁移草稿，未应用生产）

- 新增 `memory_spaces`，以一套记忆引擎承载 `claude`、`gpt`、`shared` 三个隔离空间；不复制三套数据库表。
- 每个 AI 空间可保存显示名、自我描述、说话方式、记忆策略和扩展档案；`shared` 用于经小婷确认可共同读取的内容。
- `brain`、旧 `memories` 及四张 Dreaming 表统一增加 `space_key` 外键和索引。
- 现有数据先归入 `shared`，避免依据旧 `author`/`speaker` 自动误判归属；后续迁移需单独审计。
- 现有 Bridge 工具在升级前仍写入默认 `shared`；真正的 AI 隔离还需要受限 MCP 强制过滤 `space_key`。
- migration 会检查 P0 owner-only RLS 是否已落地；当前生产库仍有 Dreaming `allow_all`，因此直接应用会安全失败并整体回滚。

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

> 上述新增 Bridge 变量目前只在修复分支代码中生效，生产 VPS 尚未配置。
