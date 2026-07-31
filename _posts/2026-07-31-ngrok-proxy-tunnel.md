---
layout: post
author: Kim, DongKi
title:  "ngrok이 하는 일 — 프록시란 무엇인가부터 터널 동작까지"
date:   2026-07-31
categories: Technology
comments: true
---

### TL;DR
* **프록시**는 중간에 서서 요청을 대신 보내거나 받아주는 중계기다. 방향에 따라 **Forward** / **Reverse**로 나뉜다.
* 집·회사 PC의 `localhost`는 보통 **공인 IP가 없고 inbound가 막혀** 인터넷에서 바로 못 연다.
* **ngrok**은 “공개 URL을 받는 **클라우드 reverse proxy**” + “내 PC에서 **밖으로 나가는 TLS 터널**을 여는 agent” 조합이다. 포트포워딩·공인 IP가 필요 없다.
* 로컬 API를 Colab GPU 서버나 외부 웹훅에 잠깐 붙일 때 제일 먼저 손이 가는 도구다. (이전 글: [Colab + ngrok으로 Spring RAG 테스트](/technology/2026/07/29/gpu-colab-ngrok-spring-rag-fewshot-test.html))

----

### 들어가며

로컬에서 FastAPI·Spring을 `localhost:8000`으로 띄워두면 “내 PC에서는” 잘 된다. 문제는 **밖**이다. 폰에서 열어보고 싶거나, 클라우드에 올린 모델 서버가 내 로컬 앱을 콜백해야 하거나, 웹훅을 받아야 하면 곧바로 벽에 부딪힌다.

예전엔 공유기 포트포워딩, DDNS, 임시 VPS를 만지작거렸다. 요즘은 개발·데모용으로 **ngrok** 한 줄이 그 역할을 대신하는 경우가 많다. 이 글은 도구 사용법 나열보다, **프록시가 뭔지 → 왜 localhost가 안 열리는지 → ngrok이 그걸 어떻게 뚫는지** 순서로 정리한다.

----

### 1. 프록시란?

프록시(proxy)는 말 그대로 **대리인**이다. 클라이언트가 서버에 바로 붙지 않고, 중간에 다른 프로세스가 요청/응답을 대신 주고받는다.

역할이 갈리는 지점은 **누가 프록시를 “내 편”으로 쓰느냐**다.

| 종류 | 누가 선택하나 | 전형적인 목적 |
|------|---------------|---------------|
| **Forward Proxy** | 클라이언트 | 회사망 출구, 캐시, 익명화, 접근 제어 |
| **Reverse Proxy** | 서비스 운영 쪽 | TLS 종료, 로드밸런싱, 오리진 숨기기, WAF |

![Forward Proxy vs Reverse Proxy](/assets/images/ngrok/01-proxy-forward-vs-reverse.png)

* **Forward**: 브라우저 → (회사)프록시 → 인터넷. “나갈 길”을 프록시가 통제한다.
* **Reverse**: 인터넷 → Nginx/ALB 같은 프록시 → 내부 앱. 사용자는 **프록시의 주소만** 알고, 진짜 서버 IP는 안 보여도 된다.

ngrok을 한 줄로 말하면 **인터넷 앞단에 있는 reverse proxy**에 가깝다. 다만 전통적인 reverse proxy처럼 “내 서버의 공인 IP로 포워딩”하지 않고, **agent가 열어둔 터널로 트래픽을 밀어 넣는다.**

----

### 2. 왜 `localhost`는 밖에서 안 열리나

`localhost` / `127.0.0.1`은 **그 머신 자기 자신**을 가리키는 루프백이다. 인터넷의 다른 장치가 내 노트북의 127.0.0.1로 패킷을 보낼 방법은 원래 없다.

현실에서 막히는 이유는 보통 겹친다.

* **사설 IP** (`192.168.x.x`, `10.x.x.x`) — 공인 인터넷에서 라우팅되지 않음
* **NAT / CGNAT** — 공유기·통신사가 outbound는 해주되, inbound는 매핑이 없으면 버림
* **방화벽** — 인바운드 포트가 기본 닫힘

![localhost is blocked by NAT/firewall](/assets/images/ngrok/02-localhost-nat-firewall.png)

그래서 “그냥 공유기에서 8000 열어줘”가 이론상 해법이지만, 관리자 권한·CGNAT·보안·동적으로 바뀌는 IP 때문에 **개발 속도가 바로 죽는다.** 여기서 터널형 도구가 뜬다.

----

### 3. ngrok은 뭐가 다른가

