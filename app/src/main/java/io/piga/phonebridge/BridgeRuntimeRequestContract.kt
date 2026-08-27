package io.piga.phonebridge

/**
 * Canonical request contract shared with the authoritative Enterprise bridge API.
 * Counters are monotonic and persisted before use, so a crash may skip a value
 * but can never intentionally reuse one.
 */
internal object BridgeRuntimeRequestContract {
    const val BRIDGE_VERSION = "0.2.0"
    const val BRIDGE_VERSION_CODE = 19
    private const val MAX_SAFE_COUNTER = 9_007_199_254_740_991L

    fun canonicalPath(apiPath: String): String {
        require(apiPath.startsWith("/api/")) { "Bridge API path must start with /api/." }
        return apiPath.removePrefix("/api")
    }

    fun canonicalMessage(
        method: String,
        apiPath: String,
        timestampMs: String,
        counter: String,
        requestId: String,
        body: String,
        controlPlaneOrigin: String,
    ): String = listOf(
        method.uppercase(),
        canonicalPath(apiPath),
        timestampMs,
        counter,
        requestId,
        body,
        controlPlaneOrigin,
    ).joinToString("\n")

    fun nextCounter(current: Long): Long {
        require(current >= 0L) { "Runtime counter cannot be negative." }
        require(current < MAX_SAFE_COUNTER) { "Runtime counter exhausted." }
        return current + 1L
    }
}
