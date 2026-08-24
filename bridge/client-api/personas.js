const SCENES = new Set(['casual', 'work', 'travel', 'livingroom', 'custom'])

const DEFAULT_PERSONAS = Object.freeze([
  Object.freeze({
    id: 'gpt',
    display_name: 'GPT',
    default_runtime: 'gpt',
    enabled: false,
    scene: 'casual',
  }),
  Object.freeze({
    id: 'claude',
    display_name: '小克',
    default_runtime: 'claude',
    enabled: true,
    scene: 'casual',
  }),
  Object.freeze({
    id: 'codex',
    display_name: 'Codex',
    default_runtime: 'codex',
    enabled: true,
    scene: 'work',
  }),
])

function normalizePersona(persona) {
  if (!persona || typeof persona !== 'object') throw new TypeError('persona must be an object')
  if (!/^[a-z][a-z0-9_-]{0,31}$/.test(persona.id || '')) throw new TypeError('persona id is invalid')
  if (typeof persona.display_name !== 'string' || !persona.display_name.trim()) {
    throw new TypeError('persona display_name is required')
  }
  if (!/^[a-z][a-z0-9_-]{0,31}$/.test(persona.default_runtime || '')) {
    throw new TypeError('persona default_runtime is invalid')
  }
  if (!SCENES.has(persona.scene)) throw new TypeError('persona scene is invalid')
  return Object.freeze({
    id: persona.id,
    display_name: persona.display_name.trim(),
    default_runtime: persona.default_runtime,
    enabled: persona.enabled === true,
    scene: persona.scene,
  })
}

export function createPersonaRegistry(personas = DEFAULT_PERSONAS) {
  const entries = personas.map(normalizePersona)
  const byId = new Map(entries.map(persona => [persona.id, persona]))
  if (byId.size !== entries.length) throw new TypeError('persona ids must be unique')

  return Object.freeze({
    get(id) {
      const persona = byId.get(id)
      return persona ? { ...persona } : null
    },
    list() {
      return entries.map(persona => ({ ...persona }))
    },
  })
}

export { SCENES }
