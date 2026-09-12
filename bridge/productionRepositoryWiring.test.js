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

test('Life Memory and livingroom default writes to Supabase unless PostgreSQL write is explicitly selected', () => {
  assert.match(server, /const LIFE_MEMORY_READ_DATABASE_URL = process\.env\.LIFE_MEMORY_READ_DATABASE_URL \|\| ''/)
  assert.match(server, /const LIFE_MEMORY_WRITE_SOURCE = process\.env\.LIFE_MEMORY_WRITE_SOURCE \|\| 'supabase'/)
  assert.match(server, /const activeMemoryV2Repository = postgresMemoryV2ReadRepository[\s\S]+: memoryV2Repository/)
  assert.match(server, /const routedMemoryV2Repository = postgresMemoryV2WriteRepository[\s\S]+: activeMemoryV2Repository/)
  assert.match(server, /const livingroomReadRest = postgresMemoryV2ReadRepository[\s\S]+: supabaseLivingroomRest/)
  assert.match(server, /const livingroomRest = postgresMemoryV2WriteRepository[\s\S]+: livingroomReadRest/)
  assert.match(server, /life_memory_write_source: postgresMemoryV2WriteRepository \? 'postgres' : 'supabase'/)
  assert.match(server, /livingroom_write_source: postgresMemoryV2WriteRepository \? 'postgres' : 'supabase'/)
})

test('Life Memory PostgreSQL write selection is atomic across memory and livingroom', () => {
  assert.match(server, /LIFE_MEMORY_WRITE_SOURCE === 'postgres'/)
  assert.match(server, /pool: postgresMemoryV2ReadRepository\.pool/)
  assert.match(server, /repository: routedMemoryV2Repository/)
  assert.match(server, /createPostgresLivingroomWriteCutover/)
})

test('Life Memory read cutover does not reuse the Engineering database configuration', () => {
  const memoryReadConstruction = server.match(
    /const postgresMemoryV2ReadRepository[\s\S]*?\nconst activeMemoryV2Repository/,
  )?.[0]
  assert.match(memoryReadConstruction, /connectionString: LIFE_MEMORY_READ_DATABASE_URL/)
  assert.doesNotMatch(memoryReadConstruction, /ENGINEERING_DATABASE_URL/)
})
