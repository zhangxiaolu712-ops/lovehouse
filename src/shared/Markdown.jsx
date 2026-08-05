import { useMemo } from 'react'

function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function parseInline(text) {
  const parts = []
  const re = /(`[^`]+`)|(\*\*[^*]+\*\*)|(\*[^*]+\*)|(\[([^\]]+)\]\(([^)]+)\))/g
  let last = 0
  let m

  while ((m = re.exec(text)) !== null) {
    if (m.index > last) parts.push(text.slice(last, m.index))
    if (m[1]) parts.push(<code key={m.index} className="md-code">{m[1].slice(1, -1)}</code>)
    else if (m[2]) parts.push(<strong key={m.index}>{m[2].slice(2, -2)}</strong>)
    else if (m[3]) parts.push(<em key={m.index}>{m[3].slice(1, -1)}</em>)
    else if (m[4]) parts.push(<a key={m.index} className="md-link" href={m[6]} target="_blank" rel="noopener noreferrer">{m[5]}</a>)
    last = m.index + m[0].length
  }
  if (last < text.length) parts.push(text.slice(last))
  return parts.length ? parts : [text]
}

function parseBlocks(src) {
  const lines = src.split('\n')
  const blocks = []
  let i = 0

  while (i < lines.length) {
    const line = lines[i]

    if (line.startsWith('```')) {
      const lang = line.slice(3).trim()
      const code = []
      i++
      while (i < lines.length && !lines[i].startsWith('```')) {
        code.push(lines[i])
        i++
      }
      blocks.push({ type: 'codeblock', lang, content: code.join('\n') })
      i++
      continue
    }

    if (line.startsWith('### ')) {
      blocks.push({ type: 'h3', content: line.slice(4) })
      i++; continue
    }
    if (line.startsWith('## ')) {
      blocks.push({ type: 'h2', content: line.slice(3) })
      i++; continue
    }
    if (line.startsWith('# ')) {
      blocks.push({ type: 'h1', content: line.slice(2) })
      i++; continue
    }

    if (/^[-*] /.test(line)) {
      const items = []
      while (i < lines.length && /^[-*] /.test(lines[i])) {
        items.push(lines[i].slice(2))
        i++
      }
      blocks.push({ type: 'ul', items })
      continue
    }

    if (/^\d+\. /.test(line)) {
      const items = []
      while (i < lines.length && /^\d+\. /.test(lines[i])) {
        items.push(lines[i].replace(/^\d+\. /, ''))
        i++
      }
      blocks.push({ type: 'ol', items })
      continue
    }

    if (line.trim() === '') {
      i++; continue
    }

    blocks.push({ type: 'p', content: line })
    i++
  }

  return blocks
}

export default function Markdown({ text }) {
  const blocks = useMemo(() => parseBlocks(text || ''), [text])

  return (
    <div className="md">
      {blocks.map((b, i) => {
        switch (b.type) {
          case 'h1': return <h4 key={i} className="md-h1">{parseInline(b.content)}</h4>
          case 'h2': return <h5 key={i} className="md-h2">{parseInline(b.content)}</h5>
          case 'h3': return <h6 key={i} className="md-h3">{parseInline(b.content)}</h6>
          case 'codeblock':
            return (
              <pre key={i} className="md-pre">
                {b.lang && <span className="md-lang">{b.lang}</span>}
                <code>{escapeHtml(b.content)}</code>
              </pre>
            )
          case 'ul':
            return <ul key={i} className="md-ul">{b.items.map((item, j) => <li key={j}>{parseInline(item)}</li>)}</ul>
          case 'ol':
            return <ol key={i} className="md-ol">{b.items.map((item, j) => <li key={j}>{parseInline(item)}</li>)}</ol>
          default:
            return <p key={i} className="md-p">{parseInline(b.content)}</p>
        }
      })}
    </div>
  )
}
