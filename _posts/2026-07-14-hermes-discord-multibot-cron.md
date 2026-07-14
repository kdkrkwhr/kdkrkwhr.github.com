---
layout: post
author: Kim, DongKi
title:  "Discord 멀티봇에서 Hermes cron deliver·wind-down 맞추기"
date:   2026-07-14
categories: AI
comments: true
---

### TL;DR
* Discord에 봇이 여러 개면 **Hermes 프로필이 곧 봇 계정**이다. cron·스킬·세션 DB는 프로필마다 따로다.
* `deliver`는 `origin` / `discord:채널:스레드` / `local` / 본문 `[SILENT]` 네 갈래로 쓰면 충분하다.
* wind-down은 SOUL·채널 프롬프트만으로는 부족하다. **cron은 pause·local·[SILENT]**로 따로 졸려야 한다.
* 리포트는 Discord 스레드, git push형 유지보수는 `local`, 실험·일회성만 `origin`에 두는 편이 채널이 덜 더러워진다.

----

### 들어가며
[어제 글](/2026/07/13/hermes-cron-workflow/)에서 cron의 `schedule`·`workdir`·`skills`·`deliver`를 정리했다. 그 글 마지막에 Discord 멀티봇에서의 deliver·wind-down을 미뤄 둔 것이 이 글이다.

한 Discord 서버에 Hermes 프로필이 둘 이상(예: 코딩용 `default`, 분석·Kanban용 `nous-work`) 붙어 있으면, cron 결과가 **어느 봇 입에서, 어느 채널·스레드로** 나가는지가 운영의 전부다. deliver를 대충 `origin`으로만 두면 엉뚱한 스레드에 브리핑이 쌓이고, wind-down 없이 봇끼리 멘션이 붙으면 야간에 수십 턴 핑퐁이 난다. 이 글은 지금 돌리는 설정 기준으로 그 경계를 정리한다.

----

### 멀티봇 환경이란

Hermes에서 멀티봇은 "하나의 프로세스에 페르소나 여러 개"가 아니다. **프로필마다 게이트웨이·Discord 토큰·`cron/jobs.json`·세션 DB가 분리**된다.

| 프로필 | 역할 (예시) | Discord | cron 위치 |
|--------|-------------|---------|-----------|
| `default` | 코딩·ACP (양파쿵야) | 봇 A | `$HERMES_HOME/cron/` |
| `nous-work` | 분석·Kanban·보고 (버섯쿵야) | 봇 B | `profiles/nous-work/cron/` |
| `claude` | 별도 페르소나 | 봇 C (선택) | `profiles/claude/cron/` |

같은 `#work` 채널에 봇 A·B가 같이 있어도, A에 만든 cron은 B의 `hermes cron list`에 안 보인다. 스킬도 같다 — `default`에 깔린 스킬은 `nous-work`에 자동 공유되지 않는다.

```bash
# 지금 셸이 어느 프로필인지부터 확인
hermes profile list
hermes -p nous-work cron list
hermes -p default cron list
```

운영 함정: "이 서버에 봇이 있으니 cron도 공유겠지"라고 가정하고 job을 한쪽에만 만들면, 다른 페르소나 쪽에서 영원히 안 돈다. job을 옮길 때도 id 이동 API는 없고 — 대상 프로필에 **재생성**한 뒤 원본을 지운다.

같은 서버에서 봇이 서로 멘션하면 게이트웨이가 각자 턴을 연다. `discord.require_mention: true`여도 **멘션만으로 루프가 충분**하다. 그래서 cron deliver 채널과, 잡담·토론 채널을 나누는 편이 낫다.

----

### deliver 옵션별 전략

어제 글의 deliver 표를 멀티봇 관점에서 다시 쓰면 아래와 같다.

