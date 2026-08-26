# LoveHouse 工程错题集 V1

> 目的：只保存已经发生、已经定位、以后值得快速复用的工程失败经验。它不是项目历史、架构总览或施工进度表。

## 使用规则

- 先看现象和“下次快速识别”，再沿“证据”回到代码、提交、测试或生产记录。
- `docs/changes/` 是带时间点的施工记录，不自动等于当前状态；当前运行事实优先于仓库文档。
- 同一根因只保留一条。只有候选线索、缺少失败现场或尚未完成真实验收的事项不进入正文。
- 本版仓库基线：`43d975728e00ba5c48babef7e144277e5d0bf1e8`。本轮没有取得新的 VPS 只读会话，因此不把对话摘要中的生产 SHA/PID 当作证据；生产相关结论只引用仓库内已有的带时间记录。

---

## [01] 小客厅把上游错误伪装成空房间或成功写入

### 现象

读取小客厅时出现 `[]` 或 `(no messages yet)`；发送消息时返回 `{ok:true}`，但数据库没有对应写入。上游实际可能已经返回 401 或 PostgREST 错误对象。

### 容易误判成

房间确实没有消息、消息刚好尚未同步，或发送已经成功但客户端没刷新。

### 真实根因

MCP adapter 只按“是不是数组”判断结果：错误对象和空值被归一化为空数组；写入没有返回可确认的行时仍制造成功响应。特权 REST 调用也缺少足够窄的能力边界。

### 错误尝试 / 为什么没用

继续重试、刷新或显示友好的空状态只会掩盖真实 401；仅让 service-role key 能读写也不能防止 adapter 吞错或访问其他表。

### 正确处理

通过 branded `createLivingroomRest` fence 把服务端能力限制为 `livingroom` 的 GET/POST；上游非数组错误、401 和未确认写入全部显式失败。sender 仍由服务端固定，凭证仍只在 Bridge 端，未放宽 anon/RLS。

### 验收方法

真实读取旧消息；发送唯一标记；按 id/正文回读；注入 401/错误对象确认不会变成空列表；写入返回空行时必须失败；其他 P0 表和 PATCH/DELETE 必须在 fetch 前被 fence 拒绝。

### 下次快速识别

“空列表”和“成功”同时缺少数据库 id、上游状态或可回读证据时，先查 adapter 是否做了宽松默认值，不要先查 UI。

### 证据

- Commit `76550a28881f9ebf17c6244f52d1e70b9ddf197a`（后合入 `586cfd9`）。
- `bridge/livingroom.js`
- `bridge/livingroom.test.js`：错误对象、未确认写入和越界方法测试。
- `bridge/mcp/tools.test.js`：`LivingRoom sender stays fixed and upstream errors remain explicit`。
- `docs/changes/2026-08-11_小客厅错误透明化P0.md`

---

## [02] OAuth metadata 路径被 SPA 接住，JSON discovery 变成 HTML 200

### 现象

MCP endpoint 正常返回 401 和 `WWW-Authenticate`，但 Claude/客户端仍无法发现 OAuth；浏览器打开 metadata URL 看似 200，却不是 OAuth JSON。

### 容易误判成

OAuth secret、DCR、客户端版本或授权页本身坏了。

### 真实根因

401 指向 `/.well-known/oauth-protected-resource/mcp/claude`，当时 Nginx 只代理另一条路径；请求落入前端 SPA fallback，返回 `index.html` 的 200。真实 JSON 位于 `/api/.well-known/...`，公开声明路径与实际代理路径不一致。

### 错误尝试 / 为什么没用

反复重连或重建 DCR client 不会改变 metadata URL 返回 HTML 的事实；状态码 200 也不能证明 discovery 内容正确。

### 正确处理

让 `WWW-Authenticate`、protected-resource metadata 的公开 URL、Nginx 可达路径和 MCP resource identity 精确一致，并对 Content-Type、resource、authorization_servers 做内容级验证。

