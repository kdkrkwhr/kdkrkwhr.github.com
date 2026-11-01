---
layout: post
author: Kim, DongKi
title: "외부 API 호출을 @Transactional 안에 넣으면 어디까지 안전할까?"
date: 2026-11-01 16:50:00 +0900
permalink: /technology/2026/11/01/transactional-external-api-boundary.html
categories: Technology
comments: true
description: "주문 저장과 외부 예약 API를 한 메서드에 넣었을 때 남는 실패 구간을 재현합니다. Spring 전파 옵션·롤백 규칙·격리 수준이 바꾸는 DB 경계와, 멱등성 키를 둔 복구 흐름을 살펴봅니다."
excerpt: "@Transactional은 주문 DB의 커밋과 롤백을 관리하지만 HTTP 예약까지 되돌리지는 않습니다. 로컬 스텁 테스트로 실패를 재현하고, 옵션과 격리 수준을 실제 복구 설계에 연결했습니다."
---

### TL;DR

* `@Transactional` 메서드 안에서 예약 API가 성공해도, 뒤따르는 주문 DB 롤백은 예약을 취소하지 않습니다. 반대로 HTTP 타임아웃은 원격 처리 실패의 증거가 아닙니다.
* `REQUIRED`·`REQUIRES_NEW`·`NESTED`와 격리 수준은 **DB 트랜잭션의 참여·가시성**을 조정합니다. 어떤 조합도 일반 HTTP 호출을 DB 커밋에 원자적으로 묶지 않습니다.
* 롤백 규칙, `readOnly`, 트랜잭션 `timeout`도 용도가 다릅니다. 특히 DB 트랜잭션 제한 시간은 HTTP 요청 제한 시간을 대신하지 않습니다.
* 주문을 `PENDING`으로 먼저 커밋하고, 안정된 멱등성 키로 예약을 호출한 뒤, 짧은 트랜잭션으로 확정합니다. 사이에 멈춘 주문을 조회·재시도·조정하는 경로까지 있어야 합니다.

----

### 들어가며

주문을 저장하고 외부 예약을 잡는 코드를 한 메서드에 넣으면 `@Transactional` 하나로 모두 묶인 것처럼 보이기 쉽습니다. 하지만 그 어노테이션이 다루는 자원은 설정한 트랜잭션 매니저의 DB 연결이고, 보통의 HTTP 예약 서버는 그 연결에 참여하지 않습니다. [앞선 Kafka 중복 처리 글]({% post_url 2026-10-26-kafka-duplicate-processing-idempotency %})에서 DB와 메시지 offset의 경계를 살폈다면, 이번에는 **동기 HTTP 호출**이 DB 트랜잭션 사이에 들어왔을 때를 보겠습니다.

여기서 주문·예약·실패는 모두 **교육용 재구성 사례**입니다. 실제 서비스의 주문, 회사 시스템, 운영 장애를 옮기거나 재현한 것은 아닙니다. 예제의 예약 서버도 로컬 `127.0.0.1` HTTP 스텁이며 결제나 실제 예약을 하지 않습니다.

----

### HTTP 200을 받았는데 주문이 사라지는 순서

가장 작은 코드는 아래와 같습니다. `reserve`가 200을 돌려준 뒤 예외가 나면 Spring은 로컬 주문 INSERT를 롤백할 수 있습니다. 예약 서버에는 그 롤백 메시지가 가지 않습니다.

```java
@Transactional(timeout = 3, rollbackFor = {IOException.class, InterruptedException.class})
public void remoteSuccessThenRollback(String id) throws IOException, InterruptedException {
    db.update("INSERT INTO orders VALUES (?, 'PENDING')", id);
    gateway.reserve(id, false); // 로컬 HTTP 스텁은 예약 효과를 기록하고 200을 반환
    throw new IllegalStateException("synthetic failure after HTTP success");
}
```

