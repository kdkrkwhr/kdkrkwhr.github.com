---
layout: post
author: Kim, DongKi
title:  "Colab T4에서 Q-LoRA 시드 SFT 돌리기"
date:   2026-08-10
permalink: /ai/2026/08/10/qlora-colab-t4-sft-seed.html
categories: AI
comments: true
---

### TL;DR
* 이전 Colab 글이 "모델을 **서빙**하고 호출하는" 이야기였다면, 이번은 "모델을 **학습**시키는" 쪽이다.
* 무료 T4(VRAM 16GB)에서 7B 모델을 full fine-tuning 하는 건 불가능하다. 4bit 양자화 base + LoRA adapter, 즉 **Q-LoRA**가 사실상 유일한 선택지다.
* 데이터 증강에 들어가기 전에 **시드(seed) SFT**를 먼저 돌린다. 수백 건 규모로 "형식이 잡히는지"만 확인하는 단계다.
* 산출물은 adapter zip 하나(수십 MB)다. base + adapter를 다시 올려 **스모크 테스트**까지 해야 한 사이클이 끝난다.

----

### 들어가며

few-shot 프롬프트로 스키마를 맞추다 보면 어느 순간 한계가 온다. 형식은 맞는데 선택이 흔들리거나, 프롬프트가 너무 길어져 지연·비용이 감당이 안 되는 구간이다. 그때 SFT를 고민하게 되는데, 문제는 GPU다.

로컬에 GPU가 없으면 검증 자체를 못 해 보고 판단이 멈춘다. 그래서 "일단 T4 한 장으로 작은 사이클을 한 바퀴 돌려 보는" 방법을 정리했다. 목표는 좋은 모델을 만드는 게 아니라, **학습 파이프라인이 실제로 도는지 증명하는 것**이다.

----

### 1. 시드 SFT를 먼저 하는 이유

데이터 증강은 비싸다. 수천~수만 건을 만들어 놓고 학습을 돌렸는데 라벨 포맷이 잘못돼 있으면, 그 비용을 통째로 날린다.

시드 SFT는 그 앞에 두는 안전장치다.

| 단계 | 데이터 규모 | 확인하려는 것 |
|---|---|---|
| 시드 SFT | 200~800건 | 포맷·토큰화·학습 루프가 도는가 |
| 증강 후 SFT | 수천~ | 실제 성능이 오르는가 |
| DPO 등 정렬 | 선호쌍 | 선택 품질이 좋아지는가 |

시드 단계에서는 성능 지표를 보지 않는다. loss가 **내려가기만 하면** 통과다. 오히려 loss가 0에 가깝게 떨어지면 데이터가 너무 단순하거나 누수가 있다는 신호로 본다.

----

### 2. 환경 준비

```bash
pip install -q -U transformers peft accelerate bitsandbytes datasets trl
nvidia-smi --query-gpu=name,memory.total --format=csv
```

T4는 bfloat16을 제대로 지원하지 않는다. `fp16=True`로 두고 bf16 옵션은 끄는 편이 안전하다. Ampere 이상(A100/L4)이면 반대로 bf16을 쓴다.

----

### 3. 4bit로 base 올리기

```python
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
import torch

BASE = "Qwen/Qwen2.5-7B-Instruct"

bnb = BitsAndBytesConfig(
    load_in_4bit=True,
    bnb_4bit_quant_type="nf4",
    bnb_4bit_use_double_quant=True,
    bnb_4bit_compute_dtype=torch.float16,
)

tok = AutoTokenizer.from_pretrained(BASE)
model = AutoModelForCausalLM.from_pretrained(
    BASE, quantization_config=bnb, device_map="auto"
)
model.config.use_cache = False
```

`nf4` + double quant 조합이 T4에서 가장 무난했다. compute dtype만 fp16으로 맞추면 로딩에서 걸리는 일은 거의 없다.

----

### 4. LoRA 설정

