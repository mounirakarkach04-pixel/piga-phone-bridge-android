from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "PIGA_PHONE_ORCHESTRATION_V1.json"
GRADLE = ROOT / "app/build.gradle.kts"
BRIDGE = ROOT / "app/src/main/java/io/piga/phonebridge/BridgeService.kt"
VERIFIER = ROOT / "app/src/main/java/io/piga/phonebridge/OrchestrationPlanVerifier.kt"
RECOVERY = ROOT / "app/src/main/java/io/piga/phonebridge/BridgeRecoveryWorker.kt"
SCHEDULER = ROOT / "app/src/main/java/io/piga/phonebridge/BridgeRecoveryScheduler.kt"
BOOT = ROOT / "app/src/main/java/io/piga/phonebridge/BootReceiver.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"


def main() -> int:
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    gradle = GRADLE.read_text(encoding="utf-8")
    bridge = BRIDGE.read_text(encoding="utf-8")
    verifier = VERIFIER.read_text(encoding="utf-8")
    recovery = RECOVERY.read_text(encoding="utf-8")
    scheduler = SCHEDULER.read_text(encoding="utf-8")
    boot = BOOT.read_text(encoding="utf-8")
    manifest = MANIFEST.read_text(encoding="utf-8")

    assert contract["controlPlane"]["gearboxVersion"] == "1.6"
    assert f'versionCode = {contract["phoneNode"]["minimumCompatibleVersionCode"]}' in gradle
    assert f'versionName = "{contract["phoneNode"]["minimumCompatibleVersionName"]}"' in gradle
    assert contract["phoneNode"]["applicationId"] in gradle
    assert 'androidx.work:work-runtime-ktx:2.11.2' in gradle

    command_type = contract["command"]["type"]
    scope = contract["command"]["capabilityScope"]
    assert command_type in bridge
    assert scope in bridge
    assert "expectedPlanSha256" in bridge
    assert "OrchestrationPlanVerifier" in bridge

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

    assert "master_autonomy" in recovery
    assert "emergency_stop" in recovery
    assert "Result.retry()" in recovery
    assert "NetworkType.CONNECTED" in scheduler
    assert "15, TimeUnit.MINUTES" in scheduler
    assert "ACTION_BOOT_COMPLETED" in boot
    assert "ACTION_MY_PACKAGE_REPLACED" in boot
    assert "RECEIVE_BOOT_COMPLETED" in manifest
    assert ".PigaBridgeApp" in manifest

    print("PIGA phone/control-plane synchronization v1.6: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
