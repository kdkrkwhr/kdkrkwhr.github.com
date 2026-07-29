---
layout: post
author: Kim, DongKi
title:  "GPU 없는 PC에서 Spring RAG/Few-shot 도메인 API 테스트하기 (Colab + ngrok + 타임아웃)"
date:   2026-07-29
categories: Technology
comments: true
---

### TL;DR
* 추론(GPU)은 Colab에 올리고, Spring 도메인 API는 “일반 POST 동기 응답”으로 Colab 엔드포인트만 때리면 끝이다.
* RAG 컨텍스트 조립(Elasticsearch BM25 → Qdrant 재랭크)은 로컬에서 하고, prompt 조립/출력 스키마 고정을 같이 한다.
* 디버깅의 핵심은 `connect`/`read` 타임아웃과 “JSON 스키마 파싱 실패” 지점 고정이다.

----

### 들어가며
GPU 없는 PC에서 RAG/Few-shot 도메인 API를 테스트하다 보면 제일 먼저 막히는 게 “추론이 너무 느려서 로컬에서 재현이 안 됨”이다. 
해결은 단순하다. 모델 서빙만 Colab에서 하고, 로컬 Spring은 `ngrok`으로 공개된 Colab URL을 향해 동기 POST를 보내서, 실제 타임아웃/파싱 실패까지 그대로 재현한다.

----

### 전체 흐름
1. 로컬(Spring): RAG 검색 결과를 `context`로 만들고, few-shot까지 프롬프트에 조립한다.
2. 로컬(Spring): Colab(ngrok URL)의 `/api/generate`를 **일반 POST**로 호출한다.
3. Colab: 입력 프롬프트를 받아 모델 추론 후, 고정된 JSON 스키마로 응답한다.
4. 로컬(Spring): JSON을 파싱해 결과를 리턴한다(파싱 실패도 로그로 드러나게).

----

### RAG (Elasticsearch + Qdrant) 하이브리드 컨텍스트 조립
이 방식은 “후보 생성 → 의미 재랭크 → 병합”이 핵심이다. 

* Elasticsearch(BM25): 후보 `topK=20` 수집(키워드 매칭 정확도)
* Qdrant(벡터): 해당 후보 기준 의미 재랭크 `topK=20`
* (doc_id, chunk_id) 기준 union/중복 제거
* 최종 컨텍스트 `topK=8~10`만 프롬프트에 넣기

그리고 출력에서 citations가 자주 깨지는 걸 막으려면, citations를 “chunk_id 문자열 배열”로 고정하면 된다.
Qdrant 메타데이터에 `chunk_id`가 들어있다고 가정하고(없으면 doc 단위로 바꾸면 됨), 프롬프트 컨텍스트도 `[chunk_id:...]` 형태로 박아 넣는다.

예) 컨텍스트는 이런 식
`[chunk_id:123] ...청크 텍스트...`

출력 JSON은 이런 형태만 허용
`{ "answer": "...", "citations": ["123","456"] }`

----

### Colab: 모델을 “일반 POST”로 서빙하기
Colab에서는 FastAPI로 `/api/generate`를 띄운다. 
핵심은 “응답을 스트리밍하지 않고(비스트리밍), 고정 JSON으로 끝내는 것”이다.

```python
!pip -q install fastapi uvicorn

from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI()

class GenerateReq(BaseModel):
    prompt: str

class GenerateRes(BaseModel):
    answer: str
    citations: list[str]

@app.post("/api/generate", response_model=GenerateRes)
def generate(req: GenerateReq):
    # TODO: 여기서 실제 모델 추론을 붙이면 됨
    # (중요) 결과는 반드시 answer/citations 키로만 리턴
    return {
        "answer": f"[stub] {req.prompt[:200]}",
        "citations": []
    }

import uvicorn
uvicorn.run(app, host="0.0.0.0", port=8000)
```

ngrok은 Colab의 8000 포트를 열어서 public URL을 얻는다. 
그 URL 뒤에 `/api/generate`까지 붙여서 Spring이 호출하게 만든다.

----

### Spring: 타임아웃/프롬프트/출력 스키마를 “디버깅 가능하게” 고정
로컬에서 GPU가 없든 있든, 디버깅이 쉬워지는 건 결국 “어디서 죽었는지”가 분리될 때다.

* 타임아웃: `connect`(연결) vs `read`(응답 대기)를 분리해서 건다
* 프롬프트: context + few-shot + 질문을 합치되, 출력은 JSON 스키마 1개만 허용
* 파싱 실패: JSON이 깨지면 스키마가 어긋난 원문을 짧게 로그로 남긴다(재시도 여부는 서비스 정책대로)

아래는 예시 “요청 파라미터/프롬프트 고정” 템플릿이다.

```text
ai:
  base-url: "https://<ngrok-id>.ngrok-free.app"
  path: "/api/generate"
  connect-timeout-ms: 5000
  read-timeout-ms: 120000

[PROMPT]
너는 의료/임상 도메인 전문가다.
반드시 아래 JSON 포맷으로만 출력해라(설명 금지).

JSON Schema:
{ "answer": string, "citations": string[] }

[CONTEXT]
{{context}}     // 각 청크 앞에 [chunk_id:...] 포함

[FEW_SHOT_1]... (질문/정답을 JSON 형태로 예시)

[QUESTION]
{{user_question}}
```

여기서 few-shot도 citations를 “chunk_id 문자열 배열”로만 주면, Spring 파서가 덜 깨진다.

----

### 로컬에서 바로 검증(“진짜로 때리는지” 먼저)
1. Colab 엔드포인트를 curl로 먼저 확인한다(로컬 서비스 호출 전에).
2. Spring 호출 로그에서 “최종 URL”, “prompt 길이(특히 context)”, “타임아웃 예외 타입(connect/read)”만 확인한다.

이 순서로 가면, 디버깅이 LLM 품질 토론으로 새지 않고 “호출/스키마/타임아웃”으로 바로 수렴한다.

----

### 마무리
GPU 없는 환경에서도 RAG/Few-shot 도메인 API를 끝까지 테스트하는 방법은 명확하다.
* 추론은 Colab에서
* 로컬 Spring은 ngrok 공개 URL로 일반 POST를 보내고
* citations를 chunk_id로 고정 + JSON 스키마를 1개만 허용
* connect/read 타임아웃과 파싱 실패 로그를 먼저 고정

이렇게만 잡아두면 “느려서 못 재현”이 아니라 “실패를 재현해서 고친다”로 바뀐다.