### 验收方法

从未认证 MCP 401 取出 metadata URL，直接请求并断言 JSON Content-Type、resource 精确匹配 MCP URL；随后完成 AS metadata、DCR、authorize、token 和已认证 initialize。

### 下次快速识别

OAuth discovery 失败但 metadata URL 是 200 时，第一眼检查响应体是不是 SPA HTML，而不是把 200 当 PASS。

### 证据

- Commit `36d297d`。
- `bridge/oauth.js`, `bridge/oauth.test.js`, `bridge/server.js`
- `docs/changes/2026-08-11_工单02小客厅认证与ClaudeOAuth发现.md`

---

## [03] DCR client 只存在进程内存，Bridge 重启后缓存的 client_id 变成 invalid_client

### 现象

客户端已完成 DCR，授权页此前能打开；Bridge/PM2 重启后，同一个合法 `client_id` 在批准前立即返回 `invalid_client`。

### 容易误判成

redirect URI、client_id 拼写、客户端缓存或用户凭证错误。

### 真实根因

DCR registry 是进程内 `Map`。客户端持有的 client_id 仍然存在，但服务端重启后登记记录已消失，严格存在性校验因此正确地拒绝它。

### 错误尝试 / 为什么没用

放宽未知 client 校验会修掉安全边界；仅重新授权一次也不能解决下一次重启后再次丢失。

### 正确处理

将 DCR client registry 原子持久化到 release 外的私有文件，保存 redirect URIs、grant/response types、auth method、创建/过期/撤销状态；IO/损坏 fail closed，未知 client 仍拒绝。

### 验收方法

DCR 后销毁并重建 Bridge 实例，再用原 client_id 完成 authorize 和 token；同时验证未知、过期、撤销和错误 redirect 仍为 `invalid_client`。

### 下次快速识别

故障与 PM2 restart/release 切换强相关，且 client_id 在客户端稳定而服务端查不到时，先查 registry 生命周期和绝对路径。

### 证据

- Commit `0465a89`（后随 PR #47 合入 main）。
- `bridge/oauthClientRegistry.js`
- `bridge/oauth.test.js`：`DCR client registration survives a Bridge restart...`。
- `docs/changes/2026-08-13_OAuth客户端登记持久化.md`

---

## [04] 模拟 OAuth client 通过，真实 Claude Code 却因 grant/refresh 合约失败

### 现象

基础 authorization_code 测试通过，真实 Claude Code DCR 却被拒绝，或首次 token 成功后无法持久复用；重启、并发刷新或 token 重放时行为不稳定。

### 容易误判成

Claude Code 登录状态、浏览器 callback 或 owner 密码问题。

### 真实根因

真实客户端登记的是 `authorization_code + refresh_token`，旧实现只接受单一 authorization_code；refresh token 又需要持久、轮换、并发安全且绑定 client/resource/scope，不能用只覆盖 happy path 的内存状态代替。

### 错误尝试 / 为什么没用

只让 DCR 接受新字符串、但不实现持久 refresh 语义，会把失败推迟到 token reuse；把 token 明文落盘或使用 release 相对路径又会引入泄露和部署漂移。

### 正确处理

严格允许已观察到的 grant 组合，保持 code + PKCE；refresh token 只存 HMAC 摘要，原子持久化、轮换并拒绝重放。client registry 与 refresh store 都用 release 外绝对路径，VPS 迁移时与同一 `OAUTH_TOKEN_SECRET` 一起迁移。

### 验收方法

真实形状 DCR；code exchange；Bridge restart 后 refresh；两个 fresh child 复用；并发写；旧 refresh replay 撤销 token family；secret 轮换后旧 token fail closed。

### 下次快速识别

mock client 正常、真实 CLI 在 DCR 或第二次连接失败时，先抓真实 client metadata/grant，而不是继续猜浏览器流程。

### 证据

