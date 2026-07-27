---
layout: post
author: Kim, DongKi
title:  "노션·지라 아침 브리핑 cron 만들기 — Discord로 받는 실전 루틴"
date:   2026-07-27
categories: AI
comments: true
---

### TL;DR
* cron 글에서 “스케줄러”까지 다뤘다면, 이번엔 **매일 아침 일정 브리핑**이라는 실전 루틴이다.
* 일정 분류는 결정적이다. LLM agent보다 **`no_agent` + script**가 싸고 안정적이다.
* 이슈 트래커·문서 DB를 조회해 오늘 시작 / 진행 중 / 오늘 마감 / 기한 초과로 나눈 뒤, stdout을 Discord로 deliver한다.
* 결과가 비면 **SILENT**. 평일만 돌리고, 시크릿은 env에 둔다.

----

### 들어가며

[Hermes cron](/ai/2026/07/13/hermes-cron-workflow.html)으로 반복 작업을 예약하는 법은 정리했다. [Discord deliver](/ai/2026/07/13/discord-hermes-bot.html)로 채널에 받는 패턴도 있다. 그런데 “매일 아침, 내 일정이 한 장으로 오면 좋겠다”는 욕구는 따로 남는다.

날씨·뉴스 요약은 agent가 맞아도, **시작일·마감일 기준으로 티켓을 버킷에 넣는 일**은 판단이 아니라 분류다. 여기에 LLM을 붙이면 토큰만 쓰고, ACP 세션이 중간에 죽으면 아침이 비게 된다. 그래서 브리핑은 script로 옮겼다.

----

### 목표 출력 형태

채팅에 이렇게만 오면 충분하다. (예시 — 실제 티켓·회사명은 넣지 않음)

```text
[일정 브리핑] 2026-07-27 (월)

■ 1. 오늘부터 진행
- (없음)

■ 2. 기간 중
- [PROJ-12] 문서 파서 개선 | 07-21 ~ 08-07
- [PROJ-80] 고객 PoC | 07-13 ~ 08-31

■ 3. 오늘 마감
- (없음)

■ 4. 기한 초과
- [PROJ-3] 스키마 반영 | 07-21 ~ 07-24
```

섹션 정의는 단순하게 고정한다.

| 섹션 | 조건 (개념) |
|------|-------------|
| 오늘부터 진행 | start ≤ today ≤ due, start == today |
| 기간 중 | start < today < due (또는 start ≤ today ≤ due에서 오늘시작·오늘마감 제외) |
| 오늘 마감 | due == today |
| 기한 초과 | due < today, 미완료 |

완료(Done 계열)는 전부 제외한다.

----

### 왜 agent가 아니라 script인가

* **입력이 구조화**돼 있다. REST JSON → 날짜 비교 → 문자열 조립이면 끝이다.
* **출력이 고정**이다. 섹션 순서가 바뀌면 오히려 읽기 어렵다.
* agent cron은 프로바이더·세션 장애에 민감하다. 아침 알림은 **안 오는 것**이 제일 아프다.
* `no_agent: true`면 stdout이 곧 메시지다. 비우면 deliver가 침묵한다 — Discord wind-down과 같은 철학이다.

요약·판단이 필요하면 agent. **분류·임계값·헬스체크**면 script.

----

### 최소 파이프라인

```text
env(API 키) → REST 조회 → 날짜 버킷 → 마크다운 문자열
                └ 비면 stdout 없음 (SILENT)
```

의사코드 예시:

```python
# pseudocode — 필드명·엔드포인트는 환경마다 다름
issues = fetch_tracker(assignee="me", exclude_done=True)
today = local_date()
buckets = {"start": [], "ongoing": [], "due": [], "overdue": []}

for it in issues:
    s, d = parse_date(it.start), parse_date(it.due)
    if d and d < today: buckets["overdue"].append(it)
    elif d == today: buckets["due"].append(it)
    elif s == today: buckets["start"].append(it)
    elif s and d and s < today < d: buckets["ongoing"].append(it)

text = render_brief(today, buckets)
if not has_any(buckets):
    sys.exit(0)  # empty stdout → SILENT
print(text)
```

문서 DB(Notion류)는 선택이다. “오늘 할 일” 데이터베이스를 query해서 제목만 맨 아래에 붙이면, 트래커에 없는 개인 할 일도 한 장에 모인다. DB id·토큰은 플레이스홀더만 쓰고 코드에 하드코딩하지 않는다.

----

### 이슈 트래커 쪽 포인트

* assignee = 나, statusCategory ≠ Done 정도만 필터해도 아침용으로는 충분하다.
* start·due 필드가 비어 있으면 그 이슈는 브리핑에서 빼는 편이 낫다. “추정”하면 노이즈가 된다.
* 프로젝트 키·보드 URL·내부 호스트는 글·레포에 넣지 말고 **로컬 스크립트 + env**에만 둔다.

문서 DB 쪽은 페이지 제목·마감 날짜 정도만. 본문 전체를 긁을 필요는 없다.

----

### Hermes cron 등록 예시

```yaml
# 개념 예시 — 채널 id·경로는 로컬 값으로 교체
name: weekday-morning-brief
schedule: "30 7 * * 1-5"   # 평일 07:30
no_agent: true
script: scripts/morning_brief.py
deliver: "discord:0000000000000000000"
```

CLI로 만들 때도 동일하다. `no_agent`면 `prompt`/`skills`는 무시되고, **비어 있지 않은 stdout만** 채널로 간다. 게이트웨이가 꺼져 있으면 cron 자체가 스킵되니, 아침 루틴을 믿으려면 게이트웨이 상시가 전제다.

----

### 운영 팁

* **빈 아침은 침묵** — “오늘 할 일 없음”을 매일 보내면 알림이 죽는다.
* **시크릿은 `.env`** — 스크립트·git·블로그에 토큰을 넣지 않는다.
* **agent cron용 프로바이더와 분리** — IDE ACP 세션은 장시간 cron에 잘 깨진다. 브리핑은 script로 고정하는 편이 안전하다.
* 주말·공휴일은 schedule에서 빼거나, 스크립트에서 요일 가드를 한 겹 더 둔다.

----

### 마무리

cron으로 **언제** 돌릴지, Discord로 **어디에** 받을지 정한 다음 단계가 이 루틴이다. 스케줄러 → 채널 운영 → **매일 쓰는 자동화**. 아침 한 장이면 충분하고, 비면 말하지 않으면 된다.
