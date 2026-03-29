# Iris 코드베이스 리팩토링 설계

> 작성일: 2026-03-21
> 범위: God Object 분해, 동시성 버그 수정, 성능 최적화, 테스트 기반 확보

---

## 배경

코드베이스 분석 결과 다음 4가지 구조적 문제가 식별되었다.

1. **동시성 안전성 결함** — `ConcurrentHashMap.getOrPut` 경쟁 조건, `@Synchronized` + `runBlocking` 중첩
2. **God Object** — `IrisServer`(524줄, 5개 책임), `H2cDispatcher`(504줄, 4개 책임), `KakaoDB`(512줄, 4개 책임), `Replier`(456줄, 4개 책임)
3. **성능 병목** — `SELECT *` 전체 컬럼 fetch, 매 호출 `Cipher.getInstance()`, DB 락 경합
4. **테스트 불가 구조** — `Configurable`·`Replier` companion object 싱글턴이 전역 가변 상태 보유, 인터페이스 부재

## 접근 전략

Risk-Ordered 4단계 접근법을 채택한다.

- Phase 1 → 2 → 3 → 4 순서로 진행
- 각 Phase는 독립적으로 머지 가능
- Phase 내 항목도 개별 커밋 단위로 분리

---

## Phase 1: 동시성·안전성 버그 수정

목적: 프로덕션 안정성 위협 요소 즉시 제거. 기존 동작 변경 없음.

### 1-1. KakaoDecrypt.keyCache 경쟁 조건 수정

- **파일:** `KakaoDecrypt.kt:321`
- **현재:** `ConcurrentHashMap.getOrPut(saltStr) { deriveKey(...) }` — Kotlin `getOrPut`은 `get` 후 `put`의 비원자적 조합
- **문제:** 동일 key 동시 miss 시 PBKDF2 `deriveKey()` 중복 실행
- **수정:** `keyCache.computeIfAbsent(saltStr) { deriveKey(KEY_BYTES, salt, 2, 32) }` 로 교체. `computeIfAbsent`는 동일 key에 대해 람다 최대 1회 실행 보장

### 1-2. @Synchronized + runBlocking 데드락 위험 제거

- **파일:** `DBObserver.kt:48`, `KakaoProfileIndexer.kt:stop()`, `Replier.kt:restartMessageSender()`, `Replier.kt:shutdown()`
- **현재:** `@Synchronized` 모니터 락 보유 상태에서 `runBlocking { job.cancelAndJoin() }` 실행
- **문제:** 재시작/종료 시나리오에서 다른 스레드가 `startXxx()`을 동시 호출 시 모니터 대기 + coroutine 블로킹이 교착 가능
- **수정:** `@Synchronized` 블록 내에서 job reference만 캡처(+ null 설정) → 블록 밖에서 `runBlocking { capturedJob.cancelAndJoin() }` 실행. `Replier.shutdown()` 포함 동일 패턴 전수 적용

### 1-3. ObserverHelper.advanceLastLogId 경로 통일

- **파일:** `ObserverHelper.kt:104-108`
- **현재:** `parseMetadata` 실패 시 `lastLogId = logEntry.id` 직접 변경 후 `null` 반환. `advanceLastLogId()`를 경유하지 않음
- **문제:** lastLogId 변경 경로가 두 갈래로 분기 → 향후 로깅/감사 로직 추가 시 누락 가능
- **수정:** `parseMetadata` 실패 경로도 `advanceLastLogId()` 경유로 통일

---

## Phase 2: God Object 분해

목적: 단일 책임 원칙 적용. 추출 시 기존 public API 시그니처 유지하여 호출부 변경 최소화.

### 2-1. IrisServer.kt 분해 (524줄 → ~200줄 + 3개 추출)

#### QuerySanitizer.kt (신규)

- **추출 대상:** `isReadOnlyQuery()`, `WRITE_KEYWORD_PATTERN`, `SAFE_PRAGMAS`
- **책임:** 읽기 전용 쿼리 판별, pragma 화이트리스트 관리
- **인터페이스:** 불필요 (stateless 유틸리티)

#### ConfigUpdateHandler.kt (신규)

- **추출 대상:** `updateXxxConfig()` 4개 메서드 + 파라미터 파싱·검증 로직
- **책임:** 설정값 파싱, 도메인 유효성 검증, `Configurable` 적용
- **설계:** `fun handle(request: ConfigRequest): ConfigUpdateResponse` 단일 진입점