```python
from peft import LoraConfig, prepare_model_for_kbit_training, get_peft_model

model = prepare_model_for_kbit_training(model)

lora = LoraConfig(
    r=16, lora_alpha=32, lora_dropout=0.05,
    bias="none", task_type="CAUSAL_LM",
    target_modules=["q_proj","k_proj","v_proj","o_proj",
                    "gate_proj","up_proj","down_proj"],
)
model = get_peft_model(model, lora)
model.print_trainable_parameters()
```

시드 단계에서는 `r=16` 정도면 충분하다. r을 키우면 표현력은 늘지만 VRAM과 과적합 위험이 같이 올라간다. `print_trainable_parameters()`에서 학습 파라미터가 전체의 1% 미만으로 찍히는지 꼭 확인한다. 여기서 0이 나오면 target_modules 이름이 모델과 안 맞은 것이다.

----

### 5. 데이터 포맷

instruction 튜닝은 결국 "하나의 문자열"을 만드는 작업이다. chat template을 쓰면 base 모델이 학습된 형식과 어긋날 위험이 준다.

```python
def to_text(ex):
    msgs = [
        {"role": "user", "content": ex["input"]},
        {"role": "assistant", "content": ex["output"]},
    ]
    return {"text": tok.apply_chat_template(msgs, tokenize=False)}
```

출력이 JSON 스키마라면 학습 데이터의 JSON을 **키 순서까지 고정**해 두는 편이 좋다. 순서가 들쭉날쭉하면 모델이 형식을 배우는 데 쓸데없는 용량을 쓴다.

----

### 6. 학습

```python
from trl import SFTTrainer, SFTConfig

args = SFTConfig(
    output_dir="out",
    per_device_train_batch_size=1,
    gradient_accumulation_steps=16,
    num_train_epochs=2,
    learning_rate=2e-4,
    fp16=True,
    logging_steps=10,
    gradient_checkpointing=True,
    max_seq_length=1024,
    save_strategy="epoch",
)

SFTTrainer(model=model, args=args, train_dataset=ds).train()
model.save_pretrained("out/adapter")
```

T4에서 OOM이 나면 순서대로 이렇게 줄인다.

1. `max_seq_length` 축소 (2048 → 1024 → 768)
2. `gradient_checkpointing=True` 확인
3. batch size는 1로 두고 `gradient_accumulation_steps`로 유효 배치 확보

Colab 무료 런타임은 끊긴다. `save_strategy="epoch"`로 중간 산출물을 남기고, 가능하면 Drive에 마운트해서 저장한다.

----

### 7. adapter 회수와 스모크 테스트

```bash
zip -r adapter.zip out/adapter
```

adapter 디렉터리는 보통 수십 MB다. base 가중치는 들어있지 않으므로, 나중에 반드시 **같은 base + 같은 양자화 설정**으로 다시 얹어야 한다.

```python
from peft import PeftModel

base = AutoModelForCausalLM.from_pretrained(BASE, quantization_config=bnb, device_map="auto")
m = PeftModel.from_pretrained(base, "out/adapter")
m.eval()
```

스모크 테스트는 학습 데이터에 없는 입력 3~5건으로 한다. 볼 것은 정확도가 아니라 다음 두 가지다.

* 요구한 출력 형식(JSON 등)이 **깨지지 않고** 나오는가
* 학습 전과 눈에 띄게 **달라졌는가** (안 달라졌으면 adapter가 실제로 안 붙었을 가능성)

두 번째 확인을 위해 adapter 없이 base만으로 같은 입력을 한 번 돌려 비교해 두면 좋다.

----

### 마무리

* 시드 SFT의 성공 기준은 성능이 아니라 **파이프라인이 끝까지 도는 것**이다. 여기서 막히면 증강 데이터를 늘려도 똑같이 막힌다.
* T4에서는 `nf4` 4bit + fp16 compute + gradient checkpointing + seq 1024가 기본값이라고 보면 대체로 통과한다.
* adapter는 base와 양자화 설정에 묶여 있다. base 버전과 `BitsAndBytesConfig`를 adapter와 같이 기록해 두지 않으면 나중에 재현이 안 된다.
