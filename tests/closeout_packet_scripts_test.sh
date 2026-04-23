#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

required_scripts=(
  "scripts/replay_closeout.sh"
  "scripts/verify_closeout_packet.py"
  "scripts/closeout_facts.py"
  "scripts/generate_bundle_manifest.py"
)

for file in "${required_scripts[@]}"; do
  if [[ ! -f "$repo_root/$file" ]]; then
    echo "missing required closeout script: $file"
    exit 1
  fi
done

packet_dir="$tmpdir/packet"
mkdir -p "$packet_dir/scripts" "$packet_dir/docs" "$packet_dir/gradle/wrapper" "$packet_dir/tools" "$packet_dir/artifacts/metadata"

cp "$repo_root/scripts/replay_closeout.sh" "$packet_dir/scripts/replay_closeout.sh"
cp "$repo_root/scripts/verify_closeout_packet.py" "$packet_dir/scripts/verify_closeout_packet.py"
cp "$repo_root/scripts/closeout_facts.py" "$packet_dir/scripts/closeout_facts.py"
chmod +x "$packet_dir/scripts/replay_closeout.sh"

cat >"$packet_dir/scripts/verify-all.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
pwd > artifacts/metadata/replay-pwd.txt
echo "verify-all invoked" > artifacts/metadata/replay-marker.txt
EOF
chmod +x "$packet_dir/scripts/verify-all.sh"

touch "$packet_dir/gradlew"
touch "$packet_dir/build.gradle.kts"
touch "$packet_dir/settings.gradle.kts"
touch "$packet_dir/gradle/wrapper/gradle-wrapper.jar"
cat >"$packet_dir/local.properties" <<'EOF'
sdk.dir=/tmp/fake-android-sdk
EOF
cat >"$packet_dir/tools/Cargo.toml" <<'EOF'
[workspace]
members = []
EOF
cat >"$packet_dir/README.md" <<'EOF'
closeout packet
EOF
cat >"$packet_dir/docs/executive-closeout.md" <<'EOF'
# Executive Closeout

Item 5 Status: Closed
Residual Risk: None

References: `artifacts/metadata/packet-facts.json` `scripts/replay_closeout.sh` `scripts/verify-all.sh`
EOF
cat >"$packet_dir/docs/evidence-index.md" <<'EOF'
# Evidence Index

Item 5 Status: Closed
Residual Risk: None

References: `artifacts/metadata/consistency-check.json` `scripts/verify_closeout_packet.py`
EOF

python3 "$packet_dir/scripts/closeout_facts.py" "$packet_dir" >"$packet_dir/artifacts/metadata/packet-facts.json"
python3 "$repo_root/scripts/generate_bundle_manifest.py" "$packet_dir"
python3 "$packet_dir/scripts/verify_closeout_packet.py" "$packet_dir"
python3 "$repo_root/scripts/generate_bundle_manifest.py" "$packet_dir"
python3 "$packet_dir/scripts/verify_closeout_packet.py" "$packet_dir"
ANDROID_SDK_ROOT="$tmpdir/android-sdk" mkdir -p "$tmpdir/android-sdk"
ANDROID_SDK_ROOT="$tmpdir/android-sdk" bash "$packet_dir/scripts/replay_closeout.sh"

if [[ ! -f "$packet_dir/artifacts/metadata/replay-marker.txt" ]]; then
  echo "replay_closeout.sh did not invoke verify-all.sh"
  exit 1
fi

if ! grep -Fq "$packet_dir" "$packet_dir/artifacts/metadata/replay-pwd.txt"; then
  echo "replay_closeout.sh did not execute from packet root"
  exit 1
fi

if ! grep -Fq "$tmpdir/android-sdk" "$packet_dir/local.properties"; then
  echo "replay_closeout.sh did not rewrite local.properties from ANDROID_SDK_ROOT fallback"
  exit 1
fi

if ! grep -Fq "scripts/replay_closeout.sh" "$packet_dir/BUNDLE_MANIFEST.txt"; then
  echo "BUNDLE_MANIFEST.txt missing replay_closeout.sh"
  exit 1
fi

if ! grep -Fq "scripts/verify_closeout_packet.py" "$packet_dir/BUNDLE_MANIFEST.txt"; then
  echo "BUNDLE_MANIFEST.txt missing verify_closeout_packet.py"
  exit 1
fi

if ! grep -Fq "scripts/closeout_facts.py" "$packet_dir/BUNDLE_MANIFEST.txt"; then
  echo "BUNDLE_MANIFEST.txt missing closeout_facts.py"
  exit 1
fi

if ! grep -Fq "artifacts/metadata/consistency-check.json" "$packet_dir/BUNDLE_MANIFEST.txt"; then
  echo "BUNDLE_MANIFEST.txt missing consistency-check.json"
  exit 1
fi

incomplete_dir="$tmpdir/incomplete-packet"
mkdir -p "$incomplete_dir/scripts" "$incomplete_dir/docs" "$incomplete_dir/artifacts/metadata"
cp "$repo_root/scripts/verify_closeout_packet.py" "$incomplete_dir/scripts/verify_closeout_packet.py"
cp "$repo_root/scripts/closeout_facts.py" "$incomplete_dir/scripts/closeout_facts.py"
cat >"$incomplete_dir/README.md" <<'EOF'
closeout packet
EOF
cat >"$incomplete_dir/docs/executive-closeout.md" <<'EOF'
# Executive Closeout

Item 5 Status: Closed
Residual Risk: None
EOF
cat >"$incomplete_dir/docs/evidence-index.md" <<'EOF'
# Evidence Index

Item 5 Status: Closed
Residual Risk: None
EOF
python3 "$incomplete_dir/scripts/closeout_facts.py" "$incomplete_dir" >"$incomplete_dir/artifacts/metadata/packet-facts.json"
python3 "$repo_root/scripts/generate_bundle_manifest.py" "$incomplete_dir"

set +e
python3 "$incomplete_dir/scripts/verify_closeout_packet.py" "$incomplete_dir" >/dev/null 2>&1
status=$?
set -e

if [[ $status -eq 0 ]]; then
  echo "verify_closeout_packet.py unexpectedly accepted a non-self-contained packet"
  exit 1
fi

echo "closeout packet scripts test passed"