<div class="diagram-link">
  <a href="{{ '/assets/images/transaction-boundary/sequence.svg' | relative_url }}">
    <picture>
      <source media="(max-width: 600px)" srcset="{{ '/assets/images/transaction-boundary/sequence-mobile.svg' | relative_url }}">
      <img src="{{ '/assets/images/transaction-boundary/sequence.svg' | relative_url }}" alt="주문 PENDING을 DB에 미커밋 기록한 뒤 HTTP 예약이 성공하지만, 커밋 전 실패로 주문만 롤백되고 원격 예약은 남는 순서" loading="lazy">
    </picture>
  </a>
</div>

*그림 1. **재구성한 실패 순서**입니다. 모바일에는 세로 그림을 표시합니다. 그림을 누르면 원본 크기로 볼 수 있습니다.*

커밋 직전에도 실패할 수 있습니다. 첨부 예제는 `beforeCommit` 콜백에서 예외를 던져 **커밋 전 실패**를 재현합니다. 테스트에서 주문은 없고 예약 효과만 남습니다. 이것은 실제 DB 엔진의 커밋 I/O 오류나 네트워크 단절을 재현한 테스트는 아닙니다. 다만 DB 커밋이 실제로 실패하더라도 이미 성공한 HTTP 호출을 DB 롤백으로 되돌릴 수 없다는 경계는 같습니다.

<div class="table-scroll" markdown="1">

| 실패 시점 | 주문 DB에서 가능한 상태 | 예약 서버에서 가능한 상태 | 필요한 후속 동작 |
| --- | --- | --- | --- |
| HTTP 성공 → 이후 예외·DB 커밋 실패 | 주문 롤백 | 예약 완료 | 예약 조회 후 취소 또는 주문 복구를 결정 |
| HTTP 타임아웃 | 로컬 롤백 또는 `PENDING` | **완료·미완료 모두 가능** | 같은 키로 결과 조회·재시도하고 불명확 상태 기록 |
| `PENDING` 커밋 → HTTP 호출 전 프로세스 종료 | `PENDING` | 예약 없음 | 누락된 호출 재개 |
| HTTP 성공 → 주문 확정 트랜잭션 실패 | `PENDING` 또는 `UNKNOWN` | 예약 완료 | 예약 결과 확인 후 확정 재시도 |

</div>

타임아웃은 특히 조심해야 합니다. 요청이 서버에 도달해 예약이 반영된 뒤 응답만 늦어질 수 있거든요. 이때 새로운 주문 키로 다시 요청하면 중복 예약이 생길 수 있습니다. 테스트 스텁은 **효과를 먼저 기록하고 응답을 600ms 늦추며**, 클라이언트는 150ms 뒤 타임아웃됩니다. 이는 이 가능성을 보여주는 로컬 실험이지 실제 외부 API의 보장이나 장애 확률을 측정한 결과는 아닙니다.

또 하나는 자원 점유입니다. DB 작업으로 연결을 확보한 상태에서 HTTP 응답을 기다리면 그 연결은 트랜잭션 완료까지 묶일 수 있고, 이미 얻은 잠금도 오래 남을 수 있습니다. **모든 `@Transactional` 진입이 곧바로 연결이나 행 잠금을 얻는다는 뜻은 아닙니다.** 데이터 접근 방식, 트랜잭션 매니저, SQL 실행 시점에 따라 달라집니다. 이 예제는 `JdbcTransactionManager`로 트랜잭션을 시작하고 먼저 INSERT한 뒤 HTTP를 호출합니다.

----

### 전파 옵션을 바꾸면 무엇이 달라질까요?

내부 서비스 메서드를 호출할 때 전파 옵션이 주로 정하는 것은 **DB 작업이 어느 물리 트랜잭션에 들어가는지**입니다. [Spring의 전파 문서](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)는 논리적 범위와 물리 트랜잭션을 구분합니다.

