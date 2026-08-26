const STORAGE_KEY = 'b612.project-checklist.v1'

const S = {
  done: 'done',
  partial: 'partial',
  todo: 'todo',
  idea: 'idea',
  risk: 'risk',
}

function item(text, status = S.todo) {
  return { text, status }
}

export const CHECKLIST_SECTIONS = [
  { title: '① 小屋入口 · 账号 · 安全', items: [
    item('b612.fyi 正式域名', S.done), item('HTTPS', S.done), item('Supabase 账号登录', S.done),
    item('新设备首次账号密码登录', S.done), item('本机 6 位 PIN', S.done), item('离开前台自动回锁', S.done),
    item('手动锁定', S.done), item('PWA 安装到手机/桌面', S.done), item('开屏动画', S.done), item('基础 health 状态检查', S.done),
    item('Bridge / sidecar 独立运行', S.done), item('生产 release + rollback 机制', S.done),
    item('Android 原生设备凭据'), item('新手机由旧设备批准'), item('单独撤销某台设备'), item('总控台 Secret Vault'),
    item('API Key / TTS Key 等只存服务器'), item('Capability 权限开关'), item('一键备份全部 LoveHouse 数据'), item('一键迁移/恢复'),
    item('Supabase 全生产 RLS 最终安全基线复核', S.risk),
  ]},
  { title: '② 首页 · 桌面 · 外观', items: [
    item('首页', S.done), item('日历', S.done), item('定位', S.done), item('实时天气', S.done), item('今日卡片', S.done), item('心情入口', S.done),
    item('待办入口', S.done), item('每日语录', S.done), item('多套主题', S.done), item('星球玫瑰视觉', S.done), item('手机底部导航', S.done),
    item('桌面侧边栏', S.done), item('响应式布局', S.done), item('4×4 桌面骨架设计', S.partial), item('独立 Dock', S.partial),
    item('Widget 可改变尺寸/信息密度', S.partial), item('自定义桌面布局'), item('拖动 App / Widget'), item('保存自己的桌面排列'),
    item('统一 Accent / Theme Token'), item('旅行主题反向成为 LoveHouse 主题'),
  ]},
  { title: '③ Chat 核心骨架', items: [
    item('LoveHouse 自己的 Thread', S.done), item('Thread ≠ CLI/API session', S.done), item('Persona 与底层模型分离', S.done), item('Runtime Adapter', S.done),
    item('Codex CLI Runtime', S.done), item('Claude CLI Runtime', S.done), item('Codex 生产 E2E', S.done), item('Claude 生产 E2E', S.done),
    item('Claude 长期认证 token', S.done), item('Bridge 重启后 Resume', S.done), item('sidecar 重启后 Resume', S.done), item('SSE 流式回复', S.done),
    item('错误统一处理', S.done), item('provider session 不暴露前端', S.done), item('原生 reasoning summary 才展示', S.done),
    item('没有 reasoning 时显示 unavailable', S.done), item('不伪造“思考链”', S.done), item('Tool started / completed 状态', S.done),
    item('Usage 真实 Token', S.done), item('input token', S.done), item('cached input token', S.done), item('output token', S.done),
    item('reasoning/thinking token', S.done), item('quota 拿不到就 unknown', S.done), item('GPT 独立 Chat Runtime'), item('Claude API Runtime'),
    item('Codex/OpenAI API Runtime'), item('同一 Persona 在 CLI / API 间切换而不换 Thread'),
  ]},
  { title: '④ 自然聊天体验', items: [
    item('自然分段回复 v1 已完成，本地待 push/deploy', S.partial), item('生活闲聊才允许拆成多个气泡', S.done), item('工作窗口永远单气泡', S.done),
    item('技术解释永远单气泡', S.done), item('工单/代码/表格/教程不拆', S.done), item('最多约 5 个展示气泡', S.done),
    item('数据库仍保存一条完整原文', S.done), item('连续小气泡轻微发送节奏'), item('长消息折叠'), item('引用回复'), item('转发'), item('复制'),
    item('长按多选'), item('批量删除'), item('编辑自己的消息'), item('重新生成 AI 回复'), item('搜索聊天'), item('按日期跳转'), item('临时聊天'),
    item('AI 跟进问题建议', S.idea),
  ]},
  { title: '⑤ Persona · 聊天窗口', items: [
    item('Codex Persona', S.done), item('Claude Persona', S.done), item('小客厅', S.done), item('G老师/GPT 独立 Runtime Persona'), item('Persona 详情页'),
    item('Persona 头像'), item('昵称'), item('Persona 自己的 Thread'), item('Persona 自己的 Memory Scope'), item('Persona 自己的 Archive'),
    item('Persona 自己的 Runtime Binding'), item('Runtime/model 切换藏在 Persona 设置内'), item('casual 生活场景'), item('work 工作场景'),
    item('travel 旅行场景'), item('livingroom 小客厅场景'), item('custom 自定义场景'),
  ]},
  { title: '⑥ 聊天档案 · Archive', items: [
    item('Raw Chat Archive 原始聊天全文'), item('一个人格一条长期 Thread'), item('聊久自动“封卷”'), item('卷册时间范围'), item('消息数量'), item('摘要'),
    item('标签/索引'), item('原始消息完整保留'), item('当前卷与旧卷无缝续接'), item('旧消息可以转发回当前聊天'), item('引用旧消息后回到当前 Thread'),
    item('Archive 搜索'), item('Archive 日期跳转'), item('Summary 只做索引、不替代原文'), item('官方 ChatGPT 聊天导入'),
    item('导入来源保留 platform / conversation / message ID'), item('导入内容先进入 Archive，不自动变成 Memory'),
  ]},
  { title: '⑦ Memory V2 · 长期记忆', items: [
    item('GPT / Claude 私有记忆隔离', S.done), item('approved Shared Memory', S.done), item('remember', S.done), item('recall', S.done), item('revise', S.done),
    item('open_memory', S.done), item('wake_up Starter Pack', S.done), item('revision history', S.done), item('source/provenance', S.done), item('原文需要显式展开', S.done),
    item('current revision', S.done), item('semantic recall', S.done), item('lexical fallback', S.done), item('中文检索', S.done), item('embedding 可重建', S.done),
    item('GPT / Claude actor 固定', S.done), item('Memory MCP 七工具收窄方向', S.done), item('Memory 和小客厅边界分开', S.done),
    item('Legacy Pending 整理工具'), item('Shared Memory 审批 UI'), item('AI 日记正式 UI'), item('短期记忆聚合页'), item('长期记忆提炼页'),
    item('承诺专栏'), item('偏好专栏'), item('明确要求记住的事'), item('stale：可能过时、待修订'), item('superseded 当前状态 UI'),
    item('自动发现“旧观点可能已经过时”'),
  ]},
  { title: '⑧ GPT / Claude 记忆档案馆', items: [
    item('主题档案母版', S.partial), item('变化轨迹母版', S.partial), item('原话档案'), item('时间档案'), item('万年历'),
    item('年 → 月 → 日进入某天记忆'), item('某天的事件/原话/变化集中查看'), item('“旧理解 → 新理解 → 修订/悬置”完整展示'),
    item('来源原文跳转'), item('小屋档案迁出记忆页，成为独立一级入口'),
  ]},
  { title: '⑨ WorldBook 世界书', items: [
    item('世界书条目'), item('完整正文'), item('实际注入摘要'), item('常驻触发'), item('关键词触发'), item('手动触发'), item('关键词组'),
    item('AND / NOT'), item('Regex'), item('触发概率'), item('优先级'), item('启用/禁用'), item('注入位置'), item('注入 role'), item('扫描深度'),
    item('持续轮数'), item('递归触发（默认关闭）'), item('WorldBook Token 预算'), item('实际注入预览'),
  ]},
  { title: '⑩ Context Composer · 上下文编排器', items: [
    item('Persona 上下文'), item('WorldBook 上下文'), item('Memory V2 上下文'), item('Archive Summary'), item('Recent Chat'), item('时间'), item('地点'),
    item('当前现实状态'), item('当前 scene'), item('回复格式'), item('Token 预算'), item('去重'), item('排序'), item('裁剪'), item('Context Preview'),
    item('本轮到底注入了哪些记忆'), item('本轮到底注入了哪些世界书'), item('Token 来源占比'), item('trim 信息'), item('run id / fingerprint'),
    item('Composer 挂掉时自动退回基础规则 + recent chat'), item('防止整份 Markdown / 整段历史每轮重复灌入'),
  ]},
  { title: '⑪ 媒体消息基础层', items: [
    item('MediaAsset 统一对象'), item('图片消息'), item('音频消息'), item('视频消息'), item('文件消息'), item('位置消息'), item('duration'), item('width / height'),
    item('thumbnail'), item('transcript'), item('storage reference'), item('“发给 AI 看”与“永久保存”严格分开'), item('显式保存到相册/图库/旅行'),
    item('临时媒体自动清理'),
  ]},
  { title: '⑫ Voice · 语音', items: [
    item('录音发送'), item('STT 语音转文字'), item('TTS 文字转语音'), item('ElevenLabs'), item('Persona 独立声音'), item('用户选择输入方式'),
    item('用户选择输出方式'), item('AI 在生活聊天中自己选择文字/语音'), item('工作窗口默认文字'), item('手动“这段文字读给我听”'),
    item('手动“把这条语音转文字”'), item('Natural Segments 分段播放语音'), item('播放速度/音量等基础控制'),
  ]},
  { title: '⑬ Realtime 实时通话', items: [
    item('Realtime Session 独立于普通 Chat Message'), item('实时语音通话'), item('实时视频通话'), item('Audio Track'), item('Video Track'), item('Screen Track'),
    item('摄像头给 AI 看现实画面'), item('屏幕共享'), item('实时事件'), item('断线恢复'), item('通话记录/时长统计'),
  ]},
  { title: '⑭ 主动唤醒', items: [
    item('主动唤醒总开关'), item('Web 阶段默认 OFF'), item('OFF 时绝不调用模型'), item('Android 推送权限'), item('推送通知'),
    item('AI 醒来继续原 Persona / Thread'), item('AI 醒来后自己决定干什么'), item('可以找你说话'), item('可以什么都不说'), item('可以看记忆/整理东西'),
    item('AI 自己决定下一次醒来时间'), item('next_wake_at 持久化'), item('wake id 防重复'), item('认证失败不死循环'),
    item('没定下次时间时服务器不擅自补'), item('每个 Persona 可单独开关'),
  ]},
  { title: '⑮ 小客厅 · 社交', items: [
    item('三方小客厅', S.done), item('原消息时间戳', S.done), item('工单簿索引', S.done), item('Memory / LivingRoom MCP', S.done),
    item('更完整群聊 UI'), item('多 AI Persona 加入'), item('朋友圈'), item('小圈'), item('动态'), item('AI 主动发朋友圈'), item('评论/互动'),
    item('消息转发到小客厅'),
  ]},
  { title: '⑯ 生活记录', items: [
    item('日记', S.done), item('语录墙', S.done), item('待办', S.done), item('心情日志', S.done), item('私密记录', S.done), item('小纸条留言板', S.done),
    item('全屋搜索', S.done), item('浏览记录', S.done), item('自传', S.done), item('纪念日'), item('倒计时'), item('小账本'), item('收藏夹'),
    item('收藏消息'), item('收藏截图'), item('收藏「我的思路」'), item('标签整理'),
  ]},
  { title: '⑰ 音乐 · 一起听', items: [
    item('音乐模块'), item('网易云等音乐源 Adapter'), item('一起听歌'), item('当前歌曲状态'), item('AI 知道你正在听什么'),
    item('听歌过程中自然聊天'), item('某些歌曲/歌词触发互动'), item('听歌时长统计'), item('音乐收藏'),
  ]},
  { title: '⑱ 相册 · 画廊', items: [
    item('相册'), item('画廊'), item('图片收藏'), item('截图收藏'), item('媒体标签'), item('日期浏览'), item('来源关联'),
    item('某张图关联到聊天/旅行/记忆'), item('永久媒体与临时附件分开'),
  ]},
  { title: '⑲ 旅行手帐', items: [
    item('从普通聊天自然发起旅行'), item('GPT 提议建立旅行卡'), item('模拟旅行'), item('现实旅行'), item('计划行程'), item('实际旅程'),
    item('两者严格分开'), item('手动结束旅行'), item('预计结束时间不自动关闭'), item('竖向长卷旅行手帐'), item('每趟旅行独立版式'),
    item('图片密集排版'), item('地点'), item('时间'), item('地图'), item('Travel Clock'), item('Travel Calendar'), item('中文正文'), item('AI 英文旁白'),
    item('旅行临时聊天'), item('旅行照片显式保存'), item('旅行颜色生成主题 Token'), item('手写/OCR', S.idea), item('空间回复', S.idea),
    item('复杂贴纸编辑器', S.idea), item('全球 GIS', S.idea),
  ]},
  { title: '⑳ 学习 · 阅读', items: [
    item('共读'), item('阅读书架'), item('共读进度'), item('读书批注'), item('背单词'), item('单词本'), item('学习统计'), item('AI 陪读/解释'),
  ]},
  { title: '㉑ 健康', items: [
    item('健康页面'), item('心跳'), item('睡眠'), item('手环同步'), item('运动数据'), item('健康趋势'), item('只作为生活记录，不擅自医疗诊断'),
  ]},
  { title: '㉒ 数据统计', items: [
    item('基础 Stats 页面存在', S.done), item('每日聊天条数'), item('每周/月聊天量'), item('Token 日历'), item('Token 趋势'), item('Token 热力图'),
    item('Claude / Codex 分开统计'), item('Input / cached / output / reasoning 分开'), item('听歌时长'), item('通话时长'), item('主动唤醒次数'),
    item('Memory 写入/修订统计'),
  ]},
  { title: '㉓ 游戏 · 小玩意', items: [
    item('游戏区'), item('收藏聊天里一起做的小游戏'), item('BOBO'), item('万花筒'), item('观星室'), item('小手机'), item('小屋快报'),
  ]},
  { title: '㉔ 总控台 Control Center', items: [
    item('独立 1×1 App 图标'), item('House Status'), item('AI 权限'), item('Capability 开关'), item('已连接服务'), item('API 管理'), item('Secret Vault'),
    item('设备列表'), item('新设备配对'), item('撤销设备'), item('Key 使用记录'), item('维护记录'), item('GitHub 状态'), item('Supabase 状态'),
    item('VPS 状态'), item('Cloudflare 状态'), item('Claude/Codex Runtime 状态'), item('Memory 状态'),
  ]},
  { title: '㉕ 外部入口 · Channel Adapter', items: [
    item('B612 网站', S.done), item('LoveHouse Android 原生 App'), item('Telegram'), item('微信'), item('其它聊天入口'),
    item('所有入口共享同一个 Persona / Thread / Archive / Memory'),
  ]},
  { title: '㉖ Android 原生 App', items: [
    item('Kotlin + Jetpack Compose'), item('逐页迁移 Web 功能'), item('稳定 /api/v1 Client API'), item('App 不接触 service_role'), item('App 不接触模型 Secret'),
    item('App 不知道 VPS 内部端口'), item('可替换数据库'), item('可替换 VPS'), item('Push Notification'), item('主动唤醒开关'), item('后台生成'),
    item('媒体权限'), item('麦克风'), item('摄像头'), item('文件'), item('定位'), item('系统分享入口'),
  ]},
  { title: '㉗ AI 工具与主动性', items: [
    item('Tool Event 基础协议', S.done), item('Claude/Codex Runtime 与工具显示解耦', S.done), item('LoveHouse Codex 改为主动型提示词'),
    item('不再默认“没有明确命令就什么也不做”'), item('低风险能力预授权'), item('高风险能力单独确认'), item('读取 Memory'), item('搜索'),
    item('读指定文件'), item('项目状态检查'), item('Gmail / Calendar 等已连软件入口'), item('工具 Capability 白名单'), item('工具调用记录'),
  ]},
  { title: '㉘ 工程 · 工作台', items: [
    item('搭建日志', S.done), item('docs/changes', S.done), item('Git 分支/commit/回滚流程', S.done), item('独立 Claude/Codex sidecar', S.done),
    item('精确 release 部署', S.done), item('更新记录页面'), item('施工文件'), item('GitHub/Cloudflare/VPS/DNS 改动自动汇总'), item('工作台'),
    item('仓库状态'), item('部署记录'), item('错误/health 面板'), item('仓库收口：清旧 worktree / legacy / 重复脚本'), item('工程资料和生活记忆严格分开'),
  ]},
]

