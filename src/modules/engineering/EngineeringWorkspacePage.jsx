import { useCallback, useEffect, useMemo, useState } from 'react'

import { ENGINEERING_CATEGORIES, groupEngineeringItems } from './engineeringCategories'
import {
  archiveEngineeringFact,
  expandEngineeringSource,
  listEngineeringFacts,
  openEngineeringFact,
  restoreEngineeringFact,
  saveEngineeringFact,
} from './engineeringService'
import './engineeringWorkspace.css'

const EMPTY_FORM = { subjectKey: '', content: '', category: '', component: '', reason: '', sourceKind: '', locator: '', quoteText: '' }

function latestRevision(fact) {
  return fact?.revisions?.find(revision => revision.id === fact.entry.current_revision_id)
    || fact?.revisions?.at(-1)
}

function formatTime(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

export default function EngineeringWorkspacePage() {
  const [items, setItems] = useState([])
  const [query, setQuery] = useState('')
  const [includeArchived, setIncludeArchived] = useState(false)
  const [selectedKey, setSelectedKey] = useState(null)
  const [fact, setFact] = useState(null)
  const [form, setForm] = useState(EMPTY_FORM)
  const [evidence, setEvidence] = useState({})
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  const loadList = useCallback(async () => {
    setLoading(true)
    setError('')
    try { setItems(await listEngineeringFacts({ query, includeArchived })) }
    catch (cause) { setError(cause.message) }
    finally { setLoading(false) }
  }, [query, includeArchived])

  useEffect(() => { loadList() }, [loadList])

  async function selectFact(subjectKey) {
    setSelectedKey(subjectKey)
    setError('')
    setEvidence({})
    try {
      const opened = await openEngineeringFact(subjectKey)
      setFact(opened)
      const current = latestRevision(opened)
      setForm({
        ...EMPTY_FORM,
        subjectKey,
        content: current?.content || '',
        category: current?.metadata?.category || '',
        component: current?.metadata?.component || '',
      })
    } catch (cause) { setError(cause.message) }
  }

  function newFact() {
    setSelectedKey(null)
    setFact(null)
    setEvidence({})
    setNotice('')
    setError('')
    setForm(EMPTY_FORM)
  }

  async function submit(event) {
    event.preventDefault()
    setSaving(true)
    setError('')
    setNotice('')
    try {
      const metadata = { ...(latestRevision(fact)?.metadata || {}) }
      if (form.category.trim()) metadata.category = form.category.trim()
      else delete metadata.category
      if (form.component.trim()) metadata.component = form.component.trim()
      else delete metadata.component
      const source = form.sourceKind.trim() ? [{
        sourceKind: form.sourceKind.trim(),
        locator: form.locator.trim() ? { reference: form.locator.trim() } : {},
        quoteText: form.quoteText.trim() || null,
      }] : undefined
      const payload = {
        subject_key: form.subjectKey.trim(),
        content: form.content.trim(),
        metadata,
        ...(form.reason.trim() ? { reason: form.reason.trim() } : {}),
        ...(source ? { sources: source } : {}),
      }
      const result = await saveEngineeringFact(payload)
      setNotice(result.action === 'noop' ? '内容没有变化，已保留当前版本。' : '工程记录已保存。')
      await loadList()
      await selectFact(payload.subject_key)
    } catch (cause) { setError(cause.message) }
    finally { setSaving(false) }
  }

  async function toggleArchive() {
    if (!fact) return
    const archived = fact.entry.status === 'archived'
    if (!archived && !window.confirm(`归档 ${fact.entry.subject_key}？之后仍可恢复。`)) return
    setSaving(true)
    setError('')
    try {
      if (archived) await restoreEngineeringFact(fact.entry.subject_key)
      else await archiveEngineeringFact(fact.entry.subject_key)
      setNotice(archived ? '已恢复。' : '已归档，可在“显示归档”中恢复。')
      await loadList()
      if (archived || includeArchived) await selectFact(fact.entry.subject_key)
      else newFact()
    } catch (cause) { setError(cause.message) }
    finally { setSaving(false) }
  }

  async function showEvidence(sourceId) {
    if (evidence[sourceId]) return
    try {
      const source = await expandEngineeringSource(sourceId)
      setEvidence(current => ({ ...current, [sourceId]: source }))
    } catch (cause) { setError(cause.message) }
  }

  const groups = useMemo(() => groupEngineeringItems(items), [items])
  const componentOptions = ENGINEERING_CATEGORIES.find(item => item.key === form.category)?.components || []

  return (
    <div className="engineering-workspace">
      <header className="engineering-header">
        <div><p className="engineering-kicker">OWNER WORKSPACE</p><h1>工程区</h1><p>按分类、组件和稳定 subject_key 管理工程事实。</p></div>
        <button type="button" className="engineering-primary" onClick={newFact}>新增记录</button>
      </header>

      {error && <div className="engineering-message error" role="alert">{error}</div>}
      {notice && <div className="engineering-message success" role="status">{notice}</div>}

      <div className="engineering-layout">
        <aside className="engineering-index">
          <label className="engineering-search">搜索<input value={query} onChange={event => setQuery(event.target.value)} placeholder="subject 或正文" /></label>
          <label className="engineering-check"><input type="checkbox" checked={includeArchived} onChange={event => setIncludeArchived(event.target.checked)} />显示归档</label>
          {loading ? <p className="engineering-muted">加载中…</p> : groups.length === 0 ? <p className="engineering-muted">暂无工程记录。</p> : groups.map(group => (
            <section key={group.key} className="engineering-group">
              <h2>{group.label}<span>{group.key}</span></h2>
              {group.components.map(component => <div key={component.key}>
                <h3>{component.key}</h3>
                {component.items.map(item => <button type="button" key={item.subject_key} className={selectedKey === item.subject_key ? 'selected' : ''} onClick={() => selectFact(item.subject_key)}>
                  <strong>{item.subject_key}</strong><small>r{item.revision_number} · {item.status === 'archived' ? '已归档' : item.last_modified_actor}</small>
                </button>)}
              </div>)}
            </section>
          ))}
        </aside>

        <main className="engineering-editor">
          <form onSubmit={submit}>
            <div className="engineering-form-head"><h2>{fact ? '修订工程记录' : '新增工程记录'}</h2>{fact && <button type="button" className="engineering-danger" disabled={saving} onClick={toggleArchive}>{fact.entry.status === 'archived' ? '恢复' : '归档'}</button>}</div>
            <label>Subject key *<input required maxLength="200" disabled={Boolean(fact)} value={form.subjectKey} onChange={event => setForm({ ...form, subjectKey: event.target.value })} placeholder="例如 runtime.codex.session" /></label>
            <div className="engineering-fields">
              <label>Category<input list="engineering-categories" value={form.category} onChange={event => setForm({ ...form, category: event.target.value })} placeholder="可稍后补充" /></label>
              <datalist id="engineering-categories">{ENGINEERING_CATEGORIES.map(item => <option key={item.key} value={item.key}>{item.label}</option>)}</datalist>
              <label>Component<input list="engineering-components" value={form.component} onChange={event => setForm({ ...form, component: event.target.value })} placeholder="可稍后补充" /></label>
              <datalist id="engineering-components">{componentOptions.map(value => <option key={value} value={value} />)}</datalist>
            </div>
            <p className="engineering-hint">分类建议尽量细分，但 category / component 留空或使用新值都可先保存。</p>
            <label>内容 *<textarea required maxLength="50000" rows="10" value={form.content} onChange={event => setForm({ ...form, content: event.target.value })} /></label>
            <label>修订原因<input maxLength="1000" value={form.reason} onChange={event => setForm({ ...form, reason: event.target.value })} placeholder="可选" /></label>
            <details className="engineering-source-form"><summary>添加 source evidence（可选）</summary>
              <div className="engineering-fields"><label>来源类型<input value={form.sourceKind} onChange={event => setForm({ ...form, sourceKind: event.target.value })} placeholder="commit / pr / document" /></label><label>定位信息<input value={form.locator} onChange={event => setForm({ ...form, locator: event.target.value })} placeholder="URL、commit SHA 或路径" /></label></div>
              <label>证据原文<textarea rows="4" value={form.quoteText} onChange={event => setForm({ ...form, quoteText: event.target.value })} /></label>
            </details>
            <button className="engineering-primary" disabled={saving}>{saving ? '保存中…' : fact ? '保存为新 revision' : '保存记录'}</button>
          </form>

          {fact && <section className="engineering-history"><h2>Revision history</h2>{[...(fact.revisions || [])].reverse().map(revision => <article key={revision.id}>
            <header><strong>Revision {revision.revision_number}</strong><span>{revision.created_by_actor} · {formatTime(revision.created_at)}</span></header>
            {revision.reason && <p className="engineering-reason">{revision.reason}</p>}<pre>{revision.content}</pre>
            {revision.sources?.length > 0 && <div className="engineering-sources"><h3>Source evidence</h3>{revision.sources.map(source => <div key={source.source_id} className="engineering-source">
              <button type="button" onClick={() => showEvidence(source.source_id)}>{source.source_kind} · 查看证据</button>
              <code>{JSON.stringify(source.locator)}</code>
              {evidence[source.source_id] && <blockquote>{evidence[source.source_id].available ? evidence[source.source_id].quote_text : '该来源没有可展开的原文。'}</blockquote>}
            </div>)}</div>}
          </article>)}</section>}
        </main>
      </div>
    </div>
  )
}
