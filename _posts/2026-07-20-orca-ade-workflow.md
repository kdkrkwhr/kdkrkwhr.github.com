---
layout: post
author: Kim, DongKi
title:  "Orca ADE로 코딩 에이전트 함대 운영하기 — Discord·Hermes와 병행"
date:   2026-07-20
permalink: /2026/07/20/orca-ade-workflow.html
categories: AI
comments: true
---

### TL;DR
* **Orca(onorca.dev)** 는 Cursor 대체가 아니라, Claude Code·Codex·Cursor CLI·Hermes 등 **코딩 에이전트 CLI를 한 데스크톱에서 병렬로** 돌리는 ADE(Agent Development Environment)다.
* 나는 **Discord + Hermes = 지휘/알림**, **Orca = 손(실행 UI)** 로 역할을 나눴다. 디스코를 Orca로 바꾸는 게 아니다.
* 레포마다 worktree(또는 메인 체크아웃)를 붙이고, 탭마다 에이전트 세션을 띄운다. **“병렬”은 협업 공유가 아니라 서로 다른 이슈를 동시에** 처리하는 뜻이다.
* Hermes는 Orca 안에서 CLI 에이전트로도 띄울 수 있고, `orca-status` 플러그인으로 세션 이벤트가 Orca UI에 반영된다.

----

### 들어가며
Hermes로 Discord에서 일을 시키고, Cursor ACP로 코드를 고치는 패턴을 쓰다 보면 “에이전트는 늘었는데 창이 부족하다”는 순간이 온다. Claude Code는 SSoT 쪽, Cursor는 Java 백엔드, Hermes는 cron·칸반 알림… **툴은 여러 개인데 화면은 하나**인 상태.

Orca는 이 문제를 “LLM 오케스트레이션”이 아니라 **프로세스·워크스페이스 오케스트레이션**으로 푼다. LangGraph처럼 프롬프트 그래프를 그리는 게 아니라, **설치된 CLI 에이전트를 worktree 단위로 동시에** 띄워 준다.

----

### 내 스택에서 Orca의 위치

![Discord · Hermes · Orca 역할 분리](/assets/images/orca/orca-stack-diagram.png)

| 레이어 | 도구 | 하는 일 |
|--------|------|--------|
| 지휘 | Discord | `@봇` 멘션, cron 결과, 칸반 알림 |
| 오케스트레이션 | Hermes | 게이트웨이, 프로필, cron, 칸반 디스패치 |
| 실행 UI | **Orca** | 레포별 worktree + 터미널 + 에이전트 탭 |
| 코딩 | Cursor ACP / Claude Code / Hermes CLI | 실제 파일 수정·빌드·PR |

Discord를 Orca로 **대체**하려고 하면 바로 막힌다. Orca에는 채팅 홈·멀티봇 토론·cron deliver가 없다. 반대로 Discord만으로는 **worktree 4개 + 터미널 5개**를 한 화면에서 보기 어렵다. 그래서 **병행**이 맞다.

----

### 실제 적용 — worktree + 에이전트 탭

Orca를 켜면 레포를 “워크스페이스”로 등록하고, 브랜치(또는 worktree)마다 탭을 연다. 내 PC에는 대략 이런 구성이다.

* Java 백엔드 레포 — `develop` 브랜치, Claude·Hermes 세션
* Git SSoT 레포 — 스펙·DDL 정리용 셸
* 레거시 백엔드 / TTS 엔진 레포 — 브랜치별로 분리

사이드바에는 레포 카드가 한눈에 모인다.

![Orca 사이드바 — 레포 목록](/assets/2026-07-20-orca-repos.png)

CLI로 상태를 보면 worktree 목록과 에이전트 타입(`hermes`, `claude` 등)이 JSON으로 나온다.

```bash
# Orca CLI (설치본 내 index.js 경로 예시)
node "%LOCALAPPDATA%/Programs/orca/resources/app.asar.unpacked/out/cli/index.js" worktree ps --json
```

