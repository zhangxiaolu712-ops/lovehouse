export function resolveBridgePort(value = process.env.PORT) {
  if (value === undefined || value === null || value === '') return 3000
  const text = String(value).trim()
  if (!/^\d+$/.test(text)) throw new TypeError('PORT must be an integer between 1 and 65535')
  const port = Number.parseInt(text, 10)
  if (port < 1 || port > 65535) throw new RangeError('PORT must be an integer between 1 and 65535')
  return port
}
