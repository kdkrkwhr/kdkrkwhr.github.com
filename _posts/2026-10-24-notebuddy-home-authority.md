---
layout: post
author: Kim, DongKi
title: "노트버디 개발기 5: AI에게 어디까지 맡길 것인가"
date: 2026-10-24
permalink: /ai/2026/10/24/notebuddy-home-authority.html
categories: AI
comments: true
description: "노트버디의 가구 배치는 AI가 고르지만 코인과 소유권, 배치 가능 여부는 Python 규칙이 판단합니다. 후보 ID와 revision, 오래된 요청과 구매 재시도를 실제 호출 경로로 살펴봅니다."
excerpt: "친구에게 집을 꾸며 달라고 부탁하되, 코인까지 맡기지는 않았습니다. 가능한 배치를 먼저 만들고 AI가 그중 하나를 고르게 한 이유를 적어봤습니다."
---

### TL;DR

* 사용자는 가구를 사고, 친구는 코드가 만든 유효한 배치 후보 중 하나를 고릅니다. AI가 좌표나 가격을 직접 정하지는 않습니다.
* 코드가 소유권과 충돌·동선을 검사하고, 적용 시점에 친구 ID와 홈 revision, 후보 ID를 다시 확인합니다.
* 오래된 배치는 거부하고, 이미 처리한 구매의 재시도는 같은 요청의 결과로 확인합니다. 저장 확인을 위해 AI를 다시 부르지는 않습니다.

----

### 들어가며

[4편]({{ '/ai/2026/10/20/notebuddy-pixel-world.html' | relative_url }})에서는 친구가 집과 마당을 걷는 모습을 소개했습니다. 이번에는 그 공간을 누가, 어떤 권한으로 바꾸는지 이야기해보려 합니다.

노트버디에서는 사용자가 가구를 고르고 배치는 친구에게 맡깁니다. 그런데 “아늑하게 꾸며 줘”라는 부탁 안에는 서로 다른 판단이 들어 있습니다. 어느 쪽이 친구의 취향에 맞는지, 가진 가구인지, 놓아도 길이 막히지 않는지 같은 것들이요. **취향은 AI가 고르되, 실행해도 되는지는 코드가 확인하도록** 나눴습니다.