**에이전트 패널** — 워크스페이스별로 작업 카드가 쌓이고, Claude/Hermes 세션 상태·메시지 수·마지막 활동 시각이 보인다.

![Orca 에이전트 패널 — 워크스페이스별 세션](/assets/images/orca/orca-agent-panel.png)

스크린샷에 터미널 출력(JWT·DB 비밀번호 등)이 잡히지 않게 **UI 영역만** 잘라 썼다. 전체 화면 캡처는 블로그에 그대로 올리면 안 된다.

----

### Hermes 연동 — orca-status 플러그인

Orca가 Hermes를 에이전트로 띄우면, Hermes 쪽에 **`orca-status` 플러그인**이 붙는다. 세션 시작·툴콜 전후·승인 요청 같은 이벤트를 로컬 HTTP 훅으로 Orca에 POST한다.

```text
POST http://127.0.0.1:{ORCA_AGENT_HOOK_PORT}/hook/hermes
X-Orca-Agent-Hook-Token: ...
```

덕분에 Orca UI에서 “지금 Hermes가 어떤 툴을 쓰는지”를 Discord 채팅과 별도로 **실행 패널**에서 볼 수 있다. area(가상 사무실)는 여전히 **구독·시각화** 쪽이고, Orca는 **터미널·worktree** 쪽이다.

----

### Orca vs LLM 오케스트레이션 vs 칸반

| | Orca | LangGraph/CrewAI | Hermes Kanban |
|--|------|------------------|---------------|
| 단위 | CLI 프로세스 + git worktree | API 호출·툴 그래프 | 보드 카드 + assignee |
| 병렬 의미 | **다른 이슈** 동시 처리 | 분기/맵 리듀스 | **다른 카드** 동시 디스패치 |
| 협업 | ❌ (상태 공유 X) | 워크플로 설계 | ✅ handoff·댓글 |

“에이전트끼리 한 작업을 나눠 한다”기보다 **함대를 동시에 출격**시키는 도구에 가깝다. 한 카드를 여러 에이전트가 같이 밀려면 Hermes 칸반이 맞고, Orca는 **한 사람이 여러 CLI 창을 IDE처럼** 쓰는 느낌이다.

----

### 쓸 만했던 점 / 아쉬운 점

**좋았던 것**
* 레포 4개를 **한 앱**에서 탭 전환 — Discord↔터미널 왕복이 줄었다.
* Claude Code + Hermes + Cursor를 **같은 worktree 컨텍스트**에 두기 쉽다.
* 모바일 앱으로 원격 조종(세션 attach) — “밖에서 디스코로 시키고, 집 PC Orca에서 돌아가는지 확인” 패턴.

**아쉬운 것**
* Hermes 칸반·cron과 **자동 연동은 없음** — 카드 상태가 Orca에 안 뜬다.
* Discord·승인·감사로그와 **레이어가 분리** — Bridge/프로토콜 없으면 “디스코 Approve → Orca 실행”은 직접 엮어야 한다.
* 이미 칸반으로 배분 중이면 **이득이 얇을 수 있음** — 이슈 5개를 동시에 갈길 때 빛난다.

----

### 정리

Orca는 **“클라우드 멀티프로토콜”** 이 아니라 **데스크톱 ADE**다. 나는 Discord·Hermes·Cursor·Claude·Nous를 유지한 채, **코딩만 Orca 화면으로 모았다**. 디스코 대체재가 아니라 **옆자리 함대 컨트롤러**로 두는 게 맞다.

다음에 다루고 싶은 건 Orca 밑 레이어 — **워크스페이스 계약·세션 제어·행동 영수증** 같은, ADE들이 아직 표준화하지 못한 얇은 프로토콜이다.

----

### 참고

* [Orca — onorca.dev](https://www.onorca.dev/)
* [Hermes Agent 소개 글](/2026/07/01/hermes-agent-intro.html)
* [ACP 이해하기](/2026/07/15/acp-protocol.html)