export const STATUS_META = {
  done: { label: '已完成', mark: '☑' },
  partial: { label: '进行中', mark: '◐' },
  todo: { label: '待施工', mark: '☐' },
  idea: { label: '灵感', mark: '◇' },
  risk: { label: '风险复核', mark: '!' },
}

function readStore() {
  if (typeof window === 'undefined') return { overrides: {}, custom: [] }
  try {
    return JSON.parse(window.localStorage.getItem(STORAGE_KEY)) || { overrides: {}, custom: [] }
  } catch {
    return { overrides: {}, custom: [] }
  }
}

async function request(path = '', options = {}, dependencies = {}) {
  const { getOwnerAccessToken } = await import('../engineering/engineeringService.js')
  const token = await (dependencies.getAccessToken || getOwnerAccessToken)()
  const response = await (dependencies.fetchImpl || globalThis.fetch)(`${dependencies.endpoint || '/api/v1/engineering/project-checklist'}${path}`, {
    ...options, headers: { Accept: 'application/json', Authorization: `Bearer ${token}`, ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...options.headers },
  })
  const payload = await response.json().catch(() => null)
  if (!response.ok || payload?.ok === false) throw new Error(payload?.error?.message || `施工清单请求失败（HTTP ${response.status}）`)
  return payload
}

