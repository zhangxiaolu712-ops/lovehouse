import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router'
import LineIcon from '../../shared/LineIcon'
import {
  STATUS_META,
  addProjectChecklistItem,
  deleteProjectChecklistItem,
  loadProjectChecklist,
  saveProjectChecklistItem,
} from './projectChecklistService'
import './projectChecklist.css'

const FILTERS = ['all', 'todo', 'partial', 'done', 'risk', 'idea']

function today() {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

export default function ProjectChecklistPage() {
  const [sections, setSections] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState('all')
  const [collapsed, setCollapsed] = useState({})
  const [editing, setEditing] = useState(null)
  const [addingTo, setAddingTo] = useState(null)
  const [newText, setNewText] = useState('')

  const allItems = useMemo(() => sections.flatMap(section => section.items), [sections])
  const counts = useMemo(() => allItems.reduce((acc, entry) => {
    acc.total += 1
    acc[entry.status] = (acc[entry.status] || 0) + 1
    return acc
  }, { total: 0, done: 0, partial: 0, todo: 0, idea: 0, risk: 0 }), [allItems])

  const progress = counts.total ? Math.round((counts.done / counts.total) * 100) : 0

  async function refresh() {
    try { setSections(await loadProjectChecklist()); setError('') }
    catch (cause) { setError(cause.message || '施工清单读取失败') }
    finally { setLoading(false) }
  }

  useEffect(() => { refresh() }, [])

  async function patchItem(entry, patch) {
    const next = { ...entry, ...patch }
    if (patch.status === 'done' && !next.completedAt) next.completedAt = today()
    if (patch.status && patch.status !== 'done' && entry.status === 'done') next.completedAt = ''
    try { await saveProjectChecklistItem(next); await refresh() } catch (cause) { setError(cause.message || '保存失败') }
  }

  async function handleAdd(sectionIndex) {
    const text = newText.trim()
    if (!text) return
    try { await addProjectChecklistItem(sectionIndex, text) } catch (cause) { setError(cause.message || '新增失败'); return }
    setNewText('')
    setAddingTo(null)
    refresh()
  }

  const normalizedQuery = query.trim().toLowerCase()

  return (
    <div className="project-checklist-page">
      <header className="project-checklist-head">
        <div className="project-checklist-title-row">
          <Link to="/" className="page-back" aria-label="返回首页"><LineIcon name="back" size={20} /></Link>
          <div>
            <p className="project-checklist-eyebrow">B612 BUILD MAP · V1</p>
            <h1>功能施工清单</h1>
          </div>
        </div>
        <p className="project-checklist-subtitle">需求继续长也没关系。这里保留当前基线，纸笔验收和工程状态分开记录。</p>

        <div className="project-checklist-progress-card">
          <div className="project-checklist-progress-top">
            <span>整体完成度</span>
            <strong>{progress}%</strong>
          </div>
          <div className="project-checklist-progress-track" aria-label={`整体完成度 ${progress}%`}>
            <span style={{ width: `${progress}%` }} />
          </div>
          <div className="project-checklist-summary">
            <span><b>{counts.done}</b> 已完成</span>
            <span><b>{counts.partial}</b> 进行中</span>
            <span><b>{counts.todo}</b> 待施工</span>
            <span><b>{counts.risk}</b> 风险</span>
            <span><b>{counts.idea}</b> 灵感</span>
          </div>
        </div>
      </header>

      <div className="project-checklist-toolbar">
        <label className="project-checklist-search">
          <LineIcon name="search" size={17} />
          <input value={query} onChange={event => setQuery(event.target.value)} placeholder="搜模块、功能、关键词…" />
        </label>
        <div className="project-checklist-filters" aria-label="按状态筛选">
          {FILTERS.map(key => (
            <button key={key} type="button" className={filter === key ? 'on' : ''} onClick={() => setFilter(key)}>
              {key === 'all' ? `全部 ${counts.total}` : `${STATUS_META[key].mark} ${STATUS_META[key].label} ${counts[key]}`}
            </button>
          ))}
        </div>
      </div>

      <div className="project-checklist-sections">
        {loading && <div className="empty">正在从工程服务读取清单…</div>}
        {error && <div className="empty">{error}</div>}
        {sections.map(section => {
          const visibleItems = section.items.filter(entry => {
            const statusMatch = filter === 'all' || entry.status === filter
            const queryMatch = !normalizedQuery || `${section.title} ${entry.text} ${entry.note || ''}`.toLowerCase().includes(normalizedQuery)
            return statusMatch && queryMatch
          })
          if (!visibleItems.length && (filter !== 'all' || normalizedQuery)) return null

          const sectionDone = section.items.filter(entry => entry.status === 'done').length
          const isCollapsed = collapsed[section.sectionIndex]

          return (
            <section className="project-checklist-section" key={section.title}>
              <button
                type="button"
                className="project-checklist-section-head"
                onClick={() => setCollapsed(current => ({ ...current, [section.sectionIndex]: !isCollapsed }))}
              >
                <span className="project-checklist-chevron">{isCollapsed ? '›' : '⌄'}</span>
                <span className="project-checklist-section-title">{section.title}</span>
                <span className="project-checklist-section-count">{sectionDone}/{section.items.length}</span>
              </button>

              {!isCollapsed && (
                <div className="project-checklist-list">
                  {visibleItems.map(entry => {
                    const meta = STATUS_META[entry.status]
                    const isEditing = editing === entry.id
                    return (
                      <article className={`project-checklist-item status-${entry.status}`} key={entry.id}>
                        <button
                          type="button"
                          className="project-checklist-status"
                          onClick={() => patchItem(entry, { status: entry.status === 'done' ? 'todo' : 'done' })}
                          aria-label={entry.status === 'done' ? '标记为待施工' : '标记为已完成'}
                          title="快速切换 已完成 / 待施工"
                        >
                          {meta.mark}
                        </button>

                        <div className="project-checklist-item-main">
                          <div className="project-checklist-item-line">
                            <span className="project-checklist-item-text">{entry.text}</span>
                            <button type="button" className="project-checklist-detail-btn" onClick={() => setEditing(isEditing ? null : entry.id)}>
                              {entry.note || entry.completedAt ? '详情' : '备注'}
                            </button>
                          </div>

                          <div className="project-checklist-item-meta">
                            <select value={entry.status} onChange={event => patchItem(entry, { status: event.target.value })} aria-label="施工状态">
                              {Object.entries(STATUS_META).map(([key, value]) => <option key={key} value={key}>{value.mark} {value.label}</option>)}
                            </select>
                            {entry.completedAt && <span className="project-checklist-date-inline">完成 {entry.completedAt}</span>}
                            {entry.note && <span className="project-checklist-note-inline">{entry.note}</span>}
                          </div>

                          {isEditing && (
                            <div className="project-checklist-editor">
                              <label>
                                <span>完成日期</span>
                                <input type="date" value={entry.completedAt || ''} onChange={event => patchItem(entry, { completedAt: event.target.value })} />
                              </label>
                              <label className="wide">
                                <span>备注</span>
                                <textarea
                                  defaultValue={entry.note || ''}
                                  placeholder="验收结果、卡点、下一步……"
                                  onBlur={event => patchItem(entry, { note: event.target.value.trim() })}
                                />
                              </label>
                              {entry.custom && (
                                <button type="button" className="project-checklist-delete" onClick={async () => {
                                  await deleteProjectChecklistItem(entry.id)
                                  setEditing(null)
                                  await refresh()
                                }}>删除这条自定义需求</button>
                              )}
                            </div>
                          )}
                        </div>
                      </article>
                    )
                  })}

                  {addingTo === section.sectionIndex ? (
                    <div className="project-checklist-add-row">
                      <input
                        autoFocus
                        value={newText}
                        onChange={event => setNewText(event.target.value)}
                        onKeyDown={event => {
                          if (event.key === 'Enter') handleAdd(section.sectionIndex)
                          if (event.key === 'Escape') { setAddingTo(null); setNewText('') }
                        }}
                        placeholder="新增一个需求…"
                      />
                      <button type="button" onClick={() => handleAdd(section.sectionIndex)}>加入</button>
                      <button type="button" className="ghost" onClick={() => { setAddingTo(null); setNewText('') }}>取消</button>
                    </div>
                  ) : (
                    <button type="button" className="project-checklist-add" onClick={() => setAddingTo(section.sectionIndex)}>
                      <LineIcon name="plus" size={15} /> 新增需求
                    </button>
                  )}
                </div>
              )}
            </section>
          )
        })}
      </div>

      <footer className="project-checklist-rule">
        <strong>总规则：加家具，不拆墙。</strong>
        <span>B612 是网站，LoveHouse 是 App。新功能优先作为模块接入现有 Persona / Thread / Archive / Memory 骨架，不为单个平台重造一套。</span>
      </footer>
    </div>
  )
}
