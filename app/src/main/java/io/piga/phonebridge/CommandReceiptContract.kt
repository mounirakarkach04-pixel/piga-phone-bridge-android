package io.piga.phonebridge

/**
 * Pure contract helpers for the command ACK/result lifecycle.
 * Keeping endpoint construction outside Android/service code makes the
 * signed canonical path deterministic and directly unit-testable.
 */
object CommandReceiptContract {
    fun ackPath(deviceId: String, commandId: String): String =
        "/api/bridge/devices/$deviceId/commands/$commandId/ack"

    fun resultPath(deviceId: String, commandId: String): String =
        "/api/bridge/devices/$deviceId/commands/$commandId/result"

    data class PendingResult(
        val commandId: String,
        val commandNonce: String,
        val status: String,
        val detail: String,
        val createdAtMs: Long
    )

    fun outboxKey(commandId: String): String = "pending_command_result_$commandId"

    fun encodePendingResult(result: PendingResult): String = listOf(
        result.commandId,
        result.commandNonce,
        result.status,
        result.createdAtMs.toString(),
        result.detail
    ).joinToString("\n") { escape(it) }

    fun decodePendingResult(encoded: String): PendingResult? {
        val parts = splitEscaped(encoded)
        if (parts.size != 5) return null
        val createdAt = parts[3].toLongOrNull() ?: return null
        return PendingResult(
            commandId = parts[0],
            commandNonce = parts[1],
            status = parts[2],
            createdAtMs = createdAt,
            detail = parts[4]
        )
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")

    private fun splitEscaped(value: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var escaping = false
        for (ch in value) {
            if (escaping) {
                when (ch) {
                    'n' -> current.append('\n')
                    '\\' -> current.append('\\')
                    else -> {
                        current.append('\\')
                        current.append(ch)
                    }
                }
                escaping = false
            } else if (ch == '\\') {
                escaping = true
            } else if (ch == '\n') {
                result += current.toString()
                current.setLength(0)
            } else {
                current.append(ch)
            }
        }
        if (escaping) current.append('\\')
        result += current.toString()
        return result
    }
}