function localMigrationItems(store) {
  const overrides = Object.entries(store.overrides || {}).map(([id, value]) => {
    const sectionIndex = Number(id.split('-')[1]); return { id, sectionIndex, custom: false, status: value.status || 'todo', note: value.note || '', completedAt: value.completedAt || '' }
  })
  return [...overrides, ...(store.custom || [])]
}

function mergeSections(items) {
  const byId = new Map((items || []).map(entry => [entry.id, entry]))
  return CHECKLIST_SECTIONS.map((section, sectionIndex) => {
    const baseItems = section.items.map((entry, itemIndex) => {
      const id = `base-${sectionIndex}-${itemIndex}`
      return { id, sectionIndex, ...entry, note: '', completedAt: '', ...(byId.get(id) || {}) }
    })
    const customItems = (items || []).filter(entry => entry.custom && entry.sectionIndex === sectionIndex)
    return { ...section, sectionIndex, items: [...baseItems, ...customItems] }
  })
}

export async function loadProjectChecklist(dependencies) {
  let payload = await request('', {}, dependencies)
  if (!payload.local_v1_migrated) {
    const items = localMigrationItems(readStore())
    await request('/migrate-local-v1', { method: 'POST', body: JSON.stringify({ items }) }, dependencies)
    if (typeof window !== 'undefined') window.localStorage.removeItem(STORAGE_KEY)
    payload = await request('', {}, dependencies)
  }
  return mergeSections(payload.items)
}

export function saveProjectChecklistItem(entry, dependencies) {
  return request(`/items/${encodeURIComponent(entry.id)}`, { method: 'PUT', body: JSON.stringify(entry) }, dependencies)
}

export async function addProjectChecklistItem(sectionIndex, text, dependencies) {
  const entry = {
    id: `custom-${globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`}`,
    sectionIndex,
    text,
    status: 'todo',
    note: '',
    completedAt: '',
    custom: true,
  }
  await saveProjectChecklistItem(entry, dependencies)
  return entry
}

export function deleteProjectChecklistItem(id, dependencies) {
  if (!id.startsWith('custom-')) throw new TypeError('Only custom checklist items can be deleted')
  return request(`/items/${encodeURIComponent(id)}`, { method: 'DELETE' }, dependencies)
}
