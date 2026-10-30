---
layout: post
author: Kim, DongKi
title: "Text-to-SQL, 생성된 쿼리를 바로 실행해도 될까?"
date: 2026-10-30 09:37:00 +0900
permalink: /ai/2026/10/30/text-to-sql-query-execution-boundary.html
categories: AI
comments: true
description: "문법상 맞는 SQL도 JOIN 중복, 취소 주문, 시간대, 테넌트 범위 때문에 틀릴 수 있습니다. 가상 매출 fixture와 Java 21 실행 예제로 Text-to-SQL의 검증 경계를 살펴봅니다."
excerpt: "Text-to-SQL의 위험은 SQL 문법 오류만이 아닙니다. 독립 fixture로 오답을 재현하고, 자연어를 제한된 조회 의도로 바꾼 뒤 서버가 권한과 의미를 검증하는 설계를 정리했습니다."
---

### TL;DR

* SQL이 실행되고 숫자가 나와도 질문에 맞는 답이라는 뜻은 아닙니다. `JOIN`으로 주문 금액이 중복되거나 취소 주문과 날짜 경계가 잘못 들어갈 수 있습니다.
* DB의 읽기 전용 권한은 쓰기 위험을 줄이지만, 허용된 데이터의 노출·비싼 조회·잘못된 집계까지 해결하지는 않습니다.
* 조회 범위가 정해져 있다면 LLM에는 **제한된 조회 의도**를 추출하게 하고, 서버가 인증된 테넌트·지표·기간을 검증한 다음 고정된 SQL에 값만 바인딩하는 편이 검증하기 쉽습니다.
* 이 방식도 모델이 의도를 잘못 읽을 수 있습니다. 모호한 요청은 재질문하고, 계약이나 권한 밖의 요청은 실행하지 않아야 합니다.

----

### 들어가며

[어제 글]({% post_url 2026-10-29-ai-implementation-tests-requirement-gap %})에서는 구현과 테스트가 같은 요구 해석을 공유하면 둘 다 통과해도 원래 요구를 놓칠 수 있다고 이야기했습니다. Text-to-SQL에서도 비슷한 빈틈이 생깁니다. 생성된 SQL이 문법 검사를 통과하고 DB가 결과를 돌려줘도, **그 결과가 사용자가 뜻한 매출인지**는 별개의 질문이더라고요.

“10월 1일부터 2일까지 결제된 매출은?”이라는 짧은 질문으로 살펴보겠습니다. 주문·금액·테넌트는 모두 이 글을 위해 만든 **교육용 재구성 데이터**입니다. 실제 서비스의 스키마, 고객 정보, 운영 성과를 옮긴 사례가 아닙니다.

----

### 질문보다 먼저, 집계 계약을 정합니다

이 글에서 “매출”은 **결제 완료(`PAID`) 주문의 주문 총액을 주문당 한 번씩 합한 원화 금액**입니다. 취소(`CANCELED`) 주문은 제외합니다. 날짜는 `Asia/Seoul` 기준이며, “10월 1~2일”은 **10월 1일 00:00 이상, 10월 3일 00:00 미만**입니다. 다른 사람의 상점 주문은 포함하지 않습니다. 환불·부분 취소·배송비·세금은 예제에 없으므로 이 계약의 범위 밖입니다. 실제 제품이라면 이 항목도 지표 정의에 적어야 합니다.

<div class="table-scroll" markdown="1">

| 주문 | 테넌트 | 저장된 UTC 시각 | 서울 시각 | 상태 | 주문 총액 | 항목 수 |
| --- | --- | --- | --- | --- | ---: | ---: |
| 1 | `shop-a` | 9/30 15:00 | **10/1 00:00** | `PAID` | 10,000원 | 1 |
| 2 | `shop-a` | 10/1 03:00 | 10/1 12:00 | `PAID` | 20,000원 | **2** |
| 3 | `shop-a` | 10/2 14:59 | 10/2 23:59 | `CANCELED` | 30,000원 | 1 |
| 4 | `shop-a` | 10/2 15:00 | **10/3 00:00** | `PAID` | 40,000원 | 1 |
| 5 | `shop-b` | 10/1 04:00 | 10/1 13:00 | `PAID` | 50,000원 | 1 |

</div>