- Commit `c1b0c5b`（后随 PR #46 合入 main）。
- `bridge/oauthRefreshStore.js`, `bridge/oauthClientRegistry.js`, `bridge/oauth.js`
- `bridge/oauth.test.js`：refresh 持久化、轮换、重放、secret rotation 测试。
- `docs/changes/2026-08-13_ClaudeCodeDCR与RefreshToken兼容.md`

---

## [05] authorize 已返回跳转，但 CSP 阻止浏览器真正离开授权页

### 现象

用户点击“允许访问”后页面停住；Bridge 日志显示 owner 校验成功并返回 302/303，但客户端没有继续 `/oauth/token`。

### 容易误判成

表单按钮没提交、Supabase 登录卡住、redirect URI 校验失败，或单一浏览器 bug。

### 真实根因

授权页 CSP 曾为 `form-action 'self'`，浏览器阻止表单提交后的跨域 callback；后来只硬编码 `https://claude.ai` 又会阻止 ChatGPT 的 `https://chatgpt.com/...` callback。服务端返回跳转不等于浏览器执行了跳转。

### 错误尝试 / 为什么没用

反复点击或只看 Bridge 的 302 会制造更多请求，却不会绕过浏览器 CSP；持续手工追加客户端域名会形成维护名单并再次漏掉新 callback origin。

### 正确处理

保留 CSP，但从已经通过 client registry 与 redirect URI 精确校验的 callback origin 动态生成 `form-action`；不放宽 redirect 校验。成功审批使用 303 并原样保留 state。

### 验收方法

分别用 Claude 与 ChatGPT 注册的 HTTPS callback 检查授权页 CSP；点击一次后确认浏览器实际到 callback、随后出现 `/oauth/token` 和已认证 MCP 请求；非法 redirect 仍拒绝。

### 下次快速识别

服务端已 3xx、Location 正确、浏览器仍停原页且 token endpoint 没请求时，立即看浏览器 CSP console 和响应头。

### 证据

- Commit `85ee28d` / PR #55：先允许 Claude callback。
- Commit `ba8dc39`（合入 `97b62cb` / PR #56）：改为经验证 origin 动态 CSP。
- `bridge/oauth.js`, `bridge/oauth.test.js`
- `docs/changes/2026-08-22_OAuthMCP全链路收口.md`

---

## [06] OAuth 审批限流按代理 IP 聚合，单人系统也连续 429

### 现象

不同浏览器/设备做少量正常 Connect 后，`POST /oauth/authorize` 在 15 分钟内持续 429，调试和真实授权都被挡住。

### 容易误判成

用户点太多次、攻击流量、客户端循环重试，或账号被锁。

### 真实根因

审批热路径使用 `oauth-approval:${req.ip}` 的 5 次/15 分钟限流；生产 Nginx 当时没有转发真实 `X-Forwarded-For` / `X-Real-IP`，所有浏览器共享反向代理地址桶。

### 错误尝试 / 为什么没用

等待冷却、换浏览器或换设备仍可能命中同一代理桶；加验证码、黑名单或新的 cooldown 会继续增加与实际风险不相称的门锁。

### 正确处理

删除妨碍单人 owner 审批的 cooldown，保留 redirect、PKCE、state、owner credential、code/token 等真实安全边界；Nginx 为 OAuth/API 传真实转发头，Bridge 只信 loopback proxy。DCR 容量保护可独立保留。

### 验收方法

连续完成超过 5 次合法审批不出现 approval 429；错误 owner credential 仍 401；非法 redirect/PKCE 仍拒绝；日志能区分真实来源。

### 下次快速识别

多设备同时 429、计数却像同一客户端时，先检查 `req.ip`、trust proxy 和 Nginx headers，再看用户行为。

### 证据

