---
layout: post
author: Kim, DongKi
title:  "Hermes Agent로 로컬 AI 자동화하기"
date:   2026-07-01
categories: AI
comments: true
---

### TL;DR

* **Hermes Agent**는 Nous Research에서 만든 오픈소스 AI 에이전트 프레임워크로, CLI·Discord·Telegram 등 여러 채널에서 동작합니다.
* **cron**으로 반복 작업을 예약하고, **스킬(Skill)**로 워크플로를 재사용할 수 있습니다.
* 로컬 LLM(Ollama)부터 Claude·GPT API까지 provider를 바꿔 끼울 수 있어, 프라이버시와 비용 사이에서 선택이 가능합니다.
* 개인 프로젝트 자동화, 봇 운영, 정기 리포트 생성 등에 활용하기 좋습니다.

----
### 들어가며

AI 코딩 도구를 쓰다 보면 "이거 매일 아침 돌려주면 좋겠는데", "디스코드에서도 같은 에이전트 쓰고 싶은데" 같은 생각이 자주 듭니다. Cursor나 Claude Code는 IDE 안에서는 강력하지만, **스케줄링·멀티채널·커스텀 워크플로**까지 한 번에 해결해 주진 않거든요.

최근에 [Hermes Agent](https://github.com/NousResearch/hermes-agent)를 써 보면서, 이런 니즈를 꽤 깔끔하게 채울 수 있겠다는 느낌을 받았습니다. 설치부터 cron·스킬·Discord 연동까지, 제가 실제로 써 본 흐름을 정리해 봅니다.

----
### Hermes Agent란?

Hermes는 **자율 AI 에이전트 런타임**입니다. 핵심 특징은 다음과 같습니다.

* **멀티 프로바이더** — OpenAI, Anthropic, 로컬 Ollama, Cursor ACP 등 다양한 LLM 백엔드 지원
* **도구(Tool) 생태계** — 터미널, 파일, 웹 검색, 브라우저, cron 등 내장
* **스킬 시스템** — 반복 작업을 `SKILL.md`로 문서화·재사용
* **채널 연동** — CLI, Discord, Telegram, Slack 등에서 동일 에이전트 사용

"그냥 챗봇"이 아니라, **도구를 호출하고 파일을 수정하고 스크립트를 실행하는** 에이전트에 가깝습니다.

----
### 설치와 기본 설정

공식 문서 기준으로 Python 3.10+ 환경에서 설치합니다.

```bash
pip install hermes-agent
hermes setup
```

설정은 `~/.hermes/config.yaml`에 저장됩니다. provider, model, API 키 등을 여기서 관리합니다.

```yaml
# 예시 (실제 키는 환경변수나 .env 사용 권장)
providers:
  anthropic:
    api_key: ${ANTHROPIC_API_KEY}
default_provider: anthropic
default_model: claude-sonnet-4
```

**프로필(profile)** 기능으로 작업마다 다른 설정을 분리할 수도 있습니다. 예를 들어 `work` 프로필은 업무용 repo, `default`는 개인용으로 나누는 식이죠.

```bash
hermes --profile work "이슈 목록 보여줘"
```

----
### cron으로 반복 작업 자동화

Hermes의 cron은 단순 알람이 아니라 **에이전트 세션을 예약 실행**합니다.

```bash
hermes cron create \
  --name "morning-briefing" \
  --schedule "0 8 * * *" \
  --prompt "오늘 날씨와 주요 뉴스 요약해줘" \
  --skills korea-weather,naver-news-search
```

주요 옵션:

| 옵션 | 설명 |
|------|------|
| `schedule` | cron 표현식 (`0 8 * * *`) 또는 `every 2h`, `30m` |
| `workdir` | 특정 git repo에서 실행 (AGENTS.md 자동 주입) |
| `skills` | 실행 전 로드할 스킬 목록 |
| `deliver` | 결과 전달 대상 (`origin`, `discord:...`, `local`) |
| `no_agent` | LLM 없이 스크립트만 실행 (watchdog 패턴) |

`workdir`을 지정하면 해당 디렉터리의 `AGENTS.md`가 컨텍스트에 들어가서, **프로젝트 맥락을 아는 에이전트**가 됩니다.

----
### 스킬(Skill) — 재사용 가능한 워크플로

같은 작업을 반복할 때마다 프롬프트를 길게 쓰기 귀찮죠. Hermes **스킬**은 절차를 파일로 저장해 두는 방식입니다.

```
~/hermes/skills/my-workflow/
  SKILL.md          # 트리거 조건 + 단계별 지침
  references/       # API 문서, 참고 자료
  scripts/          # 보조 스크립트
```

에이전트가 작업 유형을 인식하면 스킬을 자동 로드합니다. cron job에 `--skills my-workflow`를 붙이면 예약 실행 때도 동일 워크플로가 적용됩니다.

----
### Discord 봇 연동

Hermes를 Discord 봇으로 쓰면 서버 채널에서 바로 에이전트와 대화할 수 있습니다.

```bash
hermes gateway discord
```

설정 파일에 Discord Bot Token을 넣어 두면, 멘션 또는 지정 채널에서 Hermes가 응답합니다. 여러 AI 봇을 한 서버에 두고 운영할 때는 **멘션 루프**(봇끼리 서로 멘션하며 무한 대화)에 주의해야 합니다.

----
### 로컬 LLM과 연결

API 비용이나 데이터 프라이버시가 걱정되면 **Ollama** 같은 로컬 LLM을 provider로 등록할 수 있습니다.

```bash
hermes config set providers.ollama.base_url http://localhost:11434
hermes config set default_provider ollama
hermes config set default_model llama3.2
```

로컬 모델은 도구 호출·긴 컨텍스트에서 클라우드 모델보다 약할 수 있지만, 간단한 자동화·개인 메모 정리 정도는 충분히 가능합니다.

----
### 마치며

Hermes Agent는 "IDE 안의 AI"와 "클라우드 API" 사이 **개인 자동화 레이어**를 채워 주는 도구라고 느껴집니다. cron + 스킬 + 멀티채널 조합이 익숙해지면, 반복 업무를 에이전트에게 넘기는 패턴이 자연스럽게 잡힙니다.

다음 글에서는 AI 코딩 에이전트(Cursor, Claude Code, Copilot) 비교와, Hermes cron 실전 워크플로를 이어서 정리해 볼 예정입니다.
