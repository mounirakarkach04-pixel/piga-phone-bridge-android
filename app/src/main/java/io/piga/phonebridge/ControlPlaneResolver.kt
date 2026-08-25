package io.piga.phonebridge

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object ControlPlaneResolver {
    private const val discoveryUrl = "https://raw.githubusercontent.com/mounirakarkach04-pixel/piga-phone-bridge-android/main/control-plane.json"
    private const val cacheMs = 300_000L

    @Volatile private var cachedUrl: String? = null
    @Volatile private var cachedAtMs: Long = 0L

    @Synchronized
    fun resolve(fallback: String? = null): String {
        val now = System.currentTimeMillis()
        val cached = cachedUrl
        if (!cached.isNullOrBlank() && now - cachedAtMs < cacheMs) return cached

        val discovered = try {
            fetchCanonicalUrl()
        } catch (_: Exception) {
            null
        }

        if (!discovered.isNullOrBlank()) {
            cachedUrl = discovered
            cachedAtMs = now
            return discovered
        }

        val validatedFallback = fallback?.trim()?.removeSuffix("/")?.takeIf { it.isNotBlank() }
            ?.let(::validateEndpoint)
        if (validatedFallback != null) return validatedFallback

        throw IllegalStateException("Canonical control-plane discovery unavailable and no valid fallback exists")
    }

    private fun fetchCanonicalUrl(): String {
        val connection = (URL(discoveryUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("Discovery HTTP $code")

        val json = JSONObject(text)
        require(json.optString("schema") == "piga.control-plane-discovery.v1") { "Unsupported discovery schema" }
        require(json.optInt("epoch", 0) > 0) { "Invalid discovery epoch" }
        val governance = json.optJSONObject("governance")
            ?: throw IllegalStateException("Discovery governance missing")
        require(governance.optString("mode") == "fail-closed") { "Discovery must be fail-closed" }
        require(governance.optBoolean("materialChangeRequiresReEntry", false)) { "Re-entry invariant missing" }

        return validateEndpoint(json.getString("controlPlaneUrl"))
    }

    private fun validateEndpoint(raw: String): String {
        val normalized = raw.trim().removeSuffix("/")
        val uri = URI(normalized)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Control plane must use HTTPS" }
        require(!uri.host.isNullOrBlank()) { "Control plane host missing" }
        require(uri.userInfo == null) { "Control plane credentials forbidden" }
        require(uri.query == null && uri.fragment == null) { "Control plane URL must be canonical" }
        return normalized
    }
}