<div class="diagram-link">
  <a href="{{ '/assets/images/transaction-boundary/propagation.svg' | relative_url }}">
    <picture>
      <source media="(max-width: 600px)" srcset="{{ '/assets/images/transaction-boundary/propagation-mobile.svg' | relative_url }}">
      <img src="{{ '/assets/images/transaction-boundary/propagation.svg' | relative_url }}" alt="REQUIRED는 물리 DB 트랜잭션 하나를 공유하고 REQUIRES_NEW는 안쪽에 독립 트랜잭션을 만들며 NESTED는 같은 트랜잭션에 세이브포인트를 두지만, HTTP 예약은 어느 롤백에도 포함되지 않는다" loading="lazy">
    </picture>
  </a>
</div>

*그림 2. 바깥 서비스가 **별도 Spring 빈의 안쪽 서비스를 프록시를 통해 호출**할 때의 DB 경계입니다. HTTP 호출은 어느 상자에도 들어가지 않습니다.*

<div class="table-scroll" markdown="1">

| 안쪽 메서드 옵션 | 주문 DB에서 실제로 생기는 일 | 예약 API와의 관계 |
| --- | --- | --- |
| `REQUIRED` 기본값 | 바깥 물리 트랜잭션에 참여합니다. 안쪽 예외가 `rollback-only`로 표시되면 바깥에서 예외를 잡아도 최종 커밋은 실패하고 `UnexpectedRollbackException`이 날 수 있습니다. | HTTP 효과는 그대로입니다. |
| `REQUIRES_NEW` | 바깥 트랜잭션을 보류하고 안쪽의 **독립 물리 트랜잭션**을 사용합니다. 안쪽 DB 기록이 커밋된 뒤 바깥이 롤백될 수 있습니다. | 두 DB 범위가 분리돼도 HTTP와의 원자성은 생기지 않습니다. |
| `NESTED` | 같은 물리 트랜잭션 안의 **savepoint**로 안쪽 DB 변경만 되돌릴 수 있습니다. 바깥 전체가 롤백되면 둘 다 사라집니다. | savepoint는 원격 예약을 되돌리지 않습니다. |

</div>

첨부 테스트에서는 `REQUIRED` 안쪽 실패를 바깥이 잡아도 두 행이 모두 롤백되고 `UnexpectedRollbackException`을 받습니다. `REQUIRES_NEW`에서는 안쪽 행이 남고 바깥 행은 사라집니다. `NESTED`에서는 바깥 행만 남습니다. 세 검사는 **Spring 6.2.19 + H2 2.3.232 + JDBC 트랜잭션 매니저**에서 실행했습니다. `NESTED`는 일반적으로 JDBC savepoint 지원에 의존하므로 JPA 등 다른 매니저에 그대로 적용된다고 가정하면 안 됩니다. `REQUIRES_NEW`는 바깥 연결을 붙잡은 채 안쪽에서 새 연결을 요구할 수 있어 **연결 풀 크기와 동시성**도 확인해야 합니다. [Spring 전파 문서](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)

전파 옵션은 프록시 경계를 지나야 동작합니다. 같은 객체의 `this.inner()` 호출은 기본 프록시 모드에서 안쪽 `@Transactional`을 새로 적용하지 않습니다. [Spring 어노테이션 문서](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)는 새 스레드에도 현재의 thread-bound 트랜잭션이 자동 전파되지 않는다고 명시합니다. 이 예제는 안쪽 서비스를 **별도 빈**으로 주입해 전파 테스트를 했습니다.

----

### 격리 수준은 HTTP가 아니라 DB 조회의 약속입니다

예를 들어 같은 좌석의 남은 수량을 읽고 예약 가능 여부를 판단하는 DB 쿼리가 있다면 격리 수준이 영향을 줍니다. 하지만 격리 수준을 올려도 원격 예약 서버의 HTTP 요청·응답은 DB 스냅샷에 들어오지 않습니다. `isolation = Isolation.DEFAULT`는 Spring이 새 격리 정책을 발명한다는 뜻이 아니라 **기본 DB 설정을 사용**한다는 뜻입니다. Spring의 `@Transactional` 기본 격리도 `DEFAULT`입니다. [Spring 어노테이션 문서](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)

