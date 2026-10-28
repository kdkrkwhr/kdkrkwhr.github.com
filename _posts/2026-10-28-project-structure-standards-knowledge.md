---
layout: post
author: Kim, DongKi
title: "프로젝트 구조를 먼저 정한 이유: 개발 기준과 근거 위키"
date: 2026-10-28 12:48:00 +0900
permalink: /technology/2026/10/28/project-structure-standards-knowledge.html
categories: Technology
comments: true
description: "engineering-standards의 선택형 개발 기준과 kdk-knowledge의 공개 근거 위키를 분리한 이유, 실제 디렉터리 구조와 프로젝트 적용 흐름을 설명합니다."
excerpt: "사람도 구조와 코드를 따라 읽을 수 있어야 합니다. 재사용할 개발 기준과 프로젝트에서 확인한 사실을 서로 다른 저장소에 두고 연결하는 방식을 정리했습니다."
---

### TL;DR

* 프로젝트마다 다시 쓸 개발 기준은 `engineering-standards`에, 공개 코드에서 확인한 프로젝트 지식은 `kdk-knowledge`에 둡니다.
* 공통 원칙과 언어·프레임워크 규칙을 나눠 필요한 것만 선택합니다. Spring의 네 계층은 Spring Boot 프로필의 기본안입니다.
* 중앙 위키는 각 원본 저장소의 코드와 설정을 읽고 근거를 연결합니다. 원본마다 별도 위키를 만들 필요는 없습니다.
* 이 구조는 에이전트에게 작업 기준을 주면서, 사람이 코드의 흐름과 판단 근거를 다시 찾게 하려는 선택입니다.

----

### 들어가며

프로젝트 구조를 정할 때 에이전트가 어디에 코드를 놓을지만 생각하면 부족합니다. 나중에 사람이 요청이 들어오는 곳부터 처리, 저장, 응답, 테스트까지 따라갈 수 있어야 하고요. 프로젝트를 바꿀 때마다 같은 기준을 새로 설명하는 일도 줄이고 싶었습니다.