#### ReplyAdmission.kt (기존 파일 확장)

- **추출 대상:** `enqueueReply()` 내 페이로드 검증 + admission 결정 로직
- **책임:** ReplyRequest 유효성 검사, thread reply 규칙 적용, 큐잉 판정
- **기존 파일:** `ReplyAdmission.kt`에 `ReplyAdmissionStatus`, `ReplyAdmissionResult`, `replyAdmissionHttpStatus()`, `supportsThreadReply()` 이미 존재. 여기에 `IrisServer.enqueueReply()`의 검증 로직을 `fun admitReply(request: ReplyRequest): ReplyAdmissionResult` 함수로 추가

**IrisServer.kt 잔여:** Ktor 라우팅 선언 + 인증 미들웨어 (토큰 검증) + 위 컴포넌트 조합

### 2-2. H2cDispatcher.kt 분해 (504줄 → ~250줄 + 2개 추출)

#### WebhookPayloadBuilder.kt (신규, bridge/ 패키지)

- **추출 대상:** `buildQueuedDelivery()` 내 JSON 직렬화 로직
- **책임:** `RoutingCommand` → webhook JSON 페이로드 변환
- **설계:** `fun build(command: RoutingCommand): String` (JSON 문자열 반환)

#### WebhookHttpClientFactory.kt (신규, bridge/ 패키지)

- **추출 대상:** OkHttp 클라이언트 생성 로직 (h2c vs HTTP1 분기, Dispatcher 설정)
- **책임:** 프로토콜·타임아웃·커넥션 풀 설정에 따른 OkHttpClient 인스턴스 생성
- **설계:** `fun create(transport: String, endpoint: String): OkHttpClient`

**H2cDispatcher.kt 잔여:** 라우트별 Channel + coroutine 워커 생명주기 + 재시도 루프 (`processQueuedDelivery`)

### 2-3. KakaoDB.kt 분해 (512줄 → ~300줄 + 2개 추출)

#### ChatLogDecryptor.kt (신규)

- **추출 대상:** companion object의 `decryptRow()`, `decryptMessageFields()`, `decryptProfileFields()`
- **책임:** SQLite Cursor row → 복호화된 도메인 객체 변환
- **의존:** `KakaoDecrypt`, `ConfigProvider` (botId 참조)

#### BotIdentityDetector.kt (신규)

- **추출 대상:** `detectBotUserId()`, `detectBotUserIdByStringMatch()`, `detectBotUserIdByJsonFallback()`
- **책임:** chat_logs에서 봇 user_id 자동 감지
- **설계:** `fun detect(db: SQLiteDatabase): Long?`

**KakaoDB.kt 잔여:** 연결 관리 (open/close/ATTACH) + 순수 DAO 메서드 (poll, query, upsert)

### 2-4. Replier.kt 분해 (456줄 → ~200줄 + 1개 추출)

#### ImageEncoder.kt (신규)

- **추출 대상:** `decodeBase64Image()`, `detectImageFileExtension()`, `isPngSignature()`, `isJpegSignature()`, `isGifSignature()`, `isWebPSignature()`
- **책임:** Base64 디코딩, 이미지 포맷 감지, 파일 쓰기
- **설계:** 2단계 API — `fun decode(base64: String): DecodedImage` (디코딩 + 포맷감지, `DecodedImage(bytes: ByteArray, extension: String)` 반환) + `fun save(image: DecodedImage, outputDir: File): File` (파일 쓰기). Phase 3에서 검증+디코딩 통합 시 `decode()`만 호출하여 `ByteArray`를 재사용

**Replier.kt 잔여:** 메시지 큐 관리 (Channel + coroutine worker) + Android Intent 구성

---

## Phase 3: 성능 최적화

목적: Phase 2에서 분해된 클래스 내부에서 성능 개선. 각 항목 독립 커밋.

### 3-1. DB I/O 최적화

#### SELECT * 제거 — KakaoDB.kt

- **현재:** `pollChatLogsAfter()`가 `SELECT *` 사용
- **수정:** 필요 컬럼 명시 열거: `_id, id, chat_id, user_id, message, v, created_at, type, thread_id, supplement, attachment`
- **효과:** 불필요 컬럼 deserialize 제거, 배치당 메모리 할당 감소

#### latestLogId 쿼리 개선 — KakaoDB.kt

- **현재:** `ORDER BY _id DESC LIMIT 1`
- **수정:** `SELECT MAX(_id) FROM chat_logs`
- **효과:** rowid 기반 테이블에서 full ORDER BY sort 제거

