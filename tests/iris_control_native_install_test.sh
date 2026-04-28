#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
: >"$tmpdir/iris.env"

apk_sha256="1e10ba560383b17472b4cf72fef8f9e76c66815a3e6ae8c5a9b0c5e696b0bdf8"
native_file="$tmpdir/libiris_native_core.so"
command_log="$tmpdir/adb_command.log"

printf 'native-bytes' >"$native_file"

cat >"$tmpdir/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

command_log="$(dirname "$0")/adb_command.log"
printf '%s\n' "$*" >>"$command_log"

if [[ "${1:-}" == "-s" && "${2:-}" == "127.0.0.1:5555" && "${3:-}" == "push" ]]; then
  exit 0
fi

if [[ "${1:-}" == "-s" && "${2:-}" == "127.0.0.1:5555" && "${3:-}" == "shell" ]]; then
  case "${4:-}" in
    "mkdir -p /data/iris/lib"|"chmod 644 /data/iris/lib/libiris_native_core.so"|*"ls /data/local/tmp/Iris.apk"*)
      exit 0
      ;;
  esac
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
  printf '%s  Iris.apk\n' "$apk_sha256" >"\$output"
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
output="$(
  PATH="$tmpdir:$PATH" \
  IRIS_ENV_FILE="$tmpdir/iris.env" \
  IRIS_ADB_DEVICE="127.0.0.1:5555" \
  IRIS_NATIVE_LIB_LOCAL_FILE="$native_file" \
  "$repo_root/iris_control" install 2>&1
)"
status=$?
set -e

if [[ $status -ne 0 ]]; then
  echo "iris_control install failed unexpectedly with native library"
  echo "$output"
  exit 1
fi

if ! grep -Fq "Pushing native library to device:" <<<"$output"; then
  echo "expected native library push output not found"
  echo "$output"
  exit 1
fi

expected_order=$'-s 127.0.0.1:5555 push Iris.apk /data/local/tmp/Iris.apk\n-s 127.0.0.1:5555 shell mkdir -p /data/iris/lib\n-s 127.0.0.1:5555 push '"$native_file"$' /data/iris/lib/libiris_native_core.so\n-s 127.0.0.1:5555 shell chmod 644 /data/iris/lib/libiris_native_core.so\n-s 127.0.0.1:5555 shell ls /data/local/tmp/Iris.apk'

if [[ "$(cat "$command_log")" != "$expected_order" ]]; then
  echo "unexpected adb command order"
  cat "$command_log"
  exit 1
fi

echo "iris_control native install test passed"
