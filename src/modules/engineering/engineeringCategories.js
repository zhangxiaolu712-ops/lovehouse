export const ENGINEERING_CATEGORIES = Object.freeze([
  { key: 'architecture', label: '架构', components: ['frontend', 'bridge', 'database', 'infrastructure'] },
  { key: 'feature', label: '功能', components: ['chat', 'memory', 'workspace', 'device'] },
  { key: 'operations', label: '运维', components: ['ci', 'deployment', 'monitoring', 'security'] },
  { key: 'decision', label: '决策', components: ['product', 'technical', 'process'] },
  { key: 'issue', label: '问题', components: ['bug', 'risk', 'debt'] },
])

const categoryMap = new Map(ENGINEERING_CATEGORIES.map(item => [item.key, item]))

export function categoryDetails(category) {
  const key = String(category || '').trim()
  const configured = categoryMap.get(key)
  return configured || { key: key || 'uncategorized', label: key || '未分类', components: [] }
}

export function classificationOf(item) {
  const metadata = item?.metadata && typeof item.metadata === 'object' ? item.metadata : {}
  return {
    category: String(metadata.category || '').trim(),
    component: String(metadata.component || '').trim(),
  }
}

export function groupEngineeringItems(items) {
  const groups = new Map()
  for (const item of items || []) {
    const { category, component } = classificationOf(item)
    const categoryInfo = categoryDetails(category)
    const componentKey = component || '未指定组件'
    if (!groups.has(categoryInfo.key)) groups.set(categoryInfo.key, { ...categoryInfo, components: new Map() })
    const categoryGroup = groups.get(categoryInfo.key)
    if (!categoryGroup.components.has(componentKey)) categoryGroup.components.set(componentKey, [])
    categoryGroup.components.get(componentKey).push(item)
  }
  return [...groups.values()].map(group => ({
    ...group,
    components: [...group.components].map(([key, values]) => ({ key, items: values })),
  }))
}
