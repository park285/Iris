#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${1:-$repo_root/.tasklists/closeout-packet-$(date +%Y%m%d)}"

rm -rf "$output_dir"
mkdir -p "$output_dir"

rsync -a --delete \
  --exclude='.git/' \
  --exclude='.gradle/' \
  --exclude='.idea/' \
  --exclude='.kotlin/' \
  --exclude='.vscode/' \
  --exclude='.claude/' \
  --exclude='.codex/' \
  --exclude='.tasklists/' \
  --exclude='build/' \
  --exclude='output/' \
  --exclude='**/build/' \
  --exclude='**/target/' \
  "$repo_root/" "$output_dir/"

mkdir -p "$output_dir/artifacts/metadata" "$output_dir/artifacts/patches"

{
  printf 'commit: %s\n' "$(git -C "$repo_root" rev-parse HEAD)"
  printf 'branch: %s\n' "$(git -C "$repo_root" rev-parse --abbrev-ref HEAD)"
  printf 'generated_at: %s\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
} >"$output_dir/artifacts/metadata/revision.txt"

git -C "$repo_root" diff --binary HEAD -- . ':(exclude).tasklists' >"$output_dir/artifacts/patches/working-tree.patch"

if [[ -f "$repo_root/local.properties" ]]; then
  cp "$repo_root/local.properties" "$output_dir/local.properties"
elif [[ -n "${ANDROID_HOME:-}" ]]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" >"$output_dir/local.properties"
elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
  printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" >"$output_dir/local.properties"
else
  echo "unable to determine Android SDK path for packet local.properties" >&2
  exit 1
fi

python3 "$output_dir/scripts/closeout_facts.py" "$output_dir" >"$output_dir/artifacts/metadata/packet-facts.json"
python3 "$output_dir/scripts/verify_closeout_packet.py" "$output_dir"

echo "exported closeout packet to $output_dir"
