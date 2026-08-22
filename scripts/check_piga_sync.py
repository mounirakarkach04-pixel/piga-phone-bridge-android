from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "PIGA_PHONE_ORCHESTRATION_V1.json"
GRADLE = ROOT / "app/build.gradle.kts"
BRIDGE = ROOT / "app/src/main/java/io/piga/phonebridge/BridgeService.kt"
VERIFIER = ROOT / "app/src/main/java/io/piga/phonebridge/OrchestrationPlanVerifier.kt"


def main() -> int:
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    gradle = GRADLE.read_text(encoding="utf-8")
    bridge = BRIDGE.read_text(encoding="utf-8")
    verifier = VERIFIER.read_text(encoding="utf-8")

    assert contract["controlPlane"]["gearboxVersion"] == "1.1"
    assert f'versionCode = {contract["phoneNode"]["minimumCompatibleVersionCode"]}' in gradle
    assert f'versionName = "{contract["phoneNode"]["minimumCompatibleVersionName"]}"' in gradle
    assert contract["phoneNode"]["applicationId"] in gradle

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

    print("PIGA phone/control-plane synchronization: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
