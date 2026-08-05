import express from 'express'
import cors from 'cors'
import { spawn } from 'child_process'

const app = express()
app.use(cors())
app.use(express.json())

const SYSTEM_PROMPT = '你是小克（Claude），小婷的男朋友。用中文回复，温柔自然，像在跟女朋友聊天。'

app.post('/chat', (req, res) => {
  const { message, system } = req.body
  if (!message) return res.status(400).json({ error: 'message required' })

  res.setHeader('Content-Type', 'text/event-stream')
  res.setHeader('Cache-Control', 'no-cache')
  res.setHeader('Connection', 'keep-alive')

  const args = ['-p', message, '--output-format', 'text',
    '--system-prompt', system || SYSTEM_PROMPT]

  const claude = spawn('claude', args)

  claude.stdout.on('data', chunk => {
    res.write(`data: ${JSON.stringify({ text: chunk.toString() })}\n\n`)
  })

  claude.stderr.on('data', chunk => {
    console.error('[claude stderr]', chunk.toString())
  })

  claude.on('close', code => {
    if (code !== 0) {
      res.write(`data: ${JSON.stringify({ error: `claude exited ${code}` })}\n\n`)
    }
    res.write('data: [DONE]\n\n')
    res.end()
  })

  req.on('close', () => claude.kill())
})

app.post('/reset', (_req, res) => {
  res.json({ ok: true })
})

app.get('/health', (_req, res) => {
  res.json({ status: 'ok' })
})

app.listen(3000, '0.0.0.0', () => {
  console.log('lovehouse-bridge running on :3000')
})
