# Nano Banana File-Input Validation

작성일: 2026-03-30  
목적: `Nano Banana` 계열 Gemini 이미지 편집/생성 워크플로우에서 `Files API + file_data/file_uri` 입력이 실제로 동작하는지 별도 세션에서 검증한다.  
주의: 이 검증은 Iris image ingress contract를 바꾸기 위한 절차가 아니다. 현재 Iris 계약은 `multipart/form-data` bytes 경계로 고정돼 있으며, 이 runbook은 Gemini 쪽 파일 입력 경로만 별도로 검증한다.

현재 상태:

- 이 runbook은 **참고용 보류 문서**다.
- 현재 작업 트랙에서는 이미지 항목을 아예 제외한다.
- 따라서 다음 세션의 기본 우선순위에는 이 문서를 넣지 않는다.

## 왜 이 테스트가 필요한가

현재 논점은 Iris image ingress를 다시 바꾸는 것이 아니다.  
Iris 계약은 그대로 두고, `Nano Banana` 대상 모델이 실제로 `file_data` 입력을 받아 안정적으로 응답하는지만 확인한다.

공식 문서 기준으로는 다음이 확인된다.

- Gemini API 일반 규약: 작은 미디어는 `inline_data`, 큰 파일 또는 재사용 파일은 `Files API + file_data`를 권장한다.
  - https://ai.google.dev/api
  - 2026-03-30 확인 기준: "For larger files or files you want to reuse across requests, use the File API..."
- Files API: 이미지를 업로드한 뒤 `file_uri`를 `generateContent`에 전달하는 공식 예제가 있다.
  - https://ai.google.dev/api/files
- 다만 image-generation 문서는 편집 예시를 주로 `inline_data` 중심으로 보여준다.
  - https://ai.google.dev/gemini-api/docs/image-generation

따라서 아래 순서로 검증해야 한다.

1. `base64 inline_data`가 현재 대상 모델에서 정상 동작하는지 확인
2. 같은 이미지로 `Files API + file_data`가 일반 Gemini 모델에서 정상 동작하는지 확인
3. 같은 이미지로 `Files API + file_data`가 `Nano Banana` 대상 모델에서도 정상 동작하는지 확인

이 3단계를 모두 통과해도 Iris image ingress 계약을 바꾸지 않는다.  
이 검증은 향후 선택 가능한 "추가 경로"가 있는지 확인하는 용도다.

## 세션 분리 원칙

- 이 테스트는 현재 코딩 세션에서 하지 않는다.
- 새 터미널 또는 새 Codex/Claude 세션에서만 실행한다.
- 출력물은 저장소 안이 아니라 `/tmp` 아래에 둔다.
- 실제 서비스 코드, `.env`, `iris.env`, 저장소 tracked 파일은 수정하지 않는다.

## 준비물

- `GEMINI_API_KEY`
- `Nano Banana`에 해당하는 실제 모델 ID
  - 예: 사용 중인 image preview/edit 모델 ID
  - 문서에는 예시를 넣지 말고, **현재 운영/개발에서 실제 쓰는 모델 문자열**을 넣어서 검증할 것
- 테스트 이미지 1장
- 다중 이미지 편집까지 보려면 테스트 이미지 2장
- 로컬 도구
  - `curl`
  - `jq`
  - `file`

## 환경 변수

아래 값은 새 세션에서만 설정한다.

```bash
export GEMINI_API_KEY='...'
export NANO_BANANA_MODEL='REPLACE_WITH_REAL_MODEL_ID'
export CONTROL_MODEL='gemini-2.5-flash'
export TEST_IMAGE_1='/absolute/path/to/test-image-1.png'
export TEST_IMAGE_2='/absolute/path/to/test-image-2.png'   # optional
export TEST_RUN_DIR="/tmp/nano-banana-file-input-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$TEST_RUN_DIR"
cd "$TEST_RUN_DIR"
```

검증 전 최소 체크:

```bash
test -n "$GEMINI_API_KEY"
test -n "$NANO_BANANA_MODEL"
test -f "$TEST_IMAGE_1"
```

## 판정 기준

