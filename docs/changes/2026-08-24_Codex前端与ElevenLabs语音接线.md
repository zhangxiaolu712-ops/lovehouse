# 2026-08-24 Codex 前端与 ElevenLabs 语音接线

## 状态

已完成代码接线与本地构建/测试，尚未 merge、尚未部署生产。

## 做了什么

1. 新增 `/codex` 前端聊天页，接入已经独立运行的 `lovehouse-codex-chat` sidecar。
2. Cloudflare Worker 对 `/api/codex/*` 做专门代理，转到现有 HTTPS Codex sidecar；旧 `/api/*` Bridge 路由保持不变。
3. 新增 ElevenLabs TTS 服务端适配器与 `/voice/tts` 端点；API Key 与 Voice ID 只从服务端环境变量读取，不进入浏览器、仓库或日志。
4. Codex 消息提供“朗读”按钮，通过同源 `/api/voice/tts` 获取 MP3 Blob 后本地播放。
5. 首页新增 Codex 入口，路由、图标和聊天页样式一并接线。

## 为什么这样做

- Codex sidecar 已在 VPS 独立运行并通过 `https://tingtunehouse.duckdns.org/api/codex/health` 健康检查，但生产前端此前没有入口。
- ElevenLabs 声线需要用于日常前端调试；密钥不能放在 Vite/浏览器，因此通过现有 Owner JWT 鉴权的 Bridge 转发 TTS。
- 保持 Codex、Claude Chat、Memory/MCP 与语音能力相互独立，不把新能力塞进同一个 provider 进程。

## 修改文件

- `src/modules/codex/CodexChatPage.jsx`
- `src/modules/codex/codexService.js`
- `src/modules/voice/voiceService.js`
- `src/core/router.jsx`
- `src/shared/Home.jsx`
- `src/shared/AppShell.jsx`
- `src/shared/LineIcon.jsx`
- `src/shared/global.css`
- `src/proxy.js`
- `bridge/voice.js`
- `bridge/voice.test.js`
- `bridge/server.js`
- `.env.example`
- `docs/02_当前架构.md`
- `docs/06_待开发列表.md`

## 环境变量 / 部署变化

新增服务端变量：

- `ELEVENLABS_API_KEY`（秘密，禁止提交）
- `ELEVENLABS_VOICE_ID`
- `ELEVENLABS_MODEL_ID`（默认 `eleven_v3`）
- `ELEVENLABS_STABILITY`（默认 `0.45`）

本轮没有读取或写入真实 ElevenLabs Key，没有修改生产 PM2 环境，没有重启 Bridge，没有触发 Cloudflare 生产部署。

## 验证

- `npm run lint`：通过，只有仓库既有 warning。
- `npm run build`：通过；Vite 生产构建成功。
- `bridge npm test`：163/163 通过，新增两条 voice client 测试通过。
- Codex sidecar 现网健康检查：`/api/codex/health` 返回 200。
- 尚未做带真实 Owner JWT 的浏览器端 Codex SSE 真机测试。
- 尚未注入 ElevenLabs Key，因此 `/api/voice/tts` 生产真实合成尚未测试。

## 风险 / 未完成

- 当前 Voice endpoint 只有在生产 Bridge 注入 `ELEVENLABS_API_KEY` 与 `ELEVENLABS_VOICE_ID` 后才会工作；未配置时 fail closed 为 503。
- Codex 前端依赖 Worker 新的 `/api/codex/*` 代理规则，需要 Cloudflare 部署后才能从正式前端同源访问。
- Eleven v3 输出具随机性；当前只固定 voice/model/stability，不保证复现某一次黄金样本的全部表演细节。
- 生产部署必须遵守独立 PR → 人工确认 merge → 单独部署 → 冒烟验收流程。

## 下一步

1. 提交独立分支并创建 Draft PR。
2. 人工复审后 merge。
3. 在 VPS 以环境变量注入 ElevenLabs Key 和选定 Voice ID；不落库、不提交 Git。
4. 部署 Bridge 与 Cloudflare Worker/前端。
5. 真机验收 Codex 连续两轮 thread resume、中文 TTS、错误提示与免费 credits 消耗。
