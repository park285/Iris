#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
: >"$tmpdir/iris.env"

apk_sha256="1e10ba560383b17472b4cf72fef8f9e76c66815a3e6ae8c5a9b0c5e696b0bdf8"

cat >"$tmpdir/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "-s" && "${2:-}" == "127.0.0.1:5555" && "${3:-}" == "push" ]]; then
  exit 0
fi

if [[ "${1:-}" == "-s" && "${2:-}" == "127.0.0.1:5555" && "${3:-}" == "shell" && "${4:-}" == *"ls /data/local/tmp/Iris.apk"* ]]; then
  exit 0
fi

echo "unexpected adb invocation: $*" >&2
exit 1
EOF
chmod +x "$tmpdir/adb"

cat >"$tmpdir/curl" <<EOF
#!/usr/bin/env bash
set -euo pipefail

output=""
url=""
while [[ \$# -gt 0 ]]; do
  case "\$1" in
    -o)
      output="\$2"
      shift 2
      ;;
    *)
      url="\$1"
      shift
      ;;
  esac
done

if [[ -z "\$output" || -z "\$url" ]]; then
  echo "missing curl output/url" >&2
  exit 1
fi

if [[ "\$url" == *.SHA256 ]]; then
  if [[ "\${GOOD_CHECKSUM:-1}" == "1" ]]; then
    printf '%s  Iris.apk\n' "$apk_sha256" >"\$output"
  else
    printf '%s  Iris.apk\n' "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" >"\$output"
  fi
  exit 0
fi

printf 'apk-bytes' >"\$output"
EOF
chmod +x "$tmpdir/curl"

cat >"$tmpdir/sha256sum" <<EOF
#!/usr/bin/env bash
set -euo pipefail
printf '%s  %s\n' "$apk_sha256" "\$1"
EOF
chmod +x "$tmpdir/sha256sum"

set +e
success_output="$(
  PATH="$tmpdir:$PATH" \
  IRIS_ENV_FILE="$tmpdir/iris.env" \
  IRIS_ADB_DEVICE="127.0.0.1:5555" \
  IRIS_NATIVE_LIB_LOCAL_FILE="$tmpdir/missing-libiris_native_core.so" \
  "$repo_root/iris_control" install 2>&1
)"
success_status=$?
set -e

if [[ $success_status -ne 0 ]]; then
  echo "iris_control install failed unexpectedly on valid SHA-256"
  echo "$success_output"
  exit 1
fi

if ! grep -Fq "SHA-256 checksum verification passed." <<<"$success_output"; then
  echo "expected SHA-256 success output not found"
  echo "$success_output"
  exit 1
fi

set +e
failure_output="$(
  GOOD_CHECKSUM=0 \
  PATH="$tmpdir:$PATH" \
  IRIS_ENV_FILE="$tmpdir/iris.env" \
  IRIS_ADB_DEVICE="127.0.0.1:5555" \
  IRIS_NATIVE_LIB_LOCAL_FILE="$tmpdir/missing-libiris_native_core.so" \
  "$repo_root/iris_control" install 2>&1
)"
failure_status=$?
set -e

if [[ $failure_status -eq 0 ]]; then
  echo "iris_control install unexpectedly succeeded on checksum mismatch"
  echo "$failure_output"
  exit 1
fi

if ! grep -Fq "SHA-256 checksum mismatch" <<<"$failure_output"; then
  echo "expected SHA-256 mismatch output not found"
  echo "$failure_output"
  exit 1
fi

echo "iris_control install checksum test passed"
