# STEP 21. ConfigStateStore lock 구조 단순화

우선순위: **P2**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 완료
- 구현 근거: `app/src/main/java/party/qwer/iris/config/ConfigStateStore.kt`
- 검증 근거: `app/src/test/java/party/qwer/iris/config/ConfigStateStoreTest.kt`, `app/src/test/java/party/qwer/iris/ConfigManagerStateTest.kt`, `app/src/test/java/party/qwer/iris/ConfigManagerPersistenceTest.kt`

## 1. 목적

`ConfigStateStore.updateUserState()`와 `mutate()`가 중첩 synchronized 구조입니다. 현재 deadlock은 아니지만 lock 경계를 단순화하는 편이 좋습니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/ConfigStateStore.kt`

## 3. 확인된 위치

- ConfigStateStore.kt — mutate()/updateUserState()

## 4. 현재 문제

reentrant lock이라 당장 deadlock은 아니지만, transform 안에서 외부 callback이 호출되면 lock 경계가 위험해질 수 있습니다.

## 5. 수정 방향

`mutate()` 하나만 lock을 잡게 하거나 `updateUserState()`가 직접 state를 바꾸는 방식으로 단순화합니다. 원자성 수정과 함께 `replace()`를 추가합니다.

## 6. 구현 절차

- [ ] updateUserState의 @Synchronized 제거
- [ ] mutate 내부에서만 state 교체
- [ ] replace(next) 추가
- [ ] transform side effect 점검

## 7. 코드 레벨 변경안

```kotlin
fun updateUserState(...): ConfigRuntimeState =
    mutate { current ->
        // compute next state only
    }
```

## 8. 테스트 계획

- [ ] 동시 update stress 테스트
- [ ] replace current 테스트
- [ ] 동일 값 update dirty 테스트

## 9. 문서화 반영

개발 문서에 state store는 단일 lock 경계를 유지한다고 적습니다.

## 10. 완료 기준

- ConfigStateStore의 lock 경계가 단순하고 예측 가능하다.

## 11. 주의할 리스크

- 원자성 PR과 함께 넣는 것이 좋습니다.