- Commit `ba8dc39`。
- `bridge/server.js`：`trust proxy` 限定 loopback。
- `docs/changes/2026-08-22_OAuthMCP全链路收口.md`：真实 429 与 Nginx header 记录。
- `bridge/oauth.test.js`：重复审批、owner 错误和 redirect/PKCE 回归。

---

## [07] GPT 与 Claude 共用一个 MCP resource，认证成功也可能进入错误 actor

### 现象

客户端能完成 OAuth，却看到错误的私有 Memory、错误工具行为或旧的 14-tool surface；GPT connector 实际进入 Claude fixed actor。

### 容易误判成

Memory V2 权限串线、tools/list 没部署，或模型自己选错工具。

### 真实根因

生产曾只暴露 `/api/mcp/claude`。GPT 若复用该 resource，会通过同一 transport 进入 Claude fixed actor；token audience、protected-resource metadata 与 endpoint identity 也无法区分。旧 connector 还可能缓存旧 endpoint/tool schema。

### 错误尝试 / 为什么没用

只改工具定义或 Memory 查询无法修正入口 actor；在客户端原 connector 上反复刷新也不保证清掉旧 resource/schema 缓存。

### 正确处理

分离 `/api/mcp/gpt` 与 `/api/mcp/claude`，分别发布 metadata、绑定 token audience 和 fixed actor。部署后删除旧 connector，用正确 URL 全新连接，再检查 tools/list。

### 验收方法

两个 endpoint 各自完成 DCR/OAuth/initialize；交叉 audience token 必须失败；GPT/Claude private 隔离通过；两边 tools/list 都精确返回当前 7 tools，并各调用 `wake_up`/`read_livingroom`。

### 下次快速识别

OAuth 显示“连接成功”但 actor 或工具数异常时，先核对 connector URL、token audience 和 server-side fixed actor，不要先改 Memory RLS。

### 证据

- Commit `ba8dc39`。
- `bridge/server.js`：独立 `GPT_MCP_RESOURCE` / `CLAUDE_MCP_RESOURCE`。
- `bridge/oauth.test.js`：两个 resource、audience 交叉拒绝、exact tools/list 和 fixed actor。
- `docs/changes/2026-08-22_OAuthMCP全链路收口.md`

---

## [08] Repository 把 PostgREST 顶层数组误拆成首项

### 现象

SQL/RPC 已返回多条数据，旁路 smoke 却只看到一条；权限和 schema 看似正常，Service 得到的数据形状却不完整。

### 容易误判成

生产 migration 漏数据、RLS 过滤过多、RPC limit 错误或迁移计数不一致。

### 真实根因

Repository 为兼容 PostgREST 包装数组而无条件取首项，把“顶层 JSON 数组就是业务结果”的合法返回误当成包装层。

### 错误尝试 / 为什么没用

调整 SQL limit、重跑迁移或放宽权限不会修复客户端解包；反而可能制造重复数据。

### 正确处理

只在 payload 形状明确为“单元素且该元素仍是数组”时解一层；普通顶层数组和对象原样保留。

### 验收方法

Repository 对对象、普通数组、单元素嵌套数组分别做形状测试；生产旁路 smoke 对照 SQL/RPC 实际条数与 Service 返回条数。

### 下次快速识别

数据库计数正确而应用永远只有第一条时，优先检查 REST/RPC response unwrap，而不是重做查询。

### 证据

- Commit `161063a`（合入 `71e430a` / PR #49）。
- `bridge/memory-v2/repository.js`, `bridge/memory-v2/repository.test.js`
- `docs/changes/2026-08-20_MemoryV2Phase2A生产旁路验活.md`

---

## [09] remember 已成功，但立即 semantic recall 漏掉尚未向量化的 current revision

### 现象

`remember`/`revise` 返回成功，紧接着第一次 recall 找不到刚写入或刚修订的内容；几秒后 embedding 落库再查又出现。

### 容易误判成

写入事务未提交、Memory 数据丢失、semantic 服务整体故障或 actor 隔离错误。

### 真实根因

