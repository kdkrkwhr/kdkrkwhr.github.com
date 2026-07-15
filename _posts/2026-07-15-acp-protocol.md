---
layout: post
author: Kim, DongKi
title:  "ACP(Agent Client Protocol) 이해하기"
date:   2026-07-15
categories: AI
comments: true
---

### TL;DR
* **ACP는 IDE/에디터와 AI 코딩 에이전트 사이 통신 규약**이다 — LSP가 언어 서버를 표준화한 것처럼, ACP는 에이전트-에디터를 분리한다.
* 에디터는 ACP 클라이언트, 에이전트는 ACP 서버 구조. JSON-RPC 기반이며 로컬(stdio)과 원격(HTTP/WS) 모두 지원한다.
* Hermes는 ACP provider로 Cursor·VS Code 등과 연결된다 — 에디터 안에서 Hermes의 agent workflow를 쓸 수 있다.
* "한 에디터에 여러 에이전트"나 "한 에이전트를 여러 IDE에서"가 자연스러워지는 게 핵심이다.

----

### 들어가며
MCP(Model Context Protocol)가 "LLM에 도구·데이터를 꽂는" 쪽이라면, ACP는 "사람이 앉은 에디터에 에이전트를 연결하는" 쪽이다. LSP가 언어 분석기를 편집기에서 떼어낸 것처럼, ACP는 AI 코딩 에이전트를 편집기에서 분리한다.

Cursor, Claude Code, Copilot 같은 도구를 쓰다 보면 "이 에이전트의 능력을 다른 에디터에서도 쓸 수 없을까" 싶은 순간이 온다. ACP는 그 질문에 대한 표준 답변이다.

----

### ACP 개요

ACP의 기본 골자는 단순하다.

| 역할 | 누구 | 하는 일 |
|------|------|--------|
| **ACP Client** | 에디터 (Cursor, VS Code 등) | 사용자 UI 제공, 에이전트 요청 전달, diff 표시 |
| **ACP Server** | 에이전트 (Claude Code, Hermes agent 등) | 작업 수행, 파일 수정, 결과 반환 |

통신은 **JSON-RPC 2.0** 위에서 이루어진다. MCP의 JSON 표현을 재사용하는 부분이 많아서 두 프로토콜을 함께 구현할 때 부담이 적다.

#### Local과 Remote

* **Local**: 에디터가 에이전트를 하위 프로세스로 띄우고 stdio로 통신한다. 대기 시간이 짧고 설정이 간단하다.
* **Remote**: HTTP나 WebSocket 위에서 돌아간다. 클라우드 에이전트, 원격 서버의 에이전트에 연결할 때 쓴다 (현재는 작업 중).

ACP가 다루는 영역은 크게 넷이다.

1. **파일 읽기/쓰기** — 에디터가 연 프로젝트의 파일을 에이전트가 접근한다
2. **diff 표시** — 에이전트가 제안한 변경을 에디터가 미리보기로 보여준다
3. **터미널 명령** — 에이전트가 에디터의 터미널에서 명령을 실행한다
4. **진행 상황** — 장시간 작업의 상태를 에디터 UI에 전달한다

----

### ACP vs LSP vs MCP

셋을 나란히 놓으면 헷갈리지만, 목적이 다르다.

| 프로토콜 | 표준화 대상 | 방향 |
|----------|------------|------|
| **LSP** | 언어 서버 ↔ 에디터 | 정적 분석·자동완성·리팩터 |
| **MCP** | LLM ↔ 도구/데이터 | 외부 리소스 연결 |
| **ACP** | 에이전트 ↔ 에디터 | 코딩 작업 실행·파일 조작 |

에이전트 하나가 MCP와 ACP를 동시에 구현할 수도 있다. MCP로 DB 스키마를 읽고, ACP로 에디터에 수정안을 diff로 보여주는 식이다.

----

### Hermes와 ACP

Hermes는 ACP provider를 내장하고 있다. `hermes acp` 명령으로 ACP 서버를 켜면, Cursor나 VS Code가 Hermes를 "에이전트 백엔드"로 인식한다.

```bash
# ACP 서버 실행
hermes acp start

# 상태 확인
hermes acp status
```

Cursor에서 Hermes ACP를 쓰면 이런 흐름이 된다.

1. Cursor가 ACP 클라이언트로 Hermes ACP 서버에 연결
2. 채팅창에서 요청을 보내면 Hermes의 agent workflow가 동작
3. Hermes가 파일 수정·명령 실행·diff를 ACP 규격으로 반환
4. Cursor가 이를 diff 뷰어·채팅에 표시

ACP 표준을 따르므로, 이 연결은 Cursor뿐 아니라 ACP를 지원하는 모든 에디터에서 동작한다. 반대로 Hermes가 아닌 다른 에이전트도 ACP만 구현하면 같은 에디터에 붙일 수 있다.

#### 설정 팁

```yaml
# hermes config.yaml (ACP 관련)
acp:
  enabled: true
  port: 50051
  allowed_origins:
    - "vscode://"
    - "https://cursor.sh"
```

포트 충돌이나 방화벽이 문제라면 `port`를 바꾸거나 로컬 stdio 모드로 전환한다. ACP 서버는 profile마다 따로 띄울 수 있어서, default는 코딩용·nous-work는 분석용으로 나누는 것도 가능하다.

----

### 언제 ACP가 유용한가

* **여러 에이전트를 같은 에디터에서 쓰고 싶을 때** — ACP로 각 에이전트를 서버로 띄우고 필요에 따라 전환
* **에디터를 바꿔도 에이전트 환경이 유지되길 바랄 때** — VS Code·Cursor·Windsurf 등 ACP 클라이언트끼리 프로토콜이 같아 설정 재사용 가능
* **에이전트를 원격 서버에 두고 로컬 에디터만 가볍게 쓰고 싶을 때** — Remote ACP로 SSH 너머 에이전트에 연결

반대로, 단일 IDE·단일 에이전트로 이미 workflow가 굳어 있다면 ACP가 주는 이점이 체감되지 않을 수 있다. LSP가 모든 프로젝트에 필수가 아닌 것과 같다.

----

### 마무리

ACP는 아직 RFC 단계(2025년 말 RFD 논의 중)지만, 에디터-에이전트 결합을 푸는 방향성은 분명하다. LSP가 "언어 서버 하나로 모든 에디터"를 만든 것처럼, ACP는 "에이전트 하나로 모든 에디터"를 목표로 한다.

Hermes가 ACP를 지원하는 건 "터미널·cron·Discord" 외에 **에디터**라는 또 하나의 인터페이스를 여는 셈이다. 다음에는 ACP provider를 직접 구현해보거나, MCP + ACP 조합으로 에이전트 workflow를 확장하는 사례를 정리할 예정이다.