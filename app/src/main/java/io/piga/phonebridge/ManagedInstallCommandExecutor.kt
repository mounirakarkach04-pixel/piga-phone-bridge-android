package io.piga.phonebridge

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

/**
 * Executes a single already-admitted governed install command.
 *
 * This class does not grant authority. It enforces a second local fail-closed boundary:
 * exact payload schema, allowlisted HTTPS origins, bounded download size, expiry, and the
 * integrity/signing checks in [GovernedManagedInstaller]. Android user confirmation remains
 * an OS-owned step for USER_CONFIRMED mode and is never counted as success here.
 */
class ManagedInstallCommandExecutor(private val context: Context) {
    private val installer = GovernedManagedInstaller(context)

    data class StartResult(
        val sessionId: Int,
        val mode: GovernedManagedInstaller.InstallMode,
        val detail: String,
    )

    fun execute(commandId: String, payload: JSONObject): StartResult {
        require(commandId.isNotBlank()) { "Managed install command id missing." }
        val sourceUrl = payload.requiredString("sourceUrl", 2048)
        val sha256 = payload.requiredLowerHex("sha256")
        val packageName = payload.requiredString("packageName", 200)
        val versionCode = payload.optLong("versionCode", -1L)
        val signerSha256 = payload.requiredLowerHex("signerSha256")
        val installNonce = payload.requiredString("installNonce", 128)
        val expiresAt = Instant.parse(payload.requiredString("expiresAt", 64))
        val installMode = GovernedManagedInstaller.InstallMode.valueOf(
            payload.requiredString("installMode", 32)
        )

        require(packageName.matches(Regex("^[A-Za-z0-9_.]{3,200}$"))) { "Managed install package invalid." }
        require(versionCode > 0L) { "Managed install version invalid." }
        require(installNonce.matches(Regex("^[A-Za-z0-9_-]{16,128}$"))) { "Managed install nonce invalid." }
        require(expiresAt.isAfter(Instant.now())) { "Managed install admission expired." }
        require(payload.optBoolean("automaticConsequentialRetry", true).not()) {
            "Automatic consequential retry must remain disabled."
        }
        require(payload.optBoolean("a7semReverseRequired", false)) {
            "A7SEM Reverse evidence requirement missing."
        }

        val admission = GovernedManagedInstaller.Admission(
            admissionId = commandId,
            sourceUrl = sourceUrl,
            sha256 = sha256,
            packageName = packageName,
            versionCode = versionCode,
            signerSha256 = signerSha256,
            nonce = installNonce,
            expiresAt = expiresAt,
            mode = installMode,
        )

        val apk = downloadBoundedApk(sourceUrl, commandId)
        try {
            val preflight = installer.preflight(apk, admission)
            require(preflight.admitted) { "Governed install blocked: ${preflight.reason}" }
            val sessionId = installer.createAndCommitSession(apk, admission)
            return StartResult(
                sessionId = sessionId,
                mode = installMode,
                detail = if (installMode == GovernedManagedInstaller.InstallMode.MANAGED_SILENT) {
                    "PackageInstaller session committed under verified Device Owner mode. Final PackageInstaller receipt required."
                } else {
                    "PackageInstaller session committed; Android user confirmation may still be required. Pending user action is not success."
                },
            )
        } finally {
            apk.delete()
        }
    }

    private fun downloadBoundedApk(rawUrl: String, commandId: String): File {
        val dir = File(context.cacheDir, "piga-managed-install").apply { mkdirs() }
        val output = File(dir, "$commandId.apk")
        var current = validateArtifactUrl(rawUrl)

        repeat(MAX_REDIRECTS + 1) { hop ->
            val connection = (current.toURL().openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.android.package-archive,application/octet-stream")
                setRequestProperty("User-Agent", "PIGA-Phone-Bridge/${BuildConfig.VERSION_NAME}")
            }
            try {
                when (val code = connection.responseCode) {
                    in 200..299 -> {
                        val declared = connection.contentLengthLong
                        require(declared <= MAX_APK_BYTES || declared < 0L) { "APK exceeds bounded size." }
                        var written = 0L
                        connection.inputStream.use { input ->
                            output.outputStream().use { sink ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    written += read
                                    require(written <= MAX_APK_BYTES) { "APK exceeds bounded size." }
                                    sink.write(buffer, 0, read)
                                }
                                sink.flush()
                            }
                        }
                        require(written > 0L) { "APK download was empty." }
                        return output
                    }
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308 -> {
                        require(hop < MAX_REDIRECTS) { "Too many artifact redirects." }
                        val location = connection.getHeaderField("Location")?.trim().orEmpty()
                        require(location.isNotBlank()) { "Artifact redirect missing location." }
                        current = validateArtifactUrl(current.resolve(location).toString())
                    }
                    else -> throw IllegalStateException("Artifact download HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Artifact download failed.")
    }

    private fun validateArtifactUrl(raw: String): URI {
        val uri = URI(raw)
        require(uri.scheme.equals("https", ignoreCase = true)) { "HTTPS artifact required." }
        require(uri.userInfo == null && uri.fragment == null) { "Artifact URL contains forbidden components." }
        val host = uri.host?.lowercase().orEmpty()
        require(host in ALLOWED_HOSTS) { "Artifact origin not allowlisted." }
        return uri
    }

    private fun JSONObject.requiredString(name: String, max: Int): String {
        val value = optString(name).trim()
        require(value.isNotBlank() && value.length <= max) { "$name missing or invalid." }
        return value
    }

    private fun JSONObject.requiredLowerHex(name: String): String {
        val value = requiredString(name, 64).lowercase()
        require(value.matches(Regex("^[a-f0-9]{64}$"))) { "$name invalid." }
        return value
    }

    companion object {
        private const val MAX_APK_BYTES = 64L * 1024L * 1024L
        private const val MAX_REDIRECTS = 3
        private val ALLOWED_HOSTS = setOf(
            "github.com",
            "api.github.com",
            "objects.githubusercontent.com",
            "qjvopzschqukitvudgfz.supabase.co",
        )
    }
}