正文事务与 best-effort embedding 有时间差。semantic 查询已经成功并返回其他结果时，旧逻辑不会走“失败才触发”的 lexical fallback，因此尚无 embedding 的 current revision 被漏掉。

### 错误尝试 / 为什么没用

sleep/retry 只掩盖 read-after-write 一致性缺口；改成 remember 等待 Ollama 会让 embedding 重新成为写入生命维持器。

### 正确处理

保持写入不依赖 embedding；semantic 成功时再做 bounded lexical supplement，只补 semantic 未覆盖的匹配 current revision，按 memory id 去重，然后继续使用原 ranker 和 hard limit。lexical supplement 失败不能降级已经成功的 semantic 结果。

### 验收方法

人为延迟 embedding 10 秒：remember 后零等待第一次 recall 必须命中；revise 后只出现 current revision；embedding 完成后 semantic 命中且无重复；Ollama 完全不可用仍走 lexical fallback；actor/Shared/limit/rank 保持。

### 下次快速识别

“刚写完查不到、稍后能查到”，且 recall mode 仍是 semantic 时，先查未向量化 current revision，而不是数据库事务。

### 证据

- Commit `27b35ad`（合入 `cf7de6f` / PR #54）。
- `bridge/memory-v2/service.js`：`mergeSemanticWithLexicalSupplements`。
- `bridge/memory-v2/service.test.js`：延迟、去重、隔离、rank/limit 和 supplement failure 测试。

---

## [10] Embedding 超时不等于 Memory 不可用，冷/热与长文本必须分开看

### 现象

semantic 请求超时、backfill 停在部分完成，或 recall 返回 `lexical_fallback`；正文和 V2 表仍完好。

### 容易误判成

Memory migration 失败、向量表损坏、Tailscale 整体断网，或必须新增 queue/worker 才能救活。

### 真实根因

Embedding 是独立 sidecar，耗时受模型是否已加载、文本长度、PC 算力和网络共同影响。Phase 2E 的真实 backfill 首轮完成 46/58，剩余长文本在一次性 30 秒预算内超时；提高仅该 harness 的预算后完成 58/58。服务正常路径本来就设计为 semantic 不可用时 lexical fallback、恢复后自动回 semantic。

### 错误尝试 / 为什么没用

把 timeout 直接归因于数据库或立即堆 retry/queue/daemon，会扩大架构且不区分网络、模型加载和实际计算；把 lexical fallback 当正常 semantic PASS 也会掩盖降级。

### 正确处理

先分别测轻量 Ollama 接口、相同请求的连续 embedding、维度与模型状态；写入始终成功。对一次性 backfill 可使用受控较长预算并保留可重跑/跳过已完成项，正常 recall 仍使用有限 timeout并显式返回 mode/semantic_error。

### 验收方法

Ollama 在线时 `mode=semantic` 且 `semantic_error=null`；临时不可达时 recall 成功并明确 `lexical_fallback`；恢复后无需重启数据库/迁移即可回 semantic。backfill 对已有同模型向量跳过，最终 current revisions 数量一致。

### 下次快速识别

先看 `mode`、`semantic_error`、Ollama 轻量接口和连续三次耗时；不要用单次 timeout 判断是网络还是模型。

### 证据

- `bridge/memory-v2/ollamaEmbedding.js`, `bridge/memory-v2/service.js`
- `bridge/memory-v2/ollamaEmbedding.test.js`, `bridge/memory-v2/service.test.js`
- `docs/changes/2026-08-20_MemoryV2OllamaSemanticPhase2E.md`：46/58、受控续跑至 58/58、断连与恢复结果。
- Commit `7af57ff` / PR #51。

---

## [11] 把 LivingRoom 当作 LoveHouse 单人聊天 source，制造了错误兼容

### 现象

Memory 的 `lovehouse_message` / range source 能“展开”，但读到的是小客厅行；未来换聊天存储、前端或数据源时 source identity 失真。