#### 읽기전용 커넥션 재사용 — KakaoDB.kt

- **현재:** `executeQuery()` 매 호출마다 `openDetachedReadConnection()` + ATTACH 2회 + 즉시 close
- **수정:** lazy singleton 읽기전용 커넥션. `close()` 시에만 해제. **동시성 모델:** Android `SQLiteDatabase`는 WAL 모드에서 reader-writer 병렬을 허용하나, 동일 `SQLiteDatabase` 인스턴스의 동시 read는 내부 lock으로 직렬화됨. 따라서 singleton read connection은 `synchronized(readConnection)` 블록으로 접근을 보호한다. `/query` API와 `getNameOfUserId`가 동일 read connection을 공유하되, 각 접근은 직렬화되어 thread-safety를 보장한다. Primary connection(`dbLock`)과는 완전 독립이므로 폴링 루프와의 경합이 제거된다.
- **효과:** ATTACH 초기화 오버헤드 제거, 연속 `/query` 요청 시 syscall 감소, 폴링-쿼리 간 락 경합 해소

#### sender name 캐시 상향 — KakaoDB.kt

- **현재:** LRU 64
- **수정:** LRU 256
- **효과:** 오픈채팅(수백 명) 환경에서 캐시 미스율 감소 → DB 락 경합 완화

### 3-2. Crypto 최적화

#### Cipher 인스턴스 재사용 — KakaoDecrypt.kt

- **현재:** 매 `decrypt()` 호출마다 `Cipher.getInstance("AES/CBC/NoPadding")`
- **수정:** `ThreadLocal<Cipher>` 도입. `getInstance()`는 스레드당 1회, `init()`만 매 호출 재실행
- **효과:** JCA provider lookup + reflection 비용 제거. 배치 100건 기준 ~300회 → ~1회(스레드당)

#### SecretKeySpec 캐시 통합 — KakaoDecrypt.kt

- **현재:** `keyCache` value가 `ByteArray` (key bytes)
- **수정:** `keyCache` value를 `SecretKeySpec`으로 변경하여 매번 `SecretKeySpec(bytes, "AES")` 재생성 제거
- **효과:** 객체 할당 감소 (미미하지만 캐시 구조 정리 효과)

### 3-3. 네트워크 최적화

#### H2C maxRequestsPerHost 조정 — WebhookHttpClientFactory.kt

- **현재:** h2c/HTTP1 공통 `maxRequestsPerHost = 4`
- **수정:** h2c 모드: `maxRequestsPerHost = 64` (채널 용량과 동일, multiplexing 활용). HTTP1 모드: 4 유지
- **효과:** h2c 단일 커넥션 다중화 시 불필요한 요청 대기 제거

### 3-4. 이미지 이중 디코딩 제거

#### 검증+디코딩 통합 — ImageEncoder.kt

- **현재:** `isValidBase64ImagePayloads()`에서 전체 decode → `sendImages()`에서 다시 decode (2회)
- **수정:** admission 검증 시 `ImageEncoder.decode()`로 1회 decode → 반환된 `DecodedImage` 리스트를 `sendImages()`에 전달 → `ImageEncoder.save()`로 파일 쓰기. 동일 이미지의 중복 decode 제거
- **효과:** 20 MB 이미지 기준 40 MB → 20 MB 단기 할당, GC 압력 50% 감소

### 3-5. 동시성 락 최적화

#### KakaoProfileIndexer 이중 락 분리

- **현재:** `synchronized(this)` 내부에서 `profileStore.upsert()` → `synchronized(dbLock)` 이중 진입
- **수정:** 락 내에서 identity 목록만 수집 → 락 해제 → 개별 upsert
- **효과:** indexer 락 보유 시간 단축, 폴링 루프와의 `dbLock` 경합 완화

#### getNameOfUserId DB 락 경합 분리

- **현재:** `dbLock` 보유 상태에서 name lookup 쿼리 I/O 수행
- **수정:** 읽기전용 별도 커넥션으로 name lookup 분리하여 `dbLock` 비경합
- **효과:** 폴링 루프와 프로파일 인덱서 간 직접 락 경합 제거

---

## Phase 4: 인터페이스 추출 + 테스트 기반 확보

목적: Phase 2에서 분해된 작은 클래스 대상으로 DI 전환 및 테스트 확충.

### 4-1. 인터페이스 추출