### `file_data` 지원으로 판정

아래 조건을 모두 만족하면 `Nano Banana`에서 `file_data` 입력을 지원한다고 본다.

- Files API 업로드 성공
- 업로드 결과에서 `file.uri` 확인
- `NANO_BANANA_MODEL`에 `file_data`로 요청 시 HTTP `200`
- 응답에 `candidates`가 있고, 텍스트 또는 이미지 part가 비어 있지 않음

### `file_data` 미지원 또는 추가 확인 필요로 판정

아래 중 하나면 바로 big-bang patch를 하지 않는다.

- `base64 inline_data`는 성공하는데 `file_data`만 실패
- Files API 업로드는 성공했는데 대상 모델 호출에서 `4xx/5xx`
- 단일 이미지는 되는데 다중 이미지는 안 됨
- 대상 모델이 `file_data`에서만 비정상 지연/불안정 응답

## 테스트 1: base64 inline_data baseline

목적: 현재 대상 모델이 이미지 입력 자체는 정상 처리하는지 확인한다.

이미지를 base64로 인코딩한다.

```bash
BASE64_IMAGE_1="$(base64 -w 0 "$TEST_IMAGE_1")"
MIME_TYPE_1="$(file -b --mime-type "$TEST_IMAGE_1")"
```

요청:

```bash
cat > request-inline.json <<EOF
{
  "contents": [{
    "role": "user",
    "parts": [
      {
        "inline_data": {
          "mime_type": "$MIME_TYPE_1",
          "data": "$BASE64_IMAGE_1"
        }
      },
      {
        "text": "Describe this image briefly. If image editing is supported on this model, say only that input parsing succeeded."
      }
    ]
  }]
}
EOF

curl -sS \
  -H "x-goog-api-key: $GEMINI_API_KEY" \
  -H "Content-Type: application/json" \
  -X POST \
  "https://generativelanguage.googleapis.com/v1beta/models/${NANO_BANANA_MODEL}:generateContent" \
  -d @request-inline.json \
  > response-inline.json

jq '.candidates | length' response-inline.json
jq '.candidates[0].content.parts' response-inline.json
```

성공 조건:

- `response-inline.json`에 `candidates`가 존재
- 모델 에러 대신 정상 응답이 옴

실패하면:

- 대상 모델 ID 자체가 잘못됐거나
- API 키/권한/리전 문제가 있을 수 있으므로 `file_data` 테스트로 넘어가지 말고 먼저 수정

## 테스트 2: Files API 업로드 자체 검증

목적: `Nano Banana` 문제가 아니라 업로드 경로 문제인지 먼저 분리한다.

```bash
MIME_TYPE_1="$(file -b --mime-type "$TEST_IMAGE_1")"
NUM_BYTES_1="$(wc -c < "$TEST_IMAGE_1")"

curl "https://generativelanguage.googleapis.com/upload/v1beta/files?key=${GEMINI_API_KEY}" \
  -D upload-header-1.tmp \
  -H "X-Goog-Upload-Protocol: resumable" \
  -H "X-Goog-Upload-Command: start" \
  -H "X-Goog-Upload-Header-Content-Length: ${NUM_BYTES_1}" \
  -H "X-Goog-Upload-Header-Content-Type: ${MIME_TYPE_1}" \
  -H "Content-Type: application/json" \
  -d "{\"file\": {\"display_name\": \"nano-banana-test-image-1\"}}" \
  > /dev/null

UPLOAD_URL_1="$(grep -i 'x-goog-upload-url:' upload-header-1.tmp | awk '{print $2}' | tr -d '\r')"
test -n "$UPLOAD_URL_1"

curl "$UPLOAD_URL_1" \
  -H "Content-Length: ${NUM_BYTES_1}" \
  -H "X-Goog-Upload-Offset: 0" \
  -H "X-Goog-Upload-Command: upload, finalize" \
  --data-binary "@${TEST_IMAGE_1}" \
  > file-info-1.json

jq '.file.name, .file.uri, .file.mimeType, .file.sizeBytes' file-info-1.json
```

성공 조건:

