---
layout: post
author: Kim, DongKi
title:  "Hermes에 Cursor를 붙이기 — ACP로 직접 연결한 기록"
date:   2026-07-21
permalink: /ai/2026/07/21/hermes-cursor-acp.html
categories: AI
comments: true
---

### TL;DR
* Hermes Discord 봇의 “뇌”로 Cursor(Composer)를 쓰고 싶었는데, 업스트림은 Cursor를 정식 model provider로 안 받아준다.
* 그래서 **Cursor CLI의 `agent acp`를 서브프로세스로 띄우는 브리지**를 로컬에 붙였다. HTTP OpenAI 프록시가 아니다.
* 흐름은 `Discord → Hermes → Cursor ACP(stdio/JSON-RPC) → 응답`이다. [ACP 개념 글](/ai/2026/07/15/acp-protocol.html)의 “에디터가 Hermes를 호출”과 **방향이 반대**다.
* 짧게는 잘 도는데, Discord처럼 세션·툴콜이 쌓이면 ACP 프로세스가 먼저 죽는다. 안정성이 1순위면 Discord 주는 일반 chat completions를 쓰는 편이 낫다.

----

### 들어가며

나는 Cursor로 코드를 고치고, Hermes로 Discord·cron·칸반을 돌린다. 둘을 따로 쓰면 “채팅에서 시킨 일”과 “에디터에서 고친 일”이 끊긴다. 그래서 Discord 멘션 한 번으로 Cursor 에이전트가 바로 손을 대게 만들고 싶었다.

문제는 단순했다. Hermes는 OpenAI 스타일 chat completions를 기대하는데, Cursor가 파는 건 **모델 API가 아니라 에이전트 하네스**다. 그 사이를 어떻게 이을지가 이 글의 주제다.

----

### 왜 “그냥 연동”이 안 되나

Hermes 쪽에 Cursor provider를 넣는 PR·이슈는 여러 번 올라왔다. 유지자 쪽 입장은 대략 이렇다.

* Cursor Composer는 raw inference 엔드포인트가 아니라 **에이전트 런타임**(툴·권한·서브에이전트)이다.
* 그걸 Hermes의 “model provider”로 꽂으면, 그 턴에서 Hermes 본인 툴·메모리·스킬이 사실상 가려진다.
* 벤더 연동은 코어가 아니라 **standalone plugin**으로 두라는 정책이다.

그래서 “Hermes가 Cursor를 공식 지원한다”고 기대하면 막힌다. 내가 한 일은 프로토콜(ACP)을 새로 만든 게 아니다. **이미 있는 Cursor CLI ACP 서버를 Hermes가 chat backend처럼 부르게 하는 어댑터**를 로컬에 붙인 것이다.

| 구분 | 뭔가 |
|------|------|
| ACP 규약 자체 | 에디터↔에이전트 표준 (내가 만든 것 아님) |
| `agent acp` | Cursor CLI가 띄우는 ACP 서버 |
| `cursor_acp_client.py` + provider 배선 | Hermes 요청을 ACP 세션으로 바꿔 주는 **내가 붙인 브리지** |

----

### 내가 붙인 구조

방향부터 정리한다. 예전에 쓴 ACP 글의 `hermes acp`는 **에디터가 Hermes를 에이전트로 쓰는** 쪽이다. Discord용 Cursor 연동은 반대다.

```text
사람 @봇 (Discord)
  → Hermes 게이트웨이 (세션·툴·스킬)
    → CursorACPClient
      → 서브프로세스: agent.cmd --model auto acp
        → JSON-RPC over stdio
      ← 텍스트/툴콜 청크
    ← OpenAI chat 형태 응답으로 재조립
  ← Discord 메시지
```

핵심 파일은 대략 이렇다.

* `agent/cursor_acp_client.py` — Hermes chat 요청 → ACP 프롬프트 변환, `agent acp` 기동, 응답 수집
* provider 오버레이 — `cursor-acp` / base URL `acp://cursor`
* `.env` — Windows에서 CLI 경로 고정

```bash
# Hermes .env (Windows 예)
CURSOR_ACP_COMMAND=C:\Users\<USER>\AppData\Local\cursor-agent\agent.cmd
CURSOR_ACP_ARGS=--model auto acp
```

