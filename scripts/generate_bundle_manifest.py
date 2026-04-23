#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import sys
from pathlib import Path


def sha256_hex(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fp:
        for chunk in iter(lambda: fp.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: generate_bundle_manifest.py <packet_dir>", file=sys.stderr)
        return 1

    packet_dir = Path(sys.argv[1]).resolve()
    manifest_path = packet_dir / "BUNDLE_MANIFEST.txt"

    lines: list[str] = []
    for path in sorted(packet_dir.rglob("*")):
        if not path.is_file():
            continue
        if path == manifest_path:
            continue
        rel = path.relative_to(packet_dir).as_posix()
        lines.append(f"{sha256_hex(path)}  {path.stat().st_size}  {rel}")

    manifest_path.write_text("\n".join(lines) + ("\n" if lines else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
