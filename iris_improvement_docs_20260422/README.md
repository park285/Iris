# Iris 개선 문서 세트

대상 번들: `Iris-review-bundle-full-20260422T074835Z.tar.gz`

대상 커밋: `cf9f4c9f939d7db38dd81f97d0d398b5a8156283`

작성 기준일: `2026-04-22`

이 문서 세트는 코드 리뷰에서 도출된 수정 사항을 **스텝별 실행 문서**로 분리한 것입니다. 각 스텝은 대상 파일, 문제 원인, 수정 방향, 코드 레벨 변경안, 테스트 계획, 문서화 반영, 완료 기준을 포함합니다.

## 현재 반영 상태

기준 시점: `2026-04-23` 현재 워크트리

- [x] STEP 01 ~ STEP 08 관련 구현이 현재 워크트리에 반영되어 있습니다.
- [ ] STEP 02, STEP 03, STEP 04, STEP 06, STEP 07, STEP 08의 Kotlin/Gradle 재실행 근거는 이번 동기화에 포함하지 못했습니다.
- [x] STEP 09 ~ STEP 22 관련 구현과 상태 메모를 현재 워크트리에 반영했습니다.
- [x] 이번 동기화에서 `01_step_by_step_fix_plan/STEP_01` ~ `STEP_22`, `02_documentation_updates`, `03_pr_and_release_plan`, `04_test_checklists`에 상태 메모를 추가했습니다.
- [x] STEP 01의 `BUNDLE_MANIFEST.txt` 및 재생성 번들 산출물 근거를 현재 워크트리에서 다시 확인했습니다.

## 포함 파일

- `00_전체본_상세_수정_가이드.docx`: 전체 수정 방향과 스텝별 실행안을 합친 Word 문서
- `00_전체본_상세_수정_가이드.md`: 전체본의 Markdown 버전
- `01_스탭별_수정계획/`: 각 수정 항목을 별도 문서로 분리한 작업 지시서
- `02_문서화_수정안/`: README, 운영 문서, 인증 계약 문서에 반영할 문구
- `03_PR_릴리스_계획/`: PR 분리 전략과 릴리스 전 점검표
- `04_테스트_체크리스트/`: 테스트 실행 순서와 시나리오
- `05_코드_스니펫/`: 핵심 코드 변경안만 모아둔 문서

## 스텝 목록

- STEP 01. [P0] closeout 테스트 번들 무결성 복구
- STEP 02. [P0] bridge optional/readiness 계약 정리
- STEP 03. [P0] 설정 변경 API 원자성 보장
- STEP 04. [P0] config 파일 로드 validation 강화
- STEP 05. [P0] shell quoting 및 명령 주입 방어
- STEP 06. [P0] SSE 재연결 이벤트 유실 race 수정
- STEP 07. [P0] SSE frame multiline 안정화
- STEP 08. [P0] SseEventBus actor 예외 안정성 강화
- STEP 09. [P1] Webhook outbox dispatcher 생명주기 동시성 수정
- STEP 10. [P1] Webhook outbox claim 안정화
- STEP 11. [P1] SQLite statement close 누락 수정
- STEP 12. [P1] 종료 순서와 checkpoint flush 수정
- STEP 13. [P1] ReplyAdmissionService actor 소유 상태 경계 수정
- STEP 14. [P1] multipart 입력 처리 강화
- STEP 15. [P1] 요청 body size 계산 overflow 방어
- STEP 16. [P1] 인증 nonce replay/DoS 경계 강화
- STEP 17. [P1] webhook endpoint default/route 정책 정리
- STEP 18. [P1] Rust/Kotlin canonical query 계약 일치화
- STEP 19. [P2] APK checksum 검증 SHA-256 전환
- STEP 20. [P2] readiness error 정보 노출 최소화
- STEP 21. [P2] ConfigStateStore lock 구조 단순화
- STEP 22. [P2] SqliteSseEventStore prune 입력 검증
