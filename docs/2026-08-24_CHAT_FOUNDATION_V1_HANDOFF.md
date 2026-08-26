# LoveHouse Chat Foundation v1 — 2026-08-24 施工接班包

> 用途：作为后续新窗口/新模型恢复 2026-08-24 Chat 主干施工状态的第一读取文件。
> 这是工程交接文档，不写入 LoveHouse 生活记忆库。

## 1. 当前结论

LoveHouse 已完成新的统一 Chat 主干基础。Codex 与 Claude 不再各自维护一套聊天架构，而是共享同一套 LoveHouse Thread / Runtime Adapter / SSE / usage / error / resume 语义。

当前核心关系：

```text
LoveHouse Thread
        |
   Runtime Adapter
     /        \
 Codex      Claude
  CLI         CLI
```

关键原则：

- `thread_id != provider/runtime session id`
- Persona / Thread / Archive / Memory 不因底层 runtime 切换而变化
- provider session 不进入前端统一协议
- reasoning 只展示 CLI 原生可见 summary；没有就 unavailable，禁止伪造或二次模型调用
- usage 只使用 CLI 真实数据；quota 没有可靠来源则 `unknown`
- 旧 Claude `/chat` 保持 frozen legacy，不再修、不作为新主干
- Memory V2 / LivingRoom / OAuth/MCP 业务链与新 Chat Runtime 解耦

---

## 2. Codex Chat Mainline v1.1

### 状态

生产 E2E：PASS。

曾作为主干验收基线的 commit：

```text
cd01151310de54e5c64fb3dbecc8aa00fd43ccf0
```

后续 main 已继续前进（Claude 合并后生产 main 为 `6c8c47505dbc1f6d4483097dc2badb93dedfc265`）。

### 已验证能力

- `/api/v1/*` 新 Client API 主干
- Codex CLI sidecar 独立运行
- LoveHouse Thread 与 Codex runtime session 分离并持久化
- Resume：PASS
- Bridge restart 后同一 Thread / runtime session 恢复：PASS
- 原生 reasoning item → 前端「我的思路」：PASS
- 无 reasoning item 时显示 unavailable，不伪造
- command/tool 状态事件统一映射：PASS
- command 敏感正文 / stderr / env 不进入前端：PASS
- usage 使用 CLI 累计值做本轮差分：PASS
- 字段已覆盖：input / cached input / output / reasoning output
- 旧 binding 无 baseline 时首轮 `establishing`，下一轮开始准确差分
- 原生 resume / compaction 负责上下文，不手工重复注入 reasoning summary
- Bridge / sidecar / 前端 / build 回归均通过

### Reasoning 根因修复

在使用 `--ignore-user-config` 时，仅设置：

```text
model_reasoning_summary="detailed"
hide_agent_reasoning=false
```

不足以暴露模型 reasoning summary 能力。

补充：

```text
model_supports_reasoning_summaries=true
```

后，真实 CLI 输出原生 reasoning item。

不得把 `reasoning_output_tokens > 0` 当成“一定存在可展示 reasoning summary”。

---

## 3. Claude Adapter v1.1

### 状态

生产正式完成：功能 PASS / 安全 PASS。

当前生产 main：

```text
6c8c47505dbc1f6d4483097dc2badb93dedfc265
```

Claude Adapter 开发分支：

```text
agent/claude-adapter-rebuild-v1-20260824
```

关键本地/合并前 commit：

```text
36676e2eae19acda867de605572e2316a17dae07
```

PR：#58，已合并。

### 已实现

