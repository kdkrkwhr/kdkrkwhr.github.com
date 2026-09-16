---
layout: post
author: Kim, DongKi
title:  "에이전트 실행 로그를 DuckDB로 뜯어보기 — 비용·실패를 분석 워크로드로 다루기"
date:   2026-09-16
permalink: /data/2026/09/16/agent-run-logs-duckdb-analysis.html
categories: Data/Analytics
comments: true
---

### TL;DR

* 에이전트 로그를 모아 모델별 토큰·실패·지연을 비교하는 일은 분석 워크로드에 가깝다.
* **API 호출 시도 1건을 1행**으로 기록하고, 재시도와 작업의 최종 결과를 구분한다.
* DuckDB로 JSONL을 읽어 집계하고, 반복해서 읽을 데이터는 Parquet로 저장할 수 있다.
* 비용을 모르면 `NULL`로 남긴다. 토큰 합계가 곧 청구액은 아니다.

----

### 들어가며

앞선 [OLAP과 DuckDB 글](/data/2026/09/09/olap-duckdb-analysis-tool.html)에서는 분석 워크로드의 성격을 정리했다. 이번에는 에이전트 실행 로그에 그 관점을 적용해 본다.

[에이전트 함대 글](/ai/2026/08/12/free-model-agent-fleet.html)에서 다룬 문제 중 하나는 모델의 능력과 API 가용성을 구분하는 일이었다. 나는 이 구분을 로그 집계에도 가져오고 싶다. 아래는 실제 운영 결과가 아니라 **수집 형식과 쿼리를 설명하기 위한 설계 예시**다.

----

### 1. 로그에서 답하고 싶은 질문

"지금 이 작업이 끝났나"와 "지난 실행에서 어디에 시간이 들었나"는 읽는 범위가 다르다.

| 구분 | 운영 상태 조회 | 실행 로그 분석 |
|---|---|---|
| 질문 | 이 작업의 현재 상태는? | 어떤 모델에서 실패가 잦았나? |
| 읽는 범위 | 특정 작업 | 기간 내 여러 호출 |
| 주요 연산 | 상태 조회·변경 | 필터·그룹별 합계·분위수 |
| 필요한 결과 | 즉시 다음 동작 결정 | 설정과 재시도 정책 검토 |

완료 로그는 계속 추가하고, 분석 시점에 여러 행을 훑는다. 이 글에서는 수집이 끝난 로컬 파일을 DuckDB로 읽는다. 쓰는 중인 파일보다 분석 시점이 고정된 복사본을 쓰면 비교 기준도 명확해진다.

----

### 2. 한 행의 의미부터 고정한다

여기서는 **API 호출 시도 1건당 종료 레코드 1행**이다. `run_id`는 작업 실행을 묶고, `attempt_id`는 전체 로그에서 유일한 호출 시도 식별자다. 재시도에는 새 식별자를 부여한다. 한 작업에 여러 정상 호출이 있을 수도 있으므로, 행이 많다고 모두 재시도인 것은 아니다.

| 필드 | 의미와 규칙 |
|---|---|
| `run_id`, `attempt_id` | 작업 실행과 개별 호출 시도 식별자 |
| `agent` | 호출한 에이전트 역할 |
| `provider`, `model` | 호출 제공자와 모델 식별자 |
| `ts` | 호출 종료 시각, UTC의 `Z` 표기 |
| `status` | `success` 또는 `failed`, API 호출 결과 |
| `error_type` | `rate_limit`, `timeout` 등 정규화된 원인, 성공이면 `null` |
| `tokens_in`, `tokens_out` | 보고받은 토큰 수, 알 수 없으면 `null` |
| `latency_ms` | 호출 시작부터 종료까지 밀리초, 재시도 사이 대기 제외 |
| `cost_usd` | 해당 시도의 수집된 USD 비용, 미수집이면 `null` |

`success`는 답변 품질이나 작업 완료를 뜻하지 않는다. 프로세스가 죽어 종료 로그 자체가 없다면 이 집계에서 빠진다. 시작 기록과 대조하는 누락 점검은 별도로 필요하다.

아래 세 줄을 `agent-attempts.jsonl`로 저장한다고 가정한다. 이름과 숫자는 모두 설명용이며, 비용도 실제 가격이 아니다.

```jsonl
{"run_id":"r1","attempt_id":"a1","agent":"dev","provider":"example-a","model":"model-x","ts":"2026-09-16T00:00:01Z","status":"failed","error_type":"rate_limit","tokens_in":null,"tokens_out":null,"latency_ms":900,"cost_usd":null}
{"run_id":"r1","attempt_id":"a2","agent":"dev","provider":"example-a","model":"model-x","ts":"2026-09-16T00:00:06Z","status":"success","error_type":null,"tokens_in":1200,"tokens_out":300,"latency_ms":2400,"cost_usd":0.002}
{"run_id":"r2","attempt_id":"a3","agent":"qa","provider":"example-b","model":"model-y","ts":"2026-09-16T00:00:08Z","status":"success","error_type":null,"tokens_in":800,"tokens_out":100,"latency_ms":1800,"cost_usd":null}
```

이 예시에서 `a2`는 `a1`의 재시도다. 수집기가 같은 종료 레코드를 재전송해도 행이 늘지 않도록 해야 한다. 아래 SQL은 **중복과 잘못된 상태값을 수집 단계에서 제거한 파일**을 전제로 한다. 같은 식별자의 값이 서로 다르면 임의로 한 행을 고르지 말고 원본부터 확인한다.

----

### 3. JSONL을 읽고 모델별로 집계한다

