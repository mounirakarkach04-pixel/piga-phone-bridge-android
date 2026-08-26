package io.piga.phonebridge

/**
 * Durable marker written immediately after the authoritative effect commit and
 * immediately before a command is allowed to cross the local execution boundary.
 * If the process dies after this marker exists but before a terminal result is
 * persisted, replay is unsafe and must become UNCERTAIN instead of executing
 * the capability again.
 */
internal object CommandExecutionJournal {
    data class Entry(
        val commandId: String,
        val commandNonce: String,
        val startedAtMs: Long,
        val jobId: String? = null,
        val subjobId: String? = null,
        val verifiedPlanHash: String? = null,
        val effectId: String? = null,
        val effectNonce: String? = null
    )

    const val KEY_PREFIX = "command_effect_started_"

    fun key(commandId: String): String = "$KEY_PREFIX$commandId"

    fun encode(entry: Entry): String = listOf(
        entry.commandId,
        entry.commandNonce,
        entry.startedAtMs.toString(),
        entry.jobId.orEmpty(),
        entry.subjobId.orEmpty(),
        entry.verifiedPlanHash.orEmpty(),
        entry.effectId.orEmpty(),
        entry.effectNonce.orEmpty()
    ).joinToString("\n") { escape(it) }

    fun decode(encoded: String): Entry? {
        val parts = splitEscaped(encoded)
        if (parts.size != 6 && parts.size != 8) return null
        val startedAt = parts[2].toLongOrNull() ?: return null
        if (parts[0].isBlank() || parts[1].isBlank()) return null
        return Entry(
            commandId = parts[0],
            commandNonce = parts[1],
            startedAtMs = startedAt,
            jobId = parts[3].ifBlank { null },
            subjobId = parts[4].ifBlank { null },
            verifiedPlanHash = parts[5].ifBlank { null },
            effectId = parts.getOrNull(6)?.ifBlank { null },
            effectNonce = parts.getOrNull(7)?.ifBlank { null }
        )
    }

    fun uncertainResult(entry: Entry): CommandReceiptContract.PendingResult =
        CommandReceiptContract.PendingResult(
            commandId = entry.commandId,
            commandNonce = entry.commandNonce,
            status = "uncertain",
            detail = "Device effect may have started before the previous runtime stopped. Automatic replay blocked pending target-state reconciliation.",
            createdAtMs = System.currentTimeMillis(),
            jobId = entry.jobId,
            subjobId = entry.subjobId,
            verifiedPlanHash = entry.verifiedPlanHash,
            effectId = entry.effectId,
            effectNonce = entry.effectNonce
        )

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