- `file-info-1.json`에 `file.uri`가 있음
- `file.name`이 `files/...` 형태로 나옴

업로드가 실패하면:

- `file_data` 지원 여부 이전에 Files API 경로 자체가 막혀 있는 것
- 대상 모델 검증을 진행하지 않는다

## 테스트 3: control model에서 file_data 검증

목적: `file_data` 형식 자체가 올바른지 먼저 확인한다.

```bash
FILE_URI_1="$(jq -r '.file.uri' file-info-1.json)"
MIME_TYPE_1="$(jq -r '.file.mimeType' file-info-1.json)"

cat > request-filedata-control.json <<EOF
{
  "contents": [{
    "role": "user",
    "parts": [
      {
        "file_data": {
          "mime_type": "$MIME_TYPE_1",
          "file_uri": "$FILE_URI_1"
        }
      },
      {
        "text": "Describe this image briefly."
      }
    ]
  }]
}
EOF

curl -sS \
  -H "x-goog-api-key: $GEMINI_API_KEY" \
  -H "Content-Type: application/json" \
  -X POST \
  "https://generativelanguage.googleapis.com/v1beta/models/${CONTROL_MODEL}:generateContent" \
  -d @request-filedata-control.json \
  > response-filedata-control.json

jq '.candidates | length' response-filedata-control.json
jq '.candidates[0].content.parts' response-filedata-control.json
```

성공 조건:

- control model에서 `file_data` 요청이 정상 응답

이 단계가 실패하면:

- `Nano Banana` 이전에 업로드 참조 형식이 잘못된 것
- model 문제로 결론 내리면 안 됨

## 테스트 4: Nano Banana model에서 file_data 검증

목적: 가장 중요한 판단 단계.  
단, 이 단계가 성공해도 현재 API 계약은 그대로 유지한다.

```bash
cat > request-filedata-nano-banana.json <<EOF
{
  "contents": [{
    "role": "user",
    "parts": [
      {
        "file_data": {
          "mime_type": "$MIME_TYPE_1",
          "file_uri": "$FILE_URI_1"
        }
      },
      {
        "text": "Use this image as input. If the model can read the uploaded file, respond normally."
      }
    ]
  }]
}
EOF

curl -sS \
  -H "x-goog-api-key: $GEMINI_API_KEY" \
  -H "Content-Type: application/json" \
  -X POST \
  "https://generativelanguage.googleapis.com/v1beta/models/${NANO_BANANA_MODEL}:generateContent" \
  -d @request-filedata-nano-banana.json \
  > response-filedata-nano-banana.json

jq '.candidates | length' response-filedata-nano-banana.json
jq '.candidates[0].content.parts' response-filedata-nano-banana.json
jq '.error' response-filedata-nano-banana.json
```

성공 조건:

- HTTP `200`
- `.error == null`
- `candidates[0].content.parts`가 비어 있지 않음

## 테스트 5: 다중 이미지 file_data 검증

다중 입력이 실제 요구사항이면 이 단계도 해야 한다.

두 번째 이미지 업로드:

```bash
MIME_TYPE_2="$(file -b --mime-type "$TEST_IMAGE_2")"
NUM_BYTES_2="$(wc -c < "$TEST_IMAGE_2")"

curl "https://generativelanguage.googleapis.com/upload/v1beta/files?key=${GEMINI_API_KEY}" \
  -D upload-header-2.tmp \
  -H "X-Goog-Upload-Protocol: resumable" \
  -H "X-Goog-Upload-Command: start" \
  -H "X-Goog-Upload-Header-Content-Length: ${NUM_BYTES_2}" \
  -H "X-Goog-Upload-Header-Content-Type: ${MIME_TYPE_2}" \
  -H "Content-Type: application/json" \
  -d "{\"file\": {\"display_name\": \"nano-banana-test-image-2\"}}" \
  > /dev/null

UPLOAD_URL_2="$(grep -i 'x-goog-upload-url:' upload-header-2.tmp | awk '{print $2}' | tr -d '\r')"

curl "$UPLOAD_URL_2" \
  -H "Content-Length: ${NUM_BYTES_2}" \
  -H "X-Goog-Upload-Offset: 0" \
  -H "X-Goog-Upload-Command: upload, finalize" \
  --data-binary "@${TEST_IMAGE_2}" \
  > file-info-2.json

FILE_URI_2="$(jq -r '.file.uri' file-info-2.json)"
MIME_TYPE_2="$(jq -r '.file.mimeType' file-info-2.json)"
```