<div class="table-scroll" markdown="1">

| 설정 | 이 주문 예제에서 살펴볼 DB 쟁점 | 주의점 |
| --- | --- | --- |
| `READ_UNCOMMITTED` | 미커밋 변경까지 읽을 수 있도록 허용하는 가장 약한 수준 | PostgreSQL은 이 요청을 `READ_COMMITTED`처럼 처리합니다. |
| `READ_COMMITTED` | 다른 트랜잭션의 커밋된 변경을 다음 조회에서 볼 수 있습니다. | PostgreSQL의 일반 `SELECT`는 문장마다 새 스냅샷을 보므로 두 조회 결과가 달라질 수 있습니다. |
| `REPEATABLE_READ` | 같은 트랜잭션의 반복 조회를 안정화하려는 선택 | PostgreSQL은 스냅샷으로 표준 최소 보장보다 강하게 phantom도 막지만 직렬화 이상은 남을 수 있습니다. MySQL InnoDB의 일반 consistent read와 잠금 읽기는 동작이 다릅니다. |
| `SERIALIZABLE` | DB 안의 동시 실행을 직렬 실행과 동등하게 만들려는 선택 | PostgreSQL에서는 직렬화 실패가 날 수 있어 **전체 DB 트랜잭션 재시도**가 필요합니다. 재시도 시 HTTP가 중복되지 않도록 설계해야 합니다. |

</div>

