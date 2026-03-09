# [ARCHIVE] Iris H2C Migration Notes

이 문서는 과거 h2c 전환 과정의 장애 조사 메모를 대체하는
아카이브 요약입니다.

현재 운영/구현 기준 문서는 `README.md`입니다.
이 파일은 현재 코드의 동작 설명서가 아닙니다.

## Current Status

- `ObserverHelper`는 `H2cDispatcher`를 사용합니다.
- Redis/Jedis 기반 MQ bridge 경로는 제거 완료 상태입니다.
- 현재 운영 검증은 `hololive` webhook 경로 중심으로 완료되었습니다.
- `twentyq`, `turtle-soup` 경로는 코드상 route 키는 남아 있지만
  운영 검증은 별도 확인이 필요합니다.

## Why This File Still Exists

- h2c 전환 중 어떤 종류의 장애가 있었는지 짧게 남기기 위함입니다.
- 특히 “bind 주소”와 “Iris가 실제로 도달 가능한 주소”가 다를 수 있다는
  점을 기록으로 보존하기 위함입니다.

## Historical Lessons

- webhook URL은 서버의 bind 주소가 아니라 Iris 기준 reachable address 여야 합니다.
- `127.0.0.1` 또는 `localhost`는 대부분 Iris 자신의 loopback 이므로
  외부 webhook 주소로 그대로 쓰면 안 됩니다.
- Docker bridge, Redroid, k8s NodePort 환경에서는 실제 네트워크 경로를
  장치 내부에서 검증해야 합니다.

## Removed Legacy Scope

다음 항목은 현재 문서 기준으로는 레거시이며, 상세 이력은 정리했습니다.

- Redis producer / reply consumer 기반 bridge 설명
- `assistantFullRooms`, `/어시`, `/몰트` 등 과거 라우팅 실험 설명
- 미커밋 상태를 기준으로 한 당시 파일/의존성 분석 표
- 당시 세션의 임시 작업 계획 및 운영 메모

## Reference

- 현재 설정 및 운영 가이드: `README.md`
- 현재 배포 산출물 경로: `output/Iris-debug.apk`