요청:

```bash
cat > request-filedata-multi.json <<EOF
{
  "contents": [{
    "role": "user",
    "parts": [
      {
        "file_data": {
          "mime_type": "$MIME_TYPE_1",
          "file_uri": "$FILE_URI_1"
        }
      },
      {
        "file_data": {
          "mime_type": "$MIME_TYPE_2",
          "file_uri": "$FILE_URI_2"
        }
      },
      {
        "text": "Use both uploaded images as inputs and respond normally if both are readable."
      }
    ]
  }]
}
EOF

curl -sS \
  -H "x-goog-api-key: $GEMINI_API_KEY" \
  -H "Content-Type: application/json" \
  -X POST \
  "https://generativelanguage.googleapis.com/v1beta/models/${NANO_BANANA_MODEL}:generateContent" \
  -d @request-filedata-multi.json \
  > response-filedata-multi.json

jq '.candidates | length' response-filedata-multi.json
jq '.candidates[0].content.parts' response-filedata-multi.json
jq '.error' response-filedata-multi.json
```

## 결과 해석

### 케이스 A

- inline success
- control `file_data` success
- Nano Banana `file_data` success

판정:

- `file_data`는 기술적으로 사용 가능
- 그래도 현재 `base64` 계약은 유지
- 필요하면 나중에 "추가 경로"로만 검토

### 케이스 B

- inline success
- control `file_data` success
- Nano Banana `file_data` fail

판정:

- Files API 자체는 정상
- 대상 모델 또는 해당 모델의 image-edit path에서 `file_data` 제약 가능성 높음
- 현재처럼 `base64` 유지가 맞음

### 케이스 C

- inline success
- control `file_data` fail

판정:

- upload/file reference 형식 또는 API 호출 방법 문제
- 모델 지원 여부 판단 보류

### 케이스 D

- single image `file_data` success
- multi image `file_data` fail

판정:

- 단일 이미지는 전환 가능
- 다중 이미지는 추가 설계 또는 호환 경로 유지 필요

## 수집해야 할 증거

다른 세션에서 테스트 후 아래만 남기면 된다.

- 사용한 실제 `NANO_BANANA_MODEL`
- 각 단계의 HTTP status
- 각 단계의 `jq '.error'` 결과
- 각 단계의 `jq '.candidates | length'` 결과
- `file-info-1.json`, `file-info-2.json`
- 실패 시 `response-*.json` 원문

비밀값은 남기지 않는다.

- `GEMINI_API_KEY`는 저장 금지
- 요청 헤더 전체 dump 금지
- 필요하면 응답 파일만 보관

## 정리 명령

테스트가 끝나면 업로드 파일을 정리한다.

```bash
NAME_1="$(jq -r '.file.name' file-info-1.json)"
curl -sS -X DELETE \
  -H "x-goog-api-key: $GEMINI_API_KEY" \
  "https://generativelanguage.googleapis.com/v1beta/${NAME_1}"
```

두 번째 파일도 같은 방식으로 삭제한다.

로컬 출력물 정리:

```bash
cd /
rm -rf "$TEST_RUN_DIR"
```

## 최종 의사결정 규칙

이 테스트의 목적은 Iris image ingress 계약 변경 여부를 결정하는 것이 아니다.

현재 결정은 아래와 같이 고정한다.

1. Iris image ingress는 계속 `multipart/form-data` bytes 계약을 유지한다.
2. 이 문서는 `file_data`가 기술적으로 가능한지 확인하는 참고용 검증 문서다.
3. 검증이 성공해도 Iris image ingress 계약 변경, `uploadIds` 강제, 대규모 계약 변경은 하지 않는다.
4. 향후 필요하면 `file_data`는 선택적 추가 경로로만 별도 검토한다.