공식 문서의 정의에 가깝게 말하면, ngrok은 **전 세계에 분산된 reverse proxy(엣지)** 이고, 내 앱 옆에서 돌아가는 **가벼운 agent**가 엣지와 **outbound TLS**로 항상 연결을 유지한다.

핵심은 이 한 줄이다.

> **인바운드 포트를 열지 않는다. agent가 밖으로 나가서 터널을 먼저 만든다.**

![How ngrok tunnel works](/assets/images/ngrok/03-ngrok-tunnel-flow.png)

흐름을 풀어 쓰면 이렇다.

1. 로컬에서 앱을 `0.0.0.0:8000`(또는 `localhost:8000`)에 띄운다.
2. `ngrok http 8000`을 실행하면 **agent가 ngrok 클라우드로 outbound TLS(보통 443)** 를 연다.
3. 클라우드가 `https://xxxx.ngrok-free.app` 같은 **공개 엔드포인트**를 발급한다.
4. 외부 클라이언트가 그 URL로 요청하면, **엣지 → (이미 열린) 터널 → agent → localhost:8000** 순으로 전달된다.
5. 응답은 반대로 되돌아온다. HTTPS 인증서는 엣지에서 처리되는 경우가 많다.

전통 reverse proxy가 “오리진 IP로 포워딩”한다면, ngrok은 “**지금 접속해 있는 agent 세션으로 포워딩**”한다. 그래서 노트북·라즈베리파이·Colab·온프렘 어디든, **네트워킹을 거의 안 건드리고** 같은 패턴으로 공개 URL을 만든다.

----

### 4. 최소 사용 예

로컬에 HTTP 서버가 8000이면:

```bash
# (선택) 계정 토큰 — 안정적인 URL/한도에 필요
ngrok config add-authtoken <YOUR_TOKEN>

ngrok http 8000
```

출력에 뜨는 `https://….ngrok-free.app` 이 외부에서 쓸 Base URL이다.  
Spring이라면 `ai.base-url` 같은 설정만 이 주소로 바꾸면, GPU 없는 PC에서도 Colab에 올린 모델 서버와 붙는 그림이 된다. (실전 조립은 [이전 글](/technology/2026/07/29/gpu-colab-ngrok-spring-rag-fewshot-test.html) 참고.)

프로토콜별로는 대략 이렇다.

| 명령 | 용도 |
|------|------|
| `ngrok http 8080` | 웹앱·REST API·웹훅 |
| `ngrok tcp 5432` | DB/SSH 등 TCP (정책·플랜 확인) |
| `ngrok tls …` | TLS 패스스루 |

----

### 5. 실전에서 자주 터지는 포인트

* **URL이 바뀐다** — agent를 끄거나 재시작하면 free 엔드포인트는 주소가 바뀌는 경우가 많다. 클라이언트의 Base URL도 같이 갱신.
* **콜드 스타트·브라우저 경고** — free 티어는 브라우저에서 중간 페이지가 뜨거나, 장시간 idle 후 첫 요청이 느릴 수 있다. API 클라이언트는 헤더/옵션을 문서대로 맞춘다.
* **타임아웃** — 터널은 살아 있어도, **호출하는 쪽 HTTP read timeout**이 짧으면 긴 추론에서 끊긴다. ngrok 문제가 아니라 클라이언트 설정인 경우가 많다.
* **보안** — 공개 URL은 곧 인터넷 노출이다. 데모 끝나면 agent를 끄고, 가능하면 인증·IP 제한(Traffic Policy 등)을 건다.
* **0.0.0.0 바인딩** — agent가 로컬 포트로 붙을 수 있게 서버를 `127.0.0.1`만 듣게 두지 않았는지 확인. (환경에 따라 `localhost`만으로도 되지만, Colab/컨테이너에선 `0.0.0.0`이 안전한 편.)

----

### 6. 정리

* 프록시는 **중계**. Forward는 클라이언트 편, Reverse는 서비스 앞단.
* `localhost`가 안 열리는 이유는 루프백 + NAT/방화벽이지, “서버 코드가 잘못된” 경우가 아니다.
* ngrok은 **클라우드 reverse proxy + outbound TLS agent**로, 포트포워딩 없이 공개 HTTPS URL을 만든다.
* 로컬 개발·웹훅·Colab 연동 같은 **짧은 수명의 공개 입구**에 잘 맞고, 운영 트래픽을 맡길 때는 인증·정책·고정 도메인까지 같이 설계하는 편이 낫다.

다음으로 손대면 좋은 건 Traffic Policy(인증·레이트리밋)나, Cloudflare Tunnel 같은 대안과 비교다. 필요하면 그 후속도 따로 정리하겠다.