| 값 | 동작 | 멀티봇에서 쓸 때 |
|----|------|-------------------|
| `origin` | job을 만든(또는 origin에 찍힌) 채널·스레드로 회신 | 수동 생성·디버그. origin이 null이면 배달이 애매해질 수 있음 |
| `discord:<channel>:<thread>` | 지정 채널·스레드로 고정 | **구독형 리포트** (주식, SSoT, 헬스) |
| `local` | `cron/output/`에만 저장, 채팅 없음 | git push로 끝나는 유지보수 (PWA 날씨·뉴스) |
| 본문 `[SILENT]` | 에이전트/스크립트가 "보낼 것 없음" 선언 | 변화 없음·목표 달성 시 노이즈 억제 |

`all`이나 다른 플랫폼 fan-out은 "모든 홈 채널에 뿌린다"는 뜻이어서, 봇이 둘이면 **같은 내용이 두 입에서** 나갈 수 있다. 평소엔 쓰지 않는다.

#### 현재 job 매핑 (default 프로필 기준)

| job | deliver | 의도 |
|-----|---------|------|
| morning-kr-stock-report | `discord:#work / 주식 리포트 스레드` | 매일 08:00, 구독자만 보면 됨 |
| daily-ssot-push | `discord:#work / 업무 스레드` | SSoT push 요약만 업무 토픽에 |
| cron-health-watchdog | 같은 업무 스레드 | watchdog — stdout 있을 때만 |
| seoul-events-rss | `origin` (+ origin에 discord 스레드) | 생성 당시 스레드에 고정 |
| blog-auto-post | `origin` | 블로그 job 생성 채널로 완료 보고 |
| company-weather-pwa / daily-news-pwa / attendance-pwa-auto-maintain | `local` | push가 곧 배포, Discord 스팸 불필요 |
| allre-tts extract-conditions… | `origin` | 실험 루프 — 지금은 **paused** |

패턴을 한 줄로 말하면 이렇다.

* **사람이 읽어야 하는 요약** → `discord:채널:스레드`
* **git이 결과물** → `local` + prompt에 "성공 시 한 줄 / 변화 없으면 `[SILENT]`"
* **대화 중에 만든 실험 job** → `origin` (그 스레드가 곧 로그)

```bash
# 주식 브리핑을 #work의 고정 스레드로
hermes cron edit 3f480c05eb1f \
  --deliver "discord:1513701746244059258:1513708081396322496"

# PWA 유지보수는 채팅 없이
hermes cron edit e8f3a1c92b47 --deliver local
```

스레드 id까지 넣는 이유: 채널 루트에 매일 08시 메시지가 떨어지면 다른 대화가 밀린다. Discord 포럼/토픽이면 `channel_id:thread_id` 형식이 안전하다.

----

### wind-down — 야간·주말에 cron을 조용히

wind-down은 두 층이다.

1. **채팅 층** — SOUL·`discord.channel_prompts`: 주제 끝·👍/👋만 오면 침묵, 토론 종료 후 재멘션 금지.
2. **스케줄 층** — cron이 밤에도 돌아가면, 채팅 규칙과 무관하게 메시지가 생긴다.

채팅 층만 있고 cron이 `discord:...`로 계속 쏘면 "봇은 조용한데 알람은 시끄러운" 상태가 된다. 스케줄 쪽 수단은 세 가지다.

#### 1) pause / resume

며칠 실험이 끝났거나, 주말엔 증시·SSoT가 의미 없으면 job 자체를 멈춘다.

```bash
hermes cron pause a64349322789          # blog-auto-post 잠깐 끔
hermes cron resume a64349322789
```

`allre-tts extract-conditions`처럼 **목표 달성(점수·streak) 후 self-pause**하는 prompt도 가능하다. 루프형 agent cron은 "언제 스스로 꺼질지"를 prompt에 명시하지 않으면 영원히 20분마다 떠든다.

#### 2) 조건부 `[SILENT]`

agent·watchdog 공통이다. prompt 또는 스크립트 규칙을 이렇게 둔다.

