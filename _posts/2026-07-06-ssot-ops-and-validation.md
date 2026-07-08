---
layout: post
author: Kim, DongKi
title:  "Git SSoT ② — 운영·검증·LLM 컨텍스트 다이어트"
date:   2026-07-06
categories: AI
comments: true
---

### TL;DR
* `validate-ssot`가 메타/폴더/상태 규칙을 막아주니까 쓰레기 문서가 안 들어옴
* LLM 컨텍스트는 `INDEX→요약→링크`만 넣어서 가볍게 유지함
* Agent/cron/CI가 역할을 나눠 읽고 검증하고, 쓰기는 convert 흐름에서만 함
* 정본은 `supersedes·archived`로 하나만 유지하고, 기록은 역사로 남김

----

### 들어가며
1편에서 SSoT를 “채팅이 아니라 git 정본에 결정과 규칙을 둔다”고 잡았죠. 문서가 늘면 “지금도 맞나?”가 반복됩니다.

그래서 2편은 운영 얘기입니다. `validate-ssot`로 게이트를 세우고, 컨텍스트 다이어트로 LLM에 넣는 정보량을 줄입니다.

----

### front matter 필수 필드 요약
SSoT 문서(adr/rfc/spec/prd/policy)와 상태 흐름을 유지하려면, 최소한 아래 front matter는 동일한 형태로 가져가야 합니다.

```yaml
title: "결정/요구사항 제목"
type: adr|rfc|spec|prd|policy|draft
scope: "scope-path(예: products/rws or platform/...)"
author: "본인/팀"
created: "YYYY-MM-DD"
updated: "YYYY-MM-DD"
status: proposed|accepted|superseded|archived|draft
stakeholders: ["관련 주체(필요 시)"]
related: ["다른 문서 경로(링크)"]
tags: ["키워드"]
summary: "한 줄 요약"
```
이게 틀리면 INDEX/요약/컨텍스트가 같이 흔들립니다.

----

### validate-ssot가 막는 것
`validate-ssot`는 그냥 형식 체크가 아닙니다. 메타데이터 규칙이 깨지면 통과시키지 않아서, 나중에 INDEX/요약/컨텍스트로 퍼지는 쓰레기를 초기에 끊습니다.

| 검사 항목 | 통과 기준(요약) | 막는 문제 |
|---|---|---|
| 메타데이터 완전성 | title/type/scope/status/tags/summary 필수 채움 | 요약·링크 생성 불가 |
| type-folder / scope-path 일치 | `type`→폴더, scope→경로 세그먼트 정합 | 혼종 문서 |
| ADR 대안(>=2) | 대안 2개 이상 + 기각 사유 | 선택 근거 상실 |
| PRD AC(인수기준) | 검증 가능한 AC + out of scope | 테스트 불가능 요구 |
| status/supersedes 체인 | 워크플로가 시간순, 중복 적음 | 정본이 둘로 갈라짐 |

----

### FAQ
**Q: SSoT가 무거워지면 LLM도 무거워지지 않나?**  
아니요. 전체 repo 넣지 않습니다. INDEX에서 관련 문서 요약+링크만 context에 넣어요. 경로가 있으면 그 파일 우선, 나머지는 요약으로 끊습니다.

**Q: 채팅에 이미 있는데 또 SSoT?**  
채팅은 ephemeral이라 다음 세션/cron이 못 읽습니다. SSoT는 git 정본에 고정해서 같은 근거로 재현하게 합니다.

----

### 컨텍스트 다이어트 — INDEX→요약→Agent
비유는 “창고 vs 서랍”입니다. SSoT repo가 창고, INDEX가 카탈로그고 Agent는 서랍(요약+링크)만 엽니다.

```mermaid
flowchart LR
  A[SSoT 창고(repo)] --> B[INDEX 스캔]
  B --> C[관련 문서 요약]
  C --> D[Agent 컨텍스트(링크 포함)]
  D --> E[새 결정/초안]
```

----

### 읽기·쓰기 주체별 표 (Agent / cron / CI)
운영은 “주체가 섞이지 않게”가 핵심입니다.

| 시점 | 주체 | 읽기 | 쓰기 |
|---|---|---|---|
| 작업 중 | LLM Agent | 티켓 링크 md 로드 | add-decision, convert-to-ssot 초안 |
| 아침/저녁 | Hermes cron | INDEX→validate-ssot | 검증 결과를 반영 |
| 변경 시점 | CI | 변경 md validate-ssot | 규칙 위반이면 차단 |

----

### convert-to-ssot 흐름 (drafts → 검토 → 정식 commit)
짧게만 잡으면 이 순서입니다.

* 입력을 `drafts/`로 변환
* 문서 규칙 검토(검증 포함)
* 문제가 없으면 정식 adr/spec/prd로 commit

----

### supersedes·archived로 정본 유지
정본이 둘이면 컨텍스트도 같이 찢어집니다. 그래서 상태 워크플로는 단순해야 해요.

* `supersedes`: 예전 문서를 “대체됨”으로 묶고, 링크로 다음 정본을 가리킴
* `archived`: 더 이상 쓰지 않지만 기록은 남겨둠

이렇게 하면 “하나의 정답 파일”이 INDEX/요약을 통해 흐릅니다.

----

### 마무리
개인 프로젝트로 시작할 때는, 아래만 먼저 붙이세요.

* `templates/`만 복사해서, front matter 스키마부터 고정
* validate-ssot을 로컬에서 먼저 돌려보고, 실패 케이스를 규칙으로 정리
* INDEX→요약→Agent 흐름이 “진짜로 가벼워지는지”만 확인하고 확장