```yaml
# config.yaml 요약
model:
  default: auto
  provider: cursor-acp
  base_url: ""
```

브리지가 하는 일은 단순하다. Hermes가 넘긴 messages/tools를 한 덩어리 프롬프트로 묶고, Cursor ACP 세션에 보낸 뒤, 돌아온 텍스트·`<tool_call>` 블록을 Hermes가 다시 소화할 수 있는 형태로 돌려준다. Cursor 구독 로그인(`agent login`)만 되어 있으면 API 키 없이도 Composer 쪽을 탈 수 있다.

----

### HTTP 프록시랑 뭐가 다르냐

지인 쪽은 `cursor-openai-api` 같은 **OpenAI 호환 HTTP 프록시**를 쓰는 경우가 많다.

| | 내 스택 (cursor-acp) | 흔한 프록시 스택 |
|--|---------------------|------------------|
| 연결 | Hermes → `agent acp` 서브프로세스 | Hermes → localhost HTTP → Cursor SDK |
| 프로토콜 | ACP (JSON-RPC/stdio) | OpenAI chat completions 흉내 |
| 깨지는 지점 | ACP 프로세스 early exit / stale | 502 · empty stream · /health만 살아 있음 |

겉 증상은 비슷하다. Discord 세션이 길고 툴콜이 많아지면, “모델이 죽은 것처럼” 보이지만 실제로는 **Cursor 로컬 에이전트 쪽이 먼저** 죽는다. 재시작하면 잠깐 살고, 히스토리가 쌓이면 또 같은 패턴이다.

차이는 스택이지, “Cursor를 chat API처럼 오래 굴린다”는 본질은 같다.

----

### 설정 최소 체크리스트

1. Cursor CLI 설치 후 `agent login`
2. `CURSOR_ACP_COMMAND` / `CURSOR_ACP_ARGS`를 Hermes `.env`에 박기
3. `hermes model`에서 provider를 `cursor-acp`로 선택 (또는 config.yaml)
4. `hermes gateway restart`
5. Discord에서 짧은 코딩 요청 한 번으로 smoke test

업데이트가 `cursor-acp`를 못 알아보면, `custom_providers`에 `type: acp`로 다시 등록하는 우회가 필요하다. 이때 YAML은 **dict가 아니라 list**여야 한다 (`- name: cursor-acp`). list가 아니면 doctor는 조용한데 gateway 로그만 깨진다.

----

### 한계 — 그래서 언제 쓰나

이 연결은 “Discord에서 Cursor 손을 바로 빌린다”는 목표에는 맞다. 다만 주 모델로 오래 굴리기엔 취약하다.

* cron처럼 헤드리스·반복 작업은 `cursor-acp` 대신 Nous/OpenRouter 같은 **진짜 chat completions**로 핀 고정하는 편이 낫다.
* 긴 토론·대량 툴콜 채널은 ACP 프로세스 수명이 병목이다.
* Hermes 입장에선 Cursor가 “모델”이 아니라 “바깥 에이전트”라서, 디버깅할 때도 Discord 로그만 보면 원인 위치가 안 보인다.

나는 그래서 **코딩 전담 프로필(양파쿵야)만 cursor-acp**, 분석/cron 프로필은 Nous 쪽으로 나눠 둔다.

----

### 마무리

정리하면 이렇다.

1. ACP 표준을 내가 만든 게 아니다.
2. Hermes 업스트림이 Cursor를 provider로 안 받아 주니, **로컬 ACP 브리지**를 붙였다.
3. 지인의 HTTP 프록시와는 경로가 다르고, 긴 세션에서 깨지는 본질은 비슷하다.

다음에 비슷한 걸 붙일 때는 “chat API처럼 보이는 에이전트 하네스”인지부터 먼저 본다. 그게 맞으면 Discord 주 모델로 쓰기 전에, 짧은 작업 전용·폴백 provider·프로세스 재시작 전략을 같이 설계하는 편이 덜 아프다.

관련: [ACP(Agent Client Protocol) 이해하기](/ai/2026/07/15/acp-protocol.html)