- 独立 `claude_cli` sidecar
- `/api/v1/chat persona_id=claude` 接入统一 Runtime Adapter
- `thread_id != Claude session_id`
- binding 原子持久化，权限 600
- Bridge restart：PASS
- Claude sidecar restart：PASS
- 真机三轮对话：PASS
- Resume：PASS，测试标记 `ROSE-31`
- MCP 使用空配置：`{"mcpServers":{}}`
- 普通 Claude Chat 不再把 MCP/OAuth 当启动依赖
- 原生 reasoning summary 才展示；当前 Claude CLI 真机未提供 summary，因此 `unavailable`
- raw thinking 不展示，不二次调用模型
- usage 映射 input / cache / output / thinking
- quota 无可靠来源保持 `unknown`
- `claude_api` 只保留 disabled 类型，没有假实现
- 旧 `/chat` handler 零改动
- Codex Adapter 零回归

### Claude CLI 认证

Claude Code 版本曾验收为：

```text
2.1.229
```

普通 CLI OAuth 曾出现：

```text
OAuth session expired and could not be refreshed
```

已改为官方 `claude setup-token` 生成长期 token，并通过环境变量：

```text
CLAUDE_CODE_OAUTH_TOKEN
```

只传给 Claude CLI 子进程。

安全边界：

- token 只在 VPS root-only secret 文件
- 文件 owner `root:root`
- 权限 `600`
- 不进 Git
- 不进前端 bundle
- 不进日志
- 不进聊天正文

一次诊断命令曾因引号错误回显旧 token，因此执行了完整 token rotation：

- 新旧 token 确认不同
- 旧 token 从活动 secret 文件替换
- 新 token 最小认证请求 PASS
- secret / logs / frontend bundle scan PASS
- 临时捕获 / smoke 文件全部删除
- PM2 状态已保存

不得在文档、工单、聊天或截图里粘贴 token。

---

## 4. 生产状态（2026-08-24 当时）

Claude Adapter v1.1 完成安全轮换后：

```text
Bridge PID: 548738
Claude sidecar PID: 550563
Codex sidecar PID: 539250
```

Bridge / Claude / Codex / HTTPS health：全部 200。

Claude Adapter 部署回滚点：

```text
旧 release:
cd01151310de54e5c64fb3dbecc8aa00fd43ccf0

部署备份:
/root/lovehouse-deploy-backups/claude-adapter-v11-20260824T131550Z

静态备份:
/root/lovehouse-dist-backups/pre-claude-adapter-v11-20260824T131550Z
```

注意：PID 是当时快照，不应作为长期事实；恢复时应重新查询 PM2/health。

---

## 5. Chat 自然分段回复 v1

### 状态

已本地完成并提交；尚未 push、尚未部署。

```text
Branch:
agent/chat-natural-segments-v1-20260824

Commit:
9ca597782f82b796d0b3f2fb90ce21cd0cec79c3
```

工作区 clean。

### 实际规则

只有同时满足：

```text
scene = casual
+ 明确自然对话型长回复
+ 自然分段开关开启
```

才生成 `display_segments`。

硬例外（始终单气泡）：

- `scene = work`
- 技术解释
- 教程
- 工单
- 代码块
- Markdown 表格
- 列表
- 结构化分析 / 验收报告
- 未知或无法可靠分类的内容

其它规则：

- 不额外调用模型分类
- 原始 `text_delta` 继续实时流式输出
- 最终 `message_end` 才附加最多 5 个 `display_segments`
- 历史只保存一条完整 `content`
- segments 只是展示元数据
- 搜索 / Archive / 引用 / Memory 仍按原始完整 message
- 暂未做 300–600ms 气泡延时动画，避免引入计时状态并拖慢落库

回归：

```text
Bridge: 213/213 PASS
Frontend: 14/14 PASS
lint: PASS（仅既有 warnings）
build: PASS
git diff --check: PASS
Claude/Codex Adapter: diff 0
旧 /chat / Memory / LivingRoom / OAuth/MCP: diff 0
```

以后部署前应先重新跑对应 production E2E。

---

## 6. Chat Foundation v1 当前能力边界

已稳定：