이 표는 SQL 표준의 이름만 보고 모든 엔진에 같은 잠금·phantom 동작을 약속하지 않으려는 요약입니다. 실제 기본값과 MVCC/잠금 동작은 [PostgreSQL 트랜잭션 격리 문서](https://www.postgresql.org/docs/current/transaction-iso.html), [MySQL 8.4 InnoDB 격리 문서](https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html)처럼 **사용 중인 엔진·버전**에서 확인해야 합니다. 이 글의 H2 테스트는 PostgreSQL·MySQL의 격리 결과를 검증하지 않습니다.

안쪽 `REQUIRED` 메서드에 `isolation = SERIALIZABLE`을 새로 써도 이미 시작한 바깥 트랜잭션의 격리 수준이 자동으로 바뀌지는 않습니다. 참여 범위의 격리·timeout·`readOnly` 선언은 기본적으로 바깥 설정을 따릅니다. 서로 다른 선언을 오류로 다루려면 트랜잭션 매니저의 `validateExistingTransaction` 설정을 검토할 수 있습니다. 반면 `REQUIRES_NEW`의 새 물리 트랜잭션에는 자체 격리 수준을 지정할 수 있습니다. [Spring 전파 문서](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)

----

### `timeout`, `rollbackFor`, `readOnly`는 서로 다른 손잡이입니다

위 예제의 `@Transactional(timeout = 3)`은 이 로컬 DB 트랜잭션의 시간 설정입니다. 예제 HTTP 클라이언트에는 **별도로** 연결 제한 1초와 요청 제한 150ms 또는 2초를 둡니다. 트랜잭션 매니저와 DB 드라이버가 DB 명령에 제한을 적용하는 방식은 다를 수 있지만, 트랜잭션 `timeout` 하나가 이미 전송된 HTTP를 취소하거나 원격 예약을 롤백해주지는 않습니다. HTTP 호출의 연결·응답 제한, DB 명령·잠금 대기 제한, 전체 요청 시간 예산을 각각 정해야 합니다.

기본 롤백 규칙도 살펴봐야 합니다. 기본 설정에서 Spring은 `RuntimeException`과 `Error`에는 롤백하고 **checked exception에는 롤백하지 않습니다**. 그래서 `HttpClient.send`에서 나오는 `IOException`·`InterruptedException`을 그대로 올리는 이 예제에는 `rollbackFor`를 명시했습니다. Spring 6.2부터는 `@EnableTransactionManagement(rollbackOn = ALL_EXCEPTIONS)`로 기본값을 바꿀 수 있지만, 이 예제에서는 설정하지 않았습니다. 반대로 `noRollbackFor`로 어떤 예외를 커밋 대상으로 지정해도 **원격 HTTP 효과의 취소나 확정**을 결정하는 것은 아닙니다. [Spring 롤백 문서](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html), [어노테이션 설정](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)

`readOnly = true`는 읽기 목적을 매니저·DB에 전달하는 **힌트 또는 환경별 최적화**입니다. 쓰기를 반드시 차단하는 보안 경계로 쓰면 안 됩니다. 주문 INSERT에 붙여 안전해지는 옵션도 아닙니다. 읽기 권한 자체가 필요하다면 별도의 DB 권한으로 제한해야 합니다. [Spring `@Transactional` API](https://docs.spring.io/spring-framework/docs/6.2.19/javadoc-api/org/springframework/transaction/annotation/Transactional.html)

----

### 짧은 DB 경계와 복구할 수 있는 상태를 둡니다

DB 트랜잭션을 HTTP 호출 전후로 나누면 오래 잡아두는 DB 자원을 줄일 수 있습니다. 다만 그 사이에 프로세스가 종료되는 구간이 생기므로 **상태와 재개 절차를 함께 설계**해야 합니다. 예제는 다음 순서로 움직입니다.

```text
트랜잭션 1: 주문 PENDING 저장·커밋 (주문 ID unique)
HTTP: 같은 주문 ID를 Idempotency-Key로 예약 요청
트랜잭션 2: 예약 결과를 확인하고 CONFIRMED로 변경·커밋
실패/시간 초과: PENDING 또는 UNKNOWN을 남겨 조회·재시도·조정
```

```java
public void place(String id, boolean failFinalize) throws IOException, InterruptedException {
    steps.start(id); // 별도 Spring 빈의 짧은 DB 트랜잭션이 여기서 커밋
    try {
        gateway.reserve(id, false); // 같은 비즈니스 작업에는 같은 키
        if (failFinalize) steps.confirmThenFail(id);
        else steps.confirm(id); // 다시 짧은 DB 트랜잭션
    } catch (HttpTimeoutException e) {
        steps.markUnknown(id);
        throw e;
    }
}
```

위 코드는 읽기 쉽게 `place`와 내부 `reserveAndFinish`의 핵심을 한곳에 모아 쓴 것입니다. 실행 소스에서는 둘을 분리했습니다. `SafeOrderService` 자체에는 `@Transactional`을 붙이지 않았고, `OrderSteps`의 `start`·`confirm`에 붙였습니다. 예제 스텁은 같은 키를 여러 번 받아도 예약 효과를 한 번만 기록합니다. **실제 API가 해당 키의 중복 요청·응답을 어떻게 다루는지는 제공자의 계약을 확인**해야 합니다. 단순히 헤더를 보냈다는 이유로 멱등성이 생기지는 않습니다. 키의 범위·보존 기간, 같은 키와 다른 요청 본문이 왔을 때의 정책도 정해야 합니다.

예제에서는 예약 성공 뒤 확정 트랜잭션을 고의로 실패시키면 `PENDING`이 남습니다. 같은 키로 재개하면 스텁의 예약 효과는 한 번인 채 주문이 `CONFIRMED`가 됩니다. 운영 환경이라면 `PENDING`·`UNKNOWN`의 나이, 마지막 시도, 제공자의 예약 ID와 조회 결과를 기록하고, 주기적으로 미완료 건을 찾아 **조회 → 안전한 재시도 또는 취소·보상 → 사람 검토**로 이어야 합니다. 여기의 데모는 자동 스케줄러나 실제 보상 API까지 구현하지 않았습니다.

`afterCommit` 이벤트로 HTTP를 보내면 DB 롤백 전에 전송하는 문제는 줄일 수 있습니다. 그러나 **DB 커밋 직후, 콜백 실행 전에 프로세스가 죽는 구간**은 남습니다. 누락 없이 재개해야 한다면 주문과 함께 전송할 작업을 같은 DB 트랜잭션에 기록하는 outbox 같은 내구성 있는 작업 목록을 고려해야 합니다. 이 역시 원격 측 멱등성과 결과 조정이 필요합니다. [Spring 트랜잭션 이벤트 문서](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)

----

### 로컬에서 어디까지 확인했을까요?

[전체 Java 소스]({{ '/assets/code/transaction-boundary-demo/src/main/java/dev/dk/blog/TransactionBoundaryDemo.java' | relative_url }}), [JUnit 테스트]({{ '/assets/code/transaction-boundary-demo/src/test/java/dev/dk/blog/TransactionBoundaryDemoTest.java' | relative_url }}), [Maven 설정]({{ '/assets/code/transaction-boundary-demo/pom.xml' | relative_url }})은 Java 21·Spring 6.2.19·H2 2.3.232로 실행할 수 있습니다. 예약 API는 테스트 프로세스 안에서 임의 로컬 포트를 여는 JDK `HttpServer`입니다. 실제 외부 주소나 자격 증명은 없습니다.

```bash
cd assets/code/transaction-boundary-demo
mvn test
```

작성 환경에서는 Maven 3.9.11을 내려받아 `mvn test`를 실행했고, **5개 테스트가 모두 통과**했습니다. 다섯 검사는 각각 HTTP 성공 후 애플리케이션 롤백, HTTP 성공 후 `beforeCommit` 실패, 서버 효과 기록 후 클라이언트 타임아웃, 확정 실패 후 같은 키로 복구, 세 전파 옵션의 DB 결과를 확인합니다. 테스트는 H2와 로컬 HTTP 스텁의 동작에 대한 증거입니다. 실제 DB 커밋 I/O 실패, 원격 서비스 장애, 연결 풀 포화, PostgreSQL·MySQL 격리 수준의 동시 실행 결과를 입증하지는 않습니다.

----

### 적용 전 체크리스트

* [ ] 외부 API가 성공했는데 DB가 롤백되면 무엇을 조회·취소·복구할지 정했나요?
* [ ] HTTP 타임아웃을 **실패 확정**으로 취급하지 않고 동일한 비즈니스 키로 결과를 조회하거나 재시도하나요?
* [ ] `PENDING`·`UNKNOWN`을 찾는 재조정 작업과 오래된 건의 경보가 있나요?
* [ ] `REQUIRED`·`REQUIRES_NEW`·`NESTED`의 실제 프록시 호출과 매니저 지원 여부를 확인했나요?
* [ ] `REQUIRES_NEW`에 필요한 연결 풀 여유, SQL 잠금 시간, DB/HTTP 제한 시간을 각각 측정했나요?
* [ ] 격리 수준의 기본값과 재시도 조건을 **실제 DB 엔진·버전**에서 검증했나요?
* [ ] checked exception의 롤백 규칙과 중단 시 인터럽트 처리를 명시했나요?

----

### 마무리

`@Transactional`은 주문 DB 안에서는 중요한 약속입니다. 하지만 HTTP 예약은 같은 약속의 대상이 아닙니다. 전파 옵션이나 격리 수준을 더 강하게 고르기 전에 **실패 후 어느 시스템에 어떤 상태가 남는지**를 그려보는 편이 도움이 됩니다. 그 결과를 바탕으로 짧은 DB 트랜잭션, 안정된 멱등성 키, 미완료 상태의 재조정 절차를 함께 두겠습니다.
