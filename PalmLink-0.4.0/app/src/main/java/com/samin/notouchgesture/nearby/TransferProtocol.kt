package com.samin.notouchgesture.nearby

/**
 * Wire messages exchanged over the Nearby BYTES channel. The screenshot itself always travels
 * as a separate FILE payload. A small optional PREVIEW message is sent only after the receiver
 * accepts the screenshot, so older builds can still complete the original OFFER/ACCEPT/FILE flow.
 */
sealed interface TransferMessage {
    data class Offer(
        val transferId: String,
        val fileName: String,
        val byteCount: Long,
        val sha256: String,
        val mimeType: String = "image/png",
        val createdAtMs: Long = 0L,
        val senderName: String = "",
    ) : TransferMessage

    data class Preview(val transferId: String, val base64Jpeg: String) : TransferMessage
    data class Accept(val transferId: String) : TransferMessage
    data class Reject(val transferId: String, val reason: String) : TransferMessage
    data class Cancel(val transferId: String) : TransferMessage
    data class Result(val transferId: String, val ok: Boolean, val detail: String = "") : TransferMessage
    data class Ping(val value: String = "hello") : TransferMessage
    data class PairSetup(val deviceId: String, val token: String) : TransferMessage
    data class AuthHello(val deviceId: String, val nonce: String) : TransferMessage
    data class AuthResponse(
        val deviceId: String,
        val challenge: String,
        val proof: String,
    ) : TransferMessage
}

object TransferProtocol {
    private const val SEP = "|"
    private const val MAX_MESSAGE_CHARS = 16_384
    private val ID_REGEX = Regex("^[A-Za-z0-9-]{8,64}$")
    private val SHA_REGEX = Regex("^[0-9a-fA-F]{64}$")
    private val MIME_REGEX = Regex("^[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+$")
    private val PREVIEW_REGEX = Regex("^[A-Za-z0-9+/=_-]{64,14000}$")

    fun isValidTransferId(value: String): Boolean = ID_REGEX.matches(value)

    fun encode(message: TransferMessage): ByteArray {
        val line = when (message) {
            is TransferMessage.Offer -> listOf(
                "OFFER",
                message.transferId,
                sanitize(message.fileName),
                message.byteCount.toString(),
                message.sha256.lowercase(),
                sanitize(message.mimeType),
                message.createdAtMs.toString(),
                sanitize(message.senderName),
            ).joinToString(SEP)
            is TransferMessage.Preview -> "PREVIEW$SEP${message.transferId}$SEP${message.base64Jpeg}"
            is TransferMessage.Accept -> "ACCEPT$SEP${message.transferId}"
            is TransferMessage.Reject -> "REJECT$SEP${message.transferId}$SEP${sanitize(message.reason)}"
            is TransferMessage.Cancel -> "CANCEL$SEP${message.transferId}"
            is TransferMessage.Result ->
                "RESULT$SEP${message.transferId}$SEP${if (message.ok) 1 else 0}$SEP${sanitize(message.detail)}"
            is TransferMessage.Ping -> "PING$SEP${sanitize(message.value)}"
            is TransferMessage.PairSetup -> "PAIR$SEP${message.deviceId}$SEP${message.token}"
            is TransferMessage.AuthHello -> "AUTHH$SEP${message.deviceId}$SEP${message.nonce}"
            is TransferMessage.AuthResponse -> "AUTHR$SEP${message.deviceId}$SEP${message.challenge}$SEP${message.proof}"
        }
        require(line.length <= MAX_MESSAGE_CHARS) { "Nearby control message is too large" }
        return line.toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): TransferMessage? {
        if (bytes.isEmpty() || bytes.size > MAX_MESSAGE_CHARS * 4) return null
        val raw = bytes.toString(Charsets.UTF_8)
        if (raw.length > MAX_MESSAGE_CHARS) return null
        val parts = raw.split(SEP)
        return when (parts.firstOrNull()) {
            "OFFER" -> {
                if (parts.size != 8) return null
                val id = parts[1].takeIf(::isValidTransferId) ?: return null
                val name = parts[2].takeIf { it.isNotBlank() } ?: return null
                val size = parts[3].toLongOrNull()?.takeIf { it > 0L } ?: return null
                val sha = parts[4].takeIf { SHA_REGEX.matches(it) }?.lowercase() ?: return null
                val mime = parts[5].takeIf { MIME_REGEX.matches(it) } ?: return null
                val created = parts[6].toLongOrNull() ?: return null
                TransferMessage.Offer(id, name, size, sha, mime, created, parts[7])
            }
            "PREVIEW" -> {
                if (parts.size != 3) return null
                val id = parts[1].takeIf(::isValidTransferId) ?: return null
                val preview = parts[2].takeIf { PREVIEW_REGEX.matches(it) } ?: return null
                TransferMessage.Preview(id, preview)
            }
            "ACCEPT" -> if (parts.size == 2) parts[1].takeIf(::isValidTransferId)?.let { TransferMessage.Accept(it) } else null
            "REJECT" -> {
                if (parts.size != 3) return null
                val id = parts[1].takeIf(::isValidTransferId) ?: return null
                TransferMessage.Reject(id, parts[2].takeIf { it.isNotBlank() } ?: "Rejected")
            }
            "CANCEL" -> if (parts.size == 2) parts[1].takeIf(::isValidTransferId)?.let { TransferMessage.Cancel(it) } else null
            "RESULT" -> {
                if (parts.size != 4) return null
                val id = parts[1].takeIf(::isValidTransferId) ?: return null
                val ok = when (parts.getOrNull(2)) {
                    "1" -> true
                    "0" -> false
                    else -> return null
                }
                TransferMessage.Result(id, ok, parts.getOrNull(3).orEmpty())
            }
            "PING" -> TransferMessage.Ping(parts.drop(1).joinToString(SEP))
            "PAIR" -> {
                val id = parts.getOrNull(1)?.takeIf(::isValidDeviceId) ?: return null
                val token = parts.getOrNull(2)?.takeIf(::isValidToken) ?: return null
                TransferMessage.PairSetup(id, token)
            }
            "AUTHH" -> {
                val id = parts.getOrNull(1)?.takeIf(::isValidDeviceId) ?: return null
                val nonce = parts.getOrNull(2)?.takeIf(::isValidNonce) ?: return null
                TransferMessage.AuthHello(id, nonce)
            }
            "AUTHR" -> {
                if (parts.size != 4) return null
                val id = parts.getOrNull(1)?.takeIf(::isValidDeviceId) ?: return null
                val challenge = parts.getOrNull(2)?.takeIf(::isValidNonce) ?: return null
                val proof = parts.getOrNull(3)?.takeIf(::isValidToken) ?: return null
                TransferMessage.AuthResponse(id, challenge, proof)
            }
            else -> null
        }
    }

    private fun sanitize(value: String): String =
        value.replace("|", "_").replace("\n", " ").replace("\r", " ").trim().take(240)

    private fun isValidDeviceId(value: String): Boolean =
        value.length in 16..80 && value.matches(Regex("^[A-Za-z0-9-]+$"))

    private fun isValidNonce(value: String): Boolean =
        value.length in 16..128 && value.matches(Regex("^[A-Za-z0-9_-]+$"))

    private fun isValidToken(value: String): Boolean =
        value.length in 32..128 && value.matches(Regex("^[A-Za-z0-9_-]+$"))
}
