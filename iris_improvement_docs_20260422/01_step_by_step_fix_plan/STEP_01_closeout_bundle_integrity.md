# STEP 01. closeout 테스트 번들 무결성 복구

우선순위: **P0**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 완료
- 구현 근거: `tests/closeout_packet_scripts_test.sh`, `scripts/replay_closeout.sh`, `scripts/verify_closeout_packet.py`, `scripts/closeout_facts.py`
- 검증 근거: `bash tests/closeout_packet_scripts_test.sh` 통과, `scripts/export-closeout-packet.sh <tmpdir>` 재생성 후 `python3 scripts/verify_closeout_packet.py <packet_dir>` 통과
- 메모: `BUNDLE_MANIFEST.txt`가 `scripts/replay_closeout.sh`, `scripts/verify_closeout_packet.py`, `scripts/closeout_facts.py`, `artifacts/metadata/consistency-check.json`을 포함하는 것까지 확인했습니다.

## 1. 목적

현재 테스트가 참조하는 closeout 스크립트가 실제 번들에 없어 테스트가 즉시 실패합니다. 테스트와 번들의 자가완결성을 먼저 복구해야 합니다.

## 2. 대상 파일

- `tests/closeout_packet_scripts_test.sh`
- `scripts/replay_closeout.sh`
- `scripts/verify_closeout_packet.py`
- `scripts/closeout_facts.py`
- `BUNDLE_MANIFEST.txt`

## 3. 확인된 위치

- tests/closeout_packet_scripts_test.sh:12 — replay_closeout.sh 복사
- tests/closeout_packet_scripts_test.sh:13 — verify_closeout_packet.py 복사
- tests/closeout_packet_scripts_test.sh:14 — closeout_facts.py 복사

## 4. 현재 문제

`tests/closeout_packet_scripts_test.sh`는 closeout 패킷 검증용 스크립트를 복사해서 테스트 환경을 구성합니다. 그런데 실제 번들에는 `scripts/replay_closeout.sh`, `scripts/verify_closeout_packet.py`, `scripts/closeout_facts.py`가 없습니다. 이 상태는 단순한 테스트 누락이 아니라, 번들이 테스트 기준으로 자가완결적이지 않다는 뜻입니다.

이 문제를 방치하면 CI에서 실패하거나, 누군가 테스트를 임시로 제외하는 식으로 품질 기준이 흐려질 수 있습니다. closeout 기능이 아직 제품 범위에 있다면 누락 파일을 복구해야 하고, 기능이 제거되었다면 테스트도 함께 정리해야 합니다.

## 5. 수정 방향

권장 방향은 closeout 기능을 유지하는 것입니다. 이미 테스트가 존재하므로 기능 계약으로 보는 편이 안전합니다. 누락된 파일을 복구하고, 테스트 시작 시 필수 파일 존재 여부를 먼저 검증하도록 바꿉니다.

closeout 기능이 제거된 경우에는 테스트를 조건부 skip으로만 덮지 말고, 테스트 자체를 제거하거나 새로운 기능 범위에 맞게 재작성해야 합니다.

## 6. 구현 절차

- [ ] 필수 스크립트 존재 여부를 테스트 초반에 검사합니다.
- [ ] 누락된 스크립트가 실제로 필요한 기능이면 `scripts/` 아래에 복구합니다.
- [ ] 세 스크립트가 Git 추적 대상인지 확인합니다.
- [ ] 압축 번들 재생성 시 manifest에 세 파일이 들어가는지 확인합니다.
- [ ] closeout 기능이 제거된 경우 테스트 목적을 재정의하거나 제거합니다.

## 7. 코드 레벨 변경안

```bash
required_scripts=(
  "scripts/replay_closeout.sh"
  "scripts/verify_closeout_packet.py"
  "scripts/closeout_facts.py"
)

for file in "${required_scripts[@]}"; do
  if [[ ! -f "$repo_root/$file" ]]; then
    echo "missing required closeout script: $file" >&2
    exit 1
  fi
done
```

복구 후 기대 파일 구조입니다.

```text
scripts/
  replay_closeout.sh
  verify_closeout_packet.py
  closeout_facts.py
  zygisk_next_bootstrap.sh
  zygisk_next_watchdog.sh
  check-bridge-boundaries.sh
```

## 8. 테스트 계획

- [ ] `bash tests/closeout_packet_scripts_test.sh` 단독 실행
- [ ] 전체 shell test 묶음 실행
- [ ] 압축 번들 재생성 후 `BUNDLE_MANIFEST.txt`에 세 파일 포함 확인
- [ ] 실행 권한이 필요한 파일은 `chmod +x` 확인

## 9. 문서화 반영

테스트 문서에는 closeout 스크립트가 어떤 산출물을 검증하는지 적어야 합니다. 특정 릴리스 빌드에서만 closeout 패킷이 생성된다면, 테스트 실행 조건도 명확히 문서화합니다.

## 10. 완료 기준

- closeout 테스트가 파일 누락 없이 통과한다.
- `scripts/` 아래 closeout 관련 파일이 실제 번들에 포함된다.
- 테스트 실패 시 누락 파일명이 명확히 출력된다.

## 11. 주의할 리스크

- 파일만 추가하고 기능 동작을 검증하지 않으면 껍데기 테스트가 될 수 있습니다.
- 조건부 skip을 남용하면 CI에서 영구적으로 실행되지 않는 죽은 테스트가 될 수 있습니다.
