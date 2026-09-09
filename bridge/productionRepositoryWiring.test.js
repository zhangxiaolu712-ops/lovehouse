import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'

const server = fs.readFileSync(new URL('./server.js', import.meta.url), 'utf8')

test('production wiring keeps Engineering reads and writes directly on Railway PostgreSQL', () => {
  assert.doesNotMatch(server, /ReadCutoverEngineeringRepository|ReadCutoverProjectChecklistStore/)
  assert.match(server, /: railwayEngineeringRepository\s+\? railwayEngineeringRepository\s+: memoryV2Repository/)
  assert.match(server, /new ProjectChecklistStore\(\{ repository: railwayEngineeringRepository \}\)/)
  assert.match(server, /engineering_write_source: railwayEngineeringRepository \? 'railway_postgres' : 'supabase'/)
})

test('Life Memory and livingroom default to Supabase unless PostgreSQL read is explicitly configured', () => {
  assert.match(server, /const LIFE_MEMORY_READ_DATABASE_URL = process\.env\.LIFE_MEMORY_READ_DATABASE_URL \|\| ''/)
  assert.match(server, /const activeMemoryV2Repository = postgresMemoryV2ReadRepository[\s\S]+: memoryV2Repository/)
  assert.match(server, /const livingroomRest = postgresMemoryV2ReadRepository[\s\S]+: supabaseLivingroomRest/)
  assert.match(server, /life_memory_write_source: 'supabase'/)
  assert.match(server, /livingroom_write_source: 'supabase'/)
})

test('Life Memory read cutover does not reuse the Engineering database configuration', () => {
  const memoryReadConstruction = server.match(
    /const postgresMemoryV2ReadRepository[\s\S]*?\nconst activeMemoryV2Repository/,
  )?.[0]
  assert.match(memoryReadConstruction, /connectionString: LIFE_MEMORY_READ_DATABASE_URL/)
  assert.doesNotMatch(memoryReadConstruction, /ENGINEERING_DATABASE_URL/)
})
