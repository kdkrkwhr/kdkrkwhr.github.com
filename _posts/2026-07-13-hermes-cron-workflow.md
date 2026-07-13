---
layout: post
author: Kim, DongKi
title:  "Hermes cron으로 반복 작업 자동화하기"
date:   2026-07-13
categories: AI
comments: true
---

### TL;DR
* Hermes cron은 **에이전트 세션을 예약 실행**하는 스케줄러다. 단순 알람이 아니라 LLM·도구·스킬을 붙인 작업 단위다.
* `schedule`, `workdir`, `skills`, `deliver` 네 옵션이 대부분의 운영 패턴을 커버한다.
* `no_agent: true` + `script`는 **watchdog**(임계값·헬스체크)용, 기본은 **agent 모드**(요약·판단·리포트)다.
* 게이트웨이가 꺼져 있으면 cron이 스킵된다. 상시 실행이 전제다.

----

### 들어가며
매일 아침 날씨·뉴스, 주간 SSoT push, 블로그 초안 — 반복 작업은 IDE 에이전트에 매번 붙여서 돌리기엔 비용·맥락 손실이 크다. Hermes cron은 같은 에이전트 런타임을 **시간·간격 기준으로 예약**해, 결과를 Discord·origin 채널로 보내는 흐름을 제공한다. 이 글은 제가 실제로 쓰는 cron 설계 기준을 정리한다.

----

### cron job의 구성 요소

| 필드 | 역할 |
|------|------|
| `schedule` | 실행 시각. cron 표현식(`0 8 * * *`) 또는 `every 2h`, `30m` |
| `prompt` | agent 모드에서 에이전트에게 줄 작업 지시 |
| `workdir` | 실행 cwd. 해당 repo의 `AGENTS.md`·`.cursorrules`가 컨텍스트에 주입됨 |
| `skills` | 실행 전 로드할 스킬 이름 목록 |
| `deliver` | 결과 전달 대상 (`origin`, `discord:...`, `local`, `[SILENT]`) |
| `script` + `no_agent` | LLM 없이 스크립트만 실행 (watchdog) |

job 정의는 `~/.hermes/cron/jobs.json`에 저장되고, CLI로 생성·수정한다.

```bash
hermes cron create \
  --name "morning-brief" \
  --schedule "0 8 * * *" \
  --prompt "오늘 날씨와 주요 뉴스 요약" \
  --skills korea-weather,naver-news-search \
  --deliver origin
```

----

### workdir — 프로젝트 맥락 주입

`workdir`을 절대 경로로 지정하면, 해당 디렉터리에서 터미널·파일 도구가 동작하고 프로젝트 컨텍스트 파일이 시스템 프롬프트에 들어간다. 블로그 자동 포스팅처럼 **특정 git repo 안에서만 의미 있는 작업**은 workdir을 repo 루트로 고정하는 편이 낫다.

```bash
hermes cron edit <job_id> --workdir "D:/develop/project/my-blog"
```

주의: workdir이 있는 job은 스케줄러가 **순차 실행**한다. 같은 시각에 여러 workdir job이 겹치면 대기한다.

----

### skills — 절차 재사용

반복 프롬프트를 매번 쓰지 않으려면 스킬을 job에 붙인다. cron 실행 시 스킬 `SKILL.md`가 먼저 로드되고, `prompt`가 그 위에서 작업 지시 역할을 한다.

```bash
hermes cron edit <job_id> --add-skill korean-stock-search
```

스킬에는 API 엔드포인트, 금지 사항, 검증 단계를 넣고, cron `prompt`에는 **이번 실행만의 변수**(날짜, deliver 형식 등)를 짧게 적는 패턴이 유지보수에 유리하다.

----

### deliver — 결과를 어디로 보낼지

| 값 | 동작 |
|----|------|
| `origin` | job을 만든 채널·스레드로 회신 (권장) |
| `discord` / `discord:<chat_id>` | 홈 채널 또는 지정 채널 |
| `local` | `~/.hermes/cron/output/`에만 저장, 메시지 없음 |
| (에이전트 응답) `[SILENT]` | 변경·알림 없을 때 배달 억제 |

리포트형 job은 deliver를 origin으로 두고, 에이전트가 **최종 응답 본문이 곧 Discord 메시지**가 되게 작성한다. `send_message`를 cron 안에서 호출할 필요는 없다.

----

### agent 모드 vs watchdog (`no_agent`)

**agent 모드 (기본)**  
LLM이 prompt·skills·도구를 사용해 작업한다. 요약, 조건부 분기, 여러 소스 합성에 적합하다. 토큰 비용이 든다.

**watchdog (`no_agent: true` + `script`)**  
`~/.hermes/scripts/` 아래 스크립트만 실행한다. stdout이 비어 있으면 **조용히 종료**, 내용이 있으면 그대로 전달, non-zero exit는 에러 알림이다.

```bash
hermes cron create \
  --name "disk-watch" \
  --schedule "every 1h" \
  --script check_disk.py \
  --no-agent \
  --deliver origin
```

디스크·게이트웨이·API 헬스처럼 **고정 포맷 알림**은 watchdog, "오늘 뭐가 중요한지 골라서 써줘"라는 agent 모드로 나누는 것이 일반적이다.

----

### 운영 시 흔한 이슈

1. **게이트웨이 미기동** — cron은 Hermes 게이트웨이 프로세스가 떠 있어야 한다. 재부팅 후 스킵된 job은 `hermes cron run <job_id>`로 수동 실행한다.
2. **missed schedule** — 다운타임이 grace(기본 2시간)를 넘기면 해당 회차는 스킵되고 다음 시각으로 fast-forward된다.
3. **pause** — `hermes cron pause <job_id>`로 일시 중지. 실험·LLM 쿼터 보호에 사용한다.

```bash
hermes cron list
hermes cron run a64349322789 --accept-hooks
hermes cron pause <job_id>
hermes cron resume <job_id>
```

----

### 마무리

* **하나의 job = 하나의 책임**. 날씨·주식·SSoT를 한 prompt에 욱여넣지 말고 job을 쪼갠다.
* **workdir + skills + 짧은 prompt** 조합이 장기적으로 diff가 작다.
* watchdog은 stdout을 의도적으로 비우는 설계가 핵심이다. "변화 없음 = 침묵"이어야 채널이 지저분해지지 않는다.

다음 글에서는 Discord 멀티봇 환경에서 deliver·wind-down을 어떻게 맞추는지 이어서 다룬다.
