#!/usr/bin/env python3

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


def cargo_counts(packet_dir: Path) -> dict[str, int]:
    test_results_dir = packet_dir / "artifacts" / "test-results"
    cargo_log = None
    for candidate in ("cargo-test.txt", "cargo-output.txt"):
        path = test_results_dir / candidate
        if path.is_file():
            cargo_log = path.read_text()
            break
    if cargo_log is None:
        return {}

    running_matches = list(
        re.finditer(
            r"Running .*?(iris_common|auth_contract_test|iris_ctl|iris_daemon).*?\n.*?test result: ok\. (\d+) passed",
            cargo_log,
            re.S,
        )
    )
    if running_matches:
        return {match.group(1): int(match.group(2)) for match in running_matches}

    counts = [int(match) for match in re.findall(r"test result: ok\. (\d+) passed", cargo_log)]
    named_counts: dict[str, int] = {}
    if len(counts) >= 4:
        named_counts = {
            "iris_common": counts[0],
            "auth_contract_test": counts[1],
            "iris_ctl": counts[2],
            "iris_daemon": counts[3],
        }
    return named_counts


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: closeout_facts.py <packet_dir>", file=sys.stderr)
        return 1

    packet_dir = Path(sys.argv[1]).resolve()
    junit_dir = packet_dir / "artifacts" / "test-results" / "junit"
    top_level_files = sorted(path.name for path in packet_dir.iterdir() if path.is_file())
    rust_counts = cargo_counts(packet_dir)
    has_gradlew = (packet_dir / "gradlew").is_file()
    has_build_gradle = (packet_dir / "build.gradle.kts").is_file()
    has_settings_gradle = (packet_dir / "settings.gradle.kts").is_file()
    has_gradle_wrapper_jar = (packet_dir / "gradle" / "wrapper" / "gradle-wrapper.jar").is_file()
    has_local_properties = (packet_dir / "local.properties").is_file()
    has_tools_cargo_toml = (packet_dir / "tools" / "Cargo.toml").is_file()
    has_replay_script = (packet_dir / "scripts" / "replay_closeout.sh").is_file()
    has_verify_closeout_script = (packet_dir / "scripts" / "verify_closeout_packet.py").is_file()
    has_verify_all_script = (packet_dir / "scripts" / "verify-all.sh").is_file()
    has_executive_closeout = (packet_dir / "docs" / "executive-closeout.md").is_file()
    has_evidence_index = (packet_dir / "docs" / "evidence-index.md").is_file()

    facts = {
        "packet_name": packet_dir.name,
        "top_level_files": top_level_files,
        "junit_total_files": sum(1 for path in junit_dir.rglob("*") if path.is_file()) if junit_dir.is_dir() else 0,
        "junit_xml_files": sum(1 for path in junit_dir.rglob("*.xml")) if junit_dir.is_dir() else 0,
        "rust_counts": rust_counts,
        "rust_total_tests": sum(rust_counts.values()),
        "has_gradlew": has_gradlew,
        "has_build_gradle": has_build_gradle,
        "has_settings_gradle": has_settings_gradle,
        "has_gradle_wrapper_jar": has_gradle_wrapper_jar,
        "has_local_properties": has_local_properties,
        "has_tools_cargo_toml": has_tools_cargo_toml,
        "has_replay_script": has_replay_script,
        "has_verify_closeout_script": has_verify_closeout_script,
        "has_verify_all_script": has_verify_all_script,
        "has_executive_closeout": has_executive_closeout,
        "has_evidence_index": has_evidence_index,
        "is_self_contained": (
            has_gradlew
            and has_build_gradle
            and has_settings_gradle
            and has_gradle_wrapper_jar
            and has_local_properties
            and has_tools_cargo_toml
            and has_replay_script
            and has_verify_closeout_script
            and has_verify_all_script
            and has_executive_closeout
            and has_evidence_index
        ),
    }
    print(json.dumps(facts, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
