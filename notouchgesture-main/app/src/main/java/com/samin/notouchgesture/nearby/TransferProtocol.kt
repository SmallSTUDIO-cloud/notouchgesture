package com.samin.notouchgesture.nearby

sealed interface TransferMessage {
    data class Offer(val fileName: String, val byteCount: Long) : TransferMessage
    data class Accept(val fileName: String) : TransferMessage
    data class Reject(val fileName: String, val reason: String) : TransferMessage
    data class Ping(val value: String = "hello") : TransferMessage
}

object TransferProtocol {
    private const val SEP = "|"

    fun encode(message: TransferMessage): ByteArray {
        val line = when (message) {
            is TransferMessage.Offer -> "OFFER$SEP${sanitize(message.fileName)}$SEP${message.byteCount}"
            is TransferMessage.Accept -> "ACCEPT$SEP${sanitize(message.fileName)}"
            is TransferMessage.Reject -> "REJECT$SEP${sanitize(message.fileName)}$SEP${sanitize(message.reason)}"
            is TransferMessage.Ping -> "PING$SEP${sanitize(message.value)}"
        }
        return line.toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): TransferMessage? {
        val raw = bytes.toString(Charsets.UTF_8)
        val parts = raw.split(SEP)
        return when (parts.firstOrNull()) {
            "OFFER" -> {
                val size = parts.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0L } ?: return null
                parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { TransferMessage.Offer(it, size) }
            }
            "ACCEPT" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(TransferMessage::Accept)
            "REJECT" -> {
                val file = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
                val reason = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "Rejected"
                TransferMessage.Reject(file, reason)
            }
            "PING" -> TransferMessage.Ping(parts.drop(1).joinToString(SEP))
            else -> null
        }
    }

    private fun sanitize(value: String): String = value.replace("|", "_").replace("\n", " ").replace("\r", " ").trim().take(240)
}
