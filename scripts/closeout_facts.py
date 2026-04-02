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

    facts = {
        "packet_dir": str(packet_dir),
        "top_level_files": top_level_files,
        "junit_total_files": sum(1 for path in junit_dir.rglob("*") if path.is_file()) if junit_dir.is_dir() else 0,
        "junit_xml_files": sum(1 for path in junit_dir.rglob("*.xml")) if junit_dir.is_dir() else 0,
        "rust_counts": rust_counts,
        "rust_total_tests": sum(rust_counts.values()),
    }
    print(json.dumps(facts, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