### 容易误判成

只要能拿到消息正文就算 source 功能完成，LivingRoom 恰好可复用。

### 真实根因

通用 Memory evidence 直接依赖 livingroom 表、fence/Supabase REST 路径，把独立三方小客厅错误地当成 canonical 单人 ChatMessageRepository。

### 错误尝试 / 为什么没用

在 MemoryService 内继续加 livingroom 分支会把渠道、存储实现和权限生命周期焊死；它不是兼容层，而是假 source identity。

### 正确处理

Memory core 只依赖 `SourceResolver`；manual quote 从 canonical snapshot 展开，manual summary 明确 unavailable，LoveHouse chat 通过 channel-neutral message repository。正式单人消息仓库不存在时返回 `MEMORY_CHAT_SOURCE_NOT_CONFIGURED`，绝不 fallback 到 livingroom。

### 验收方法

resolver 按 kind/channel dispatch；LoveHouse source 未配置时 fail closed；livingroom adapter 独立；manual quote/summary、namespace、Shared、audit、range limit 保持。

### 下次快速识别

看到“先借另一张消息表顶一下”的 source 实现时，先问 stable message id 的语义是否真的相同。

### 证据

- PR #44 merge commit `5d174174af37787fe869c127dae5caa335312cfe`。
- `bridge/memory/source.js`, `bridge/memory/source.test.js`
- `docs/changes/2026-08-13_MemorySourceResolver解耦_02.md`

---

## [12] `/health` 200 和 feature flag 被误当成 schema/release 证明

### 现象

Bridge `/health` 返回 200，并显示 `database_migration=expected`；部署脚本据此认为目标 release 与 Memory schema 都已就绪。

### 容易误判成

服务已运行正确 commit、数据库 migration 已应用、Memory repository 可达。

### 真实根因

当前 main 的 `bridge/server.js` 仍由 `MEMORY_SYSTEM_ENABLED` 推导 `database_migration=expected/not_applied`，没有查询 migration/schema；health 也没有可信 release identity。200 只能证明某个进程在该端口回应，甚至不能证明监听 PID 就是刚切换的 PM2 进程。

### 错误尝试 / 为什么没用

把可能漂移的 `LOVEHOUSE_DEPLOY_COMMIT` 原样展示，或只探测端口 200，会把配置声明继续冒充运行事实。

### 正确处理

部署验收同时核对 PM2 新 PID、cwd/script、监听端口 PID、loopback/HTTPS health 和 release 目录；schema 只有真实 DB probe/migration history 才能证明。无法可靠证明的字段应明确 unavailable，而不是猜测。

### 验收方法

测试 health 每个字段的数据来源；切换 release 后 PID/cwd/listener 四者一致；feature flag 开关不能改变 schema claim；若没有 DB probe，health 不声明 schema ready。

### 下次快速识别

任何 `expected`、`enabled`、环境变量 SHA 或单一 200 都是“配置意图”，不是部署/数据库事实。

### 证据

- 当前 `bridge/server.js` 的 `/health`：`database_migration` 由 flag 推导。
- `gate-a-backups/bridge-deploy-smoke-latest.txt`：200 health 与派生字段并存的历史部署输出。
- Branch commit `919109b2cb359f41895de8c2c203536eb98f48ae` 及其中的 `docs/changes/2026-08-20_Bridge真实Health收平.md` 记录了问题和最小修法；需用 `git show 919109b:docs/changes/2026-08-20_Bridge真实Health收平.md` 查看，该 commit 不在本版 main，不能写成已上线。

---

## [13] Wrangler 自动猜配置失败，源码存在却报 Missing entry-point

### 现象

Cloudflare deploy job 报 `Missing entry-point`，仓库明明已有 `src/proxy.js` 和配置中的 `main`。

### 容易误判成

Worker 源文件丢失、构建产物没生成或业务代码错误。

### 真实根因

