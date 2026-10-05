package com.samin.notouchgesture.nearby

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Small, dependency-free cryptographic helper used by the local Nearby pairing handshake. */
object PairingCrypto {
    private val random = SecureRandom()
    private const val TOKEN_BYTES = 32
    private const val HEX = "0123456789abcdef"

    fun randomToken(): String = encode(randomBytes(TOKEN_BYTES))

    fun randomNonce(): String = encode(randomBytes(TOKEN_BYTES))

    /** Derives one symmetric secret from the two first-pairing tokens and stable device IDs. */
    fun deriveSharedSecret(
        localDeviceId: String,
        localToken: String,
        peerDeviceId: String,
        peerToken: String,
    ): String {
        val leftFirst = localDeviceId < peerDeviceId
        val firstId = if (leftFirst) localDeviceId else peerDeviceId
        val secondId = if (leftFirst) peerDeviceId else localDeviceId
        val firstToken = if (leftFirst) localToken else peerToken
        val secondToken = if (leftFirst) peerToken else localToken
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("PalmLinkPair/v1\u0000".toByteArray(StandardCharsets.UTF_8))
        digest.update(firstId.toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
        digest.update(secondId.toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
        digest.update(firstToken.toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
        digest.update(secondToken.toByteArray(StandardCharsets.UTF_8))
        return encode(digest.digest())
    }

    fun proof(secret: String, purpose: String, senderId: String, challenge: String, receiverId: String): String {
        val key = decode(secret)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val data = listOf(purpose, senderId, challenge, receiverId).joinToString("\u0000")
            .toByteArray(StandardCharsets.UTF_8)
        return encode(mac.doFinal(data))
    }

    fun constantTimeEquals(left: String, right: String): Boolean {
        return MessageDigest.isEqual(
            decodeOrEmpty(left),
            decodeOrEmpty(right),
        )
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    private fun decode(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            val hi = hexValue(value[index * 2])
            val lo = hexValue(value[index * 2 + 1])
            ((hi shl 4) or lo).toByte()
        }
    }

    private fun decodeOrEmpty(value: String): ByteArray = runCatching { decode(value) }.getOrElse { ByteArray(0) }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c.code - '0'.code
        in 'a'..'f' -> c.code - 'a'.code + 10
        in 'A'..'F' -> c.code - 'A'.code + 10
        else -> error("invalid hex")
    }
}
