package io.piga.phonebridge

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Instant

/**
 * Governed APK installation core.
 *
 * Integrity admission is identical for both modes. Silent installation is admitted only
 * when this app is a verified Android Device Owner. Existing consumer devices instead use
 * Android's user-confirmed PackageInstaller flow; pending user action is never success.
 */
class GovernedManagedInstaller(private val context: Context) {
    enum class InstallMode {
        MANAGED_SILENT,
        USER_CONFIRMED,
    }

    data class Admission(
        val admissionId: String,
        val sourceUrl: String,
        val sha256: String,
        val packageName: String,
        val versionCode: Long,
        val signerSha256: String,
        val nonce: String,
        val expiresAt: Instant,
        val mode: InstallMode = InstallMode.USER_CONFIRMED,
    )

    data class Preflight(
        val admitted: Boolean,
        val reason: String,
        val observedPackageName: String? = null,
        val observedVersionCode: Long? = null,
        val observedSha256: String? = null,
        val observedSignerSha256: String? = null,
    )

    fun isVerifiedDeviceOwner(): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun canUseUserConfirmedInstaller(): Boolean {
        return Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    }

    fun preflight(apk: File, admission: Admission, now: Instant = Instant.now()): Preflight {
        if (admission.mode == InstallMode.MANAGED_SILENT && !isVerifiedDeviceOwner()) {
            return Preflight(false, "DEVICE_OWNER_REQUIRED_FOR_SILENT_INSTALL")
        }
        if (admission.mode == InstallMode.USER_CONFIRMED && !canUseUserConfirmedInstaller()) {
            return Preflight(false, "USER_INSTALL_PERMISSION_REQUIRED")
        }
        if (admission.expiresAt <= now) return Preflight(false, "ADMISSION_EXPIRED")
        if (admission.admissionId.isBlank() || admission.nonce.length < 16) return Preflight(false, "ADMISSION_IDENTITY_INVALID")
        if (!admission.sourceUrl.startsWith("https://")) return Preflight(false, "HTTPS_ARTIFACT_REQUIRED")
        if (!admission.sha256.matches(Regex("^[0-9a-fA-F]{64}$"))) return Preflight(false, "SHA256_INVALID")
        if (!admission.signerSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) return Preflight(false, "SIGNER_SHA256_INVALID")
        if (!admission.packageName.matches(Regex("^[A-Za-z0-9_.]{3,200}$"))) return Preflight(false, "PACKAGE_NAME_INVALID")
        if (!apk.isFile || !apk.canRead() || apk.length() <= 0L) return Preflight(false, "APK_FILE_UNAVAILABLE")

        val observedSha = sha256(apk)
        if (!observedSha.equals(admission.sha256, ignoreCase = true)) {
            return Preflight(false, "APK_HASH_MISMATCH", observedSha256 = observedSha)
        }

        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } ?: return Preflight(false, "APK_PARSE_FAILED", observedSha256 = observedSha)

        val observedPackage = packageInfo.packageName
        val observedVersion = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val signers = if (Build.VERSION.SDK_INT >= 28) packageInfo.signingInfo?.apkContentsSigners else null
        if (signers.isNullOrEmpty()) {
            return Preflight(false, "APK_SIGNER_MISSING", observedPackage, observedVersion, observedSha)
        }
        val observedSigner = MessageDigest.getInstance("SHA-256")
            .digest(signers[0].toByteArray())
            .joinToString("") { "%02x".format(it) }

        if (observedPackage != admission.packageName) return Preflight(false, "PACKAGE_MISMATCH", observedPackage, observedVersion, observedSha, observedSigner)
        if (observedVersion != admission.versionCode) return Preflight(false, "VERSION_MISMATCH", observedPackage, observedVersion, observedSha, observedSigner)
        if (!observedSigner.equals(admission.signerSha256, ignoreCase = true)) return Preflight(false, "SIGNER_MISMATCH", observedPackage, observedVersion, observedSha, observedSigner)

        return Preflight(true, if (admission.mode == InstallMode.MANAGED_SILENT) "ADMITTED_MANAGED_SILENT" else "ADMITTED_USER_CONFIRMED", observedPackage, observedVersion, observedSha, observedSigner)
    }

    fun createAndCommitSession(apk: File, admission: Admission): Int {
        val preflight = preflight(apk, admission)
        require(preflight.admitted) { "Governed install blocked: ${preflight.reason}" }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(admission.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(
                    if (admission.mode == InstallMode.MANAGED_SILENT) {
                        PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    } else {
                        PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                    }
                )
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            FileInputStream(apk).use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, ManagedInstallResultReceiver::class.java).apply {
                action = ManagedInstallResultReceiver.ACTION
                putExtra(ManagedInstallResultReceiver.EXTRA_ADMISSION_ID, admission.admissionId)
                putExtra(ManagedInstallResultReceiver.EXTRA_PACKAGE_NAME, admission.packageName)
                putExtra(ManagedInstallResultReceiver.EXTRA_NONCE, admission.nonce)
                putExtra(ManagedInstallResultReceiver.EXTRA_SESSION_ID, sessionId)
                putExtra(ManagedInstallResultReceiver.EXTRA_INSTALL_MODE, admission.mode.name)
            }
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
        return sessionId
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
