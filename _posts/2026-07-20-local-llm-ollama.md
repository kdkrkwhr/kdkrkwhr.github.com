---
layout: post
author: Kim, DongKi
title:  "로컬 LLM(Ollama)로 에이전트 돌리기"
date:   2026-07-20
categories: AI
comments: true
---

### TL;DR
* Ollama는 로컬에서 오픈 웨이트 모델을 띄우는 가장 짧은 경로다. API는 OpenAI 호환(`/v1`)이라 Hermes·기타 에이전트에 바로 붙는다.
* Hermes에서는 `hermes model` → Custom endpoint → `http://localhost:11434/v1` 로 연결한다. API 키는 비워 둔다.
* **컨텍스트 길이는 Ollama 서버 쪽에서 따로 올려야** 한다. 기본값이 짧아서 에이전트 루프가 자주 끊기는 함정이 있다.
* 로컬은 프라이버시·호출당 비용 면에서 유리하고, 품질·속도는 VRAM·모델 크기에 달린다. 클라우드와 fallback을 섞는 구성이 현실적이다.

----

### 들어가며
이전 글에서 LLM API를 고를 때 비용·지연·프라이버시 축을 나눴다. 그중 "데이터가 밖으로 나가면 안 되는" 구간이 생기면 로컬이 후보가 된다. 에이전트(cron, 봇, IDE 연동)까지 붙이면 "로컬에 모델만 깔면 끝"이 아니라 **엔드포인트·컨텍스트·툴콜 지원**까지 맞춰야 한다.

이 글은 Ollama를 기준으로, Hermes Agent에 로컬 provider를 연결하는 최소 절차와 자주 밟는 함정을 정리한다.

----

### Ollama 설치와 모델 pull

```bash
# 설치 후 데몬 기동 (기본 포트 11434)
ollama serve

# 에이전트용으로 많이 쓰는 계열 예시
ollama pull qwen2.5-coder:32b
# 또는 작은 GPU / 빠른 실험용
ollama pull gemma3:4b

ollama list
```

모델 태그는 `이름:태그` 형식을 유지한다. Hermes·OpenAI 호환 클라이언트에 넘길 때도 대시로 바꾸지 않는 편이 안전하다.

----

### Hermes에 붙이기

Hermes는 Ollama를 별도 1급 provider로 두지 않고, **Custom endpoint**로 붙인다.

```bash
hermes model
# → Custom endpoint (self-hosted / VLLM / etc.)
# → URL: http://localhost:11434/v1
# → API key: (비움)
# → model: qwen2.5-coder:32b 등
```

`config.yaml`에 직접 적어도 동일하다.

```yaml
model:
  default: qwen2.5-coder:32b
  provider: custom
  base_url: http://localhost:11434/v1
  context_length: 64000
```

연결 확인:

```bash
curl http://localhost:11434/v1/models
hermes chat   # 짧은 프롬프트로 응답·툴콜 여부 확인
```

툴 호출이 필요하면 `ollama show <model>`로 해당 모델이 tool calling을 지원하는지 본다. 지원하지 않는 소형 모델은 채팅은 되지만 에이전트 루프에서 도구를 거의 못 쓴다.

----

### 컨텍스트 길이 — 가장 흔한 함정

Ollama는 모델 스펙상 긴 컨텍스트를 광고해도, **서버 기본 `num_ctx`는 VRAM에 맞춰 짧게** 잡히는 경우가 많다. Hermes 쪽 `context_length`만 키워 두면, 서버는 짧게 자르고 클라이언트만 길게 기대해서 중간에 맥락이 날아간다.

| 조치 | 방법 |
|------|------|
| 서버 전역 | `OLLAMA_CONTEXT_LENGTH=64000 ollama serve` |
| 모델 고정 | Modelfile에 `PARAMETER num_ctx 64000` 후 `ollama create ...` |
| 검증 | `ollama ps`의 CONTEXT 열 확인 |

OpenAI 호환 `/v1/chat/completions` 요청만으로는 컨텍스트를 올리지 못한다. **반드시 서버 또는 Modelfile**에서 맞춘다.

----

### 언제 로컬을 쓰고, 언제 클라우드를 쓰는가

| 상황 | 추천 |
|------|------|
| 개인 메모·민감 문서 요약 | 로컬 |
| cron·리포트처럼 호출이 잦고 단가가 아픈 작업 | 로컬 또는 소형 클라우드 |
| 긴 스펙·복잡한 코딩 에이전트 | 클라우드 상위 모델, 로컬은 보조 |
| 오프라인·키 없이 실험 | 로컬 |

실사용에서는 메인 채팅은 클라우드, 배치·민감 경로는 로컬처럼 **provider를 작업 단위로 나누는** 구성이 많다. Hermes는 cron job마다 model/provider를 고정할 수 있어, "에이전트 cron은 가벼운 모델, IDE는 ACP" 같은 분리가 가능하다.

----

### Windows + WSL 참고

Hermes를 WSL2에서 돌리고 Ollama만 Windows 호스트에 띄운 경우, 기본 NAT에서는 WSL의 `localhost`가 호스트 Ollama에 닿지 않는다. Windows 11 mirrored 네트워킹을 켜거나, 호스트 IP로 `base_url`을 잡는다. NAT로 붙일 때는 Windows 쪽 Ollama에 `OLLAMA_HOST=0.0.0.0`이 필요할 수 있다.

----

### 모델 고를 때 짧게

| 목적 | 대략적인 선택 |
|------|----------------|
| 빠른 실험·짧은 요약 | 3B~8B급 (예: gemma3:4b 계열) |
| 코드·툴콜 에이전트 | coder 계열 14B~32B, VRAM 여유 필수 |
| 추론·긴 문서 | 큰 모델 또는 클라우드. 로컬만으로 우기면 지연이 먼저 온다 |

같은 이름이라도 양자화·태그(`q4_K_M` 등)에 따라 체감이 크게 달라진다. `ollama pull` 전에 라이브러리 페이지에서 용량과 권장 RAM을 확인하는 습관이 필요하다.

----

### 마무리
* 연결은 `http://localhost:11434/v1` + `provider: custom`이면 충분하다.
* **컨텍스트는 서버에서** 올리고 `ollama ps`로 확인한다. 여기가 로컬 에이전트 실패 1순위다.
* 툴콜·코딩 품질이 필요하면 모델 크기와 VRAM을 먼저 보고, 안 되면 클라우드 fallback을 둔다.
* 로컬은 "공짜·완전 대체"가 아니라 **프라이버시·비용 축의 한 provider**로 두는 게 유지보수에 유리하다.

이전 글의 LLM API 선택 가이드·Hermes 스킬 글과 이어서 읽으면, "어디에 어떤 모델/절차를 붙일지" 한 줄로 정리하기 쉽다.