| 현재 클래스 | 추출 인터페이스 | 핵심 메서드 |
|------------|----------------|------------|
| `Configurable` (companion object) | `ConfigProvider` | `botId`, `webhookEndpointFor()`, `dbPollingRate`, `messageSendRate` 등 읽기 전용 |
| `Replier` (companion object) | `MessageSender` | `sendMessage()`, `sendPhoto()`, `sendMultiplePhotos()` |
| `KakaoDB` | `ChatLogRepository` | `pollChatLogsAfter()`, `resolveSenderName()`, `latestLogId()` |
| `KakaoDB` | `ProfileRepository` | `upsertObservedProfile()` |
| `ChatLogDecryptor` | `MessageDecryptor` | `decryptRow()` |

`QuerySanitizer`, `WebhookPayloadBuilder`, `ImageEncoder`, `BotIdentityDetector`는 stateless이므로 인터페이스 불필요.

### 4-2. 싱글턴 → 인스턴스 전환

#### Configurable → ConfigManager

- **현재:** companion object에 `@Volatile snapshotValues`, `effectiveValues`, `isDirty`, `onMessageSendRateChanged` 가변 상태
- **전환:** `class ConfigManager(configPath: String) : ConfigProvider`
- **init 블록:** `loadConfig()` 수행. Shutdown hook은 등록하지 않음
- **주입:** `Main.kt`에서 인스턴스 생성 → 모든 소비자에 생성자 주입
- **Shutdown ownership 원칙:** shutdown 조율은 `Main.kt`가 단독 보유. 각 컴포넌트는 `close()`/`stop()` 메서드만 노출하고, `Main.kt`의 shutdown hook이 순서대로 호출

#### Replier → ReplyService

- **현재:** companion object에 `messageChannel`, `messageSenderJob`, `coroutineScope` 가변 상태
- **전환:** `class ReplyService(hiddenApi: AndroidHiddenApi, config: ConfigProvider) : MessageSender`
- **주입:** `Main.kt`에서 인스턴스 생성 → `IrisServer`에 주입

DI 프레임워크 없이 수동 DI (Composition Root = `Main.kt`). 이 프로젝트 규모에서 Koin/Dagger는 과잉.

### 4-3. 테스트 확충

Phase 2에서 추출된 각 클래스에 대해 table-driven + failure case 테스트를 추가한다. 기존 `CommandParserTest`, bridge 테스트는 유지.

| 대상 클래스 | 테스트 파일 | 핵심 케이스 |
|------------|-----------|------------|
| `QuerySanitizer` | `QuerySanitizerTest.kt` | SQL injection 패턴, pragma 화이트리스트, `WITH ... SELECT`, 빈 입력 |
| `ChatLogDecryptor` | `ChatLogDecryptorTest.kt` | 정상 복호화, 빈 필드, 잘못된 encType, botId 일치/불일치 |
| `WebhookPayloadBuilder` | `WebhookPayloadBuilderTest.kt` | 라우트별 JSON 스키마, thread metadata 포함/미포함, 특수문자 이스케이프 |
| `ConfigUpdateHandler` | `ConfigUpdateHandlerTest.kt` | 유효값, 경계값, 잘못된 타입, pendingRestart 판정 |
| `ImageEncoder` | `ImageEncoderTest.kt` | PNG/JPEG/GIF/WebP 감지, 손상된 Base64, 빈 입력, 대용량 이미지 |
| `BotIdentityDetector` | `BotIdentityDetectorTest.kt` | `isMine` 문자열 매칭, JSON fallback, 감지 실패 |

---

## 변경 영향 요약

| Phase | 변경 파일 수 | 신규 파일 수 | 테스트 추가 | 독립 머지 |
|-------|-------------|-------------|------------|-----------|
| 1 | 4 | 0 | 0 | O (항목별) |
| 2 | 4 (분해 대상) + 1 (기존 확장) | 7 (신규 추출) | 0 | O (파일별) |
| 3 | ~6 | 0 | 0 | O (항목별) |
| 4 | ~8 (DI 전환) | 5 (인터페이스) + 6 (테스트) | 6 | O (클래스별) |

## 제약 조건

- 각 Phase 커밋 시 `./gradlew lint ktlintCheck assembleDebug test` 통과 필수
- 리팩토링과 기능 추가를 혼합하지 않음
- 추출 시 기존 public API 시그니처 유지 → 호출부 변경 최소화
- Phase 2 추출 클래스는 일반 `class`로 선언 (Phase 4에서 인터페이스 대상)