따라서 `shop-a`의 기대값은 주문 1과 2의 **30,000원**입니다. [주문 fixture]({{ '/assets/code/text-to-sql-orders.csv' | relative_url }}), [항목 fixture]({{ '/assets/code/text-to-sql-items.csv' | relative_url }}), [독립 기대값]({{ '/assets/code/text-to-sql-expected.csv' | relative_url }})을 따로 둔 이유는 실행 결과를 본 뒤 기대 금액을 정하지 않기 위해서입니다.

----

### 맞는 문법, 틀린 매출

#### 1. 주문 총액을 항목에 `JOIN`하면 주문이 복제됩니다

아래 SQL은 문법상 정상입니다. 하지만 주문 2에는 항목이 두 개라서 `orders`의 `total_won=20000` 행이 두 번 나타납니다.

```sql
SELECT SUM(o.total_won)
FROM orders o
JOIN order_items i ON i.order_id = o.order_id
WHERE o.tenant_id = ?
  AND o.status = 'PAID'
  AND o.placed_at >= ? AND o.placed_at < ?;
```

결과는 `10,000 + 20,000 + 20,000 = 50,000원`입니다. 여기서 `SUM(DISTINCT o.total_won)`으로 고치면 같은 금액의 서로 다른 주문까지 하나로 합칠 수 있습니다. **주문 단위 금액**을 구할 때는 항목 테이블을 불필요하게 붙이지 않거나, 필요한 항목 조건을 먼저 주문 ID 단위로 좁혀야 합니다. 주문 항목 금액을 더하려는 지표라면 처음부터 그 지표의 정의와 컬럼을 따로 써야 하고요. [PostgreSQL `SELECT` 문서](https://www.postgresql.org/docs/current/sql-select.html)의 `FROM`·`JOIN`·집계 순서를 바탕으로 볼 수 있는 행 증식 문제입니다.

#### 2. 취소 상태를 빼먹으면 다른 6만 원이 됩니다

같은 서울 시간 범위에서 `status = 'PAID'`를 빼면 주문 1·2·3이 들어와 `10,000 + 20,000 + 30,000 = 60,000원`입니다. SQL은 성공하지만, “결제된 매출”이라는 단어의 정의를 놓쳤습니다. 사용자에게 숫자만 보여주면 이 누락은 문법 오류보다 찾기 어려울 수 있습니다.

#### 3. UTC 날짜를 서울 날짜로 쓰면 역시 6만 원이 나옵니다

`2026-10-01T00:00Z`부터 `2026-10-03T00:00Z`까지 조회하면서 서울 날짜라고 설명하면 경계가 아홉 시간 밀립니다. `PAID` 조건을 제대로 넣어도 주문 1은 빠지고, 서울에서 10월 3일이 된 주문 4가 들어와 주문 2·4의 **60,000원**이 됩니다. 취소 누락 때와 같은 숫자지만 **들어간 주문은 다릅니다**.

이 예제의 정확한 UTC 경계는 서울 10월 1일 00:00에 해당하는 `2026-09-30T15:00Z`와 서울 10월 3일 00:00에 해당하는 `2026-10-02T15:00Z`입니다. 종료를 `<`로 두면 다음 날 자정 주문은 빠집니다. `23:59:59` 같은 종료값을 만드는 방식은 저장 정밀도에 따라 마지막 순간을 놓칠 수 있어 피합니다. 날짜와 타임존 타입의 차이는 [PostgreSQL 날짜·시간 문서](https://www.postgresql.org/docs/current/datatype-datetime.html)도 참고할 만합니다.

테넌트 조건까지 빠지면 올바른 날짜·상태를 사용해도 `shop-b`의 50,000원이 더해져 80,000원이 됩니다. 이때부터는 숫자 오답뿐 아니라 **다른 테넌트 데이터 노출** 문제입니다.

----

### 읽기 전용이면 충분할까요?

DB 역할에 `SELECT`만 부여하고 쓰기 권한을 주지 않으면 `INSERT`·`UPDATE`·`DELETE` 같은 변경을 제한할 수 있습니다. [PostgreSQL `GRANT` 문서](https://www.postgresql.org/docs/current/sql-grant.html)처럼 권한을 분리하는 것은 기본 방어선입니다. 그러나 “읽기 전용”을 “아무 SQL이나 실행해도 안전하다”로 해석하면 안 됩니다.

<div class="table-scroll" markdown="1">

| 남는 문제 | 읽기 전용만으로 부족한 이유 | 추가 경계 |
| --- | --- | --- |
| 데이터 노출 | 읽을 수 있는 테이블의 다른 테넌트 행이나 민감한 열도 질의할 수 있습니다. | 접근 가능한 뷰·열을 줄이고, 서버 권한 검증과 DB 행 수준 정책을 함께 둡니다. |
| 비용·가용성 | 큰 `JOIN`, 정렬, 집계는 쓰기 없이도 자원과 시간을 씁니다. | 기간·결과 행 수·동시 실행·쿼리 시간을 제한하고 계획을 관찰합니다. |
| 의미 오류 | `SELECT` 권한은 취소 제외, 지표 정의, 시간대를 알려주지 않습니다. | 지표 계약과 독립 fixture로 결과를 검증합니다. |
| SQL의 실행 범위 | 허용된 함수와 DB 설정에 따라 `SELECT`도 예상 밖의 동작을 할 수 있습니다. | 노출할 스키마·함수·권한을 별도로 검토합니다. |

</div>

[PostgreSQL 행 수준 보안](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)은 일반 쿼리에 보이는 행을 정책으로 제한할 수 있지만, 소유자와 `BYPASSRLS` 역할 등 예외도 문서화되어 있습니다. 따라서 앱의 인증된 사용자·테넌트를 확인하지 않은 채 `WHERE tenant_id = 모델이 준 값`만 붙이는 방식은 피해야 합니다. DB 정책도 설정과 역할을 시험해야 합니다. [JDBC `Statement.setQueryTimeout`](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Statement.html#setQueryTimeout(int))과 PostgreSQL의 [`statement_timeout`](https://www.postgresql.org/docs/current/runtime-config-client.html)은 시간 제한에 사용할 수 있습니다. 다만 **결과 행 수 제한은 스캔·집계 비용 제한과 같지 않습니다.**

----

### SQL을 직접 생성할지, 조회 의도를 추출할지

직접 생성 방식은 질문을 임의의 SQL로 옮기므로 탐색 범위가 넓습니다. 새로운 집계나 조합을 빠르게 시도할 수 있지만, 허용할 테이블·열·함수·조인·기간·권한을 실행 전에 모두 판정하기 어렵습니다. SQL을 문자열 검사하거나 `SELECT`로 시작하는지만 확인해서는 위의 의미 오류를 걸러낼 수 없습니다.

조회 유형이 정해져 있다면, 모델이 다음처럼 **지표와 날짜만 추출**하도록 계약을 좁힐 수 있습니다. 여기의 JSON은 LLM의 가상 출력 형식이며, 아래 Java 예제에서 실제 모델 API를 호출했다는 뜻은 아닙니다.

```json
{
  "metric": "PAID_TOTAL_WON",
  "startInclusive": "2026-10-01",
  "endExclusive": "2026-10-03"
}
```

<a href="{{ '/assets/images/text-to-sql/query-flow.svg' | relative_url }}">
  <picture>
    <source media="(max-width: 600px)" srcset="{{ '/assets/images/text-to-sql/query-flow-mobile.svg' | relative_url }}">
    <img src="{{ '/assets/images/text-to-sql/query-flow.svg' | relative_url }}" alt="사용자 질문을 LLM이 제한된 조회 의도로 추출하고, 서버가 인증된 테넌트와 지표·기간을 검증한 다음 고정 SQL에 값을 바인딩해 DB를 조회하는 흐름. 모호하거나 권한 밖의 요청은 실행하지 않는다." loading="lazy">
  </picture>
</a>

*그림 1. 이 글에서 제안하는 **제한된 조회 경계**입니다. 모바일에는 세로 그림을 표시합니다. 그림을 누르면 원본 크기로 볼 수 있습니다.*

<div class="table-scroll" markdown="1">

| 선택 | 얻는 것 | 감수할 것 |
| --- | --- | --- |
| LLM이 SQL 직접 생성 | 새 질문·조합을 빠르게 탐색할 수 있습니다. | 실행 허용 범위와 의미 검증이 어렵고, 강한 격리·검토가 필요합니다. |
| LLM이 제한된 의도 추출 | 지표별 SQL·권한·테스트를 고정할 수 있습니다. | 미리 설계하지 않은 질문은 답하지 못하고, 지표를 추가할 때 서버 코드를 고쳐야 합니다. |

</div>

후자가 항상 정답이라는 뜻은 아닙니다. 분석가가 격리된 데이터셋에서 임의 SQL을 검토하며 쓰는 도구와, 다수 사용자가 공유 DB를 질의하는 제품은 필요한 유연성이 다릅니다. 저는 **지원할 질문을 먼저 목록화할 수 있는 경우**에 제한된 의도 방식을 우선 고려하겠습니다.

----

### Java 21 예제: 실행 권한은 서버에 둡니다

[전체 Java 코드]({{ '/assets/code/TextToSqlSalesDemo.java' | relative_url }})는 위 CSV를 H2 메모리 DB에 적재하고 오답 세 가지와 올바른 값을 조회합니다. 모델 연결·HTTP 인증·운영 DB 권한 설정은 구현하지 않은 **독립 실행 예제**입니다. 아래 `AuthContext`는 실제 서비스에서는 인증 미들웨어가 만들어야 하며, 요청 본문이나 모델 출력에서 받아서는 안 됩니다.

```java
enum Metric { PAID_TOTAL_WON }
record QueryIntent(Metric metric, LocalDate startInclusive, LocalDate endExclusive) {}
record AuthContext(String tenantId) {} // 서버의 인증 결과

static Instant boundary(LocalDate date, ZoneId zone) {
    return date.atStartOfDay(zone).toInstant();
}
```

모델이 `tenantId`를 써 보내더라도 조회 의도 스키마에는 받지 않고, 서버가 인증 컨텍스트의 `tenantId`를 바인딩합니다. 지표를 `enum`으로 제한하고 날짜 순서와 최대 31일 범위를 검증합니다. 그런 뒤 쿼리의 구조를 서버 코드가 고정합니다.

```java
String sql = "SELECT COALESCE(SUM(total_won), 0) FROM orders "
        + "WHERE tenant_id = ? AND status = 'PAID' AND placed_at >= ? AND placed_at < ?";
try (PreparedStatement ps = db.prepareStatement(sql)) {
    ps.setQueryTimeout(3);
    ps.setString(1, auth.tenantId());
    ps.setObject(2, OffsetDateTime.ofInstant(boundary(intent.startInclusive(), REPORT_ZONE), ZoneOffset.UTC));
    ps.setObject(3, OffsetDateTime.ofInstant(boundary(intent.endExclusive(), REPORT_ZONE), ZoneOffset.UTC));
    // executeQuery() 후 SUM 결과 한 행을 읽습니다.
}
```

[`PreparedStatement`](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/PreparedStatement.html)는 `?` 위치의 **값**을 바인딩합니다. 테이블명·컬럼명·정렬 방향 같은 **SQL 식별자나 문법 조각**을 `?`에 넣는 방법은 아닙니다. 예를 들어 정렬 기준을 지원한다면 사용자 문자열을 SQL에 이어 붙이지 말고 `ORDER_DATE → "placed_at"`, `ORDER_AMOUNT → "total_won"`처럼 서버의 허용 목록에서 고른 고정 조각만 사용해야 합니다. 이 예제는 정렬 자체를 지원하지 않습니다.

작성 환경에서 OpenJDK **21.0.12.1**과 H2 **2.3.232**로 다음 명령을 실행했습니다. H2 JAR은 Maven Central에서 별도로 받으며 저장소에는 포함하지 않습니다. `java`의 소스 파일 실행 모드를 사용하므로 별도 `javac` 명령은 필요하지 않습니다.

```bash
curl -fLsS -o /tmp/h2-2.3.232.jar \
  https://repo.maven.apache.org/maven2/com/h2database/h2/2.3.232/h2-2.3.232.jar
java -cp /tmp/h2-2.3.232.jar assets/code/TextToSqlSalesDemo.java assets/code
```

실제 출력은 다음과 같았습니다. 앞의 세 `PASS`는 **잘못된 쿼리가 예상한 오답을 냈다**는 재현 결과입니다. 올바른 매출이라는 의미가 아닙니다.

```text
duplicated by item JOIN: 50000 won (PASS)
canceled order included: 60000 won (PASS)
UTC dates used as Seoul dates: 60000 won (PASS)
validated paid total: 30000 won (PASS)
server tenant scope: 50000 won (PASS)
reversed dates rejected: PASS
```

`shop-b` 결과가 50,000원인 검사는 같은 `QueryIntent`라도 **서버 인증 컨텍스트가 다르면 범위가 달라진다**는 것을 확인합니다. 역순 날짜는 조회 전에 거절합니다. 이 예제의 `setQueryTimeout(3)`은 실행 경계를 보여주는 설정이며, 큰 쿼리의 취소 동작이나 운영 DB 성능을 시험한 결과는 아닙니다. H2에서의 집계 결과가 PostgreSQL 등 다른 DB의 실행 계획·시간대 설정·권한 정책까지 증명하지도 않습니다.

----

### 의도 추출도 틀릴 수 있습니다

JSON 형식이 맞아도 “지난달 매출”의 기준이 결제 시각인지 주문 시각인지, “매출”이 결제액인지 환불 차감액인지 모델이 잘못 고를 수 있습니다. 스키마 검증은 **허용된 모양인지**를 확인할 뿐 **사용자 뜻을 정확히 읽었는지** 보장하지 않습니다. 그 차이를 다음처럼 다룹니다.

* **모호한 요청:** “최근 매출”처럼 기간이나 지표가 불명확하면 기본값을 조용히 채우지 않고, 기준 기간과 매출 정의를 다시 묻습니다.
* **계약 밖의 요청:** 이 예제가 지원하지 않는 환불 차감 순매출, 임의 `JOIN`, 임의 테이블 열람은 SQL을 즉석에서 만들어 우회하지 않습니다. 지원 범위를 설명하고 거절하거나 별도 기능으로 설계합니다.
* **권한 밖의 요청:** “다른 상점 매출을 보여줘”는 모델이 그 테넌트명을 추출했는지와 관계없이 인증·인가 단계에서 거절합니다. 모델의 시스템 프롬프트만으로 권한을 강제하지 않습니다.
* **오해 탐지:** 날짜 경계·취소 상태·항목 수·같은 금액의 서로 다른 주문을 넣은 독립 fixture와 실제 DB 결과를 대조합니다. 합계 하나만 보지 말고, 필요할 때 포함된 주문 ID도 진단 가능하게 남깁니다.

[OWASP의 LLM 프롬프트 인젝션 안내](https://genai.owasp.org/llmrisk/llm01-prompt-injection/)처럼 모델 입력이 의도치 않은 출력을 유도할 수 있으므로, 모델의 출력 자체를 권한 근거로 취급하지 않습니다. 개인정보나 다른 테넌트 데이터가 모델 입력·결과에 흘러가지 않도록 데이터 경계도 따로 설계해야 합니다.

----

### 적용 전 확인할 체크리스트

* [ ] 매출의 단위가 주문 총액인지 항목 금액인지, 취소·환불·세금·배송비를 어떻게 다루는지 정했나요?
* [ ] 날짜의 기준 시간대와 시작 포함·종료 제외 경계를 문서화했나요?
* [ ] 인증된 서버 컨텍스트에서 테넌트와 권한을 결정하고, 모델이 보낸 값은 권한 근거에서 제외했나요?
* [ ] 모델이 고를 수 있는 지표·기간·정렬·그룹 기준을 허용 목록으로 제한했나요?
* [ ] 값은 바인딩하고, 식별자는 서버의 고정 매핑에서만 선택하나요?
* [ ] 읽기 권한·뷰 또는 행 정책·시간·기간·결과 행 수·동시 실행 제한을 환경에 맞게 시험했나요?
* [ ] 모호한 질문, 권한 밖 질문, 범위 초과, 취소 주문, `JOIN` 중복, 시간대 경계에 대한 독립 기대값이 있나요?

----

### 마무리

Text-to-SQL의 첫 질문은 “모델이 올바른 SQL을 만들 수 있나?”보다 **“어떤 질문을, 누구의 데이터에 대해, 어떤 의미로 실행할 수 있나?”**에 가깝습니다. SQL 문법과 읽기 권한은 필요한 일부 경계이고, 집계 정의와 인증 범위는 따로 검증해야 합니다.

지원할 질문이 좁다면 모델을 자연어 인터페이스로 쓰고, 실행 가능한 조회는 서버의 작은 계약으로 만드는 편이 오답과 권한 오류를 찾기 쉽습니다. 이때도 구조화된 출력이 정답이라고 믿기보다, 어제 글에서처럼 **구현 밖에서 정한 기대값**으로 실제 결과를 계속 대조해보려 합니다.
