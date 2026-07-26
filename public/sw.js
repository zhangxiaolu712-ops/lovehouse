const CACHE_NAME = 'lovehouse-shell-v1'
const APP_ROOT = new URL('./', self.registration.scope).toString()
const SHELL_FILES = [
  APP_ROOT,
  new URL('manifest.webmanifest', APP_ROOT).toString(),
  new URL('favicon.svg', APP_ROOT).toString(),
]

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache => cache.addAll(SHELL_FILES))
      .then(() => self.skipWaiting())
  )
})

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(
        keys
          .filter(key => key.startsWith('lovehouse-shell-') && key !== CACHE_NAME)
          .map(key => caches.delete(key))
      ))
      .then(() => self.clients.claim())
  )
})

self.addEventListener('fetch', event => {
  const { request } = event
  const url = new URL(request.url)

  // 只缓存本站外壳；Supabase 数据与其他跨域请求永远不进入离线缓存。
  if (request.method !== 'GET' || url.origin !== self.location.origin) return

  event.respondWith(
    fetch(request)
      .then(response => {
        if (response.ok) {
          const copy = response.clone()
          caches.open(CACHE_NAME).then(cache => cache.put(request, copy))
        }
        return response
      })
      .catch(async () => {
        const cached = await caches.match(request)
        if (cached) return cached
        if (request.mode === 'navigate') return caches.match(APP_ROOT)
        throw new Error('offline')
      })
  )
})
