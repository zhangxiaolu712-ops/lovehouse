import test from 'node:test'
import assert from 'node:assert/strict'
import { createStreamParser } from './claudeProcess.js'

function makeStreamEvent(event) {
  return JSON.stringify({
    type: 'stream_event',
    event,
    session_id: 'test-session',
    uuid: 'test-uuid',
  })
}

function blockStart(type, extra = {}) {
  return makeStreamEvent({
    type: 'content_block_start',
    index: 0,
    content_block: { type, ...extra },
  })
}

function blockDelta(delta) {
  return makeStreamEvent({
    type: 'content_block_delta',
    index: 0,
    delta,
  })
}

function blockStop() {
  return makeStreamEvent({ type: 'content_block_stop', index: 0 })
}

test('text-only: forwards text deltas, no thinking', () => {
  const texts = []
  const thinkings = []
  const parser = createStreamParser({
    onText: t => texts.push(t),
    onThinking: t => thinkings.push(t),
  })

  parser.feed([
    blockStart('text'),
    blockDelta({ type: 'text_delta', text: '你' }),
    blockDelta({ type: 'text_delta', text: '好' }),
    blockStop(),
  ].join('\n') + '\n')

  parser.flush()
  assert.deepEqual(texts, ['你', '好'])
  assert.deepEqual(thinkings, [])
  assert.equal(parser.getText(), '你好')
})

test('thinking+text: thinking via onThinking, text via onText', () => {
  const texts = []
  const thinkings = []
  const parser = createStreamParser({
    onText: t => texts.push(t),
    onThinking: t => thinkings.push(t),
  })

  parser.feed([
    blockStart('thinking'),
    blockDelta({ type: 'thinking_delta', thinking: '让我想想' }),
    blockDelta({ type: 'thinking_delta', thinking: '...' }),
    blockStop(),
    blockStart('text'),
    blockDelta({ type: 'text_delta', text: '答案是42' }),
    blockStop(),
  ].join('\n') + '\n')

  parser.flush()
  assert.deepEqual(thinkings, ['让我想想', '...'])
  assert.deepEqual(texts, ['答案是42'])
  assert.equal(parser.getText(), '答案是42')
})

test('redacted_thinking: silently ignored, text still works', () => {
  const texts = []
  const thinkings = []
  const parser = createStreamParser({
    onText: t => texts.push(t),
    onThinking: t => thinkings.push(t),
  })

  parser.feed([
    blockStart('redacted_thinking'),
    blockDelta({ type: 'redacted_thinking_delta', data: 'opaque' }),
    blockStop(),
    blockStart('text'),
    blockDelta({ type: 'text_delta', text: '回复' }),
    blockStop(),
  ].join('\n') + '\n')

  parser.flush()
  assert.deepEqual(thinkings, [])
  assert.deepEqual(texts, ['回复'])
  assert.equal(parser.getText(), '回复')
})

test('unknown event types: non-stream_event lines silently ignored', () => {
  const texts = []
  const thinkings = []
  const parser = createStreamParser({
    onText: t => texts.push(t),
    onThinking: t => thinkings.push(t),
  })

  parser.feed([
    JSON.stringify({ type: 'active_goal', value: null }),
    JSON.stringify({ type: 'system', subtype: 'init', model: 'test' }),
    JSON.stringify({ type: 'autocompact_state', value: {} }),
    JSON.stringify({ type: 'rate_limit_event', rate_limit_info: {} }),
    JSON.stringify({ type: 'assistant', message: { content: [] } }),
    JSON.stringify({ type: 'result', result: 'text' }),
    blockStart('text'),
    blockDelta({ type: 'text_delta', text: '正常' }),
    blockStop(),
  ].join('\n') + '\n')

  parser.flush()
  assert.deepEqual(texts, ['正常'])
  assert.deepEqual(thinkings, [])
  assert.equal(parser.getText(), '正常')
})

test('unknown block type: silently ignored', () => {
  const texts = []
  const parser = createStreamParser({ onText: t => texts.push(t) })

  parser.feed([
    blockStart('future_block_type'),
    blockDelta({ type: 'future_delta', data: 'mystery' }),
    blockStop(),
    blockStart('text'),
    blockDelta({ type: 'text_delta', text: 'ok' }),
    blockStop(),
  ].join('\n') + '\n')

  parser.flush()
  assert.deepEqual(texts, ['ok'])
})

