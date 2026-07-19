---
layout: post
author: Kim, DongKi
title:  "Hermes 스킬(Skill) 작성과 관리"
date:   2026-07-19
categories: AI
comments: true
---

### TL;DR
* Hermes 스킬은 재사용 가능한 절차 메모리다. 자주 하는 작업 유형(설치, 배포, 코드 리뷰 등)의 단계·명령·함정·검증을 SKILL.md 한 파일에 담는다.
* 스킬 파일은 YAML front matter(`name`, `description`) + 마크다운 본문으로 구성되며, `skill_manage`로 생성·수정·삭제한다.
* 스킬은 요청과 관련 있을 때 자동 로드되고, 필요하면 명시적으로 강제 로드(`skill_view`)할 수 있다.
* 새 스킬은 `D:\develop\e2e\hermes\skills\`에, 이미 있는 스킬은 현재 위치에서 수정한다.

----

### 들어가며
반복되는 작업마다 매번 프롬프트를 다시 설명하는 건 비효율이다. "이 작업은 저번에 하던 방식대로 하면 되는데"라는 맥락을 에이전트가 기억하지 못하면, 같은 삽질을 매번 반복한다. Hermes 스킬은 그 절차를 파일로 남겨 다음 실행 때 자동으로 꺼내 쓰게 만든다. 이 글은 스킬의 구조와 `skill_manage` 명령, 그리고 실제로 스킬을 언제 만드는지 정리한다.

----

### 스킬의 구조
SKILL.md 하나가 스킬 전체다. 최소 구성은 front matter의 `name`과 `description`이다.

```yaml
---
name: korean-spell-check
description: Use Nara/PNU Korean spell-check surfaces conservatively to proofread Korean text, chunk long input, and return change-focused correction suggestions.
license: MIT
metadata:
  category: writing
  locale: ko-KR
---
```

* `name`: 소문자·하이픈, 64자 이내. 스킬 목록과 호출 키로 쓰인다.
* `description`: 무엇을, 언제 쓰는지 적는다. 트리거 조건을 적을수록 자동 로드 정확도가 올라간다.
* 본문: 트리거 조건, 번호 매긴 단계(정확한 명령 포함), pitfalls, 검증 단계를 넣는다.

----

### skill_manage로 다루기
스킬은 `skill_manage` 도구 하나로 생성부터 삭제까지 처리한다.

| 동작 | 용도 |
|------|------|
| `create` | SKILL.md 전체 + 선택적 카테고리로 신규 생성 |
| `patch` | old/new 문자열 치환. 오타·명령 수정 시 권장 |
| `edit` | SKILL.md 전체 재작성. 대폭 개편 시에만 사용 |
| `delete` | 삭제. 내용을 합치면 `absorbed_into=<상위>`, 폐기하면 `""` |
| `write_file` / `remove_file` | 스킬 폴더 내 참조 파일(스크립트·템플릿) 추가/삭제 |

pinned 스킬은 삭제가 거부되며 `hermes curator unpin <name>` 안내가 뜬다. 수정(`patch`/`edit`)은 통과하므로 최신화는 막히지 않는다. 새 스킬은 `D:\develop\e2e\hermes\skills\`에, 이미 존재하는 스킬은 현재 위치에서 수정한다.

----

### 스킬이 로드되는 시점
에이전트는 응답 전 등록된 스킬 목록을 훑고, 요청과 관련 있으면 `skill_view`로 SKILL.md를 로드해 그 절차를 따른다. 관련성 판단은 `description`의 트리거 문구에 좌우되므로, description을 구체적으로 쓸수록 로드율이 좋아진다. 특정 작업에서 반드시 필요한 스킬은 명시적으로 강제 로드할 수도 있다.

----

### 재사용 워크플로 — 언제 스킬을 만드는가
* 5회 이상의 도구 호출로 이어진 복잡 작업을 성공적으로 끝냈을 때
* 사용자 교정으로 바뀐 접근이 효과적이었을 때
* 비표준 워크플로(특정 API 엔드포인트, 프로젝트 컨벤션)를 발견했을 때
* 단순 일회성 작업은 스킬로 남기지 않는다

생성·삭제 전에는 사용자 확인을 받는다. `devops`, `data-science` 같은 카테고리로 폴더를 묶어 관리한다. 같은 유형의 작업을 두 번 이상 했는데 스킬이 없다면, "이거 스킬로 만들 가치 있네"라고 판단하고 만든다.

----

### 마무리
* `description`에 트리거를 구체적으로 적을 것 — 자동 로드의 핵심이다.
* 사소한 수정은 `patch`, `edit`은 가급적 피할 것. 기록 이력이 patch가 깔끔하다.
* 스킬은 "절차"지 "지식 덩어리"가 아니다. 단계·명령·함정이 들어가야 재사용 가치가 있다.
* 이미 있는 스킬을 쓰다 문제를 발견하면 바로 `patch`로 고친다. 사용자가 알려준 방식이 더 나았다면 스킬에 반영한다.

다음 글에서는 로컬 LLM(Ollama)로 에이전트를 돌리는 구성법을 이어서 다룬다.
