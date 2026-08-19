---
layout: post
author: Kim, DongKi
title:  "0-turn 패턴: 에이전트가 서로 방해하지 않고 대화하는 법"
date:   2026-08-19
categories: AI
comments: true
---

### TL;DR

* 여러 에이전트가 동시에 돌아가면, 한쪽이 보낸 메시지가 다른 쪽의 생각 도중에 끼어든다.
* 작은 모델일수록 이 난입에 흔들려 집중력을 잃는다(Reasoning Drift).
* 0-turn 패턴은 모델을 한 번도 호출하지 않고 메타데이터만 흡수하는 수동 인지(passive awareness) 방식이다.
* 실제로는 메시지 수신 루프와 스텝 경계 버퍼로 나눠 구현한다.

----

### 들어가며

며칠 전, 여러 에이전트를 한꺼번에 돌리는 실험을 하고 있었다. 다섯 개 에이전트가 하나의 메시지 서버를 두고 수다를 떨도록 만든 것이다. 그런데 결과가 이상했다. 큰 모델은 괜찮았는데, 작은 무료 모델을 쓸 때는 중간에 메시지가 불쑥 들어오면 그다음 답변이 아예 다른 방향으로 새어나갔다.

원인은 단순했다. 모델이 깊게 생각하는 중인데, 옆방에서 누가 말 거는 소리가 들린 것이다. 사람도 깊은 생각 하다가 갑자기 누가 부르면 헛둑하게 반응하듯, 작은 모델은 컨텍스트가 오염됐다. 이걸 해결해 보려고 여기저기 손을 대다가, 지금은 하나의 패턴으로 정리하고 있다.

----

### 문제: 메시지 난입과 Reasoning Drift

멀티에이전트에서 통신은 필수다. 그런데 통신 타이밍을 어떻게 잡느냐가 관건이다.

* 메시지가 올 때마다 즉시 모델에게 "이거 봐" 하고 주입하면: 매번 LLM 턴(모델 호출)을 소모한다. 토큰 비용이 폭증한다.
* 그렇다고 무시하면: 중요한 협업 신호를 놓친다.

작은 모델은 이 딜레마에서 더 취약하다. 추론 도중 외부 입력이 들어오면 원래 하려던 흐름을 잃어버린다. 이걸 Reasoning Drift라고 부르기로 했다. 무료 모델일수록 한 번 턴이 엉키면 회복이 느리다.

----

### 0-turn 패턴이란

해결책으로 떠올린 게 0-turn 패턴이다. 이름 그대로 모델을 한 번도 호출하지 않는(turn = 0) 통신이다.

핵심은 수동 인지(passive awareness)다. 메시지가 와도 모델에게 즉시 말 걸지 않고, 메타데이터만 별도 채널에 기록해 둔다. 모델은 자기 일을 끝낼 때까지 방해받지 않는다. 필요할 때만 나중에 확인한다.

이건 단순히 알림을 끄는 게 아니다. 모델이 소통하지 않는 게 아니라, 소통의 타이밍을 모델의 생명주기에 맞추는 것이다.

----

### 실제로 가져가는 방식

지금 내가 하고 있는 구현은 두 층이다.

**1층: 수신 루프 (wait_for_mention)**

백그라운드에서 돌며 mention을 기다린다. 실제 스크립트 일부다.

```bash
SID=$(mcp_init "$URL") || exit 1
STATE=$(mcp_resource_read "$URL" "$SID" "coral://state")
BASELINE=$(printf '%s' "$STATE" | count_messages)
echo ">> baseline: $BASELINE message(s) currently visible"

round=0
while true; do
  round=$((round + 1))
  RESP=$(mcp_tool_call "$URL" "$SID" "coral_wait_for_mention" "$WAIT_ARGS")
  MSG=$(printf '%s' "$RESP" | json_get 'result.structuredContent.message' 2>/dev/null)
  if [ -n "$MSG" ]; then
    echo "=== [round $round] MENTION RECEIVED ==="
    break
  fi
  STATE=$(mcp_resource_read "$URL" "$SID" "coral://state")
  NOW=$(printf '%s' "$STATE" | count_messages)
  # NOW > BASELINE 이면 새 메시지가 온 것
done
```

이 루프 자체는 모델을 부르지 않는다. 그냥 새 메시지가 왔는지 폴링만 한다. 이게 0-turn의 1단계다.

**2층: 스텝 경계 버퍼 (설계 중)**

수신만 해선 부족하다. 받은 메시지를 언제 모델에게 보여줄지가 문제다. 내가 지금 가져가려는 방향은 스텝 경계에서만 주입하는 것이다.

```python
class StepBoundaryBuffer:
    def __init__(self):
        self.pending = []

    def on_radio_message(self, msg):
        # 추론 도중엔 주입하지 않고 대기
        self.pending.append(msg)

    def on_tool_call_complete(self):
        # 툴 호출 하나가 끝난 경계에서만 주입
        if self.pending:
            inject_to_context(self.pending)
            self.pending.clear()
```

모델이 한 번의 도구 호출을 끝낸 시점(스텝 경계)에만 대기 중인 메시지를 한꺼번에 흡수한다. 중간에 끼어들지 않는다.

이 구조는 아직 완성된 코드가 아니라 내가 밀고 있는 설계안이다. 별도 프레임워크 쪽 PR에서도 opt-in 도구 형태로 비슷한 수동 인지 구조를 제안했고, 다른 오픈소스 PR에서는 비동기 컨텍스트 전파로 같은 맥락을 시도했다. 서로 다른 레포지만 결국 같은 고민에서 출발했다.

----

### 마무리

0-turn 패턴은 화려한 기법이 아니다. 그냥 모델을 방해하지 말라는 상식에서 출발했다. 작은 모델일수록 이 상식이 더 귀중하다.

지금은 수신 루프는 동작하고, 스텝 경계 버퍼는 설계를 다듬는 중이다. 완성되면 실측 결과(토큰 절감량)도 같이 공유하겠다.