test('bad JSON lines: gracefully skipped, text still works', () => {
  const texts = []
  const errors = []
  const parser = createStreamParser({
    onText: t => texts.push(t),
    onError: e => errors.push(e),
  })

  parser.feed([
    'not json at all',
    '{broken json',
    '',
    '   ',
    blockStart('text'),
    'another bad line {{{',
    blockDelta({ type: 'text_delta', text: '没问题' }),
    blockStop(),
  ].join('\n') + '\n')

  parser.flush()
  assert.deepEqual(texts, ['没问题'])
  assert.deepEqual(errors, [])
  assert.equal(parser.getText(), '没问题')
})

test('signature in content_block_start is not forwarded to callbacks', () => {
  const texts = []
  const thinkings = []
  const allCalls = []
  const parser = createStreamParser({
    onText: t => { texts.push(t); allCalls.push({ type: 'text', value: t }) },
    onThinking: t => { thinkings.push(t); allCalls.push({ type: 'thinking', value: t }) },
  })

  parser.feed([
    makeStreamEvent({
      type: 'content_block_start',
      index: 0,
      content_block: { type: 'thinking', thinking: '', signature: 'sig_abc123xyz' },
    }),
    blockDelta({ type: 'thinking_delta', thinking: '思考中' }),
    blockStop(),
    blockStart('text'),
    blockDelta({ type: 'text_delta', text: '回复' }),
    blockStop(),
  ].join('\n') + '\n')

  parser.flush()
  assert.deepEqual(thinkings, ['思考中'])
  assert.deepEqual(texts, ['回复'])
  for (const call of allCalls) {
    assert.ok(
      !String(call.value).includes('sig_abc123xyz'),
      'signature must not appear in any callback value',
    )
  }
})

test('partial line buffering works across feed chunks', () => {
  const texts = []
  const parser = createStreamParser({ onText: t => texts.push(t) })

  const line1 = blockStart('text')
  const line2 = blockDelta({ type: 'text_delta', text: 'hello' })
  const line3 = blockStop()
  const full = line1 + '\n' + line2 + '\n' + line3 + '\n'

  const mid = Math.floor(full.length / 2)
  parser.feed(full.slice(0, mid))
  parser.feed(full.slice(mid))
  parser.flush()

  assert.deepEqual(texts, ['hello'])
  assert.equal(parser.getText(), 'hello')
})

test('callbacks are optional: no crash if omitted', () => {
  const parser = createStreamParser({})

  parser.feed([
    blockStart('thinking'),
    blockDelta({ type: 'thinking_delta', thinking: '思考' }),
    blockStop(),
    blockStart('text'),
    blockDelta({ type: 'text_delta', text: '回复' }),
    blockStop(),
  ].join('\n') + '\n')

  parser.flush()
  assert.equal(parser.getText(), '回复')
})

test('getText returns only text blocks, not thinking', () => {
  const parser = createStreamParser({})

  parser.feed([
    blockStart('thinking'),
    blockDelta({ type: 'thinking_delta', thinking: '这是内部思考' }),
    blockStop(),
    blockStart('text'),
    blockDelta({ type: 'text_delta', text: '这是正文' }),
    blockStop(),
  ].join('\n') + '\n')

  parser.flush()
  assert.equal(parser.getText(), '这是正文')
  assert.ok(!parser.getText().includes('内部思考'))
})

test('empty stream: getText returns empty string', () => {
  const parser = createStreamParser({})
  parser.flush()
  assert.equal(parser.getText(), '')
})

test('multiple text blocks: all concatenated', () => {
  const texts = []
  const parser = createStreamParser({ onText: t => texts.push(t) })

  parser.feed([
    blockStart('text'),
    blockDelta({ type: 'text_delta', text: 'first' }),
    blockStop(),
    blockStart('text'),
    blockDelta({ type: 'text_delta', text: 'second' }),
    blockStop(),
  ].join('\n') + '\n')

  parser.flush()
  assert.deepEqual(texts, ['first', 'second'])
  assert.equal(parser.getText(), 'firstsecond')
})

test('onThinking callback error does not break text parsing', () => {
  const texts = []
  const parser = createStreamParser({
    onThinking: () => { throw new Error('callback boom') },
    onText: t => texts.push(t),
  })

  parser.feed([
    blockStart('thinking'),
    blockDelta({ type: 'thinking_delta', thinking: 'boom' }),
    blockStop(),
    blockStart('text'),
    blockDelta({ type: 'text_delta', text: '正常回复' }),
    blockStop(),
  ].join('\n') + '\n')

  parser.flush()
  assert.deepEqual(texts, ['正常回复'])
  assert.equal(parser.getText(), '正常回复')
})
