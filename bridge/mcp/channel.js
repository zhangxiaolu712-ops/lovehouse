import { createMcpToolDefinitions, createMcpToolHandler } from './tools.js'

/**
 * Binds one authenticated transport to one server-owned actor. Request body,
 * query, headers and tool arguments are deliberately not actor sources.
 */
export function createMcpChannel({ actor, memoryService, livingroomRest }) {
  const tools = createMcpToolDefinitions(actor)
  const fixedActorToolHandler = createMcpToolHandler({
    actor,
    memoryService,
    livingroomRest,
  })

  return Object.freeze({
    actor,
    tools,
    callTool(name, args, trustedContext = {}) {
      return fixedActorToolHandler(name, args, trustedContext)
    },
  })
}
