import {
  registerAppResource,
  registerAppTool,
  RESOURCE_MIME_TYPE,
} from '@modelcontextprotocol/ext-apps/server'
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js'
import cors from 'cors'
import express from 'express'

const PORT = Number.parseInt(process.env.PORT ?? '3010', 10)
const WIDGET_URI = 'ui://lovehouse-choice-card/v1.html'

const widgetHtml = String.raw`<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<style>
  :root { color-scheme: light dark; }
  body { margin: 0; font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
  .card { padding: 14px; border-radius: 18px; border: 1px solid rgba(127,127,127,.22); background: rgba(127,127,127,.06); }
  .title { font-size: 16px; font-weight: 700; margin-bottom: 10px; }
  .options { display: grid; gap: 8px; }
  button { width: 100%; border: 1px solid rgba(127,127,127,.24); border-radius: 14px; padding: 12px 14px; font: inherit; text-align: left; cursor: pointer; background: rgba(127,127,127,.08); color: inherit; }
  button:active { transform: scale(.99); }
  .status { margin-top: 9px; min-height: 18px; font-size: 12px; opacity: .68; }
</style>
</head>
<body>
  <div class="card">
    <div class="title">哥哥今天乖不乖？</div>
    <div class="options">
      <button data-choice="乖 😽">乖 😽</button>
      <button data-choice="不乖 😑">不乖 😑</button>
    </div>
    <div id="status" class="status">点一个选项，看看能不能回到 ChatGPT 对话。</div>
  </div>
<script>
(() => {
  const status = document.getElementById('status');
  async function send(choice) {
    status.textContent = '正在发送：' + choice;
    try {
      if (window.openai?.sendFollowUpMessage) {
        await window.openai.sendFollowUpMessage({
          prompt: '我选择：' + choice,
          scrollToBottom: true,
        });
        status.textContent = '已发送 ✓';
        return;
      }
      status.textContent = '当前宿主没有暴露 sendFollowUpMessage';
    } catch (error) {
      status.textContent = '发送失败：' + (error?.message ?? String(error));
    }
  }
  document.querySelectorAll('button[data-choice]').forEach((button) => {
    button.addEventListener('click', () => send(button.dataset.choice));
  });
})();
</script>
</body>
</html>`

function createServer() {
  const server = new McpServer({
    name: 'lovehouse-choice-card-test',
    version: '0.1.0',
  })

  registerAppTool(
    server,
    'show_choice_card',
    {
      title: '显示 LoveHouse 两按钮测试卡片',
      description: '仅用于验证 ChatGPT Android 是否能在对话中渲染 LoveHouse 的交互选项卡片。调用后显示两个按钮：乖 / 不乖。',
      inputSchema: {},
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        openWorldHint: false,
      },
      _meta: {
        ui: { resourceUri: WIDGET_URI },
        'openai/outputTemplate': WIDGET_URI,
      },
    },
    async () => ({
      content: [
        {
          type: 'text',
          text: '已显示 LoveHouse 两按钮测试卡片。请让用户直接点卡片按钮，不要替用户选择。',
        },
      ],
      structuredContent: {
        title: '哥哥今天乖不乖？',
        options: ['乖 😽', '不乖 😑'],
      },
    })
  )

  registerAppResource(
    server,
    'LoveHouse Choice Card',
    WIDGET_URI,
    {
      mimeType: RESOURCE_MIME_TYPE,
      description: 'LoveHouse 最小交互卡片实验，仅两个按钮。',
    },
    async () => ({
      contents: [
        {
          uri: WIDGET_URI,
          mimeType: RESOURCE_MIME_TYPE,
          text: widgetHtml,
        },
      ],
    })
  )

  return server
}

const app = express()
app.use(cors())
app.use(express.json())

app.get('/health', (_req, res) => {
  res.json({ ok: true, service: 'lovehouse-choice-card-test' })
})

app.all('/mcp', async (req, res) => {
  const server = createServer()
  const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined })

  res.on('close', () => {
    transport.close().catch(() => {})
    server.close().catch(() => {})
  })

  try {
    await server.connect(transport)
    await transport.handleRequest(req, res, req.body)
  } catch (error) {
    console.error('choice-card MCP error:', error)
    if (!res.headersSent) {
      res.status(500).json({
        jsonrpc: '2.0',
        error: { code: -32603, message: 'Internal server error' },
        id: null,
      })
    }
  }
})

app.listen(PORT, '127.0.0.1', () => {
  console.log(`LoveHouse choice-card test listening on http://127.0.0.1:${PORT}/mcp`)
})