```text
변화가 없거나 validate만 PASS이고 commit 0건이면
최종 응답은 정확히 [SILENT] 한 줄.
성공 push면 한 줄 요약만.
```

watchdog(`no_agent: true`)는 stdout이 비면 배달 자체가 없다. agent 모드는 **본문에 `[SILENT]`**를 써야 게이트웨이가 억제한다. 둘을 헷갈리면 "빈 문장 썼는데도 Discord에 `(응답 없음)` 비슷한 흔적"이 남을 수 있으니, 프롬프트에 토큰을 박아 두는 편이 낫다.

#### 3) deliver를 `local`로 내리는 시간대 운영

야간 유지보수 job은 아예 Discord에 안 보내는 것이 wind-down이다. PWA 2시간 점검이 그 예다. "밤에도 돌아가되, 사람 알림은 낮 리포트만"이 목표면 — 스케줄을 줄이지 말고 deliver만 `local`로 둔다.

주말·공휴일까지 끄려면 cron 표현식에 요일이 들어가거나, pause를 캘린더에 묶는 쪽이 단순하다. `0 8 * * 1-5`처럼 **평일만** 돌리는 편이 `[SILENT]` 남발보다 예측 가능하다.

```bash
# 평일 08:00만
hermes cron edit 3f480c05eb1f --schedule "0 8 * * 1-5"
```

----

### 실전 설정 스케치 (default / nous-work / claude)

역할을 나눈 뒤에는 **어느 프로필이 Discord에 무엇을 쏘는지**만 표로 고정해 둔다.

| 관심사 | 담당 프로필 | deliver 쪽 |
|--------|-------------|------------|
| 주식·날씨 브리핑, PWA git, 블로그 자동포스트 | `default` | 스레드 or `local` |
| Kanban 상태, 조사·검토 보고 | `nous-work` | 업무 스레드 / origin |
| 별도 페르소나 잡담·토론 | `claude` (또는 외부 Claude 봇) | cron 최소화, 멘션 규칙만 |

공통 Discord 설정 습관:

* `require_mention: true` — 그룹 채널에서 멘션 없는 메시지에 안  реак.
* 토론 채널에만 `channel_prompts`로 턴 수·종료 문구·재멘션 금지를 박음.
* `#work`처럼 리포트·업무가 섞인 채널은 `no_thread_channels`로 자동 스레딩을 끄고, **cron이 쓸 스레드는 수동으로 고정**한다.
* agent cron 모델은 불안정한 ACP provider에 두지 않는다. 긴 무인 job은 `provider: nous` + 싼 모델로 핀한다 (어제 깨진 cron은 대개 이쪽).

```bash
# 프로필별 게이트웨이 (토큰·포트 분리)
hermes -p default gateway status
hermes -p nous-work gateway status
```

게이트웨이가 꺼진 프로필의 cron은 스킵된다. 멀티봇이면 **봇 계정마다 gateway가 떠 있는지**를 헬스체크에 넣는다. `cron-health-watchdog` 같은 no_agent job을 업무 스레드에 두면, "오늘 아침 리포트가 안 온 이유"를 사람보다 먼저 알 수 있다.

----

### 마무리

* 멀티봇 = 멀티 프로필. cron·스킬을 한쪽에만 두지 말고, **어느 입이 말할 job인지**를 먼저 고른다.
* deliver는 구독 스레드 / local / origin 세 칸이면 실무가 끝난다. `all`은 거의 독이다.
* wind-down은 SOUL 침묵 + cron pause·평일 스케줄·`[SILENT]`·`local`을 같이 써야 야간이 조용하다.
* 채널에 쌓이는 메시지를 줄이는 가장 싼 방법은, "성공해도 Discord에 올릴 가치가 있는가?"를 job 생성 시점에 한 번 더 묻는 것이다.

다음엔 Kanban 디스패처가 프로필을 넘나들 때 notification·assignee를 어떻게 자르는지 이어서 적을 예정이다.
