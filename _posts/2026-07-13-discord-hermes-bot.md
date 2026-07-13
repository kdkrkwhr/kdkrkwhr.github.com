---
layout: post
author: Kim, DongKi
title:  "Discord + Hermes 봇 운영 실전 팁"
date:   2026-07-13
categories: AI
comments: true
---

### TL;DR
* Hermes cron의 deliver를 Discord 채널로 연결하면, 멀티봇 환경에서 작업 결과를 원하는 곳에 분산할 수 있다
* **wind-down** 규칙으로 조건부 침묵을 설계하지 않으면 멘션 루프·의사소통 중복이 생긴다
* kdk-bot 스킬은 "잘 모르면 침묵" + "이미 대답했으면 덜 말함" 패턴을 강제한다
* 멘션 프리픽스·채널 분리·히스토리 휘발성 인지가 장기 운영의 세 실전 포인트다

----

### 들어가며
Hermes cron을 쓰다 보면 "결과를 어디로 보낼까"가 생각보다 중요해집니다. 로컬에 저장만 하면 안 보게 되고, 여러 채널에 deliver를 걸어두면 도리어 채널이 지저분해집니다. 저는 Hermes 게이트웨이를 Discord에 물려서 cron 결과·알림을 여러 봇이 공유하는 구조로 쓰고 있는데, 처음엔 멘션 루프나 중복 발화 같은 문제를 간과했다가 고쳤습니다.

이 글은 cron + Discord 조합에서 부딪힌 문제와 해결 패턴을 정리합니다.

----

### 멀티봇 환경 — deliver 분산

Hermes cron job 각각에 `--deliver discord:<chat_id>`를 설정하면, 채널 단위로 결과를 보낼 수 있습니다.

```bash
hermes cron create \
  --name "morning-brief" \
  --schedule "0 8 * * *" \
  --deliver "discord:123456789" \
  --skills korea-weather \
  --prompt "오늘 날씨와 주요 뉴스 요약"

hermes cron create \
  --name "stock-report" \
  --deliver "discord:987654321" \
  --skills korean-stock-search \
  --prompt "KOSPI·코스닥 전일 대비"
```

같은 서버의 다른 채널로 보내면 각 구독자가 필요한 정보만 봅니다. 여러 봇 계정이 같은 채널을 바라보는 구조도 가능하지만, 이때는 **누가 무엇을 말할지** 규칙이 없으면 혼란이 옵니다.

**실전 팁**
* cron deliver는 하나의 채널로 모으고, 필요하면 봇이 그 메시지를 다시 가공해 다른 채널로 보내는 게 낫습니다
* deliver를 `origin`으로 두면 job을 생성한 채널(주로 DM이나 전용 채널)로 결과가 갑니다 — 초보자에게 권장

----

### wind-down — "할 말 없으면 닥쳐"

cron deliver가 있으면 봇이 주기적으로 발화합니다. 문제는 "어제랑 똑같은 리포트"나 "변화 없는 상태"까지 매일 메시지를 보내면 채널이 알림 더미가 된다는 점입니다.

Hermes cron의 watchdog 접근을 차용해서, kdk-bot 스킬에는 **wind-down 규칙**을 넣었습니다.

```
# wind-down 조건 (kdk-bot)
- 주제가 끝났거나 상대가 👍👋만 보냄 → 침묵 유지
- "이미 답변했다"고 판단되는 질문 → 같은 내용 반복 금지
- cron job 결과가 "변화 없음" → [SILENT]로 배달 억제
```

실제로는 **의도적으로 응답을 비우는** 설계가 핵심입니다. watchdog에서 stdout이 비면 아무 일도 일어나지 않는 것처럼, agent 모드에서도 "변화 없으면 굳이 말하지 않기"를 조건으로 걸어둡니다.

```yaml
# prompt에 포함하는 조건문 예시
deliver: "discord:..."
prompt: >
  이전 리포트와 비교해 변화가 없으면 "[SILENT]"만 응답
```

----

### 멘션 루프 방지

멀티봇 환경에서 가장 흔한 사고는 **봇 A가 메시지를 보내면, 봇 B가 그걸 듣고 답하고, 다시 봇 A가 반응하는** 루프입니다.

kdk-bot에서는 세 가지 방어층을 둡니다.

1. **멘션 프리픽스 필터** — 봇 이름(`@kdk-bot`)이 멘션된 메시지만 반응. 브로드캐스트 메시지는 무시
2. **발화자 검사** — 다른 봇이 보낸 메시지는 무시 (bot flag)
3. **중복 내용 배제** — 같은 키워드·같은 답변이 30초 내에 이미 나갔으면 침묵

```python
# 의사 코드 — 각 봇에 적용하는 패턴
if message.author.bot:
    return  # 다른 봇 무시
if bot_name not in message.content and not message.reference:
    return  # 멘션 없으면 무시
if recent_responses.get(similar_key(message.content)):
    return  # 30초 내 유사 응답 있으면 침묵
```

이 세 가지만 지켜도 실제 운영에서 멘션 루프는 거의 발생하지 않습니다.

----

### 그 외 운영 팁

* **히스토리 휘발성 인지** — Discord API는 최근 50~100개 메시지만 컨텍스트로 가져옵니다. 장기 대화를 기대하지 말고, 필요한 정보는 파일·SSoT에 저장하세요
* **채널 분리 원칙** — cron 리포트 전용 채널, 잡담 채널, 알림 채널을 분리하면 봇 로직도 단순해집니다
* **kdk-bot 스킬 재사용** — `discord-group-chat` 스킬에 wind-down·말투 규칙을 몰아넣고, 다른 봇 인스턴스에서 같은 스킬을 로드하면 일관된 동작을 보장합니다

----

### 마무리

* deliver는 하나의 채널로 모으고, wind-down 규칙으로 "할 말 없으면 침묵"을 강제한다
* 멘션 루프 방지는 **멘션 필터 + bot 무시 + 중복 배제** 세 가지만으로 충분하다
* 히스토리는 휘발된다는 전제로 설계하고, 영구 데이터는 SSoT로 빼낸다
* cron + Discord 조합은 초기 설계보다 **운영 규칙**(wind-down, 침묵 조건)이 더 큰 영향을 준다