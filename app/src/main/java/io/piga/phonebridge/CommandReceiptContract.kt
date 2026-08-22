package io.piga.phonebridge

/**
 * Pure contract helpers for the command ACK/result lifecycle.
 *
 * Factory correlation is carried end-to-end when present, while decode remains
 * compatible with the older five-field pending-result format so an app update
 * cannot strand an existing local outbox entry.
 */
object CommandReceiptContract {
    fun ackPath(deviceId: String, commandId: String): String =
        "/api/bridge/devices/$deviceId/commands/$commandId/ack"

    fun resultPath(deviceId: String, commandId: String): String =
        "/api/bridge/devices/$deviceId/commands/$commandId/result"

    data class FactoryCorrelation(
        val jobId: String,
        val subjobId: String,
        val verifiedPlanHash: String,
        val actionSpecHash: String,
        val expectedCommandId: String
    )

    data class PendingResult(
        val commandId: String,
        val commandNonce: String,
        val status: String,
        val detail: String,
        val createdAtMs: Long,
        val jobId: String? = null,
        val subjobId: String? = null,
        val verifiedPlanHash: String? = null
    )

    fun outboxKey(commandId: String): String = "pending_command_result_$commandId"

    fun encodePendingResult(result: PendingResult): String = listOf(
        result.commandId,
        result.commandNonce,
        result.status,
        result.createdAtMs.toString(),
        result.jobId.orEmpty(),
        result.subjobId.orEmpty(),
        result.verifiedPlanHash.orEmpty(),
        result.detail
    ).joinToString("\n") { escape(it) }

    fun decodePendingResult(encoded: String): PendingResult? {
        val parts = splitEscaped(encoded)
        return when (parts.size) {
            5 -> {
                val createdAt = parts[3].toLongOrNull() ?: return null
                PendingResult(
                    commandId = parts[0],
                    commandNonce = parts[1],
                    status = parts[2],
                    createdAtMs = createdAt,
                    detail = parts[4]
                )
            }

            8 -> {
                val createdAt = parts[3].toLongOrNull() ?: return null
                PendingResult(
                    commandId = parts[0],
                    commandNonce = parts[1],
                    status = parts[2],
                    createdAtMs = createdAt,
                    jobId = parts[4].ifBlank { null },
                    subjobId = parts[5].ifBlank { null },
                    verifiedPlanHash = parts[6].ifBlank { null },
                    detail = parts[7]
                )
            }

            else -> null
        }
    }

    fun parseFactoryCorrelation(
        commandId: String,
        factoryContext: org.json.JSONObject?
    ): FactoryCorrelation? {
        if (factoryContext == null) return null

        val jobId = factoryContext.optString("jobId").trim()
        val subjobId = factoryContext.optString("subjobId").trim()
        val verifiedPlanHash = factoryContext.optString("verifiedPlanHash").trim().lowercase()
        val actionSpecHash = factoryContext.optString("actionSpecHash").trim().lowercase()
        val expectedCommandId = factoryContext.optString("expectedCommandId").trim()

        require(jobId.isNotBlank()) { "Factory jobId missing." }
        require(subjobId.isNotBlank()) { "Factory subjobId missing." }
        require(verifiedPlanHash.matches(Regex("^[0-9a-f]{64}$"))) {
            "Factory verifiedPlanHash invalid."
        }
        require(actionSpecHash.matches(Regex("^[0-9a-f]{64}$"))) {
            "Factory actionSpecHash invalid."
        }
        require(expectedCommandId.isNotBlank()) { "Factory expectedCommandId missing." }
        require(expectedCommandId == commandId) { "Factory command correlation mismatch." }

        return FactoryCorrelation(
            jobId = jobId,
            subjobId = subjobId,
            verifiedPlanHash = verifiedPlanHash,
            actionSpecHash = actionSpecHash,
            expectedCommandId = expectedCommandId
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
