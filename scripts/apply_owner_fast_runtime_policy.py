from pathlib import Path

BRIDGE = Path("app/src/main/java/io/piga/phonebridge/BridgeService.kt")
GRADLE = Path("app/build.gradle.kts")

text = BRIDGE.read_text(encoding="utf-8")

if "private val commandPollIdleMs = 3_000L" not in text:
    constants_anchor = '    private val failoverContractVersionCode = "19"\n'
    constants = '''    private val failoverContractVersionCode = "19"\n    // Owner fast-runtime policy: faster command pickup without multiplying idle work.\n    private val commandPollIdleMs = 3_000L\n    private val commandPollBusyMs = 750L\n    private val unpairedPollMs = 15_000L\n    private val safetySyncIntervalMs = 60_000L\n    private val failoverPresenceIntervalMs = 300_000L\n    private val initialErrorBackoffMs = 2_000L\n    private val maxErrorBackoffMs = 30_000L\n    private val runtimeStatusPersistIntervalMs = 15_000L\n    private val runtimeNotificationIntervalMs = 30_000L\n'''
    if constants_anchor not in text:
        raise SystemExit("Bridge constants anchor missing; refusing broad rewrite")
    text = text.replace(constants_anchor, constants, 1)

old_poll = '''    private fun pollLoop() {\n        while (running.get()) {\n            try {\n                if (!prefs.getBoolean("paired", false)) {\n                    Thread.sleep(5000)\n                    continue\n                }\n                val root = prefs.getString("base_url", null)?.trim()?.removeSuffix("/")\n                    ?: throw IllegalStateException("Missing bridge base URL")\n                val deviceId = prefs.getString("device_id", null)\n                    ?: throw IllegalStateException("Missing device id")\n                val pairingId = prefs.getString("pairing_id", null)?.trim().orEmpty()\n\n                tryFailoverPresence(deviceId)\n                if (pairingId.isBlank()) {\n                    prefs.edit().putLong("last_poll_ms", System.currentTimeMillis()).putString("runtime_status", "PRESENCE_ONLY").apply()\n                    updateNotification("PIGA Bridge presence-only • commands blocked")\n                    Thread.sleep(15000)\n                    continue\n                }\n                syncSafety(root, deviceId, pairingId)\n                retryPendingResults(root, deviceId, pairingId)\n\n                val canonicalPath = "/api/bridge/devices/$deviceId/commands"\n                val response = signedRuntimeGet("$root$canonicalPath", canonicalPath, pairingId)\n                val commands = response.optJSONArray("commands")\n                val count = commands?.length() ?: 0\n                if (commands != null) {\n                    for (i in 0 until commands.length()) {\n                        processCommand(root, deviceId, pairingId, commands.getJSONObject(i))\n                    }\n                }\n\n                prefs.edit()\n                    .putLong("last_poll_ms", System.currentTimeMillis())\n                    .putString("runtime_status", "ONLINE commands=$count")\n                    .apply()\n                updateNotification("PIGA Bridge online • pending $count")\n            } catch (e: Exception) {\n                prefs.edit().putString("runtime_status", "ERROR ${e.message ?: e.javaClass.simpleName}").apply()\n                updateNotification("PIGA Bridge reconnecting")\n            }\n            try {\n                Thread.sleep(15000)\n            } catch (_: InterruptedException) {\n                running.set(false)\n            }\n        }\n    }\n'''

