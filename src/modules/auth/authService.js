const PIN_STORAGE_PREFIX = 'lovehouse:device-pin:v1:'
const PIN_ITERATIONS = 210000

function pinStorageKey(userId) {
  return `${PIN_STORAGE_PREFIX}${userId}`
}

function bytesToBase64(bytes) {
  let binary = ''
  bytes.forEach(byte => {
    binary += String.fromCharCode(byte)
  })
  return btoa(binary)
}

function base64ToBytes(value) {
  const binary = atob(value)
  return Uint8Array.from(binary, char => char.charCodeAt(0))
}

async function derivePin(pin, salt, iterations) {
  if (!window.isSecureContext || !crypto.subtle) {
    throw new Error('请使用 HTTPS 安全网址打开小屋后再设置 PIN。')
  }

  const material = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(pin),
    'PBKDF2',
    false,
    ['deriveBits']
  )

  const bits = await crypto.subtle.deriveBits(
    {
      name: 'PBKDF2',
      hash: 'SHA-256',
      salt,
      iterations,
    },
    material,
    256
  )

  return bytesToBase64(new Uint8Array(bits))
}

function readPinCredential(userId) {
  try {
    const value = localStorage.getItem(pinStorageKey(userId))
    if (!value) return null
    const parsed = JSON.parse(value)
    if (!parsed.salt || !parsed.hash || !parsed.iterations) return null
    return parsed
  } catch {
    return null
  }
}

export function hasDevicePin(userId) {
  return Boolean(readPinCredential(userId))
}

export async function saveDevicePin(userId, pin) {
  const salt = crypto.getRandomValues(new Uint8Array(16))
  const hash = await derivePin(pin, salt, PIN_ITERATIONS)
  localStorage.setItem(pinStorageKey(userId), JSON.stringify({
    salt: bytesToBase64(salt),
    hash,
    iterations: PIN_ITERATIONS,
  }))
}

export async function verifyDevicePin(userId, pin) {
  const credential = readPinCredential(userId)
  if (!credential) return false

  const candidate = await derivePin(
    pin,
    base64ToBytes(credential.salt),
    credential.iterations
  )

  if (candidate.length !== credential.hash.length) return false
  let difference = 0
  for (let index = 0; index < candidate.length; index += 1) {
    difference |= candidate.charCodeAt(index) ^ credential.hash.charCodeAt(index)
  }
  return difference === 0
}

export function removeDevicePin(userId) {
  localStorage.removeItem(pinStorageKey(userId))
}
