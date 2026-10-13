package fyi.b612.lovehouse.feature.chat

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

private val secureMessageIdentityRandom = SecureRandom()

/** Generates an RFC 9562 UUIDv7 without any network dependency. */
internal fun newMessageId(
    nowEpochMillis: Long = System.currentTimeMillis(),
    randomBytes: ByteArray? = null,
): String {
    require(nowEpochMillis in 0..0x0000_FFFF_FFFF_FFFFL) { "UUIDv7 timestamp is out of range" }
    val bytes = randomBytes?.copyOf() ?: ByteArray(16).also(secureMessageIdentityRandom::nextBytes)
    require(bytes.size == 16) { "UUIDv7 requires 16 random bytes" }
    for (index in 0 until 6) {
        bytes[index] = (nowEpochMillis ushr (40 - index * 8)).toByte()
    }
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val buffer = ByteBuffer.wrap(bytes)
    return UUID(buffer.long, buffer.long).toString()
}

internal fun isMessageId(value: String): Boolean = runCatching {
    val uuid = UUID.fromString(value)
    uuid.version() == 7 && uuid.variant() == 2
}.getOrDefault(false)
