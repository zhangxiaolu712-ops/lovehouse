# LoveHouse 开发日志

> 这个文件记录了 LoveHouse 项目的所有开发步骤和决策。
> 任何 AI 修改本项目前，必须先读取此文件了解当前状态。

---

## 项目概述

LoveHouse 是一个个人数字空间（情侣向），使用 React + Vite + Supabase 构建。
核心设计原则：**每个部分都是独立零件，可以单独替换，互不耦合。**

---

## Supabase 信息

- **项目名**: lovehouse
- **项目ID**: cvyguanuaxcypsvoozeo
- **区域**: ap-southeast-1（新加坡）
- **API URL**: https://cvyguanuaxcypsvoozeo.supabase.co
- **状态**: ACTIVE_HEALTHY
- **注意**: 所有表的 RLS（行级安全）当前关闭，个人项目暂可接受

### 数据库表结构

| 表名 | 字段 | 说明 |
|------|------|------|
| memories | id, content, category, importance, created_at | 记忆碎片，category默认"日常"，importance默认1 |
| diary | id, title, content, mood, created_at | 日记本 |
| quotes | id, content, speaker, created_at | 语录墙，speaker默认"小克" |
| todo | id, content, done, created_at | 待办事项，done默认false |
| mood_log | id, mood, note, created_at | 心情日志 |
| stream | id, title, content, created_at | 动态流 |

---

## 项目结构

```
lovehouse/
├── .env                         ← Supabase 密钥（不提交到 Git，本地开发用）
├── .env.production              ← Supabase 公开配置（已提交，构建部署用）
├── .env.example                 ← 环境变量模板
├── index.html                   ← 入口 HTML
├── package.json                 ← 依赖管理
├── vite.config.js               ← Vite 配置
│
├── src/
│   ├── main.jsx                 ← 应用入口
│   │
│   ├── core/                    ← 核心零件（可单独替换）
│   │   ├── supabase.js          ← Supabase 客户端（换数据库只改这里）
│   │   ├── theme.jsx            ← 主题切换系统（Context + localStorage）
│   │   └── router.jsx           ← 路由配置（所有页面路由在此注册）
│   │
│   ├── modules/                 ← 功能模块（每个独立，互不依赖）
│   │   ├── diary/               ← 📖 日记
│   │   │   ├── diaryService.js  ← 数据层（CRUD操作）
│   │   │   └── DiaryPage.jsx    ← 页面组件
│   │   ├── memory/              ← 💎 记忆
│   │   │   ├── memoryService.js
│   │   │   └── MemoryPage.jsx
│   │   ├── quotes/              ← 💬 语录
│   │   │   ├── quotesService.js
│   │   │   └── QuotesPage.jsx
│   │   ├── todo/                ← ✅ 待办
│   │   │   ├── todoService.js
│   │   │   └── TodoPage.jsx
│   │   ├── mood/                ← 🌈 心情
│   │   │   ├── moodService.js
│   │   │   └── MoodPage.jsx
│   │   └── stream/              ← 🌊 动态流
│   │       ├── streamService.js
│   │       └── StreamPage.jsx
│   │
│   ├── themes/                  ← 主题样式（每套独立 CSS）
│   │   ├── classic/style.css    ← 🌸 恋爱小屋（奶油色+粉色，原版风格）
│   │   ├── cozy/style.css       ← 💙 浪漫蓝（清新淡蓝色调）
│   │   ├── vintage/style.css    ← 📜 复古手账（米色泛黄，纸质感）
│   │   └── desktop/style.css    ← 🌙 夜空紫（深色调）
│   │
│   └── shared/                  ← 共享组件
│       ├── AppShell.jsx         ← 应用外壳 + 底部导航
│       ├── Home.jsx             ← 首页（天数计数、心情、语录、快捷入口）
│       └── global.css           ← 全局样式 + 通用组件样式
```

---

## 开发规则

1. **先读取此文件**，了解当前项目状态
2. **不创建新的替代项目**，在现有结构上修改
3. **不删除已有页面和功能**
4. **新功能以模块形式添加**到 modules/ 目录
5. **新主题以 CSS 文件形式添加**到 themes/ 目录
6. **修改前先说明方案**
7. **修改后更新此文件的变更记录**

---

## 变更记录

### 2026-07-18 | 初始搭建

**操作**: 从零搭建整个项目
**分支**: claude/repo-structure-overview-ebeg5q

完成内容：
- 初始化 Vite + React 项目
- 安装 @supabase/supabase-js、react-router-dom
- 创建 Supabase 连接模块（core/supabase.js）
- 创建 6 个功能模块各自独立的数据层和页面
- 创建 3 套可切换主题（温馨小屋、极简、桌面空间）
- 创建首页、应用外壳、路由系统
- 构建验证通过

### 2026-07-18 | UI 美化重设计

**操作**: 参照用户提供的截图，重新设计首页和主题
**参考风格**:
- 风格A（浪漫蓝）: 淡蓝色背景，天数计数器居中，头像+爱心，天气/心情卡片，重要日子倒计时
- 风格B（复古手账）: 米色泛黄纸质感背景，撕纸边缘，回形针装饰，手写感字体，每日备忘

完成内容：
- 重新设计 cozy 主题 → 浪漫蓝风格
- 新增 vintage 主题 → 复古手账风格
- 重新设计首页布局（天数计数、心情打卡、每日语录、功能入口）
- 更新底部导航样式

### 2026-07-18 | 经典小屋主题 + GitHub Pages 部署

**操作**: 还原旧版风格为可切换主题，配置自动部署

完成内容：
- 新增 classic 主题（🌸 恋爱小屋）— 完整还原旧版网页风格
- 经典主题首页：问候语、欢迎卡片、心情天气、快速心情输入、花瓣分隔符
- 配置 GitHub Actions 自动构建部署到 GitHub Pages
- 使用 HashRouter 兼容 GitHub Pages 静态托管
- Vite base 配置为 /lovehouse/
- 部署地址: https://zhangxiaolu712-ops.github.io/lovehouse/

当前 4 套主题：🌸恋爱小屋、💙浪漫蓝、📜复古手账、🌙夜空紫

### 2026-07-18 | 修复部署构建

**操作**: 添加 .env.production 解决 GitHub Actions 构建时无法读取环境变量的问题

完成内容：
- 创建 .env.production（Supabase 公开客户端配置，受 RLS 保护）
- Vite 构建时自动加载 .env.production，无需 GitHub Secrets
- 简化 deploy.yml，移除 secrets 依赖
- PR #2 和 PR #3 已合并，GitHub Pages 自动部署已触发

---

## 待开发功能

- [ ] 纪念日模块（events 表 + 倒计时功能）
- [ ] 相册模块（gallery）
- [ ] 设置模块（用户配置持久化）
- [ ] 记忆系统分级（固定/长期/短期/临时/偏好/项目）
- [ ] Supabase RLS 安全策略
- [ ] MCP 接入
- [ ] AI 统一记忆读取 API
- [ ] 自动归档模块
