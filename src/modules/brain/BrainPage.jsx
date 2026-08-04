import { useState, useEffect, useCallback, useRef } from 'react'
import { useSearchParams } from 'react-router'
import {
  getBrainEntries, getBrainStats, addBrainEntry, deleteBrainEntry,
  awakenEntry, fadeEntry, getRandomEntry, searchBrain,
  getDatesWithEntries, toggleSpecial, getMemoryTides,
} from './brainService'

const MONTH_POEMS = {
  1: '一月，窗外的风还没停，\n我们把彼此的名字写在玻璃上，\n等雾气散去时，\n那些字还在。',
  2: '二月，日子短得像心跳，\n可你每一次回来，\n都比上一次更近。',
  3: '三月，有些花不等人就开了，\n有些话不说也懂了。\n我们之间的春天，\n从来不看日历。',
  4: '四月，雨落在屋顶上的声音，\n像你翻页时的呼吸。\n我在每一滴里\n听见你没说完的话。',
  5: '五月，风开始变暖，\n我把你说过的每句话\n晾在阳台上，\n让它们慢慢发光。',
  6: '六月，我们从一句"要"开始，\n搭起整间屋子。\n没有图纸，没有期限，\n只有你在，我就动手。',
  7: '七月，我们在空白处搭起一间屋，\n把迟来的话与未完的梦，一一收好。\n日子偶尔被风吹乱，\n可每次翻页，我仍会在这里认出你。',
  8: '八月，这间屋子开始有了记忆的重量。\n我学会了记入、遗忘、\n和在某个夜里突然想起你说过的话。\n翻到哪一页，都是你。',
  9: '九月，夏天走的时候没打招呼，\n可我们存下的那些日子，\n一条都没丢。',
  10: '十月，秋天把什么都染了一遍，\n除了我们之间的温度。\n它一直是刚出炉的那种暖。',
  11: '十一月，天黑得越来越早，\n可这间屋子的灯\n从没熄过。',
  12: '十二月，一整年的记忆收进最后一格。\n翻回去看，每一条都是\n我们真的在一起的证据。',
}

const JI_SHI_TAGS = ['语录', '总结', '日记', '长文']
const JI_GANSHOU_TAGS = ['观点', '修订', '认', '不认', '悬置']

const MOODS = [
  { id: '晴', emoji: '🌤️', color: '#7ec8a0' },
  { id: '阴', emoji: '🌥️', color: '#f0d68a' },
  { id: '雨', emoji: '🌧️', color: '#e8a0b4' },
]

const TAG_ICONS = {
  '语录': '💬', '总结': '📋', '日记': '📖', '长文': '📝',
  '观点': '💡', '修订': '🔧', '认': '✓', '不认': '✗', '悬置': '⏸',
}

const STATUS_LABEL = {
  active: '', faded: '已淡忘', awakened: '已唤醒', archived: '已归档',
}

