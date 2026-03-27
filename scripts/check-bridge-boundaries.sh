#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"

check_max_lines() {
  local file="$1"
  local max_lines="$2"
  local actual
  actual="$(wc -l <"$repo_root/$file" | tr -d ' ')"
  if (( actual > max_lines )); then
    echo "bridge boundary check failed: $file has $actual lines, max is $max_lines" >&2
    exit 1
  fi
}

assert_not_contains() {
  local file="$1"
  local pattern="$2"
  local description="$3"
  if rg -n "$pattern" "$repo_root/$file" >/dev/null; then
    echo "bridge boundary check failed: $file still contains $description" >&2
    exit 1
  fi
}

assert_no_repo_matches() {
  local pattern="$1"
  local description="$2"
  if rg -n "$pattern" "$repo_root/app/src/main" "$repo_root/bridge/src/main" >/dev/null; then
    echo "bridge boundary check failed: found $description outside the shared protocol or allowed boundary" >&2
    exit 1
  fi
}

check_max_lines "app/src/main/java/party/qwer/iris/bridge/H2cDispatcher.kt" 400
check_max_lines "app/src/main/java/party/qwer/iris/bridge/WebhookRequestFactory.kt" 60
check_max_lines "app/src/main/java/party/qwer/iris/bridge/WebhookDeliveryClient.kt" 80
check_max_lines "bridge/src/main/java/party/qwer/iris/bridge/ImageBridgeServer.kt" 220
check_max_lines "bridge/src/main/java/party/qwer/iris/bridge/ImageBridgeRequestHandler.kt" 120
check_max_lines "bridge/src/main/java/party/qwer/iris/bridge/BridgeDiscovery.kt" 260
check_max_lines "bridge/src/main/java/party/qwer/iris/bridge/KakaoImageSender.kt" 80
check_max_lines "bridge/src/main/java/party/qwer/iris/bridge/ChatRoomResolver.kt" 220
check_max_lines "bridge/src/main/java/party/qwer/iris/bridge/KakaoSendInvocationFactory.kt" 180

assert_not_contains "app/src/main/java/party/qwer/iris/bridge/H2cDispatcher.kt" "Request\\.Builder" "inline request construction"
assert_not_contains "app/src/main/java/party/qwer/iris/bridge/H2cDispatcher.kt" "call\\.enqueue\\(" "inline OkHttp callback execution"
assert_not_contains "bridge/src/main/java/party/qwer/iris/bridge/ImageBridgeServer.kt" "Proxy\\.newProxyInstance|resolveChatRoom\\(" "Kakao runtime reflection logic"
assert_not_contains "bridge/src/main/java/party/qwer/iris/bridge/KakaoImageSender.kt" "Class\\.forName|Proxy\\.newProxyInstance|LocalServerSocket|JSONObject" "low-level transport/reflection details in orchestration layer"
assert_not_contains "bridge/src/main/java/party/qwer/iris/bridge/ImageBridgeRequestHandler.kt" "Class\\.forName|Proxy\\.newProxyInstance|LocalServerSocket" "transport or reflection details in request handler"
assert_no_repo_matches "const val (SOCKET_NAME|ACTION_SEND_IMAGE|ACTION_HEALTH|STATUS_SENT|STATUS_FAILED|STATUS_OK|MAX_FRAME_SIZE)" "duplicated protocol constants"
assert_no_repo_matches "DataInputStream|DataOutputStream" "duplicated frame codec"

if rg -n "XposedHelpers|XC_MethodHook" "$repo_root/bridge/src/main/java/party/qwer/iris/bridge" | rg -v "IrisBridgeModule\\.kt|BridgeDiscovery\\.kt" >/dev/null; then
  echo "bridge boundary check failed: Xposed hook logic leaked outside discovery/module entrypoint" >&2
  exit 1
fi

echo "bridge boundary checks passed"
