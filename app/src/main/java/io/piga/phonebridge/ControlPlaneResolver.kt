package io.piga.phonebridge

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object ControlPlaneResolver {
    const val CANONICAL_CONTROL_PLANE = "https://pigapocket.com"

    private const val DISCOVERY_URL =
        "https://raw.githubusercontent.com/mounirakarkach04-pixel/piga-phone-bridge-android/main/control-plane.json"
    private const val CACHE_MS = 300_000L

    @Volatile private var cachedUrl: String? = null
    @Volatile private var cachedAtMs: Long = 0L

    @Synchronized
    fun resolve(fallback: String? = null): String {
        val now = System.currentTimeMillis()
        val cached = cachedUrl
        if (!cached.isNullOrBlank() && now - cachedAtMs < CACHE_MS) return cached

        val discovered = try {
            fetchCanonicalUrl()
        } catch (_: Exception) {
            null
        }

        val candidate = when {
            !discovered.isNullOrBlank() -> discovered
            !fallback.isNullOrBlank() -> fallback
            else -> CANONICAL_CONTROL_PLANE
        }

        val validated = validateEndpoint(candidate)
        cachedUrl = validated
        cachedAtMs = now
        return validated
    }

    private fun fetchCanonicalUrl(): String {
        val connection = (URL(DISCOVERY_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("Discovery HTTP $code")

        val json = JSONObject(text)
        require(json.optString("schema") == "piga.control-plane-discovery.v1") {
            "Unsupported discovery schema"
        }
        require(json.optInt("epoch", 0) > 0) { "Invalid discovery epoch" }
        val governance = json.optJSONObject("governance")
            ?: throw IllegalStateException("Discovery governance missing")
        require(governance.optString("mode") == "fail-closed") {
            "Discovery must be fail-closed"
        }
        require(governance.optBoolean("materialChangeRequiresReEntry", false)) {
            "Re-entry invariant missing"
        }

        return validateEndpoint(json.getString("controlPlaneUrl"))
    }

    internal fun validateEndpoint(raw: String): String {
        val normalized = raw.trim().removeSuffix("/")
        val uri = URI(normalized)
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Control plane must use HTTPS"
        }
        require(uri.host.equals("pigapocket.com", ignoreCase = true)) {
            "Untrusted control-plane host"
        }
        require(uri.port == -1) { "Non-default control-plane port forbidden" }
        require(uri.userInfo == null) { "Control-plane credentials forbidden" }
        require(uri.path.isNullOrEmpty()) { "Control-plane URL must not contain a path" }
        require(uri.query == null && uri.fragment == null) {
            "Control-plane URL must be canonical"
        }
        require(normalized == CANONICAL_CONTROL_PLANE) {
            "Control-plane origin mismatch"
        }
        return normalized
    }
}
