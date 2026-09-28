---
layout: post
author: Kim, DongKi
title: "AI 자동화 프로젝트를 시작했다: Kafka에서 Coral까지 연결한 첫 번째 기록"
date: 2026-09-28
permalink: /ai/2026/09/28/event-driven-llm-first-pipeline.html
categories: AI
comments: true
description: "개인 프로젝트 event-driven-llm의 첫 구현 기록. Java 21, Kafka, Coral을 연결하고 중복 전송과 서버 재시작, 결과 전달 실패를 검증한 과정을 정리합니다."
---

### TL;DR

* 개인 프로젝트로 Kafka 기반 AI 작업 파이프라인을 만들기 시작했다.
* 첫 목표는 요청 하나가 처리되어 Coral 메시지로 남는 흐름을 확인하는 것이었다. Kafka와 Coral은 실제 서버를 사용하고, 모델 추론은 DEMO 응답으로 대체했다.
* 첫 연결 뒤에는 앱 재시작, 중복 결과, Coral 장애를 검증했다. 이 과정에서 자동 테스트가 놓친 세션 조회 오류도 발견했다.

----

### 들어가며

개인 프로젝트로 이벤트 기반 AI 자동화 시스템을 만들어 보기로 했다. 기획서에 Kafka, LLM, Coral을 적어 놓고, Codex와 함께 Java 21과 Docker로 프로젝트 뼈대를 만들었다. 이름은 [event-driven-llm](https://github.com/deepwhale-labs/event-driven-llm)이다.

진행하다 보니 궁금해졌다. 지금 실제로 구현된 건 어디까지일까? 자동화할 업무는 아직 고민 중이었고, 모델을 실행할 장비를 따로 마련하기도 부담이었다. 실행 구조를 갖추는 것과 그 구조가 실제로 동작하는지 확인하는 것은 각각 해야 할 일이었다.

----

### 첫 목표를 요청 하나로 줄였다

처음부터 모든 것을 확정하기는 어려웠다. 우선 요청 하나가 Kafka에 들어가고, Worker를 거쳐, 결과가 Coral에 남는 과정을 확인하기로 했다.

기본 Worker는 입력에 `[DEMO - no model inference]` 표시를 붙여 반환한다. 이 상태로도 이벤트 전달, 결과 조회, 실패 처리를 시험할 수 있다. 실제 모델 호출은 Ollama 실행 옵션으로 열어 두었다.

<div class="mermaid" role="img" aria-label="요청이 Kafka와 Worker를 거쳐 Coral 메시지로 전달되는 구조. Worker와 Coral 전송 실패는 각각 별도 DLT로 이동한다.">
flowchart TD
    A["요청 API"] --> B[(llm-commands)]
    B --> C["Worker / 기본 DEMO 응답"]
    C --> D[(llm-results)]
    D --> E["Coral Consumer"]
    E --> F["Coral 스레드와 메시지"]
    C -->|재시도 후 실패| G[(llm-commands.DLT)]
    E -->|재시도 후 실패| H[(llm-results.DLT)]
</div>

Kafka에는 요청과 결과 이벤트를 보관하고, Coral에는 작업별 대화 공간인 스레드와 결과 메시지를 남긴다. 현재는 이 구조를 개인 환경에서 실험하는 단계다. 실제 업무에서 필요한 처리량이나 효과는 아직 측정하지 않았다.

----

### Coral은 처음부터 직접 연결했다

Coral 연결에는 앞서 만든 [Agent Hub]({% post_url 2026-09-18-agent-hub-radio %})의 실행 방식을 참고했다. 공식 서버 JAR을 받아 실행하고, MCP로 스레드 생성과 메시지 전송 도구를 호출하는 방식이다.

앱은 Java 21을 유지하고, Coral은 별도 JRE 25 컨테이너에서 실행했다. [공식 1.4.0 배포 파일](https://github.com/Coral-Protocol/coral-server/releases/tag/v1.4.0)은 이미지 빌드 중 내려받되 SHA-256 해시를 확인한다. 서버마다 요구하는 실행 환경을 Docker로 나눌 수 있었다.

개인 저장소에 올리는 파일도 정리했다. Git에는 소스와 실행 정의를 허용하고, 인증키와 접속 URL, 로그, 모델 파일은 제외했다. Coral 인증키는 실행 시 생성해 전용 볼륨에 보관한다. `.gitignore`와 별도로 소스에 비밀값을 직접 적지 않는 확인도 필요하다.

----

### 첫 응답을 받기까지

실행하자마자 연결된 것은 아니었다. Kafka는 데이터 볼륨의 쓰기 권한 때문에 시작하지 못했다. 저장 경로를 공식 이미지에서 쓰도록 준비된 디렉터리로 바꾸었다. 앱의 8080 포트도 이미 사용 중이라 호스트 접속 포트를 18080으로 옮겼다.

이 문제들을 해결한 뒤 결과 조회에서 `taskId`와 실제 Coral의 `threadId`를 확인했다. 요청이 메시지로 남는 흐름이 처음으로 이어졌다. 당시 검증 요청의 응답에서 주요 필드만 남기면 다음과 같다. ID는 자리표시자로 바꾸었다.

```json
{
  "taskId": "<요청 UUID>",
  "threadId": "<Coral 스레드 UUID>",
  "output": "[DEMO - no model inference] Received: Kafka to Coral smoke test"
}
```

지금도 Docker 환경에서 아래 명령으로 같은 흐름을 확인할 수 있다. 마지막 명령은 PowerShell용이다.

```powershell
git clone https://github.com/deepwhale-labs/event-driven-llm.git
cd event-driven-llm
docker compose up --build -d
.\scripts\smoke.ps1
```

----

### 테스트는 통과했는데, 재시작하니 세션이 달라졌다

첫 응답을 받은 뒤 앱을 재시작했다. Coral 서버는 그대로 실행 중이었으므로 기존 세션과 결과를 다시 조회할 수 있어야 했다. 그런데 새로운 세션이 만들어졌다.

원인은 세션 목록 응답을 잘못 읽은 데 있었다. 특정 네임스페이스의 세션 조회 응답을 `sessions` 필드가 있는 객체로 예상했지만, 실제 API는 세션 목록 배열을 반환했다. 기존 세션을 찾지 못한 코드가 새 세션을 만들고 있었다.

자동 테스트에 사용한 가짜 서버도 같은 잘못된 응답 구조를 돌려주었다. 그래서 테스트 안에서는 세션 재사용이 정상으로 보였다. 이 부분이 이번 작업에서 가장 기억에 남았다. 테스트가 구현과 같은 가정을 공유하면 그 가정의 오류까지 통과할 수 있었다.

[연결 코드](https://github.com/deepwhale-labs/event-driven-llm/blob/50015ece293f7fcfcd9ed5ed490cd9467fa7b2d7/src/main/java/io/github/deepwhalelabs/eventdrivenllm/CoralClient.java)와 테스트의 응답 형식을 수정하고, 실제 서버를 대상으로 앱 재시작 후 세션 ID와 결과가 유지되는지 다시 확인했다.

----

### 결과 전달이 실패하는 경우까지 확인했다

Coral이 내려가면 이미 만든 결과는 어떻게 될까? 이 경우에 대비해 Worker가 결과 이벤트를 발행하는 단계와 Coral Consumer가 메시지를 전달하는 단계를 분리했다. Coral 전송 실패는 결과 Consumer에서 재시도한다.

최초 처리 후 두 번 더 실패하면 해당 메시지를 실패 보관 토픽인 DLT로 보낸다. 이번에는 코드 테스트 7개와 별도로 실제 컨테이너에서 다음을 확인했다.

| 확인한 상황 | 관찰한 결과 |
| --- | --- |
| 앱만 재시작 | 기존 Coral 세션과 결과 재사용 |
| 같은 결과를 다시 발행 | 해당 작업의 스레드 1개 / 메시지 1개 유지 |
| Coral을 중단한 상태에서 요청 | 처리된 결과가 `llm-results.DLT`로 이동 |
| Coral을 다시 시작한 뒤 새 요청 | 새 세션에 결과 전송 성공 |

중복 확인은 현재 Coral 세션과 단일 앱 범위에서 동작한다. Kafka 결과 이벤트 자체는 중복될 수 있다. DLT에 들어간 과거 결과를 자동으로 재전송하는 기능도 아직 없다.

----

### 이제 정할 것은 첫 번째 업무다

현재 연결된 Kafka와 Coral은 실제 서버다. LLM 응답은 기본 DEMO이고, Ollama를 통한 실제 추론은 별도 검증이 남아 있다. Coral의 스레드와 메시지는 메모리에 있으므로 Coral 서버를 재시작하면 사라진다. 기록 영속화도 다음 과제다.

이번 작업으로 요청이 어디를 지나고, 어느 단계의 실패가 어디에 남는지 확인할 수 있게 됐다. 다음에는 자동화할 업무 하나를 정해서 이 흐름에 넣어 보려 한다. 어떤 이벤트를 입력으로 받을지, 결과에 무엇이 들어 있어야 쓸모가 있을지부터 정할 차례다.

이 글은 [2026년 9월 28일의 구현 커밋](https://github.com/deepwhale-labs/event-driven-llm/commit/50015ece293f7fcfcd9ed5ed490cd9467fa7b2d7)을 기준으로 작성했다. 실행 옵션과 현재 구현 범위는 [프로젝트 README](https://github.com/deepwhale-labs/event-driven-llm#readme)에 정리해 두었다.
