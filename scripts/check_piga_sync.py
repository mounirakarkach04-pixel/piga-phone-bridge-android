from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "PIGA_PHONE_ORCHESTRATION_V1.json"
DISCOVERY = ROOT / "control-plane.json"
GRADLE = ROOT / "app/build.gradle.kts"
BRIDGE = ROOT / "app/src/main/java/io/piga/phonebridge/BridgeService.kt"
VERIFIER = ROOT / "app/src/main/java/io/piga/phonebridge/OrchestrationPlanVerifier.kt"
RECOVERY = ROOT / "app/src/main/java/io/piga/phonebridge/BridgeRecoveryWorker.kt"
SCHEDULER = ROOT / "app/src/main/java/io/piga/phonebridge/BridgeRecoveryScheduler.kt"
BOOT = ROOT / "app/src/main/java/io/piga/phonebridge/BootReceiver.kt"
APP = ROOT / "app/src/main/java/io/piga/phonebridge/PigaBridgeApp.kt"
MAIN = ROOT / "app/src/main/java/io/piga/phonebridge/MainActivity.kt"
RESOLVER = ROOT / "app/src/main/java/io/piga/phonebridge/ControlPlaneResolver.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
WAKE = ROOT / ".github/workflows/continuous-governance-wake.yml"


def main() -> int:
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    discovery = json.loads(DISCOVERY.read_text(encoding="utf-8"))
    gradle = GRADLE.read_text(encoding="utf-8")
    bridge = BRIDGE.read_text(encoding="utf-8")
    verifier = VERIFIER.read_text(encoding="utf-8")
    recovery = RECOVERY.read_text(encoding="utf-8")
    scheduler = SCHEDULER.read_text(encoding="utf-8")
    boot = BOOT.read_text(encoding="utf-8")
    app = APP.read_text(encoding="utf-8")
    main = MAIN.read_text(encoding="utf-8")
    resolver = RESOLVER.read_text(encoding="utf-8")
    manifest = MANIFEST.read_text(encoding="utf-8")
    wake = WAKE.read_text(encoding="utf-8")

    assert contract["controlPlane"]["gearboxVersion"] == "1.6"
    assert contract["controlPlane"]["canonicalOrigin"] == "https://pigapocket.com"
    assert discovery["controlPlaneUrl"] == contract["controlPlane"]["canonicalOrigin"]
    assert discovery["governance"]["mode"] == "fail-closed"
    assert discovery["governance"]["materialChangeRequiresReEntry"] is True

    assert f'versionCode = {contract["phoneNode"]["minimumCompatibleVersionCode"]}' in gradle
    assert f'versionName = "{contract["phoneNode"]["minimumCompatibleVersionName"]}"' in gradle
    assert contract["phoneNode"]["applicationId"] in gradle
    assert "isMinifyEnabled = true" in gradle
    assert "isShrinkResources = true" in gradle
    assert "androidx.work:work-runtime-ktx:2.11.2" in gradle

    # The orchestration contract is evidence-only (`productionAuthorized=false`).
    # Its verifier must remain present and deterministic, but the current
    # Enterprise bridge must not expose that capability as an executable local
    # effect until the authoritative server registers/admit its scope.
    command_type = contract["command"]["type"]
    scope = contract["command"]["capabilityScope"]
    assert contract["invariants"]["productionAuthorized"] is False
    assert command_type not in bridge
    assert scope not in bridge
    assert 'type != "local_notification"' in bridge
    assert 'scope != "pocket.notification"' in bridge
    assert "BridgeServerProtocolPolicy" in bridge
    assert "admissionPath" in bridge
    assert "admissionCommitPath" in bridge
    assert "X-PIGA-Counter" in bridge
    assert "X-PIGA-Request-Id" in bridge
    assert "X-PIGA-Control-Plane-Origin" in bridge

    for required in contract["receiptRequired"]:
        assert required in verifier
    assert "gate2AfterFrontier" in verifier
    assert "gate2AfterRuntime" in verifier
    assert "production_authority_forbidden" in verifier
    assert "scheduler_authority_invariant_missing" in verifier
    assert "productionAuthorized" in verifier
    assert "externalActionExecuted" in verifier

    inv = contract["invariants"]
    assert inv["communicationTransportIsNotActionAuthority"] is True
    assert inv["externalMessageSendRequiresGate2"] is True
    assert inv["telephonyInitiationRequiresGate2"] is True
    assert inv["offlineQueuePreservesNonceExpiryAndScope"] is True
    assert inv["symbolicNumerologyIsNotScientificEvidence"] is True
    assert inv["scripturalReferenceRequiresProvenanceAndContext"] is True
    assert inv["creativeNarrativeIsNotFactEvidence"] is True
    assert inv["masterAutonomyRequiresExplicitUserEnablement"] is True
    assert inv["pairedAutonomyPersistsAcrossProcessRestart"] is True
    assert inv["emergencyStopDominatesAutonomy"] is True
    assert inv["singleLauncherSurface"] is True
    assert inv["canonicalControlPlanePinned"] is True

    assert "master_autonomy" in recovery
    assert "emergency_stop" in recovery
    assert "Result.retry()" in recovery
    assert "NetworkType.CONNECTED" in scheduler
    assert "15, TimeUnit.MINUTES" in scheduler
    assert "ACTION_BOOT_COMPLETED" in boot
    assert "ACTION_MY_PACKAGE_REPLACED" in boot

    assert 'putBoolean("master_autonomy", false)' in app
    assert "paired && masterAutonomy && !emergencyStop" in app
    assert "BridgeRecoveryScheduler.requestRecovery(this)" in app
    assert "ARMED_EXPLICIT" in main
    assert "BLOCKED_NOT_PAIRED" in main
    assert "CANONICAL_CONTROL_PLANE" in resolver
    assert 'require(normalized == CANONICAL_CONTROL_PLANE)' in resolver

    assert "RECEIVE_BOOT_COMPLETED" in manifest
    assert ".PigaBridgeApp" in manifest
    assert 'android:usesCleartextTraffic="false"' in manifest
    assert manifest.count("android.intent.action.MAIN") == 1
    assert manifest.count("android.intent.category.LAUNCHER") == 1

    assert "id-token: write" in wake
    assert 'AUDIENCE="piga-pocket-enterprise"' in wake
    assert '"$ROOT/api/health"' in wake
    assert "piga.control-plane-health.v1" in wake
    assert "payload.get('authority') == 'none'" in wake
    assert "payload.get('engineCount') == 5" in wake
    assert "payload.get('additionalEngineCreated') is False" in wake
    assert "payload.get('a7semReverseIsEngine') is False" in wake
    assert 'trigger_id="${GITHUB_RUN_ID}:${GITHUB_RUN_ATTEMPT}:${cycles}"' in wake
    assert 'X-PIGA-Trigger-Id: $trigger_id' in wake
    assert "X-PIGA-Trigger-Event: continuous-governance-wake" in wake
    assert '"$ROOT/api/factory/trigger/next"' in wake

    print("PIGA phone/control-plane market-readiness synchronization v0.2.0: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
