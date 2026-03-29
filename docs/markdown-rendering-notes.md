# Kakao Markdown Reply 렌더링 노트

최종 확인: 2026-03-28 KST

이 문서는 Iris의 `/reply-markdown` 및 in-thread `/reply` text가 타는 Kakao share intent 기반 markdown 렌더링 동작을 실측한 결과를 정리한 것입니다.
코드 경로 설명과 실제 렌더링 결과를 분리해서 기록합니다.

## 전송 경로 요약

- `/reply-markdown`은 Kakao direct share intent에 `markdown=true`, `markdownParam=true`를 넣어 전송한다.
- in-thread `/reply` text도 현재는 같은 share/graft lane을 사용한다.
- 관련 코드:
  - `app/src/main/java/party/qwer/iris/ReplyMarkdownIntent.kt`
  - `app/src/main/java/party/qwer/iris/ReplyService.kt`

## 현재 확인된 렌더링 결과

아래 표는 2026-03-28 KST에 실제 KakaoTalk 화면에서 확인한 결과다.

| 문법 | 예시 | 결과 | 비고 |
| --- | --- | --- | --- |
| Bold | `**bold**` | 렌더링됨 | 일부 문맥 예외 있음 |
| Italic | `*italic*`, `_italic_` | 렌더링됨 | |
| Inline code | `` `code` `` | 렌더링됨 | 백틱은 보이지 않음 |
| Unordered list | `- item` | 렌더링됨 | `·` 형태 글머리표로 표시 |
| Ordered list | `1. item` | 렌더링됨 | |
| Strike | `~~strike~~` | 렌더링 안 됨 | `~~`가 raw text로 남음 |
| Heading | `# h1`, `## h2` | 렌더링 안 됨 | `#`가 raw text로 남음 |
| Blockquote | `> quote` | 렌더링 안 됨 | `>`가 raw text로 남음 |
| Task list | `- [x]`, `- [ ]` | 렌더링 안 됨 | 체크박스가 아닌 plain text |
| Fenced code block | ````` ```lang ... ``` ````` | 렌더링 안 됨 | code block으로 보이지 않음 |
| Table | `| a | b |` | 렌더링 안 됨 | 표가 아닌 plain text |
| Markdown link | `[label](https://example.com)` | 렌더링 안 됨 | label link로 바뀌지 않음 |
| Autolink | `<https://example.com>` | 부분 동작 | markdown보다는 URL 자동 감지에 가까움 |

## 재현된 문제 조건

아래 조합에서 bold가 안정적으로 렌더링되지 않는 사례를 확인했다.

- 긴 괄호 포함 구절을 `**...**`로 감싼다.
- 닫는 `**` 바로 뒤에 공백 없이 한국어 조사나 접미사가 붙는다.

### 최소 재현 결과

| 케이스 | 입력 | 결과 |
| --- | --- | --- |
| A | `**탄소를 포함한 분자**의 구조` | 렌더링됨 |
| B | `**탄소-수소 결합**의 구조` | 렌더링됨 |
| C | `**탄소를 포함한 분자(특히 탄소-수소 결합이 있는 화합물)**의 구조` | 렌더링 안 됨 |
| D | `**탄소를 포함한 분자(특히 탄소-수소 결합이 있는 화합물)** 의 구조` | 렌더링됨 |

## 해석

직접 확인된 사실:

- 짧은 bold는 정상 동작한다.
- 긴 괄호 포함 구절도 standalone bold 자체는 동작할 수 있다.
- 다만 `**긴 괄호 포함 구절**의` 같은 붙임 문맥에서는 raw `**`가 남는 사례가 재현됐다.
- 같은 문자열도 닫는 `**` 뒤에 공백을 추가하면 정상 렌더링됐다.

추론:

- Kakao 내부 markdown 파서가 긴 괄호 포함 구절 뒤에 한국어 조사가 바로 이어질 때 마커 경계를 안정적으로 처리하지 못할 가능성이 높다.
- 이는 실측 기반 추론이며, Kakao 내부 파서 구현을 직접 확인한 것은 아니다.

## 회피 방법

- `**...**의` 대신 `**...** 의`처럼 한 칸 띄운다.
- 괄호가 긴 경우 bold 범위를 줄인다.
  - 예: `**탄소를 포함한 분자**(특히 탄소-수소 결합이 있는 화합물)의 구조`
- 긴 설명 문장은 강조 구간과 조사 사이를 문장부호나 공백으로 끊어서 보낸다.

## 코드 참고

- `app/src/main/java/party/qwer/iris/ReplyMarkdownIntent.kt`
  - Kakao direct share intent에 markdown 관련 extra를 넣는다.
- `app/src/main/java/party/qwer/iris/ReplyService.kt`
  - `/reply-markdown`과 in-thread `/reply` text가 share 기반 text lane으로 합류한다.