```text
Runtime
- Codex CLI ✅
- Claude CLI ✅

Message
- SSE 流式 ✅
- Thread / Runtime 分离 ✅
- Resume ✅
- Bridge restart recovery ✅
- sidecar restart recovery ✅
- 原生 reasoning summary 展示 ✅/unavailable
- Tool Events ✅
- Usage / Token 差分 ✅
- Natural Segments：本地完成，待 push/deploy
```

不要因为后续功能重新设计第二套 Chat。

---

## 7. 下一阶段：Media Message Foundation

用户担心未来不只有 Voice，还会有视频，因此不要把底层设计成“Voice-only”。

下一层应定义为媒体消息基础层：

```text
Message
├─ text
├─ image
├─ audio
├─ video
├─ file
└─ location
```

统一媒体对象方向：

```text
MediaAsset
- asset_id
- type: audio | video | image | file
- mime_type
- duration
- width / height
- size
- thumbnail
- transcript
- storage_ref
```

第一版建议只真正实现 audio message 最小闭环，同时 schema 预留 image / video / file。

推荐最小链：

```text
上传/录音
→ MediaAsset
→ Chat message 引用 asset_id
→ 播放
```

之后再加：

- STT
- TTS
- AI 回复文字/语音选择
- casual 场景可按 natural segments 分段播放

### 普通媒体消息 != 实时通话

未来实时音视频单独抽象：

```text
Realtime Session
├─ audio track
├─ video track
├─ screen track
└─ realtime events
```

以后可以支持：

- 实时语音通话
- 实时视频通话
- 摄像头画面
- 屏幕共享
- AI 看用户正在拍的内容

不要为了未来视频现在就实现 Realtime，只需保持边界不写死。

---

## 8. 主动唤醒：APP 阶段需求，当前不施工

用户明确要求：这个功能可以等 Android APP 有推送通知权限后再做。

当前要求：

- 必须有用户总开关
- Web 阶段默认 OFF
- OFF 时后端不得因此触发模型调用
- APP 有通知权限后再由用户主动开启
- AI 被唤醒后，不需要服务器规定固定任务；允许它在当前权限范围内自己决定做什么
- 下一次醒来的时间由该次 AI 自己决定
- 服务器只负责保存/执行 `next_wake_at`
- 若 AI 未决定下一次时间，不由 scheduler 擅自补时间
- 唤醒器应认 Persona / Thread，不与特定 Claude/Codex runtime 写死

未来底层至少要有：

- proactive wake 总开关
- `next_wake_at` 持久化
- 防重复 wake id / 幂等
- auth 失败不要无限重试
- Android notification 权限与主动唤醒权限分开

当前阶段：只作为需求保留，不实现 scheduler，不在 Web 假做通知。

---

## 9. 后续恢复工作的推荐读取顺序

新窗口/额度恢复后：

1. 先读本文件：`docs/2026-08-24_CHAT_FOUNDATION_V1_HANDOFF.md`
2. 再按任务读取：
   - Codex Chat 相关文档 / changes
   - Claude Adapter 相关文档 / changes
   - Natural Segments 分支和 commit
3. 查询当前 Git / PM2 / health，绝不把本文件中的 PID 当实时状态
4. 若继续媒体层，从本文件第 7 节开始，不重新定义 Chat Runtime

当前最推荐的下一刀：

```text
先处理 Natural Segments 的 push / production E2E
然后再开 Media Message Foundation v1
```

如果额度不足，可停在此文件；不要为了赶进度同时启动 Voice + Video + Realtime。

---

## 10. 长期边界

- 工程进度、commit、部署、smoke test、数据库/API bug：留在工程文档，不写入 LoveHouse 生活记忆库。
- LoveHouse 生活记忆库用于生活事件、共同互动、原话细节、关系/理解变化。
- Raw Chat Archive / Summary / Memory 必须继续三层分离。
- Media “发给 AI 看” != “永久保存到图库/旅行/档案”；永久媒体必须显式保存。
- App 以后应继续遵守：加家具，不拆墙。
