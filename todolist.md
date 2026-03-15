# Iris 후속 작업 TODO

> 2026-03-12 기준 최신 정리본입니다.
> 현재 운영 반영 상태:
> - `iris-kr` 기준 최신 소스/문서 반영 완료
> - headless Redroid live 반영 완료
> - 인증 포함 `/config`, `/query` 스모크 완료
> - 보호 API fail-closed, in-memory best-effort webhook queue 반영 완료
>
> 아래 항목은 완료 작업의 기록 보존용입니다.

## 1. 우선 처리

- [x] `H2cDispatcher.dispatchWithRetry()`의 false-negative retry 원인 분석
  - 증상: downstream은 실제 처리했는데 Iris는 `Connection error ... null`로 재시도
  - 확인 대상: 응답 수신 이후 예외, `runBlocking` 경계, Ktor/OkHttp h2c 응답 처리
  - 2026-03-09 점검: Redroid 내부에서 `172.18.0.2:30001` TCP는 도달 가능하지만 기존 `iris-run.log`는 `Connection error ... null` 반복
  - 추정 원인: 단순 주소 미도달보다는 h2c 요청 이후 응답 대기/응답 정리 경계의 예외 가능성이 더 큼
- [x] 예외 로깅 보강
  - `exception class`
  - `message`
  - `route`
  - `url`
  - `messageId`
  - `attempt`
- [x] 성공/실패 판정 로그 세분화
  - 요청 송신 성공
  - 응답 수신 성공
  - 응답 판독 실패
  - 실제 연결 실패
- [x] 동일 `messageId` 재시도 조건 재점검
  - 동일 `messageId`는 메모리 내 `queuedMessageIds` 기준으로 중복 enqueue를 막음
  - 재시도는 bounded backoff로 최대 6회까지만 수행
  - 프로세스 재시작 시 pending delivery는 복구되지 않음

## 2. 운영 경로 점검

- [x] `twentyq` webhook 경로 실제 reachability 확인
  - 현재 device config: `http://172.18.0.10:30081/webhook/iris`
  - Redroid 내부 `nc -w 3 172.18.0.10 30081` 실패
  - 2026-03-09 현재 사용하지 않아 device config에서 비활성화
- [x] `turtle-soup` webhook 경로 실제 reachability 확인
  - 현재 device config: `http://172.18.0.12:30082/webhook/iris`
  - Redroid 내부 `nc -w 3 172.18.0.12 30082` 실패
  - 2026-03-09 현재 사용하지 않아 device config에서 비활성화
- [x] 운영 반영 후 webhook queue 동작 기준 정리
  - in-memory bounded queue
  - bounded retry/drop 기준
  - restart recovery 없음
  - README Troubleshooting에 정리 반영

## 3. E2E 검증

- [x] 카카오톡에서 `!도움` 1회 E2E 재확인
  - 사용자 확인 기준 완료
- [x] 카카오톡에서 `/스자` 1회 E2E 확인
  - 현재 비활성 라우트이므로 운영 범위에서 제외된 상태로 정리
- [x] 카카오톡에서 `/스프` 1회 E2E 확인
  - 현재 비활성 라우트이므로 운영 범위에서 제외된 상태로 정리
- [x] 봇 로그와 Iris 로그에서 동일 `messageId` 기준 상호 대조
  - 사용자 최종 확인 기준으로 종료 처리
  - 참고: 2026-03-09 재배포 직후 기존 `hololive` pending delivery 3건은 `HTTP 200`으로 처리되었고, Iris `Replier` 로그상 카카오톡 reply 전송까지 수행됨

## 4. 문서 정리

- [x] `README.md`에 검증 완료 범위 명시
  - `hololive`: 실검증 완료
  - `twentyq`, `turtle-soup`: 미검증 또는 별도 확인 필요
- [x] `docs/H2C_MIGRATION_STATUS.md`를 archive 성격으로 더 명확히 표기하거나 별도 분리
- [x] 운영 webhook 주소 정책 정리
  - redroid에서 실제 도달 가능한 대상 IP 사용
  - `127.0.0.1` bind 포트와 bridge IP 사용 차이 기록

## 5. 현재 결정 사항 메모

- MQ/Redis 활성 경로는 제거됨
- `openclaw` 및 관련 prefix/설정은 제거됨
- 운영 `hololive` webhook은 현재 `http://100.100.1.3:30001/webhook/iris` 기준
- `twentyq`, `turtle-soup`는 현재 미사용이라 Redroid config에서 제거함
- 재배포 후 기존 `ClosedByteChannelException` 증상은 재현되지 않았고, 동일 pending delivery 건들은 1회 시도에서 모두 `200` 성공
- `iris-init.sh`, `iris-watchdog.sh`는 기본값으로 `twentyq`, `turtle-soup` webhook을 다시 활성화하지 않도록 정리함
- 보호 API(`/config`, `/reply`, `/query`)는 `botToken` 미설정 시 fail-open 하지 않고 `503`으로 거부
- 신규 webhook admission은 disk journal 없이 in-memory bounded queue만 사용
- 재시도는 bounded backoff 후 drop 되며 restart recovery는 제공하지 않음
- config 저장 모델은 `webhooks.hololive` 대신 단일 `endpoint`를 사용하며, 구형 설정은 로드 시 자동 이관됨
- image reply 파일 생성은 queue admission 이후 worker에서 수행되고, `IRIS_IMAGE_MEDIA_SCAN=0`으로 media scan을 끌 수 있음
- image cleanup은 `IRIS_IMAGE_DELETE_INTERVAL_MS`, `IRIS_IMAGE_RETENTION_MS`로 운영 조정 가능하며 기본값은 1시간/1일
- bot user_id 탐지 실패 시 기존 configured `botId`를 0으로 덮어쓰지 않고, 최근 `chat_logs.v` JSON fallback 탐지를 수행함
- `IRIS_LOG_LEVEL=NONE`을 지원하며, 기본 로그 레벨은 계속 `ERROR` 유지
- `iris_control`은 Linux host 전용으로 정리됐고 `install_redroid` 등 비핵심 Ubuntu 설치 분기는 제거됨
- host-side control script는 `iris_control` 하나만 유지하고, `IRIS_LOG_LEVEL`을 실제 런타임으로 전달함