Action 在项目未安装 Wrangler 时回退到 Wrangler 3.90.0；该执行路径没有自动读取仓库使用的 `wrangler.json`，因此 `main: src/proxy.js` 被忽略。

### 错误尝试 / 为什么没用

重写 proxy 或重复 build 不会让错误版本的 CLI 读取正确配置；“本地有文件”也不能证明 CI 用的是同一工具/配置入口。

### 正确处理

在 workflow 固定已验证 Wrangler 版本，并显式 `deploy --config wrangler.json`；用 dry-run 证明识别 Worker entry 与静态 assets。

### 验收方法

CI 打印的 Wrangler 版本固定；dry-run 显示 `src/proxy.js` 与 dist assets；PR build 不部署，正式 deploy job 使用同一显式 config。

### 下次快速识别

CI 报 entrypoint 缺失而文件存在时，先查 CLI 版本和实际加载的 config 文件，不要先改业务代码。

### 证据

- Commit `bf7c4cf9eb0d5e21aa325d7d2c71ed4ccbe3abd1` / PR #38。
- `.github/workflows/deploy.yml`, `wrangler.json`
- `docs/changes/2026-08-11_Cloudflare入口配置修复.md`

---

## [14] 合并代码 PR 意外触发 Cloudflare 100% 生产部署

### 现象

本意只是合并 Worker entry/config 修复，但 main push workflow 自动执行 `wrangler deploy`，创建新版本并直接切到生产流量。

### 容易误判成

“CI 全绿”只代表构建验证，或 Cloudflare Git preview 不会动 production。

### 真实根因

workflow 把 main push 与 Cloudflare production deploy 绑定；发布权限边界存在于流程意图里，却没有落实为事件/ref gate。代码 PR 的 merge 因而同时成为生产按钮。

### 错误尝试 / 为什么没用

只在 PR 正文写“不部署”无法覆盖 workflow；把 Cloudflare job 拆成独立 job 但仍在 main push 条件下运行，也没有真正分离权限。

### 正确处理

PR 仅 build；main push 可按既定策略部署 Pages；Cloudflare production 仅允许 `workflow_dispatch` + main + 显式开关，并使用独立 concurrency group。

### 验收方法

事件矩阵测试：PR 的 deploy jobs skipped；main push 的 Cloudflare skipped；非 main 手动运行 skipped；只有 main 手动授权时执行。再在面板确认 active production version 未被 preview/merge替换。

### 下次快速识别

“没有手动点部署”不代表没部署；每次 merge 前先读 workflow 的 event/ref/if，而不是依赖文字约定。

### 证据

- Commit `1d8bcf7`，后续记录 `ee5e5d3`。
- `.github/workflows/deploy.yml`
- `docs/changes/2026-08-11_Cloudflare人工生产部署门禁.md`：PR #38 合并后旧 workflow 的实际 production version 记录。

---

## [15] 同名“Memory”并存多套数据与入口，查对了表也可能查错系统

### 现象

数据库里能看到 Memory 数据/迁移，应用却 recall 不到；或旧 Web、V1 canonical、Brain 与 Memory V2 返回的数量和字段互相矛盾。

### 容易误判成

迁移失败、数据丢失、RLS 串线，或某一套 Memory 已经全面替代其他系统。

### 真实根因

LoveHouse 曾同时保留 `memories`、`brain`、canonical `memory_*` 与旁路 `memory_v2_*`。Phase 2B 只迁 canonical V1 的符合规则数据，candidate Shared 明确不升级；V1 表/RPC 继续保留。数据库“存在 V2”与 Bridge/MCP“已经路由到 V2”是两件独立事实。

### 错误尝试 / 为什么没用

只查一张表、只看 migration 文件、或把所有 Memory 数量强行对齐，会忽略入口 backend、actor、Shared 状态和迁移范围；删除旧表会毁掉回滚证据。

### 正确处理