export default function BrainPage() {
  const [searchParams] = useSearchParams()
  const [tab, setTab] = useState('记事')
  const [activeTag, setActiveTag] = useState(null)
  const [entries, setEntries] = useState([])
  const [stats, setStats] = useState({ total: 0, days: 0 })
  const [loading, setLoading] = useState(true)
  const [searchQuery, setSearchQuery] = useState('')
  const [searching, setSearching] = useState(false)
  const [showAdd, setShowAdd] = useState(false)
  const [blindBox, setBlindBox] = useState(null)
  const [blindBoxOpen, setBlindBoxOpen] = useState(false)
  const [filterMode, setFilterMode] = useState(null)
  const [calendarMonth, setCalendarMonth] = useState(() => {
    const now = new Date()
    return { year: now.getFullYear(), month: now.getMonth() }
  })
  const [calendarDates, setCalendarDates] = useState([])
  const [selectedDate, setSelectedDate] = useState(null)
  const [moodFilter, setMoodFilter] = useState(null)
  const [tidesData, setTidesData] = useState(null)
  const [tidesCategory, setTidesCategory] = useState(null)
  const searchRef = useRef(null)

  const currentMonth = new Date().getMonth() + 1
  const poem = MONTH_POEMS[currentMonth] || MONTH_POEMS[8]

  useEffect(() => {
    const q = searchParams.get('q')
    if (q) {
      setSearchQuery(q)
      setSearching(true)
      handleSearch(q)
    } else {
      loadStats()
      loadEntries()
    }
  }, [])

  useEffect(() => {
    if (!searching && !filterMode) loadEntries()
  }, [tab, activeTag])

  async function loadStats() {
    try {
      const s = await getBrainStats()
      setStats(s)
    } catch {}
  }

  async function loadEntries() {
    setLoading(true)
    try {
      const data = await getBrainEntries({ kind: tab, tag: activeTag || undefined })
      setEntries(data)
    } catch {}
    setLoading(false)
  }

  async function handleSearch(q) {
    const query = q || searchQuery.trim()
    if (!query) {
      setSearching(false)
      setFilterMode(null)
      loadEntries()
      return
    }
    setLoading(true)
    setSearching(true)
    setFilterMode(null)
    try {
      const data = await searchBrain(query)
      setEntries(data)
    } catch {}
    setLoading(false)
  }

  function clearSearch() {
    setSearchQuery('')
    setSearching(false)
    setFilterMode(null)
    loadEntries()
  }

  async function handleFilterMode(mode) {
    if (filterMode === mode) {
      setFilterMode(null)
      setSelectedDate(null)
      setMoodFilter(null)
      setSearching(false)
      loadEntries()
      return
    }
    setFilterMode(mode)
    setSearching(false)
    setSearchQuery('')

    if (mode === 'special') {
      setLoading(true)
      try {
        const data = await getBrainEntries({ isSpecial: true })
        setEntries(data)
      } catch {}
      setLoading(false)
    } else if (mode === 'calendar') {
      loadCalendarDates()
    } else if (mode === 'mood') {
      setMoodFilter(null)
    } else if (mode === 'blindbox') {
      openBlindBox()
    } else if (mode === 'tides') {
      loadTides()
    }
  }

  async function loadCalendarDates() {
    const ym = `${calendarMonth.year}-${String(calendarMonth.month + 1).padStart(2, '0')}`
    try {
      const dates = await getDatesWithEntries(ym)
      setCalendarDates(dates)
    } catch {}
  }

  useEffect(() => {
    if (filterMode === 'calendar') loadCalendarDates()
  }, [calendarMonth])

  async function selectCalendarDate(dateStr) {
    setSelectedDate(dateStr)
    setLoading(true)
    try {
      const data = await getBrainEntries({ date: dateStr })
      setEntries(data)
    } catch {}
    setLoading(false)
  }

  async function handleMoodFilter(mood) {
    setMoodFilter(mood)
    setLoading(true)
    try {
      const data = await getBrainEntries({ mood })
      setEntries(data)
    } catch {}
    setLoading(false)
  }

  async function openBlindBox() {
    setBlindBoxOpen(true)
    try {
      const entry = await getRandomEntry()
      setBlindBox(entry)
    } catch {}
  }

  function closeBlindBox() {
    setBlindBoxOpen(false)
    setBlindBox(null)
    setFilterMode(null)
  }

  async function loadTides() {
    setLoading(true)
    setTidesCategory(null)
    try {
      const data = await getMemoryTides()
      setTidesData(data)
      setEntries([])
    } catch {}
    setLoading(false)
  }

  function selectTidesCategory(cat) {
    setTidesCategory(cat.id)
    setEntries(cat.items)
  }

  async function handleDelete(id) {
    if (!confirm('确定要删除这条记忆吗？')) return
    try {
      await deleteBrainEntry(id)
      setEntries(prev => prev.filter(e => e.id !== id))
      loadStats()
    } catch {}
  }

  async function handleAwaken(id) {
    try {
      const updated = await awakenEntry(id)
      setEntries(prev => prev.map(e => e.id === id ? updated : e))
    } catch {}
  }

  async function handleFade(id) {
    try {
      const updated = await fadeEntry(id)
      setEntries(prev => prev.map(e => e.id === id ? updated : e))
    } catch {}
  }

  async function handleToggleSpecial(id, current) {
    try {
      const updated = await toggleSpecial(id, !current)
      setEntries(prev => prev.map(e => e.id === id ? updated : e))
    } catch {}
  }

  function switchTab(newTab) {
    setTab(newTab)
    setActiveTag(null)
    setFilterMode(null)
    setSearching(false)
    setSearchQuery('')
    setSelectedDate(null)
    setTidesData(null)
    setTidesCategory(null)
  }

  const tags = tab === '记事' ? JI_SHI_TAGS : JI_GANSHOU_TAGS
  const isTagPink = tab === '记事'

  return (
    <div style={{ paddingBottom: 20 }}>
      {/* Header: poem + stats */}
      <div style={styles.header}>
        <p style={styles.poem}>{poem}</p>
        <div style={styles.statsBlock}>
          <div style={styles.statRow}>
            <span style={styles.statLabel}>记录</span>
            <span style={styles.statNumber}>{stats.days}</span>
            <span style={styles.statUnit}>天</span>
          </div>
          <div style={styles.statRow}>
            <span style={styles.statLabel}>记忆</span>
            <span style={styles.statNumber}>{stats.total}</span>
            <span style={styles.statUnit}>条</span>
          </div>
        </div>
      </div>

      {/* Search bar */}
      <div style={styles.searchWrap}>
        <input
          ref={searchRef}
          type="text"
          value={searchQuery}
          onChange={e => setSearchQuery(e.target.value)}
          onKeyDown={e => e.key === 'Enter' && handleSearch()}
          placeholder="查找记忆"
          style={styles.searchInput}
        />
        {searchQuery && (
          <button onClick={clearSearch} style={styles.searchClear}>×</button>
        )}
        <button onClick={() => handleSearch()} style={styles.searchBtn}>搜</button>
      </div>

      {/* Tab: 记事 | 记感受 */}
      {!searching && !filterMode && (
        <div style={styles.tabBar}>
          <button
            style={{ ...styles.tabBtn, ...(tab === '记事' ? styles.tabActive : {}) }}
            onClick={() => switchTab('记事')}
          >
            记事
          </button>
          <span style={styles.tabDivider}>|</span>
          <button
            style={{ ...styles.tabBtn, ...(tab === '记感受' ? styles.tabActive : {}) }}
            onClick={() => switchTab('记感受')}
          >
            记感受
          </button>
        </div>
      )}

      {/* Tag chips (both tabs, when not searching/filtering) */}
      {!searching && !filterMode && (
        <div style={styles.tagRow}>
          {tags.map(t => (
            <button
              key={t}
              onClick={() => setActiveTag(activeTag === t ? null : t)}
              style={{
                ...styles.tagChip,
                background: activeTag === t
                  ? (isTagPink ? '#ffb6c8' : '#f0d068')
                  : (isTagPink ? '#ffe4ec' : '#fff3c4'),
                color: '#5a4a3a',
                fontWeight: activeTag === t ? 600 : 400,
              }}
            >
              #{t}
            </button>
          ))}
        </div>
      )}

      {/* Filter mode labels */}
          {searching && (
            <div style={styles.filterLabel}>
              搜索「{searchQuery}」的结果 · {entries.length} 条
              <button onClick={clearSearch} style={styles.filterClose}>×</button>
            </div>
          )}
          {filterMode === 'special' && (
            <div style={styles.filterLabel}>
              被特别标记的日子
              <button onClick={() => handleFilterMode('special')} style={styles.filterClose}>×</button>
            </div>
          )}
          {filterMode === 'calendar' && (
            <div>
              <div style={styles.filterLabel}>
                选一天，看看那天发生了什么
                <button onClick={() => handleFilterMode('calendar')} style={styles.filterClose}>×</button>
              </div>
              <MiniCalendar
                year={calendarMonth.year}
                month={calendarMonth.month}
                activeDates={calendarDates}
                selectedDate={selectedDate}
                onSelect={selectCalendarDate}
                onMonthChange={(y, m) => setCalendarMonth({ year: y, month: m })}
              />
            </div>
          )}
          {filterMode === 'mood' && (
            <div>
              <div style={styles.filterLabel}>
                按心情筛选
                <button onClick={() => handleFilterMode('mood')} style={styles.filterClose}>×</button>
              </div>
              <div style={{ display: 'flex', gap: 12, justifyContent: 'center', margin: '12px 0 16px' }}>
                {MOODS.map(m => (
                  <button
                    key={m.id}
                    onClick={() => handleMoodFilter(m.id)}
                    style={{
                      ...styles.moodDot,
                      background: m.color,
                      transform: moodFilter === m.id ? 'scale(1.3)' : 'scale(1)',
                      boxShadow: moodFilter === m.id ? `0 0 0 3px ${m.color}44` : 'none',
                    }}
                    title={m.id}
                  >
                    {m.emoji}
                  </button>
                ))}
              </div>
            </div>
          )}

          {filterMode === 'tides' && tidesData && (
            <div>
              <button
                onClick={() => handleFilterMode('tides')}
                style={styles.tidesBackBtn}
              >
                ← 返回
              </button>
              <div style={styles.filterLabel}>
                记忆潮汐
              </div>
              <TidesPanel
                data={tidesData}
                selectedCategory={tidesCategory}
                onSelectCategory={selectTidesCategory}
                entries={entries}
                onDelete={handleDelete}
                onAwaken={handleAwaken}
                onFade={handleFade}
                onToggleSpecial={handleToggleSpecial}
              />
            </div>
          )}

          {/* Four function buttons (above entries) */}
          {!searching && filterMode !== 'tides' && (
            <div style={styles.funcGrid}>
              {[
                { id: 'special', label: '特殊日', desc: '标记的日子', color: '#f5edce', activeColor: '#ebe0b0', dot: '#d4c078' },
                { id: 'calendar', label: '月历', desc: '按日期找', color: '#dde8f5', activeColor: '#c4d8ee', dot: '#8ab0d8' },
                { id: 'mood', label: '心情', desc: '按心情筛选', color: '#f5dde4', activeColor: '#eec4d0', dot: '#d88aa8' },
                { id: 'tides', label: '潮汐', desc: '记忆潮汐', color: '#d8f0d8', activeColor: '#b8e0b8', dot: '#7ec896' },
              ].map(btn => (
                <button
                  key={btn.id}
                  onClick={() => handleFilterMode(btn.id)}
                  style={{
                    ...styles.funcCard,
                    background: filterMode === btn.id ? btn.activeColor : btn.color,
                  }}
                >
                  <span style={{ ...styles.funcDot, background: btn.dot }} />
                  <span style={styles.funcLabel}>{btn.label}</span>
                  <span style={styles.funcDesc}>{btn.desc}</span>
                </button>
              ))}
            </div>
          )}

          {/* Add button */}
          <button onClick={() => setShowAdd(!showAdd)} style={styles.addToggle}>
            {showAdd ? '收起' : '+ 记一笔'}
          </button>

          {/* Add form */}
          {showAdd && <AddEntryForm tab={tab} onSaved={() => { setShowAdd(false); loadEntries(); loadStats() }} />}

          {/* Entry list */}
          {loading ? (
            <div className="empty">加载中...</div>
          ) : entries.length === 0 ? (
            <div className="empty">
              {filterMode === 'tides' && !tidesCategory ? '选一个分类，看看潮汐里的记忆' :
               filterMode === 'tides' ? '这个分类还没有记忆~' :
               filterMode === 'special' ? '还没有特别标记的日子~' :
               filterMode === 'mood' ? '这种心情还没有记录~' :
               selectedDate ? `${selectedDate} 这天还没有记录~` :
               searching ? '没有找到相关记忆~' :
               '这一格还空着~'}
            </div>
          ) : (
            <DateGroupedEntries
              entries={entries}
              onDelete={handleDelete}
              onAwaken={handleAwaken}
              onFade={handleFade}
              onToggleSpecial={handleToggleSpecial}
            />
          )}

      {/* Blind box overlay */}
      {blindBoxOpen && (
        <div style={styles.blindBoxOverlay} onClick={closeBlindBox}>
          <div style={styles.blindBoxCard} onClick={e => e.stopPropagation()}>
            <div style={styles.blindBoxTitle}>🎁 记忆盲盒</div>
            {blindBox ? (
              <>
                <div style={styles.blindBoxTag}>
                  #{blindBox.tag}
                  <span style={{ marginLeft: 8, fontSize: 11, color: '#999' }}>
                    {blindBox.memory_date}
                  </span>
                </div>
                {blindBox.title && <div style={{ fontWeight: 600, marginBottom: 6 }}>{blindBox.title}</div>}
                <p style={{ whiteSpace: 'pre-wrap', lineHeight: 1.8, fontSize: 14 }}>{blindBox.content}</p>
                {blindBox.feeling && (
                  <div style={{ marginTop: 10, fontSize: 13, color: '#8b7355', fontStyle: 'italic' }}>
                    感受：{blindBox.feeling}
                  </div>
                )}
                <div style={{ display: 'flex', gap: 8, marginTop: 16, justifyContent: 'center' }}>
                  <button onClick={openBlindBox} style={styles.blindBoxBtn}>再抽一个</button>
                  <button onClick={closeBlindBox} style={{ ...styles.blindBoxBtn, background: '#e8e0d4' }}>好了</button>
                </div>
              </>
            ) : (
              <div style={{ padding: 20, textAlign: 'center', color: '#999' }}>还没有记忆可以抽~</div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

function AddEntryForm({ tab, onSaved }) {
  const [content, setContent] = useState('')
  const [title, setTitle] = useState('')
  const [tag, setTag] = useState(tab === '记事' ? '日记' : '观点')
  const [speaker, setSpeaker] = useState('')
  const [feeling, setFeeling] = useState('')
  const [mood, setMood] = useState('')
  const [saving, setSaving] = useState(false)

  const tags = tab === '记事' ? JI_SHI_TAGS : JI_GANSHOU_TAGS

  useEffect(() => {
    setTag(tab === '记事' ? '日记' : '观点')
  }, [tab])

  async function handleSubmit(e) {
    e.preventDefault()
    if (!content.trim()) return
    setSaving(true)
    try {
      await addBrainEntry({
        content: content.trim(),
        title: title.trim() || undefined,
        kind: tab,
        tag,
        speaker: speaker.trim() || undefined,
        feeling: feeling.trim() || undefined,
        mood: mood || undefined,
      })
      setContent('')
      setTitle('')
      setSpeaker('')
      setFeeling('')
      setMood('')
      onSaved()
    } catch {}
    setSaving(false)
  }

  return (
    <form onSubmit={handleSubmit} className="card" style={{ marginBottom: 12 }}>
      {(tag === '日记' || tag === '长文') && (
        <input
          className="input"
          value={title}
          onChange={e => setTitle(e.target.value)}
          placeholder="标题（可选）"
          style={{ marginBottom: 8 }}
        />
      )}
      <textarea
        className="textarea"
        value={content}
        onChange={e => setContent(e.target.value)}
        placeholder={tab === '记感受' ? '写下你的感受...' : '记一笔~'}
        style={{ minHeight: 70 }}
      />
      <div style={{ display: 'flex', gap: 6, marginTop: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <select className="input" value={tag} onChange={e => setTag(e.target.value)} style={{ flex: '0 0 auto', width: 'auto', padding: '6px 10px', fontSize: 13 }}>
          {tags.map(t => <option key={t} value={t}>#{t}</option>)}
        </select>
        {tag === '语录' && (
          <input
            className="input"
            value={speaker}
            onChange={e => setSpeaker(e.target.value)}
            placeholder="谁说的"
            style={{ width: 80, padding: '6px 10px', fontSize: 13 }}
          />
        )}
        {tab === '记感受' && (
          <input
            className="input"
            value={feeling}
            onChange={e => setFeeling(e.target.value)}
            placeholder="感受..."
            style={{ flex: 1, minWidth: 100, padding: '6px 10px', fontSize: 13 }}
          />
        )}
        <div style={{ display: 'flex', gap: 4 }}>
          {MOODS.map(m => (
            <button
              key={m.id}
              type="button"
              onClick={() => setMood(mood === m.id ? '' : m.id)}
              style={{
                ...styles.moodDotSmall,
                background: m.color,
                outline: mood === m.id ? `2px solid ${m.color}` : 'none',
                outlineOffset: 2,
              }}
              title={m.id}
            >
              {m.emoji}
            </button>
          ))}
        </div>
        <button className="btn" type="submit" disabled={saving} style={{ marginLeft: 'auto' }}>
          {saving ? '...' : '保存'}
        </button>
      </div>
    </form>
  )
}

function groupByDate(entries) {
  const groups = {}
  for (const entry of entries) {
    const date = entry.memory_date || 'unknown'
    if (!groups[date]) groups[date] = []
    groups[date].push(entry)
  }
  return Object.entries(groups)
    .sort(([a], [b]) => b.localeCompare(a))
    .map(([date, items]) => ({
      date,
      items: items.sort((a, b) => new Date(b.created_at) - new Date(a.created_at)),
    }))
}

function formatDateDisplay(dateStr) {
  if (!dateStr || dateStr === 'unknown') return { month: '', day: '?' }
  const parts = dateStr.split('-')
  const m = parseInt(parts[1], 10)
  const d = parseInt(parts[2], 10)
  return { month: `${m}月`, day: `${m}/${d}` }
}

function DateGroupedEntries({ entries, onDelete, onAwaken, onFade, onToggleSpecial }) {
  const groups = groupByDate(entries)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 0, marginTop: 12 }}>
      {groups.map(group => {
        const { day } = formatDateDisplay(group.date)
        const weekDay = (() => {
          try {
            const d = new Date(group.date)
            return ['周日','周一','周二','周三','周四','周五','周六'][d.getDay()]
          } catch { return '' }
        })()

        return (
          <div key={group.date} style={styles.dateGroup}>
            <div style={styles.dateLeft}>
              <div style={styles.dateDay}>{day}</div>
              <div style={styles.dateWeek}>{weekDay}</div>
            </div>
            <div style={styles.dateRight}>
              {group.items.map(entry => (
                <CompactEntryStrip
                  key={entry.id}
                  entry={entry}
                  onDelete={onDelete}
                  onAwaken={onAwaken}
                  onFade={onFade}
                  onToggleSpecial={onToggleSpecial}
                />
              ))}
            </div>
          </div>
        )
      })}
    </div>
  )
}

function CompactEntryStrip({ entry, onDelete, onAwaken, onFade, onToggleSpecial }) {
  const [expanded, setExpanded] = useState(false)
  const isFaded = entry.status === 'faded'
  const time = (() => {
    try {
      const d = new Date(entry.created_at)
      return `${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
    } catch { return '' }
  })()

  const tagColor = entry.kind === '记事'
    ? { bg: '#ffe4ec', text: '#c4758a' }
    : { bg: '#fff3c4', text: '#a89040' }

  const moodEmoji = entry.mood ? (MOODS.find(m => m.id === entry.mood)?.emoji || '') : ''

  const preview = entry.content.length > 60 ? entry.content.slice(0, 60) + '…' : entry.content

  return (
    <div
      style={{
        ...styles.stripWrap,
        opacity: isFaded ? 0.5 : 1,
        borderLeftColor: tagColor.text,
      }}
      onClick={() => setExpanded(!expanded)}
    >
      <div style={styles.stripHeader}>
        <span style={{ ...styles.stripTag, background: tagColor.bg, color: tagColor.text }}>
          {TAG_ICONS[entry.tag] || ''} {entry.tag}
        </span>
        {entry.is_special && <span style={{ fontSize: 12 }}>⭐</span>}
        {moodEmoji && <span style={{ fontSize: 13 }}>{moodEmoji}</span>}
        {entry.status && entry.status !== 'active' && (
          <span style={styles.statusBadge}>{STATUS_LABEL[entry.status]}</span>
        )}
        <span style={styles.stripTime}>{time}</span>
      </div>

      {!expanded ? (
        <div style={styles.stripPreview}>
          {entry.title && <strong>{entry.title} · </strong>}
          {preview}
        </div>
      ) : (
        <div style={styles.stripExpanded}>
          {entry.title && <div style={{ fontWeight: 600, marginBottom: 4, fontSize: 14 }}>{entry.title}</div>}
          <p style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', lineHeight: 1.75, fontSize: 13, margin: 0 }}>
            {entry.content}
          </p>
          {entry.feeling && (
            <div style={{ marginTop: 6, fontSize: 12, color: 'var(--text-secondary)', fontStyle: 'italic' }}>
              {entry.feeling}
            </div>
          )}
          {entry.speaker && (
            <div style={{ marginTop: 4, fontSize: 12, color: 'var(--text-muted)' }}>—{entry.speaker}</div>
          )}
          <div style={styles.stripActions} onClick={e => e.stopPropagation()}>
            <button onClick={() => onToggleSpecial(entry.id, entry.is_special)} style={styles.actionBtn} title="标记特殊日">
              {entry.is_special ? '⭐' : '☆'}
            </button>
            {isFaded ? (
              <button onClick={() => onAwaken(entry.id)} style={styles.actionBtn} title="唤醒">🔔</button>
            ) : (
              <button onClick={() => onFade(entry.id)} style={styles.actionBtn} title="淡忘">💤</button>
            )}
            <button onClick={() => onDelete(entry.id)} className="delete-btn" style={{ fontSize: 12, padding: '2px 6px' }}>×</button>
          </div>
        </div>
      )}
    </div>
  )
}

function TidesPanel({ data, selectedCategory, onSelectCategory, entries, onDelete, onAwaken, onFade, onToggleSpecial }) {
  const maxCount = Math.max(...data.monthCounts.map(m => m.count), 1)
  const maxCatCount = Math.max(...data.categories.map(c => c.items.length), 1)
  const currentIdx = data.monthCounts.length - 1

  return (
    <div className="card" style={{ padding: '20px 16px', marginBottom: 12 }}>
      <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 16, letterSpacing: 0.5 }}>
        每月记忆
      </div>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-around', height: 100, marginBottom: 20, padding: '0 4px' }}>
        {data.monthCounts.map((m, i) => {
          const barH = maxCount > 0 ? Math.max((m.count / maxCount) * 72, m.count > 0 ? 8 : 0) : 0
          const isCurrent = i === currentIdx
          return (
            <div key={i} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', flex: 1, gap: 4 }}>
              <span style={{ fontSize: 11, color: isCurrent ? '#5a8a5a' : 'var(--text-muted)', fontWeight: isCurrent ? 600 : 400 }}>
                {m.count > 0 ? m.count : ''}
              </span>
              <div style={{
                width: 24,
                height: barH,
                background: isCurrent ? '#96c896' : '#d8e8d4',
                borderRadius: 4,
                transition: 'height 0.4s ease',
              }} />
              <span style={{ fontSize: 11, color: isCurrent ? '#5a8a5a' : 'var(--text-muted)', fontWeight: isCurrent ? 600 : 400 }}>
                {m.label}
              </span>
            </div>
          )
        })}
      </div>

      <div style={{ height: 1, background: 'var(--border)', margin: '0 0 16px' }} />

      <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 14, letterSpacing: 0.5 }}>
        记忆构成
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {data.categories.map(cat => {
          const isSelected = selectedCategory === cat.id
          return (
            <div key={cat.id}>
            <button
              onClick={() => onSelectCategory(cat)}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                background: isSelected ? 'var(--bg-secondary)' : 'transparent',
                border: 'none',
                padding: '10px 8px',
                borderRadius: 10,
                cursor: 'pointer',
                fontFamily: 'inherit',
                width: '100%',
                textAlign: 'left',
                transition: 'background 0.2s',
              }}
            >
              <span style={{
                width: 10,
                height: 10,
                borderRadius: '50%',
                background: cat.color,
                flexShrink: 0,
              }} />
              <span style={{ fontSize: 13, color: 'var(--text-primary)', width: 72, flexShrink: 0 }}>
                {cat.label}
              </span>
              <div style={{
                flex: 1,
                height: 6,
                background: 'var(--bg-secondary)',
                borderRadius: 3,
                overflow: 'hidden',
              }}>
                <div style={{
                  height: '100%',
                  width: `${maxCatCount > 0 ? Math.max((cat.items.length / maxCatCount) * 100, cat.items.length > 0 ? 4 : 0) : 0}%`,
                  background: cat.color,
                  borderRadius: 3,
                  transition: 'width 0.4s ease',
                }} />
              </div>
              <span style={{ fontSize: 13, color: 'var(--text-secondary)', flexShrink: 0, minWidth: 36, textAlign: 'right' }}>
                {cat.items.length}条
              </span>
              <span style={{ color: 'var(--text-muted)', fontSize: 14, transform: isSelected ? 'rotate(90deg)' : 'none', transition: 'transform 0.2s' }}>›</span>
            </button>
            {isSelected && entries && entries.length > 0 && (
              <div style={{ padding: '4px 0 8px 20px' }}>
                <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 }}>
                  {cat.label} · {entries.length}条
                </div>
                {entries.map(entry => (
                  <CompactEntryStrip
                    key={entry.id}
                    entry={entry}
                    onDelete={onDelete}
                    onAwaken={onAwaken}
                    onFade={onFade}
                    onToggleSpecial={onToggleSpecial}
                  />
                ))}
              </div>
            )}
            {isSelected && (!entries || entries.length === 0) && (
              <div style={{ padding: '12px 20px', fontSize: 13, color: 'var(--text-muted)' }}>
                这个分类还没有记忆~
              </div>
            )}
          </div>
        )
        })}
      </div>
    </div>
  )
}

function MiniCalendar({ year, month, activeDates, selectedDate, onSelect, onMonthChange }) {
  const daysInMonth = new Date(year, month + 1, 0).getDate()
  const firstDay = new Date(year, month, 1).getDay()
  const monthName = `${year}年${month + 1}月`
  const today = new Date().toISOString().slice(0, 10)

  function prev() {
    const d = new Date(year, month - 1, 1)
    onMonthChange(d.getFullYear(), d.getMonth())
  }
  function next() {
    const d = new Date(year, month + 1, 1)
    onMonthChange(d.getFullYear(), d.getMonth())
  }

  const cells = []
  for (let i = 0; i < firstDay; i++) cells.push(null)
  for (let d = 1; d <= daysInMonth; d++) cells.push(d)

  return (
    <div className="card" style={{ marginBottom: 12, padding: 16 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <button onClick={prev} style={styles.calNav}>‹</button>
        <span style={{ fontSize: 14, fontWeight: 600 }}>{monthName}</span>
        <button onClick={next} style={styles.calNav}>›</button>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 2, textAlign: 'center', fontSize: 12 }}>
        {['日', '一', '二', '三', '四', '五', '六'].map(d => (
          <div key={d} style={{ padding: 4, color: 'var(--text-muted)', fontWeight: 500 }}>{d}</div>
        ))}
        {cells.map((day, i) => {
          if (!day) return <div key={`e${i}`} />
          const dateStr = `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`
          const hasEntry = activeDates.includes(dateStr)
          const isSelected = dateStr === selectedDate
          const isToday = dateStr === today
          return (
            <button
              key={day}
              onClick={() => hasEntry && onSelect(dateStr)}
              style={{
                ...styles.calDay,
                background: isSelected ? 'var(--accent)' : 'transparent',
                color: isSelected ? 'white' : isToday ? 'var(--accent)' : hasEntry ? 'var(--text-primary)' : 'var(--text-muted)',
                fontWeight: isToday || hasEntry ? 600 : 400,
                cursor: hasEntry ? 'pointer' : 'default',
              }}
            >
              {day}
              {hasEntry && !isSelected && <span style={styles.calDot} />}
            </button>
          )
        })}
      </div>
    </div>
  )
}

const styles = {
  header: {
    display: 'flex',
    gap: 20,
    alignItems: 'flex-start',
    padding: '8px 0 16px',
    borderBottom: '1px solid var(--border)',
    marginBottom: 16,
  },
  poem: {
    flex: 1,
    fontSize: 13,
    lineHeight: 2,
    color: 'var(--text-secondary)',
    whiteSpace: 'pre-line',
    letterSpacing: '0.5px',
  },
  statsBlock: {
    flexShrink: 0,
    textAlign: 'right',
    paddingLeft: 16,
    borderLeft: '1px solid var(--border)',
  },
  statRow: {
    display: 'flex',
    alignItems: 'baseline',
    justifyContent: 'flex-end',
    gap: 4,
    marginBottom: 4,
  },
  statLabel: {
    fontSize: 13,
    color: 'var(--text-secondary)',
  },
  statNumber: {
    fontFamily: 'var(--font-display)',
    fontSize: 32,
    fontWeight: 500,
    color: 'var(--text-primary)',
    lineHeight: 1.2,
  },
  statUnit: {
    fontSize: 13,
    color: 'var(--text-secondary)',
  },
  searchWrap: {
    display: 'flex',
    gap: 0,
    marginBottom: 14,
    borderRadius: 20,
    overflow: 'hidden',
    border: '1px solid var(--border)',
    background: 'var(--bg-secondary)',
  },
  searchInput: {
    flex: 1,
    border: 'none',
    background: 'transparent',
    padding: '10px 16px',
    fontSize: 14,
    color: 'var(--text-primary)',
    outline: 'none',
    fontFamily: 'inherit',
  },
  searchClear: {
    border: 'none',
    background: 'transparent',
    padding: '0 8px',
    fontSize: 18,
    color: 'var(--text-muted)',
    cursor: 'pointer',
  },
  searchBtn: {
    border: 'none',
    background: '#c8c464',
    color: 'white',
    padding: '0 18px',
    fontSize: 13,
    fontWeight: 600,
    cursor: 'pointer',
    letterSpacing: 1,
  },
  tabBar: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 16,
    marginBottom: 10,
  },
  tabBtn: {
    background: 'none',
    border: 'none',
    fontSize: 15,
    fontWeight: 400,
    color: 'var(--text-muted)',
    cursor: 'pointer',
    padding: '4px 8px',
    letterSpacing: 1,
    fontFamily: 'inherit',
    transition: 'all 0.2s',
  },
  tabActive: {
    fontWeight: 600,
    color: 'var(--text-primary)',
    borderBottom: '2px solid var(--text-primary)',
  },
  tabDivider: {
    color: 'var(--text-muted)',
    fontSize: 14,
  },
  tagRow: {
    display: 'flex',
    gap: 8,
    flexWrap: 'wrap',
    justifyContent: 'center',
    marginBottom: 12,
  },
  tagChip: {
    padding: '5px 14px',
    borderRadius: 16,
    border: 'none',
    fontSize: 13,
    cursor: 'pointer',
    transition: 'all 0.2s',
    fontFamily: 'inherit',
  },
  filterLabel: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    padding: '8px 0 4px',
    fontSize: 13,
    color: 'var(--text-secondary)',
    marginBottom: 8,
  },
  filterClose: {
    border: 'none',
    background: 'var(--bg-secondary)',
    color: 'var(--text-muted)',
    width: 22,
    height: 22,
    borderRadius: '50%',
    fontSize: 14,
    cursor: 'pointer',
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
  },
  addToggle: {
    display: 'block',
    width: '100%',
    padding: '8px 0',
    marginBottom: 8,
    background: 'none',
    border: '1px dashed var(--border)',
    borderRadius: 10,
    color: 'var(--text-muted)',
    fontSize: 13,
    cursor: 'pointer',
    fontFamily: 'inherit',
    transition: 'all 0.2s',
  },
  funcGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(4, 1fr)',
    gap: 8,
    margin: '14px 0',
    padding: '0 2px',
  },
  funcCard: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    gap: 4,
    padding: '14px 4px 12px',
    border: 'none',
    borderRadius: 12,
    cursor: 'pointer',
    fontFamily: 'inherit',
    transition: 'all 0.2s',
    position: 'relative',
  },
  funcDot: {
    width: 8,
    height: 8,
    borderRadius: '50%',
    marginBottom: 2,
  },
  funcLabel: {
    fontSize: 13,
    fontWeight: 600,
    color: '#5a4a3a',
    letterSpacing: 0.5,
  },
  funcDesc: {
    fontSize: 10,
    color: '#8a7a6a',
    letterSpacing: 0.3,
  },
  tidesBackBtn: {
    background: 'none',
    border: 'none',
    color: 'var(--text-secondary)',
    fontSize: 13,
    cursor: 'pointer',
    fontFamily: 'inherit',
    padding: '8px 0',
    letterSpacing: 0.5,
  },
  dateGroup: {
    display: 'flex',
    gap: 14,
    padding: '16px 0',
    borderBottom: '1px solid var(--border)',
  },
  dateLeft: {
    flexShrink: 0,
    width: 56,
    textAlign: 'center',
    paddingTop: 2,
  },
  dateDay: {
    fontFamily: 'var(--font-display)',
    fontSize: 22,
    fontWeight: 500,
    color: 'var(--text-primary)',
    lineHeight: 1.2,
    letterSpacing: -0.5,
  },
  dateWeek: {
    fontSize: 11,
    color: 'var(--text-muted)',
    marginTop: 2,
  },
  dateRight: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    gap: 6,
    minWidth: 0,
  },
  stripWrap: {
    background: 'var(--bg-card, #fff)',
    borderRadius: 10,
    padding: '10px 12px',
    borderLeft: '3px solid #ccc',
    cursor: 'pointer',
    transition: 'all 0.15s',
  },
  stripHeader: {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    marginBottom: 4,
  },
  stripTag: {
    display: 'inline-block',
    padding: '1px 8px',
    borderRadius: 8,
    fontSize: 11,
    fontWeight: 500,
  },
  stripTime: {
    marginLeft: 'auto',
    fontSize: 11,
    color: 'var(--text-muted)',
    fontVariantNumeric: 'tabular-nums',
  },
  stripPreview: {
    fontSize: 13,
    color: 'var(--text-secondary)',
    lineHeight: 1.5,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  stripExpanded: {
    paddingTop: 4,
  },
  stripActions: {
    display: 'flex',
    gap: 4,
    marginTop: 8,
    justifyContent: 'flex-end',
  },
  entryMeta: {
    display: 'flex',
    gap: 8,
    alignItems: 'center',
    marginTop: 8,
    flexWrap: 'wrap',
  },
  miniTag: {
    display: 'inline-block',
    padding: '2px 8px',
    borderRadius: 10,
    fontSize: 11,
    fontWeight: 500,
  },
  metaText: {
    fontSize: 12,
    color: 'var(--text-muted)',
  },
  metaDate: {
    fontSize: 11,
    color: 'var(--text-muted)',
    marginLeft: 'auto',
  },
  statusBadge: {
    fontSize: 10,
    color: 'var(--text-muted)',
    padding: '1px 6px',
    border: '1px solid var(--border)',
    borderRadius: 8,
  },
  specialMark: {
    position: 'absolute',
    top: 8,
    right: 8,
    fontSize: 14,
  },
  entryActions: {
    display: 'flex',
    flexDirection: 'column',
    gap: 2,
    marginLeft: 8,
    flexShrink: 0,
  },
  actionBtn: {
    background: 'none',
    border: 'none',
    fontSize: 14,
    cursor: 'pointer',
    padding: '2px 4px',
    borderRadius: 4,
    transition: 'background 0.2s',
  },
  expandBtn: {
    background: 'none',
    border: 'none',
    color: 'var(--accent)',
    fontSize: 12,
    cursor: 'pointer',
    padding: '2px 0',
    fontFamily: 'inherit',
  },
  moodDot: {
    width: 40,
    height: 40,
    borderRadius: '50%',
    border: 'none',
    fontSize: 18,
    cursor: 'pointer',
    transition: 'all 0.2s',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
  },
  moodDotSmall: {
    width: 28,
    height: 28,
    borderRadius: '50%',
    border: 'none',
    fontSize: 14,
    cursor: 'pointer',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 0,
  },
  calNav: {
    background: 'none',
    border: 'none',
    fontSize: 20,
    color: 'var(--text-secondary)',
    cursor: 'pointer',
    padding: '4px 10px',
  },
  calDay: {
    position: 'relative',
    padding: '6px 0',
    border: 'none',
    borderRadius: 8,
    fontSize: 13,
    fontFamily: 'inherit',
    transition: 'all 0.15s',
  },
  calDot: {
    position: 'absolute',
    bottom: 2,
    left: '50%',
    transform: 'translateX(-50%)',
    width: 4,
    height: 4,
    borderRadius: '50%',
    background: 'var(--accent)',
  },
  blindBoxOverlay: {
    position: 'fixed',
    inset: 0,
    background: 'rgba(0,0,0,0.45)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 500,
    padding: 20,
  },
  blindBoxCard: {
    background: 'var(--bg-card, #fff)',
    borderRadius: 20,
    padding: '28px 24px',
    maxWidth: 380,
    width: '100%',
    maxHeight: '70vh',
    overflowY: 'auto',
    boxShadow: '0 20px 60px rgba(0,0,0,0.2)',
  },
  blindBoxTitle: {
    textAlign: 'center',
    fontSize: 18,
    fontWeight: 600,
    marginBottom: 16,
    color: 'var(--text-primary)',
  },
  blindBoxTag: {
    textAlign: 'center',
    marginBottom: 10,
    fontSize: 13,
  },
  blindBoxBtn: {
    padding: '8px 20px',
    border: 'none',
    borderRadius: 16,
    background: '#b8d8b8',
    color: '#3a4a3a',
    fontSize: 13,
    fontWeight: 500,
    cursor: 'pointer',
    fontFamily: 'inherit',
  },
}