그래서 역할이 다른 두 저장소를 나눴습니다. [engineering-standards](https://github.com/kdkrkwhr/engineering-standards)는 **앞으로 코드를 어떻게 만들고 검증할지** 고르는 기준이고, [kdk-knowledge](https://github.com/kdkrkwhr/kdk-knowledge)는 **공개된 프로젝트에서 무엇을 확인했는지** 기록하는 중앙 위키입니다. 두 저장소의 공개 파일을 기준으로 현재 구조를 살펴보겠습니다.

----

### 개발 기준은 공통 규칙과 선택 규칙으로 나눴습니다

`engineering-standards`의 실제 경로를 줄이면 다음과 같습니다.

```text
engineering-standards/
├── standards/                 # 공통 원칙, 명명, 구조, API·DTO, 트랜잭션
├── profiles/languages/       # java, python, dart, csharp, typescript
├── profiles/frameworks/      # spring-boot, 새 프로필 템플릿
├── agents/implementation.md  # 요구 → 구현 → 검증 결과
└── templates/                # 소비 프로젝트용 AGENTS.md와 project-profile.md
```

[공통 원칙](https://github.com/kdkrkwhr/engineering-standards/blob/0ab26dea2102fbb801740ba9f853a004761b7e0c/standards/core.md)은 요구와 수용 조건을 먼저 확인하고, 오류를 성공처럼 숨기지 않으며, 중요한 동작을 독립적인 기대값으로 검증하라고 합니다. [명명 규칙](https://github.com/kdkrkwhr/engineering-standards/blob/0ab26dea2102fbb801740ba9f853a004761b7e0c/standards/naming.md)은 `timeoutSeconds`처럼 단위와 의미를 드러내되 표기법은 언어 프로필에 맡깁니다. API가 있으면 [입출력 계약과 DTO](https://github.com/kdkrkwhr/engineering-standards/blob/0ab26dea2102fbb801740ba9f853a004761b7e0c/standards/api-dto.md)를, DB 작업이 있으면 [트랜잭션 경계와 동시성 검증](https://github.com/kdkrkwhr/engineering-standards/blob/0ab26dea2102fbb801740ba9f853a004761b7e0c/standards/transactions.md)을 해당 작업에 맞춰 읽습니다.

여기에 Java, Python, Dart, C#, TypeScript 중 **사용하는 언어의 프로필**을 더합니다. 프레임워크도 별도로 고릅니다. [Spring Boot 프로필](https://github.com/kdkrkwhr/engineering-standards/blob/0ab26dea2102fbb801740ba9f853a004761b7e0c/profiles/frameworks/spring-boot.md)은 새 Spring 프로젝트에서 `presentation / application / domain / infrastructure` 네 계층을 기본안으로 제시합니다. 입력·응답, 사용 사례와 트랜잭션, 도메인 규칙, DB·외부 연동의 책임을 찾기 쉽게 하기 위해서입니다. **모든 언어에 이 네 폴더를 강제하는 규칙은 아닙니다.** 기존 Spring 프로젝트의 구조도 먼저 존중합니다.

----

### 소비 프로젝트에는 선택 이유와 읽는 순서를 남깁니다

아래는 [적용 안내](https://github.com/kdkrkwhr/engineering-standards/blob/0ab26dea2102fbb801740ba9f853a004761b7e0c/README.md)에 있는 경로를 바탕으로 한 **개념 예시**입니다. 어느 특정 프로젝트에 이미 적용됐다는 뜻은 아닙니다.

```text
engineering-standards의 고정 커밋
    └─ 문서 트리를 소비 프로젝트에 복사하고 적용할 기준을 선택
         ├── AGENTS.md                         # 에이전트가 읽을 프로젝트 지침
         ├── docs/engineering-profile.md       # 선택한 규칙·예외·코드 읽기 지도
         ├── docs/engineering-standards/        # 고정한 기준 문서 사본
         └── 실제 코드와 테스트
```

[루트 AGENTS 템플릿](https://github.com/kdkrkwhr/engineering-standards/blob/0ab26dea2102fbb801740ba9f853a004761b7e0c/templates/AGENTS.md)은 계약, 선택 문서, 진입점, 빌드·테스트 명령을 프로젝트의 실제 값으로 채우게 합니다. [적용 프로필](https://github.com/kdkrkwhr/engineering-standards/blob/0ab26dea2102fbb801740ba9f853a004761b7e0c/templates/project-profile.md)에는 기준 커밋과 예외, `요청 → 처리·정책 → 저장·외부 연동 → 응답 → 테스트`의 읽기 지도를 적습니다. [에이전트 구현 지침](https://github.com/kdkrkwhr/engineering-standards/blob/0ab26dea2102fbb801740ba9f853a004761b7e0c/agents/implementation.md)은 요구사항과 구현 파일, 실제 검증 결과를 이어서 보고하게 합니다. 이렇게 해야 결과를 받은 사람도 코드를 어디서부터 읽을지 알 수 있습니다.

기준 문서를 복사한다고 다른 프로젝트에 자동으로 적용되지는 않습니다. 기존 지침을 덮어쓰지 않고 선택과 충돌을 확인해 기록하는 단계가 필요합니다.

----

### 경험과 근거는 중앙 위키에 모읍니다

`kdk-knowledge`는 규칙의 또 다른 사본이 아닙니다. 공개 저장소의 README뿐 아니라 **실제 코드·설정·테스트 파일**을 읽고, 확인한 범위를 프로젝트별 문서에 남기는 곳입니다. 원본 프로젝트마다 위키가 없어도 됩니다.

```text
kdk-knowledge/
├── docs/wiki/*.md          # 사람이 작성하는 프로젝트·기술 해설
├── docs/wiki/sources.json  # 공개 파일 URL과 고정 커밋
├── scripts/build_wiki.py   # 문서와 검색·관계 자료 생성
└── site/
    ├── wiki/               # 읽을 수 있는 문서와 Markdown 사본
    ├── search-index.json   # 본문 검색 데이터
    ├── knowledge.json      # 문서·근거·편집 관계
    └── architecture/       # 관계 지도 화면
```

[문서 작성 절차](https://github.com/kdkrkwhr/kdk-knowledge/blob/288c50ffe4f8928a1b7f8ef7ec1f4536a8af4eec/docs/wiki/maintenance.md)는 출처를 고정 커밋의 공개 URL로 등록하고, 설명을 원본과 다시 대조하게 합니다. [생성기](https://github.com/kdkrkwhr/kdk-knowledge/blob/288c50ffe4f8928a1b7f8ef7ec1f4536a8af4eec/scripts/build_wiki.py)는 Markdown에서 [위키](https://kdkrkwhr.github.io/kdk-knowledge/wiki/), 검색 색인, [관계 지도](https://kdkrkwhr.github.io/kdk-knowledge/architecture/)용 데이터를 만듭니다. 지도에 보이는 연결은 현재 사람이 근거를 읽고 등록한 **편집 관계**입니다. Graphify 분석이나 외부 LLM의 자동 판단 결과로 표시하지 않습니다.

두 저장소를 분리한 이유는 기록의 성격이 달라서입니다. 개발 기준은 프로젝트에 **선택해서 적용할 규칙**이고, 지식 허브는 이미 공개된 구현을 **근거와 함께 설명한 기록**입니다. 위키에 어떤 프로젝트와 기준 저장소가 함께 등장하더라도, 적용 커밋과 결정 기록이 없다면 그 프로젝트가 해당 기준을 따랐다고 말할 수 없습니다.

----

### 마치며

제가 중요하게 보는 구조는 디렉터리 모양 자체보다 **읽는 경로**입니다. 에이전트에게는 요청과 선택한 기준, 검증 조건을 전달하고, 사람에게는 코드맵과 근거 파일을 남깁니다. [기준 문서](https://kdkrkwhr.github.io/engineering-standards/)와 [중앙 지식 허브](https://kdkrkwhr.github.io/kdk-knowledge/)를 분리해 둔 것도 그 경로를 분명하게 만들기 위해서입니다.

새 프로젝트에서는 먼저 기존 계약과 구조를 읽고 필요한 프로필만 고르면 됩니다. 공개 프로젝트를 설명할 때는 원본 파일과 고정 커밋을 확인한 뒤 위키에 남기고요. 두 작업을 구분해야 사람이 나중에 코드를 읽을 때도 기준과 사실을 혼동하지 않습니다.
