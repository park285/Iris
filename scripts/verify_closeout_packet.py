#!/usr/bin/env python3

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


def load_json(path: Path):
    return json.loads(path.read_text())


def auth_vector_count(packet_dir: Path) -> int:
    path = packet_dir / "tests" / "contracts" / "iris_auth_vectors.json"
    if not path.is_file():
        return 0
    return len(load_json(path))


def junit_test_count(packet_dir: Path) -> int:
    junit_dir = packet_dir / "artifacts" / "test-results" / "junit"
    total = 0
    for xml in junit_dir.rglob("TEST-*.xml"):
        text = xml.read_text()
        match = re.search(r'tests="(\d+)"', text)
        if match:
            total += int(match.group(1))
    return total


def rust_test_count(packet_dir: Path) -> int:
    cargo_output_path = packet_dir / "artifacts" / "test-results" / "cargo-output.txt"
    if not cargo_output_path.is_file():
        return 0
    cargo_output = cargo_output_path.read_text()
    return sum(int(value) for value in re.findall(r"test result: ok\. (\d+) passed", cargo_output))


def referenced_paths(packet_dir: Path) -> list[str]:
    docs = [
        packet_dir / "docs" / "executive-closeout.md",
        packet_dir / "docs" / "evidence-index.md",
        packet_dir / "README.md",
    ]
    results = set()
    for doc in docs:
        if not doc.exists():
            continue
        text = doc.read_text()
        for match in re.findall(r"`((?:artifacts|app|tools|tests|scripts)/[^`]+)`", text):
            results.add(match)
    return sorted(results)


def closeout_doc_claims(packet_dir: Path) -> dict[str, tuple[str | None, str | None]]:
    claims: dict[str, tuple[str | None, str | None]] = {}
    for name in ("executive-closeout.md", "evidence-index.md"):
        path = packet_dir / "docs" / name
        if not path.is_file():
            claims[name] = (None, None)
            continue
        text = path.read_text()
        status = re.search(r"^Item 5 Status:\s*(.+)$", text, re.M)
        risk = re.search(r"^Residual Risk:\s*(.+)$", text, re.M)
        claims[name] = (
            status.group(1).strip() if status else None,
            risk.group(1).strip() if risk else None,
        )
    return claims


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_closeout_packet.py <packet_dir>", file=sys.stderr)
        return 1

    packet_dir = Path(sys.argv[1]).resolve()
    output_path = packet_dir / "artifacts" / "metadata" / "consistency-check.json"
    facts_path = packet_dir / "artifacts" / "metadata" / "packet-facts.json"
    manifest_path = packet_dir / "BUNDLE_MANIFEST.txt"
    missing = [
        path
        for path in referenced_paths(packet_dir)
        if path != "artifacts/metadata/consistency-check.json" and not (packet_dir / path).exists()
    ]
    issues = []

    if not facts_path.is_file():
        issues.append("missing packet facts metadata")
        packet_facts = {}
    else:
        packet_facts = load_json(facts_path)
        if not packet_facts.get("is_self_contained", False):
            issues.append("packet-facts.json reports is_self_contained=false")

    manifest_paths = set()
    if not manifest_path.is_file():
        issues.append("missing BUNDLE_MANIFEST.txt")
    else:
        for line in manifest_path.read_text().splitlines():
            parts = line.split("  ", 2)
            if len(parts) != 3:
                issues.append(f"invalid manifest line: {line}")
                continue
            manifest_paths.add(parts[2])

        required_manifest_paths = {
            "scripts/replay_closeout.sh",
            "scripts/verify_closeout_packet.py",
            "scripts/closeout_facts.py",
            "scripts/verify-all.sh",
            "artifacts/metadata/packet-facts.json",
        }
        missing_manifest_paths = sorted(required_manifest_paths - manifest_paths)
        if missing_manifest_paths:
            issues.append(
                "bundle manifest missing required paths: " + ", ".join(missing_manifest_paths)
            )

    replay_script = packet_dir / "scripts" / "replay_closeout.sh"
    if not replay_script.is_file():
        issues.append("missing replay_closeout.sh")
    else:
        replay_text = replay_script.read_text()
        if "./scripts/verify-all.sh" not in replay_text:
            issues.append("replay_closeout.sh does not replay the claimed full verification scope")
        if "closeout-packet-20260402" in replay_text or "BASE_REPO" in replay_text:
            issues.append("replay_closeout.sh still depends on a hard-coded packet path or external base repo")

    doc_claims = closeout_doc_claims(packet_dir)
    for name, (status, risk) in doc_claims.items():
        if status != "Closed":
            issues.append(f"{name} does not declare Item 5 Status: Closed")
        if risk != "None":
            issues.append(f"{name} does not declare Residual Risk: None")

    facts = {
        "auth_vectors": auth_vector_count(packet_dir),
        "kotlin_tests": junit_test_count(packet_dir),
        "rust_tests": rust_test_count(packet_dir),
        "missing_referenced_paths": missing,
        "packet_is_self_contained": packet_facts.get("is_self_contained"),
        "doc_claims": {
            name: {
                "item_5_status": status,
                "residual_risk": risk,
            }
            for name, (status, risk) in doc_claims.items()
        },
        "bundle_manifest_present": manifest_path.is_file(),
        "issues": issues,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(facts, indent=2, ensure_ascii=False) + "\n")
    return 0 if not missing and not issues else 2


if __name__ == "__main__":
    raise SystemExit(main())
