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
1편에서 SSoT를 “채팅이 아니라 git 정본에 결정과 규칙을 둔다”로 잡았습니다. 근데 여기서 사람들이 또 한 번 넘어지는 지점이 생깁니다. 문서는 쌓이기 시작하면, 어느 순간부터 “아 이거 지금도 맞나?”가 매번 의심된다는 거죠.

그래서 2편은 운영 얘기입니다. 검증 게이트(`validate-ssot`)로 규칙을 강제하고, 컨텍스트 다이어트로 LLM에 넣는 정보량을 줄이는 쪽으로 갑니다. SSoT가 커질수록 AI 컨텍스트도 같이 무거워진다는 걱정이 나오는데, 그건 설계로 피할 수 있습니다.

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

이게 맞지 않으면, “문서가 존재한다”는 사실만 있고 “문서가 작동한다”는 보증이 없어집니다.

----

### validate-ssot가 막는 것
`validate-ssot`는 그냥 형식 체크가 아닙니다. 메타데이터 규칙이 깨지면 통과시키지 않아서, 나중에 INDEX/요약/컨텍스트로 퍼지는 쓰레기를 초기에 끊습니다.

| 검사 항목 | 통과 기준(요약) | 막는 문제 |
|---|---|---|
| 메타데이터 완전성 | title/type/scope/상태 등 필수 항목 채움 | 요약/링크 생성이 불가능한 문서 |
| type-folder / scope-path 일치 | `type`과 폴더, scope와 경로 세그먼트가 맞음 | “겉은 adr인데 내용은 spec” 같은 혼종 |
| ADR 대안(>=2) | 대안이 2개 이상 + 기각 사유 존재 | 선택 근거가 사라진 결정 |
| PRD AC(인수기준) | 검증 가능한 acceptance criteria + out of scope 명시 | 테스트 불가능한 요구사항 |
| status 워크플로 | adr는 proposed→accepted→superseded(등) | 상태가 시간 순서대로 쌓이지 않음 |
| 중복·supersedes 체인 | superseded 체인이 자연스럽고 중복이 적음 | “정본이 둘”이 되는 사고 |

Draft(초안)는 TBD 마커 같은 건 WARN으로 두고, FAIL은 진짜 규칙 위반에만 걸어두는 게 포인트였습니다.

----

### FAQ
**Q: SSoT가 무거워지면 LLM도 무거워지지 않나?**  
아니요. 전체 repo를 통째로 넣지 않습니다. INDEX를 스캔해서 “관련된 문서의 요약+링크”만 context에 넣는 방식이에요. 티켓/작업 단서에 경로가 있으면 그 파일 우선이고, 나머지는 요약 레벨로 끊습니다.

**Q: 채팅에 이미 있는데 또 SSoT?**  
채팅은 ephemeral입니다. 다음 세션, 다른 에이전트, 그리고 cron이 “동일한 근거 문서”를 읽을 수가 없어요. SSoT는 그 근거를 git 정본으로 고정해두는 역할입니다.

----

### 컨텍스트 다이어트 — INDEX→요약→Agent
제가 제일 좋아하는 비유는 “창고 vs 서랍”입니다. SSoT repo는 창고고, INDEX는 창고 카탈로그입니다. Agent에게는 서랍만 주면 돼요. 서랍에는 요약(핵심만)과 링크(정밀 근거로 가는 길)만 들어가게 하죠.

```mermaid
flowchart LR
  A[SSoT 창고(repo)] --> B[INDEX 스캔]
  B --> C[관련 문서 요약]
  C --> D[Agent 컨텍스트(링크 포함)]
  D --> E[새 결정/초안]
```

이렇게 다이어트하면, SSoT가 커져도 LLM 컨텍스트 크기는 “필요한 것만” 남습니다. 결국 목표는 한 가지예요. 한 사실은 한 곳에 두고, 필요한 순간에만 꺼내 쓰는 것.

----

### 읽기·쓰기 주체별 표 (Agent / cron / CI)
운영에서 중요한 건 주체가 각각 뭘 책임지느냐입니다. 역할이 섞이면 검증이 누락되고, 누락은 중복으로 이어지거든요.

| 시점 | 주체 | 읽기 | 쓰기 |
|---|---|---|---|
| 작업 중 | LLM Agent | 티켓/SSoT 경로의 md 직접 로드 | add-decision, convert-to-ssot로 초안 생성 |
| 아침/저녁 | Hermes cron | INDEX 기반으로 validate-ssot 실행(검증 중심) | 정식 publish는 규칙에 맞춰 반영 |
| 변경 시점 | CI | 변경된 md에 대해 validate-ssot 자동 검증 | 규칙 위반이면 병합 차단(또는 실패 처리) |

----

### convert-to-ssot 흐름 (drafts → 검토 → 정식 commit)
convert-to-ssot은 초안을 만드는 쪽에 집중합니다. 흐름은 짧게 이렇게 잡았어요.

* 외부 입력(문서/기본 텍스트)을 `drafts/`로 변환
* 문서 형식+내용 규칙을 사용자 검토
* 문제가 없으면 정식 adr/spec/prd로 “commit” (검증도 같이 붙게)

여기서 초안을 확정하지 않는 게 핵심입니다. 확정은 검증/결정 프로세스를 거친 뒤로 미룹니다.

----

### supersedes·archived로 정본 유지
정본이 둘이면, 결국 컨텍스트도 두 개로 찢어집니다. 그래서 상태 워크플로는 단순해야 해요.

* `supersedes`: 예전 문서를 “대체됨”으로 묶고, 링크로 다음 정본을 가리킴
* `archived`: 더 이상 쓰지 않지만 기록은 남겨둠

이렇게 하면 “언제나 하나의 정답 파일”이 INDEX/요약을 통해 흘러갑니다. 기록은 남기되, 앞으로의 작업은 한 방향으로만 굴러가요.

----

### 마무리
개인 프로젝트로 시작할 때는, 거창하게 하려고 할수록 망합니다. 저는 아래 2~3개만 먼저 붙였어요.

* `templates/`만 복사해서, front matter 스키마부터 고정
* validate-ssot을 로컬에서 먼저 돌려보고, 실패 케이스를 규칙으로 정리
* INDEX→요약→Agent 흐름이 “진짜로 가벼워지는지”만 확인하고 그 다음 확장

다이어트는 반복이고, 운영은 결국 습관입니다.

