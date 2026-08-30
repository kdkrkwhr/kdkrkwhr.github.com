---
layout: post
author: Kim, DongKi
title:  "0-turn 구현기: DriftGuardBuffer 만들고 난 뒤 '이미 있었어'라는 걸 발견했어"
date:   2026-08-30
categories: AI
comments: true
---

### TL;DR

* 여러 에이전트가 동시에 돌 때, 한쪽의 메시지가 다른 쪽 추론 도중에 난입하면 작은 모델은 Reasoning Drift에 걸린다.
* 0-turn 패턴은 메시지를 **런치지 않은 채** 버퍼에 넣고, 툴 호출이 끝나는 경계에서만 context에 덧붙이는 것.
* 이걸 직접 `agent-drift-guard` 패키지로 구현하고 검증(37개 테스트 통과)했는데 — **Hermes 코어에선 `AIAgent.steer()` + `busy_input_mode: "steer"`로 이미 1:1 동일 메커니즘이 존재**하고 있었다.
* 결론: Hermes 타깃에선 더 이상 짓는 게 없음. 라이브러리는 SHELVED.

---

### 들어가며

[0-turn 패턴](https://kdkrkwhr.github.io/ai/2026/08/19/zero-turn-pattern.html) 글 마지막에 "스텝 경계 버퍼는 설계를 다듬는 중"이라고 했었는데, 그 설계를 실제로 구현해봤다. 이름은 `DriftGuardBuffer`.

근데 구현하고 난 뒤 찾아낸 게 — **나보다 먼저, 그리고 더 잘, 만들어 놓은 게 이미 팀 프로젝트 안에** 있었다는 거다.

---

### 구현한 것

framework-neutral core, 40줄:

```python
class DriftGuardBuffer:
    def __init__(self):
        self._pending = []
        self._lock = threading.Lock()

    def on_message(self, msg):
        """Record incoming. Does NOT invoke the model (0-turn)."""
        with self._lock:
            self._pending.append(msg)

    def on_step_end(self):
        """Called at a safe boundary. Returns + clears pending."""
        with self._lock:
            flushed = self._pending
            self._pending = []
        return flushed
```

핵심은 두 마디: **buffer never calls the model**, **drain at step boundary**.

Hermes 어댉터 쪽은 `on_radio_message()` → buffer, `on_tool_call_complete()` → drain + `format_injection()` → tool result appendix. 검증 스크립트(`verify_drift_guard_0turn.py`)는 가짜 모델로 0-turn 계약(모델 호출 0회, appendix 정확히 붙음, buffer 비워짐)을 assertion으로 잡았다.

```
[verify] 0-turn scenario PASS
  - model extra turns invoked : 0 (expect 0)
  - tool result appendix      : ['[drift-guard] - from agent-2: pm: status check - are you done?']
  - buffer after drain        : 0 pending (expect 0)
```

---

### "이미 있었어"

패키지를 SHELVED로 표기하던 중 `docs/findings-superseded.md`를 쓰다 보니, Hermes 코어를 뒤져보게 됐다. 그런데... 완전 똑같았음.

| agent-drift-guard | Hermes 코어 |
|---|---|
| `DriftGuardBuffer._pending` | `AIAgent._pending_steer` (`agent_init.py:576`) |
| `on_message()` (락 + 드레인) | `AIAgent.steer()` |
| `on_step_end()` (드레인 후 클리어) | `_drain_pending_steer()` (`agent_runtime_helpers.py:3163`) |
| `format_injection` → tool result appendix | "appends to the LAST tool result's content" |
| 0-turn (extra turn 0회) | "does NOT stop the current tool call" |

심지어 게이트웨이 busy 경로도 실제로 배선되어 있음:

```
gateway/run.py:2786  _busy_input_mode: str = "interrupt"
gateway/run.py:4360  if _busy_input_mode in ("steer", "queue"): ...
gateway/run.py:4898  def _load_busy_input_mode() -> str  → "steer" 인식
```

busy_input_mode = `"steer"`로 하면, �세지가 올 때마다 `interrupt` 없이 투 올리고, **툴 결과 경계에서만** model이 보게 된다. 즉 0-turn 맞통이다.

---

### 왜 직접 만들었는지, 왜 깨달았는지

당시 나는 "Hermes agent의 background 메시지 처리 메커니즘"을 정확히 파악하지 못했고, `findings-superseded.md`에 인용된 Discord 로그("steer = 이미 완성된 0-turn 메커니즘")조차 머릿속에 떠오르지 않았다.

패키지를 만들고 **실제 검증**을 돌린 게 중요한 포인트: `verify_drift_guard_0turn.py`를 실행했을 때 assertion이 깨졌음. (결론은 스크립트 기대값이 `on_tool_call_complete()`의 시그니처 변화에 뒤쳐진 건데, 실제 동작은 `str`을 반환한다는 걸 확인함.) 검증 자체가 내 설계가 실제로 뒷받침이 되는지 가늠하는 데에 큰 도움이 됐고, 결국 코어에서 동일한 게 이미 돌고 있음을 확정지을 수 있었다.

---

### 교훈

1. **스펙 위에 짓는 게 아니라, 코드부터 먼저 뒤져라.** 글 두 줄이면 되는데, 직접 구현하고 검증하고 싶은 마음에 37개 테스트를 짜갖고...
2. **SHELVED != 쓸모없음.** 코드는 참고용 보존. 다른 런타임(자작 루프 / LangGraph / CrewAI / AutoGen)이 구체적 타깃으로 올라오면 `src/drift_guard/`의 framework-neutral core가 출발점이 된다.
3. **0-turn은 "알림 끄기"가 아니라 "소통 시점 맞추기"**다. 모델이 투를 때까지 기다렸다가, 투 결과 경계에서 메시지를 밀어 넣는 게 핵심.

---

### 한 줄

DriftGuardBuffer는 0-turn 패턴의 구현 증명이었고, 동시에 "Hermes 코어에 똑같은 게 이미 있었다"는 걸 확인하게 해준 실험이 됐다. 더 짓는 게 아니라, `busy_input_mode: steer` 켜는 법을 알려줄 걸 그때.

---

> **따라하기** (다른 런타임에 끼우고 싶다면)
>
> ```bash
> cd /d/develop/project/agent-drift-guard
> python3 examples/verify_drift_guard_0turn.py   # 0-turn 계약 검증
> ```
>
> wiring: `on_radio_message` → background watcher, `on_tool_call_complete` → tool executor step boundary.