아래는 `notebook-pets`의 Anna용 `release` 트랙에서 확인한 [커밋 a9b6843](https://github.com/kdkrkwhr/notebook-pets/tree/a9b6843d6d7716b7a19e5ab8dd94fd18ced6bc26) 기준입니다. 저장소와 ETag는 [2편]({{ '/ai/2026/10/19/notebuddy-anna-porting.html' | relative_url }})에서 다뤘으니, 여기서는 행동을 허용하는 조건에 집중해보겠습니다.

----

### AI에게 빈 방의 좌표부터 주지는 않습니다

‘꾸며 줘’를 누르면 먼저 `home`으로 현재 상태와 후보를 읽습니다. 후보가 없으면 안내만 하고 끝나고요. 후보가 있을 때만 AI에 한 번 선택을 요청합니다.

후보를 만드는 곳은 [engine/home.py의 candidates](https://github.com/kdkrkwhr/notebook-pets/blob/a9b6843d6d7716b7a19e5ab8dd94fd18ced6bc26/engine/home.py#L177)입니다. 독서·휴식·개방·정돈의 네 가지 스타일 순서를 바탕으로, 보유했지만 아직 놓지 않은 가구부터 배치해봅니다. 최근에 산 가구를 먼저 살피며 한 후보에서 추가하는 가구는 최대 세 개입니다. 미배치 가구가 없다면 기존 물건 하나를 옮겨보는 경로도 있습니다.

아무 좌표나 탐색하는 구조는 아닙니다. 정해진 위치들을 시도하고, 규칙을 통과한 배치만 남깁니다. 현재 배치와 같거나 서로 중복된 후보도 제외하므로 결과가 항상 네 개인 것은 아닙니다.

AI가 받는 것은 후보 ID와 스타일, 옮길 물건과 위치, 보관함에 남을 물건입니다. 저장된 성향 값도 함께 전달합니다. 답변에는 **`candidate_id`와 180자 이내의 짧은 `reason`만** 허용합니다. 좌표를 참고해서 선택할 수는 있지만, 새로운 좌표를 제출할 권한은 없습니다. [화면의 요청·응답 검사](https://github.com/kdkrkwhr/notebook-pets/blob/a9b6843d6d7716b7a19e5ab8dd94fd18ced6bc26/anna/bundle/home.mjs)에서도 이 두 필드 외의 값이나 목록에 없는 ID를 거부합니다.

----

### 놓을 수 있다는 말에는 걸어갈 수 있다는 뜻도 들어갑니다

배치 검사에서는 가구의 존재 여부만 보지 않습니다. [valid_layout과 path](https://github.com/kdkrkwhr/notebook-pets/blob/a9b6843d6d7716b7a19e5ab8dd94fd18ced6bc26/engine/home.py#L92)가 아래 조건을 확인합니다.

| 확인할 것 | 코드에서 판단하는 내용 |
| --- | --- |
| 소유권 | 배치한 물건이 보유 목록과 카탈로그에 모두 있어야 합니다. |
| 실내 충돌 | 방 밖으로 나가거나 바닥 가구끼리 겹칠 수 없습니다. 출입구 쪽 하단 공간도 비워둡니다. |
| 가구 종류 | 벽 장식은 벽 위치에, 독서등과 찻잔은 책상에 대응하는 위치에 있어야 합니다. 러그는 바닥 가구와 같은 장애물로 취급하지 않습니다. |
| 실내 동선 | 2×2 크기의 몸·운반 공간이 문에서 각 바닥 가구 옆까지 도달할 수 있어야 합니다. |
| 마당 | 별도의 정원 규칙으로 집·문 주변과 설치물의 충돌, 필요한 이동 경로를 검사합니다. |

실내 길 찾기는 너비 우선 탐색으로 수행합니다. 가구 안으로 들어가는 경로가 아니라 옆에 설 수 있는 경로를 찾습니다. 마당 검사는 [home_world.py](https://github.com/kdkrkwhr/notebook-pets/blob/a9b6843d6d7716b7a19e5ab8dd94fd18ced6bc26/engine/home_world.py)의 `valid_garden`에 위임하고요.

최종 배치가 유효해도 옮기는 과정을 만들 수 없으면 후보에서 빠집니다. 실내 운반 단계는 `motions`가 계산하며 최대 여섯 단계로 제한합니다. 마당 가구는 이 실내 운반 목록에서 제외합니다. 화면의 이동 연출을 AI가 한 걸음씩 지시하는 구조는 아닙니다.

----

### 후보 ID는 지금 상태에서만 의미가 있습니다

AI가 고르는 동안 사용자의 상태가 달라질 수도 있습니다. 예전 후보를 받았다는 이유만으로 그대로 적용하면 안 되겠죠.

후보 ID는 친구 ID, 홈 revision, 보유 목록, 초기 성향, 스타일과 배치를 묶어 SHA-256으로 계산한 값의 앞 20자리입니다. 이것은 **상태에 연결된 후보 식별자**이지 인증 토큰이나 서명은 아닙니다. 실제 허용 여부는 적용할 때 다시 판단합니다.

`home_apply` 요청이 들어오면 `home.request`는 친구 ID와 홈 revision을 확인합니다. 이어서 **현재 상태로 후보를 다시 생성한 뒤** 요청한 ID를 찾습니다. AI가 준 배치 객체를 저장하는 것이 아니라, 코드가 다시 찾은 후보의 배치와 동선을 가져오는 겁니다.

실제 호출도 이 검사를 거칩니다.

```text
home.mjs: 후보 조회 → AI 선택 → home_apply
  → notebuddy_plugin.py: 요청 수신과 저장 처리
  → game_worker.py: home_payload를 transition에 전달
  → game_api.py → engine.py의 cmd_home → home.request
```

[game_worker.py](https://github.com/kdkrkwhr/notebook-pets/blob/a9b6843d6d7716b7a19e5ab8dd94fd18ced6bc26/anna/executas/notebuddy/game_worker.py)와 [공통 API](https://github.com/kdkrkwhr/notebook-pets/blob/a9b6843d6d7716b7a19e5ab8dd94fd18ced6bc26/engine/game_api.py)를 보면 화면의 검사만 믿고 끝내지 않는다는 점을 확인할 수 있습니다. 요청에 `coins`나 `layout` 같은 필드를 끼워 넣어도 허용된 필드 집합과 다르므로 거부합니다.

----

### 오래된 요청과 같은 구매의 재시도는 다릅니다

홈 revision은 구매·배치·되돌리기가 성공할 때 증가합니다. 예전 revision을 보낸 새 행동은 `home_conflict`가 됩니다. revision만 최신으로 바꾸고 예전 후보 ID를 보내도 현재 후보 목록에 없으면 거부하고요.

반면 이미 성공한 구매를 통신 문제로 다시 확인하는 경우에는 같은 요청 ID와 인자를 유지해야 합니다. [execute_once](https://github.com/kdkrkwhr/notebook-pets/blob/a9b6843d6d7716b7a19e5ab8dd94fd18ced6bc26/engine/engine.py#L794)는 남아 있는 처리 기록을 먼저 확인해 이전 결과를 반환합니다. 이 경우 오래된 revision을 다시 실행하는 것이 아니라 **이미 처리한 결과를 읽는 것**입니다. 같은 ID에 다른 명령이나 인자를 넣으면 `request_conflict`가 됩니다.

새 구매의 가격은 카탈로그에서 읽습니다. 이미 소유했거나 코인이 부족하면 실패하고, 성공하면 코인을 차감하고 보유 목록에 넣습니다. 구매만으로 배치하거나 AI를 호출하지는 않습니다. 되돌리기도 배치만 복원하므로, 나중에 산 물건이나 코인을 구매 전으로 돌리지는 않습니다.

화면은 저장 결과를 확인하지 못하면 대기 중인 요청을 유지합니다. ‘같은 요청 확인’은 그 요청을 재사용하며 AI 선택을 다시 요청하지 않습니다. 이 동작과 저장 계층의 재시도 처리는 [홈 UI](https://github.com/kdkrkwhr/notebook-pets/blob/a9b6843d6d7716b7a19e5ab8dd94fd18ced6bc26/anna/bundle/home.mjs#L55), [Anna 도구](https://github.com/kdkrkwhr/notebook-pets/blob/a9b6843d6d7716b7a19e5ab8dd94fd18ced6bc26/anna/executas/notebuddy/notebuddy_plugin.py)에서 이어집니다. 영원히 모든 요청을 기억한다는 보장은 아니므로, 현재 저장 프로토콜의 요청 ID와 보존된 처리 기록을 전제로 봐야 합니다.

----

### 규칙만 따로 확인해볼 수 있습니다

AI 없이도 핵심 검사는 재현할 수 있습니다. 해당 커밋의 저장소 루트에서 실행하는 명령입니다.

```bash
python -m unittest discover -s tests -p test_home.py -v
node --test anna/tests/home.test.mjs
```

[Python 테스트](https://github.com/kdkrkwhr/notebook-pets/blob/a9b6843d6d7716b7a19e5ab8dd94fd18ced6bc26/tests/test_home.py)는 구매 재시도, 코인 부족, 오래된 후보, 다른 친구 ID, 잘못된 배치와 추가 필드 주입 등을 확인합니다. [JavaScript 테스트](https://github.com/kdkrkwhr/notebook-pets/blob/a9b6843d6d7716b7a19e5ab8dd94fd18ced6bc26/anna/tests/home.test.mjs)는 후보 선택 응답의 형식과 지정된 경로의 연출을 검사합니다.

이번 글을 준비하며 Python 9개와 JavaScript 2개가 통과한 것을 확인했습니다. 별도의 Anna 저장 연동 테스트는 실행 환경에 `executa_sdk`가 없어 시작하지 못했습니다. 실제 Anna 저장소나 외부 모델은 호출하지 않았으므로, 이 결과를 앱 전체의 동작이나 AI의 취향 선택까지 검증한 것으로 볼 수는 없습니다.

----

### 마치며

친구가 방을 꾸민다는 느낌을 주기 위해 게임 규칙까지 AI에 넘길 필요는 없었습니다. 가능한 행동은 코드가 만들고, 그중 어느 쪽이 어울리는지 고르는 자리에 AI를 두었습니다.

물론 정해진 후보 안에서만 선택하니 자유로운 인테리어와는 거리가 있습니다. 앞으로 배치를 더 풍부하게 만들려면 후보 생성 규칙도 함께 다듬어야 하고요. 그래도 **친구의 취향과 사용자의 재화를 서로 다른 권한으로 다룬다**는 기준은 유지해보려 합니다.

----

{% include notebuddy-series.html %}
