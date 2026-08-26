import { listMemoryTimeline } from '../memory-v2/memoryV2Service'

export function getGptMemoryTimeline(options) {
  return listMemoryTimeline('gpt', options)
}