new_poll = '''    private fun pollLoop() {\n        var errorBackoffMs = initialErrorBackoffMs\n        while (running.get()) {\n            var nextSleepMs = commandPollIdleMs\n            try {\n                if (!prefs.getBoolean("paired", false)) {\n                    nextSleepMs = unpairedPollMs\n                    recordRuntimeStatus("UNPAIRED", force = false)\n                    updateRuntimeNotification("PIGA Bridge waiting for pairing")\n                } else {\n                    val root = prefs.getString("base_url", null)?.trim()?.removeSuffix("/")\n                        ?: throw IllegalStateException("Missing bridge base URL")\n                    val deviceId = prefs.getString("device_id", null)\n                        ?: throw IllegalStateException("Missing device id")\n                    val pairingId = prefs.getString("pairing_id", null)?.trim().orEmpty()\n                    val nowMs = System.currentTimeMillis()\n\n                    if (nowMs - prefs.getLong("last_failover_presence_attempt_ms", 0L) >= failoverPresenceIntervalMs) {\n                        prefs.edit().putLong("last_failover_presence_attempt_ms", nowMs).apply()\n                        tryFailoverPresence(deviceId)\n                    }\n\n                    if (pairingId.isBlank()) {\n                        recordRuntimeStatus("PRESENCE_ONLY", force = false)\n                        updateRuntimeNotification("PIGA Bridge presence-only • commands blocked")\n                        nextSleepMs = unpairedPollMs\n                    } else {\n                        if (shouldSyncSafety()) syncSafety(root, deviceId, pairingId)\n                        retryPendingResults(root, deviceId, pairingId)\n\n                        val canonicalPath = "/api/bridge/devices/$deviceId/commands"\n                        val response = signedRuntimeGet("$root$canonicalPath", canonicalPath, pairingId)\n                        val commands = response.optJSONArray("commands")\n                        val count = commands?.length() ?: 0\n                        if (commands != null) {\n                            for (i in 0 until commands.length()) {\n                                processCommand(root, deviceId, pairingId, commands.getJSONObject(i))\n                            }\n                        }\n\n                        recordRuntimeStatus("ONLINE commands=$count", force = count > 0)\n                        updateRuntimeNotification("PIGA Bridge online • pending $count")\n                        nextSleepMs = if (count > 0) commandPollBusyMs else commandPollIdleMs\n                        errorBackoffMs = initialErrorBackoffMs\n                    }\n                }\n            } catch (e: Exception) {\n                recordRuntimeStatus("ERROR ${e.message ?: e.javaClass.simpleName}", force = true)\n                updateRuntimeNotification("PIGA Bridge reconnecting", force = true)\n                nextSleepMs = errorBackoffMs\n                errorBackoffMs = (errorBackoffMs * 2L).coerceAtMost(maxErrorBackoffMs)\n            }\n\n            try {\n                Thread.sleep(nextSleepMs)\n            } catch (_: InterruptedException) {\n                running.set(false)\n                Thread.currentThread().interrupt()\n            }\n        }\n    }\n\n    private fun recordRuntimeStatus(status: String, force: Boolean) {\n        val now = System.currentTimeMillis()\n        val lastWrite = prefs.getLong("last_runtime_status_persist_ms", 0L)\n        val previous = prefs.getString("runtime_status", null)\n        if (!force && previous == status && now - lastWrite < runtimeStatusPersistIntervalMs) return\n        prefs.edit()\n            .putLong("last_poll_ms", now)\n            .putLong("last_runtime_status_persist_ms", now)\n            .putString("runtime_status", status)\n            .apply()\n    }\n\n    private fun updateRuntimeNotification(text: String, force: Boolean = false) {\n        val now = System.currentTimeMillis()\n        val previous = prefs.getString("last_runtime_notification_text", null)\n        val lastUpdate = prefs.getLong("last_runtime_notification_ms", 0L)\n        if (!force && previous == text && now - lastUpdate < runtimeNotificationIntervalMs) return\n        updateNotification(text)\n        prefs.edit()\n            .putString("last_runtime_notification_text", text)\n            .putLong("last_runtime_notification_ms", now)\n            .apply()\n    }\n\n    private fun safetyFingerprint(masterAutonomy: Boolean, emergencyStop: Boolean, notificationPermission: Boolean): String =\n        "$masterAutonomy:$emergencyStop:$notificationPermission"\n\n    private fun shouldSyncSafety(): Boolean {\n        val masterAutonomy = prefs.getBoolean("master_autonomy", false)\n        val emergencyStop = prefs.getBoolean("emergency_stop", false)\n        val notificationPermission = hasNotificationPermission()\n        val fingerprint = safetyFingerprint(masterAutonomy, emergencyStop, notificationPermission)\n        val previous = prefs.getString("last_safety_fingerprint", null)\n        val lastSync = prefs.getLong("last_safety_sync_ms", 0L)\n        return previous != fingerprint || System.currentTimeMillis() - lastSync >= safetySyncIntervalMs\n    }\n'''

if old_poll in text:
    text = text.replace(old_poll, new_poll, 1)
elif "private fun recordRuntimeStatus(status: String, force: Boolean)" not in text:
    raise SystemExit("pollLoop anchor changed; refusing broad rewrite")

old_safety_tail = '''        prefs.edit()\n            .putBoolean("notification_permission", notificationPermission)\n            .putLong("last_safety_sync_ms", System.currentTimeMillis())\n            .apply()\n'''
new_safety_tail = '''        prefs.edit()\n            .putBoolean("notification_permission", notificationPermission)\n            .putString("last_safety_fingerprint", safetyFingerprint(masterAutonomy, emergencyStop, notificationPermission))\n            .putLong("last_safety_sync_ms", System.currentTimeMillis())\n            .apply()\n'''
if old_safety_tail in text:
    text = text.replace(old_safety_tail, new_safety_tail, 1)
elif 'putString("last_safety_fingerprint"' not in text:
    raise SystemExit("syncSafety anchor changed; refusing broad rewrite")

# Runtime proxy returns within 7 seconds and its persistent inner store within 5.
# Bound the Android socket waits just above that envelope instead of stalling 15 seconds.
text = text.replace("            connectTimeout = 15000\n            readTimeout = 15000\n", "            connectTimeout = 8000\n            readTimeout = 9000\n")

BRIDGE.write_text(text, encoding="utf-8")

gradle = GRADLE.read_text(encoding="utf-8")
if 'versionCode = 23' in gradle and 'versionName = "1.0.6"' in gradle:
    gradle = gradle.replace('versionCode = 23', 'versionCode = 24', 1)
    gradle = gradle.replace('versionName = "1.0.6"', 'versionName = "1.0.7"', 1)
elif not ('versionCode = 24' in gradle and 'versionName = "1.0.7"' in gradle):
    raise SystemExit("version anchor changed; refusing implicit version rewrite")
GRADLE.write_text(gradle, encoding="utf-8")

required = [
    "commandPollIdleMs = 3_000L",
    "safetySyncIntervalMs = 60_000L",
    "failoverPresenceIntervalMs = 300_000L",
    "last_failover_presence_attempt_ms",
    "last_safety_fingerprint",
    "errorBackoffMs = (errorBackoffMs * 2L).coerceAtMost(maxErrorBackoffMs)",
    "connectTimeout = 8000",
    "readTimeout = 9000",
]
for needle in required:
    if needle not in text:
        raise SystemExit(f"required fast-runtime invariant missing: {needle}")

print("PIGA owner fast-runtime policy applied: command=3s idle/0.75s drain, safety=60s/change-driven, presence=5m, exponential failure backoff, throttled local writes.")