DuckDB SQL 세션에서 아래 블록을 순서대로 실행하는 예시다. 파일 경로는 해당 세션의 작업 디렉터리 기준이다. 자동 추론으로 읽되, 집계할 숫자와 시각은 명시적으로 형식을 정한다.

```sql
CREATE TEMP TABLE attempts AS
SELECT
    run_id, attempt_id, agent, provider, model,
    CAST(ts AS TIMESTAMPTZ) AS ts,
    status, error_type,
    CAST(tokens_in AS BIGINT) AS tokens_in,
    CAST(tokens_out AS BIGINT) AS tokens_out,
    CAST(latency_ms AS BIGINT) AS latency_ms,
    CAST(cost_usd AS DECIMAL(18, 8)) AS cost_usd
FROM read_json_auto('agent-attempts.jsonl', format = 'newline_delimited');
```

자료형이 맞지 않는 값은 조용히 누락시키기보다 읽을 때 확인하는 편이 낫다. 실제 수집에서는 필수 식별자, 음수 토큰·지연, 상태값도 검증해야 한다.

```sql
SELECT
    provider,
    model,
    COUNT(*) AS attempt_count,
    SUM(tokens_in) AS known_tokens_in,
    SUM(tokens_out) AS known_tokens_out,
    COUNT(*) FILTER (
        WHERE tokens_in IS NULL OR tokens_out IS NULL
    ) AS token_missing_attempts,
    ROUND(100.0 * SUM(CASE WHEN status = 'failed' THEN 1 ELSE 0 END)
          / COUNT(*), 2) AS attempt_failure_pct,
    COUNT(*) FILTER (
        WHERE status = 'success' AND latency_ms IS NOT NULL
    ) AS latency_sample_count,
    quantile_cont(latency_ms, 0.95) FILTER (
        WHERE status = 'success'
    ) AS success_p95_ms,
    SUM(cost_usd) AS known_cost_usd,
    COUNT(*) FILTER (WHERE cost_usd IS NULL) AS cost_missing_attempts
FROM attempts
GROUP BY provider, model
ORDER BY provider, model;
```

실패율의 분모는 **기록된 모든 시도**다. 첫 호출이 실패하고 재시도가 성공해도 실패 행은 남는다. 작업 최종 실패율을 알려면 별도의 작업 종료 결과가 필요하다. `run_id`별로 성공 행이 하나라도 있는지만 봐서는 작업 완료를 판단할 수 없다.

p95는 성공한 호출의 지연만 대상으로 하며 `NULL`은 제외된다. 실패 응답이 빨랐다고 성공 지연이 좋아 보이는 일을 피하려는 선택이다. 대신 시간 초과는 이 값에 드러나지 않으므로 실패율과 함께 읽는다. 세 줄짜리 샘플의 분위수로 성능을 평가할 수는 없다.

비용 합계도 **값이 있는 행의 부분 합계**다. `SUM`은 `NULL`을 제외하고, 전부 미수집이면 `NULL`을 반환한다. 미수집을 0으로 채우면 무료 호출과 구분할 수 없다. 토큰도 같은 이유로 누락 건수를 함께 표시했다. 추정 비용을 추가한다면 수집 비용과 컬럼을 나누고 적용 단가·시점을 남겨야 한다.

----

### 4. 반복해서 읽을 때는 Parquet로

JSONL은 레코드를 확인하기 쉽다. 분석 대상이 고정되면 형식을 정한 테이블을 Parquet로 저장해 재조회할 수 있다. 아래 출력 경로에는 기존 파일이 없다고 가정한다.

```sql
COPY attempts TO 'agent-attempts.parquet' (FORMAT PARQUET);

SELECT
    provider,
    error_type,
    COUNT(*) AS failed_attempts
FROM read_parquet('agent-attempts.parquet')
WHERE status = 'failed'
GROUP BY provider, error_type
ORDER BY failed_attempts DESC;
```

Parquet 변환이 누락이나 중복을 고쳐주지는 않는다. 원본과 변환본을 함께 입력해 같은 시도를 두 번 집계하지 않도록 분석 파일 목록도 관리해야 한다.

| 궁금한 점 | 먼저 볼 값 | 추가로 필요한 정보 |
|---|---|---|
| 어디에 토큰을 많이 썼나? | 모델별 토큰 합계·누락 수 | 작업 종류와 입력 길이 |
| 제공자 연결에 문제가 있었나? | 제공자·오류 원인별 실패 수 | 시간대별 호출 수와 실패율 |
| 성공 응답이 느려졌나? | 성공 p95·표본 수 | 같은 작업군의 이전 기간 |
| 비용이 얼마나 들었나? | 수집 비용 합계·누락 수 | 청구 내역과 수집 기준 |

----

### 마치며

역할과 작업 난이도가 다른 모델을 한 표에 놓았다고 공정한 비교가 되지는 않는다. 기간·작업군·입력 규모를 맞춘 뒤에도 API 성공과 결과물 품질은 따로 봐야 한다. 이 로그에는 프롬프트 원문, 응답 원문, 인증 정보, 개인정보를 넣지 않고 분석에 필요한 식별자와 수치만 남기는 편이 좋다.

DuckDB로 파일을 읽는 흐름은 사후 분석의 출발점이다. 실시간 장애 감지에는 수집 지연을 관리하는 경보 경로가 별도로 필요하다. 내가 먼저 고정하고 싶은 것은 도구보다 **한 행의 의미, 실패율의 분모, 모르는 값을 남기는 방식**이다. 그래야 집계 결과를 보고 다음 설정을 바꿀 근거가 생긴다.
