import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

export const HOUSE_RULES_SCHEMA_VERSION = 'lovehouse.house_rules.v1'
export const STARTER_PACK_SCHEMA_VERSION = 'lovehouse.starter_pack.v1'

const DEFAULT_HOUSE_RULES_PATH = fileURLToPath(new URL('./house-rules.v1.json', import.meta.url))
const MAX_RULES = 8
const MAX_RULE_TEXT_LENGTH = 220
const MAX_TOTAL_TEXT_LENGTH = 2_000
const REQUIRED_USAGE_KEYS = ['session_start', 'recall', 'save', 'revise']

export class HouseRulesConfigurationError extends Error {
  constructor(message, options = {}) {
    super(message, options)
    this.name = 'HouseRulesConfigurationError'
    this.code = 'HOUSE_RULES_CONFIGURATION_INVALID'
  }
}

function boundedText(value, label, maximum) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new HouseRulesConfigurationError(`${label} is required`)
  }
  const trimmed = value.trim()
  if (trimmed.length > maximum) {
    throw new HouseRulesConfigurationError(`${label} is too long`)
  }
  return trimmed
}

export function validateHouseRulesDocument(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new HouseRulesConfigurationError('House Rules must be an object')
  }
  if (value.schema_version !== HOUSE_RULES_SCHEMA_VERSION) {
    throw new HouseRulesConfigurationError('House Rules schema version is unsupported')
  }
  if (!Number.isSafeInteger(value.revision) || value.revision < 1) {
    throw new HouseRulesConfigurationError('House Rules revision must be a positive integer')
  }
  if (!Array.isArray(value.rules) || value.rules.length < 1 || value.rules.length > MAX_RULES) {
    throw new HouseRulesConfigurationError(`House Rules must contain 1-${MAX_RULES} rules`)
  }

  const seenIds = new Set()
  const rules = value.rules.map((rule, index) => {
    if (!rule || typeof rule !== 'object' || Array.isArray(rule)) {
      throw new HouseRulesConfigurationError(`House Rule ${index + 1} must be an object`)
    }
    const id = boundedText(rule.id, `House Rule ${index + 1} id`, 80)
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(id)) {
      throw new HouseRulesConfigurationError(`House Rule ${index + 1} id is invalid`)
    }
    if (seenIds.has(id)) throw new HouseRulesConfigurationError(`Duplicate House Rule id: ${id}`)
    seenIds.add(id)
    return {
      id,
      text: boundedText(rule.text, `House Rule ${id} text`, MAX_RULE_TEXT_LENGTH),
    }
  })

  if (!value.memory_usage || typeof value.memory_usage !== 'object' || Array.isArray(value.memory_usage)) {
    throw new HouseRulesConfigurationError('House Rules memory usage is required')
  }
  const memoryUsage = Object.fromEntries(REQUIRED_USAGE_KEYS.map(key => [
    key,
    boundedText(value.memory_usage[key], `House Rules memory usage ${key}`, MAX_RULE_TEXT_LENGTH),
  ]))
  const normalized = {
    schema_version: HOUSE_RULES_SCHEMA_VERSION,
    revision: value.revision,
    title: boundedText(value.title, 'House Rules title', 120),
    purpose: boundedText(value.purpose, 'House Rules purpose', MAX_RULE_TEXT_LENGTH),
    rules,
    memory_usage: memoryUsage,
  }
  const totalText = [
    normalized.title,
    normalized.purpose,
    ...rules.map(rule => rule.text),
    ...Object.values(memoryUsage),
  ].join('').length
  if (totalText > MAX_TOTAL_TEXT_LENGTH) {
    throw new HouseRulesConfigurationError('House Rules are too long for a starter pack')
  }
  return normalized
}

export class FileHouseRulesProvider {
  constructor({ filePath = DEFAULT_HOUSE_RULES_PATH } = {}) {
    this.filePath = filePath
  }

  async getRules() {
    try {
      const source = await readFile(this.filePath, 'utf8')
      return validateHouseRulesDocument(JSON.parse(source))
    } catch (error) {
      if (error instanceof HouseRulesConfigurationError) throw error
      throw new HouseRulesConfigurationError('House Rules could not be loaded', { cause: error })
    }
  }
}

export const bundledHouseRulesProvider = new FileHouseRulesProvider()
