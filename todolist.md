# Iris 후속 작업 TODO

> 2026-03-09 기준 운영 조치 및 최종 확인 완료.
> 현재 TODO는 완료 상태이며, 아래 항목은 작업 기록 보존용입니다.

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
- [x] 동일 `messageId` 재시도 시 outbox 잔류 조건 재점검
  - 동일 `messageId`는 같은 outbox 파일을 재사용
  - `RETRY_LATER`면 파일이 남고 5초 뒤 재큐됨
  - delete 실패 시 `SUCCESS`/`DROP` 이후에도 재시작 시 재처리될 수 있음

## 2. 운영 경로 점검

- [x] `twentyq` webhook 경로 실제 reachability 확인
  - 현재 device config: `http://172.18.0.10:30081/webhook/iris`
  - Redroid 내부 `nc -w 3 172.18.0.10 30081` 실패
  - 2026-03-09 현재 사용하지 않아 device config에서 비활성화
- [x] `turtle-soup` webhook 경로 실제 reachability 확인
  - 현재 device config: `http://172.18.0.12:30082/webhook/iris`
  - Redroid 내부 `nc -w 3 172.18.0.12 30082` 실패
  - 2026-03-09 현재 사용하지 않아 device config에서 비활성화
- [x] 운영 반영 후 outbox 디렉터리 잔류 파일 모니터링 기준 정리
  - 정상 시 기대 상태
  - 재시도 허용 시간
  - 수동 정리 기준
  - README Troubleshooting에 정리 반영
  - 2026-03-09 재배포 후 기존 hololive outbox 3건이 모두 성공 처리되어 디렉터리가 비워짐

## 3. E2E 검증

- [x] 카카오톡에서 `!도움` 1회 E2E 재확인
  - 사용자 확인 기준 완료
- [x] 카카오톡에서 `/스자` 1회 E2E 확인
  - 현재 비활성 라우트이므로 운영 범위에서 제외된 상태로 정리
- [x] 카카오톡에서 `/스프` 1회 E2E 확인
  - 현재 비활성 라우트이므로 운영 범위에서 제외된 상태로 정리
- [x] 봇 로그와 Iris 로그에서 동일 `messageId` 기준 상호 대조
  - 사용자 최종 확인 기준으로 종료 처리
  - 참고: 2026-03-09 재배포 직후 기존 `hololive` outbox 3건은 `HTTP 200`으로 처리되었고, Iris `Replier` 로그상 카카오톡 reply 전송까지 수행됨

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
- 운영 `hololive` webhook은 `http://172.18.0.2:30001/webhook/iris` 기준으로 동작 확인
- `twentyq`, `turtle-soup`는 현재 미사용이라 Redroid config에서 제거함
- 재배포 후 기존 `ClosedByteChannelException` 증상은 재현되지 않았고, 동일 outbox 건들은 1회 시도에서 모두 `200` 성공
- `iris-init.sh`, `iris-watchdog.sh`는 기본값으로 `twentyq`, `turtle-soup` webhook을 다시 활성화하지 않도록 정리함
