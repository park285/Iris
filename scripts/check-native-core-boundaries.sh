#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
nativecore_dir="$repo_root/app/src/main/java/party/qwer/iris/nativecore"
runtime_file="$nativecore_dir/NativeCoreRuntime.kt"

if rg -n "\bdata\s+class\b" "$runtime_file" >/dev/null; then
  echo "native core boundary check failed: NativeCoreRuntime.kt must not declare data classes" >&2
  exit 1
fi

allowed_model_files_pattern='/(NativeCoreConfigModels|NativeCoreDiagnostics|NativeDecryptModels|NativeIngressModels|NativeParserModels|NativeQueryProjectionModels|NativeRoutingModels|NativeStatisticsModels|NativeWebhookModels)\.kt:'
if rg -n "\bdata\s+class\b" "$nativecore_dir" --glob '*.kt' | rg -v "$allowed_model_files_pattern" >/dev/null; then
  echo "native core boundary check failed: nativecore data classes must stay in dedicated model/diagnostics files" >&2
  rg -n "\bdata\s+class\b" "$nativecore_dir" --glob '*.kt' | rg -v "$allowed_model_files_pattern" >&2
  exit 1
fi

echo "native core boundary checks passed"