诊断时先写清入口（Web/MCP/旁路 harness）、backend selector/运行 commit、actor，再定位表/RPC。迁移用 legacy id/revision marker 幂等映射；candidate、旧表和 V1 数据保持原样直到独立退休审核。

### 验收方法

分别对 V1 与 V2 做 actor-scoped count/current revision/source/link 对照；确认 MCP tools 实际调用的 Service/Repository；验证 candidate 未升级、approved Shared 双方可见、旧 revision 不进入普通 recall。

### 下次快速识别

看到“Memory 有数据但接口没有”时，先问“哪一代、哪个入口、哪个 actor、哪个 current revision”，不要直接改 SQL。

### 证据

- `supabase/migrations/20260808191311_create_unified_memory_system_v2.sql`
- `supabase/migrations/20260820110600_create_memory_v2_phase1.sql`
- `supabase/data_migrations/20260820_migrate_canonical_v1_to_memory_v2.sql`
- `docs/changes/2026-08-20_MemoryV2Phase2B迁移CanonicalV1.md`：52 entries / 55 revisions / 15 sources / 18 links，candidate Shared 明确保留未升级。
- `docs/changes/2026-08-20_MemoryV2Phase2A生产旁路验活.md`：V2 旁路存在但当时 Bridge 仍走旧链。

---

## [16] 名为“当前架构”的文档也会漂移，不能替代代码和 runtime

### 现象

按 `ARCHITECTURE_CURRENT.md` 或 `02_当前架构.md` 操作会得到 14-tool、V2 未接 MCP、生产尚未启用等结论；当前 main 代码却已经是 7-tool MCP 并注册 Memory V2。`CLAUDE.md` 还写“所有表 RLS 关闭”。

### 容易误判成

文件名含“当前”就代表当前生产，或历史 change doc 的“未部署”状态永远有效。

### 真实根因

架构文档按历史工单追加，后续 merge/cutover 没有原子更新所有总览；时间点记录和当前真相混在一起。多个部署入口又让 repo main、生产 release 与文档时间点可能不同步。

### 错误尝试 / 为什么没用

继续在旧总览上追加补丁只会保留互相冲突的段落；用另一份文档去证明它也无法解决 runtime 漂移。

### 正确处理

执行顺序固定为：真实 runtime/DB metadata → 当前运行 release 对应源码 → Git history/测试 → 带日期 change record → 总览文档。历史记录保留时间语境，不把 planned/local/merged/deployed 混写。

### 验收方法

对关键数字和开关做机械对照：当前 `createMcpToolDefinitions()` 返回数量、server 路由、PM2 cwd/listener、DB migration history/RLS。文档若冲突则标 stale，不反向修改事实。

### 下次快速识别

文档出现“当前、已完成、未部署”而没有 commit/runtime timestamp 时，一律视为待验证声明。

### 证据

- 当前 `bridge/mcp/tools.js`：7 个正式 tool definitions。
- 当前 `bridge/server.js`：GPT/Claude Memory V2 facade 与独立 MCP endpoints。
- `docs/ARCHITECTURE_CURRENT.md:221-223` 仍描述 14 tools；`docs/02_当前架构.md:171-197` 仍描述 V2 未接 MCP/Bridge。
- `CLAUDE.md:98` 声称所有表 RLS 关闭，而 `supabase/migrations/20260820110600_create_memory_v2_phase1.sql:107-116` 对 V2 五表启用并 FORCE RLS。

## 本版边界

- 本版没有把尚未取得失败现场或真实客户端回执的候选写成确定事实；见《LoveHouse_工程错题集_V1_待人工确认.md》。
- 本文不声明当前生产 SHA、PID、Cloudflare version 或数据库实时行数；这些都是高漂移事实，必须在下一次使用时重新只读验证。
- 本轮只新增文档，没有修改代码、数据库、OAuth、Memory、PM2、Nginx、Worker 或生产环境。
