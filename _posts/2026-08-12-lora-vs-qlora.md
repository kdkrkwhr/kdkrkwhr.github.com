---
layout: post
author: Kim, DongKi
title:  "LoRA vs Q-LoRA — 같은 데이터여도 가중치가 다른 이유"
date:   2026-08-12
permalink: /ai/2026/08/12/lora-vs-qlora.html
categories: AI
comments: true
---

### TL;DR
* LoRA와 Q-LoRA는 **adapter 구조(rank r, A/B 행렬)는完全相同**하다. 다른 건 base 모델을 어떤 정밀도로 올리느냐 뿐이다.
* LoRA는 base를 FP16/BF16으로 얼린 채 두고, Q-LoRA는 base를 4bit(nf4)로 양자화해 둔다.
* 그래서 **같은 데이터·같은 하이퍼파라미터로 돌려도 학습되는 A/B가 다르다.** 양자화된 base는 gradient가 지나가는 길 자체가 근사값이기 때문이다.
* VRAM은 Q-LoRA가 수 배 낮지만, 품질 격차는 r이 작거나 데이터가 노이즈일 때 커질 수 있다.
* adapter는 base 버전과 양자화 설정에 묶인다. Q-LoRA adapter를 FP16 base에 그냥 얹으면 수치가 어긋난다.

----

### 들어가며

"LoRA랑 Q-LoRA는 그냥 메모리만 다르고 결과는 같지 않아?"라는 가정을 자주 본다. Colab T4에서 Q-LoRA 시드 SFT를 돌릴 때도 만났던 의문인데, 결론부터 말하면 **아니다.**

둘은 구조적으로 같은 adapter를 학습하지만, 그 adapter가 얹히는 base의 상태가 다르다. 그리고 그 차이는 단순한 메모리 절약을 넘어, 실제로 학습되는 가중치 값까지 바꾼다. 이 글에서는 왜 그런지와 서빙 시 주의점을 정리한다.

----

### 1. 구조는 같다, 올리는 방식이 다르다

LoRA의 핵심은 원래 가중치 `W`를 건드리지 않고, 학습 가능한 저랭크 분해 `BA`(rank r)만 더해 업데이트하는 것이다.

```
W' = W_base + (B · A)      # B: d×r, A: r×k
```

Q-LoRA도 이 식은 **그대로** 쓴다. 다른 건 `W_base`가 어떻게 메모리에 있는가다.

| 구분 | LoRA | Q-LoRA |
|---|---|---|
| base 정밀도 | FP16 / BF16 (frozen) | 4bit (nf4) 양자화 |
| base 메모리 (7B) | 약 13~14 GB | 약 5~6 GB |
| adapter 구조 | BA, rank r | BA, rank r (동일) |
| 학습 파라미터 | base의 ~0.5~1% (adapter만) | 동일 |

Q-LoRA는 `W_base`를 4bit로 압축해 두고, forward 과정에서 그때그때 dequantize 해서 계산에 쓴다. 메모리만 보면 "양자화 버전 LoRA" 같지만, 학습 시에는 이 차이가 결정적이다.

----

### 2. 왜 가중치가 달라지는가

학습은 base를 얼려 둬도, **gradient는 base를 통과해서** adapter(`A`, `B`)로 흐른다. 문제는 Q-LoRA의 base가 근사값이라는 점이다.

* LoRA: backward 시 `W_base`는 FP16 정확도 그 자체. adapter로 가는 gradient도 정확하다.
* Q-LoRA: `W_base`는 4bit 버킷으로 반올림된 근사 복사본. forward마다 dequantize 되는데, 이 과정에서 양자화 오차가 gradient에 섞인다.

즉 adapter는 "조금 다른 함수"를 피팅하게 된다. 데이터와 lr·스케줄러를 똑같이 맞춰도, optimizer 스텝마다 갱신되는 `A`/`B`의 값은 수치적으로 다르게 수렴한다. "양자화는 추론용 trick"이 아니라 **학습 신호 자체에 노이즈를 넣는 연산**이기 때문에 발생하는 결과다.

----

### 3. VRAM·품질 트레이드오프

VRAM 이득은 명확하다. 7B 기준 base 가중치만 2.5배가량 줄고, adapter(수십 MB) 크기는 둘 다 같다. 그래서 T4 같은 16GB 카드에서는 Q-LoRA가 사실상 유일한 fine-tuning 경로다.

품질 격차는 조건에 따라 다르다.

* `r`이 충분히 크고(16~32) 데이터가 깔끔하면: 격차가 1점대 이내로 작게 나오는 경우가 많다.
* `r`이 작거나 데이터 노이즈가 크면: 양자화 노이즈가 분산을 키워 LoRA 대비 떨어지거나, 반대로 우연히 비슷해지기도 한다.

결국 "Q-LoRA가 LoRA보다 성능이 낮다"가 정답이 아니라, **"같은 설정으로는 재현되지 않는다"**가 정확한 표현이다. 평가는 각자 돌린 결과로 해야 한다.

----

### 4. 단일 adapter 서빙 시 주의

adapter 하나만 바꿔가며 여러 작업을 서빙하는 구조(shared base + swapped adapter)에서 자주 걸리는 함정이 있다.

* LoRA adapter는 FP16/BF16 base 위 전제로 학습됐다.
* Q-LoRA adapter는 **같은 4bit 양자화 설정**으로 올린 base 위 전제로 학습됐다.

그래서 Q-LoRA로 만든 adapter를 나중에 FP16 base에 그냥 얹으면 rank는 맞아도 통과하는 base 수치가 다르다 → 출력이 미세하게(혹은 꽤) 어긋난다. 서빙할 때는 학습 때 쓴 `BitsAndBytesConfig`(quant_type, compute_dtype, double_quant)까지 같이 기록해 둬야 재현이 된다. base 버전과 양자화 설정 이 두 가지가 adapter의 "소속"이다.

----

### 마무리

* LoRA와 Q-LoRA는 adapter 형태는 같지만, base를 FP16으로 두느냐 4bit로 두느냐가 학습되는 가중치까지 바꾼다. "같은 데이터면 같은 결과"는 아니다.
* 메모리가 빠듯하면 Q-LoRA가 정답이지만, 품질 비교는 반드시 동일 조건에서 각자 측정하자. 남의 Q-LoRA 수치를 내 LoRA 기준으로 치환하지 말 것.
* adapter는 base 버전 + 양자화 설정에 묶인다. 서빙 환경을 학습 환경과 똑같이 맞추는 것만이 재현의 전부다.
