---
layout: post
author: Kim, DongKi
title: "Kafka 메시지는 한 번 왔는데, 처리는 왜 두 번 될까?"
date: 2026-10-26 08:40:00 +0900
canonical: true
permalink: /technology/2026/10/26/kafka-duplicate-processing-idempotency.html
categories: Technology
comments: true
description: "Kafka에 한 번 기록된 이벤트도 다시 처리될 수 있습니다. DB 반영과 offset 커밋 사이의 실패 구간, processed-event 패턴과 unique key, Kafka exactly-once의 범위를 작은 SQLite 실험으로 살펴봅니다."
excerpt: "DB에는 반영됐는데 Kafka에는 어디까지 처리했는지 남기지 못했다면 어떻게 될까요? 메시지의 중복 저장과 비즈니스 처리의 중복을 나누고, 같은 이벤트를 다시 받아도 결과를 한 번만 반영하는 방법을 정리했습니다."
---

### TL;DR

* Kafka에 레코드가 하나만 있어도 같은 consumer group에서 처리 코드가 다시 실행될 수 있습니다.
* DB 반영 후 offset 커밋 전에 실패하면, 다시 읽은 이벤트가 DB에 중복 반영될 수 있습니다.
* 중복 방지 키의 unique 제약과 비즈니스 변경을 **같은 DB 트랜잭션**에 넣어야 합니다.
* Producer idempotence와 Kafka transactions가 외부 DB·HTTP 호출의 부수효과까지 자동으로 한 번만 실행해주는 것은 아닙니다.

----

### 들어가며

Kafka에서 “한 번 보냈다”는 말은 어느 지점까지를 뜻할까요? 발행 API를 한 번 호출했다는 뜻일 수도 있고, 토픽에 레코드가 하나 있다는 뜻일 수도 있습니다. 하지만 둘 다 소비자의 DB 처리가 한 번만 실행됐다는 증거는 아닙니다.

이번 글에서는 **토픽의 한 파티션에 레코드 하나가 기록된 상황**부터 생각해보겠습니다. 특정 업무 장애를 회고하는 글은 아니고, 공식 문서의 실패 조건과 로컬 DB 실험으로 처리 경계를 살펴보려 합니다.

이전에 [Outbox 패턴]({% post_url 2026-08-18-outbox-pattern-practice %})에서 소비 측 멱등성을 짧게 언급했습니다. 이번에는 발행 쪽을 반복하기보다, 소비자가 DB를 바꾼 뒤 어디까지 완료됐다고 기록해야 하는지에 집중하겠습니다.

----

### DB는 끝났는데 offset은 남지 않았습니다

이벤트 `event-1`이 포인트 10점을 적립한다고 가정해보겠습니다. 아래는 순서대로 처리하는 단일 파티션의 **설명용 시나리오**입니다. 이 예에서 offset 100 다음 위치는 101입니다.

| 순서 | 일어난 일 | 남은 상태 |
| --- | --- | --- |
| 1 | Consumer A가 offset 100을 읽습니다. | 그룹의 저장된 재개 위치는 100입니다. |
| 2 | DB에 10점을 더하고 커밋합니다. | 포인트는 10점입니다. |
| 3 | offset 101을 커밋하기 전에 A가 종료됩니다. | DB는 바뀌었지만 재개 위치는 여전히 100입니다. |
| 4 | 파티션을 넘겨받은 B가 저장된 위치부터 읽습니다. | 같은 offset 100을 다시 처리합니다. |
| 5 | DB에 다시 10점을 더합니다. | 포인트가 20점이 됩니다. |

Kafka에 레코드 두 개가 생겨야 가능한 일이 아닙니다. **DB의 완료 기록과 Kafka의 소비 위치가 서로 다른 시점에 저장되기 때문**입니다. 여기서 offset 커밋은 메시지를 삭제하는 동작이 아니라, 해당 그룹이 다시 시작할 위치를 기록하는 동작입니다. `poll()`로 읽으며 앞으로 이동한 위치와 커밋된 위치도 다르고요. [KafkaConsumer 4.1.2 문서](https://kafka.apache.org/41/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html)는 이 두 위치와 DB 저장 후 수동 커밋 사이의 중복 가능성을 설명합니다.

순서를 바꿔 offset부터 커밋하면 될까요? 그러면 DB 저장 전에 죽었을 때, 그룹은 처리하지 못한 이벤트를 지나친 위치부터 시작할 수 있습니다. 순서만 바꿔서 두 저장을 하나로 만들 수는 없습니다.

처리 후 위치를 기록해 재처리를 허용하는 것이 at-least-once의 핵심입니다. 다만 재시도 횟수를 다 쓰고 버리거나 실패 토픽으로 옮긴 레코드까지 비즈니스 처리가 성공했다는 뜻은 아닙니다. 실패 후 어디로 보내고 어떻게 다시 처리할지는 별도 정책입니다. [Kafka 전달 의미 설계](https://kafka.apache.org/41/design/design/)

----

### 재시도와 리밸런싱도 같은 경계를 만납니다

애플리케이션이 DB 커밋 뒤의 오류를 보고 처리 전체를 재시도해도 같은 문제가 생깁니다. 오류를 받았다는 사실만으로 앞선 DB 트랜잭션이 롤백됐다고 판단할 수는 없으니까요.

리밸런싱 역시 그 자체로 중복을 만드는 마법 같은 동작은 아닙니다. 담당자가 바뀔 때 **이미 반영한 DB 작업보다 커밋된 offset이 뒤에 있으면** 새 담당자가 그 구간을 다시 읽을 수 있습니다. 처리가 오래 걸려 `max.poll.interval.ms`를 넘기는 상황도 살펴봐야 합니다. 세부 동작은 그룹 설정에 따라 다르지만, 오래된 담당자의 외부 DB 작업까지 Kafka가 되돌려주지는 않습니다. [Kafka consumer 설정](https://kafka.apache.org/41/configuration/consumer-configs/)

같은 이벤트 ID를 봤다면 먼저 토픽·파티션·offset과 consumer group을 같이 확인하는 편이 좋습니다. 같은 offset의 재처리인지, 다른 offset으로 재발행된 같은 이벤트인지 구분할 수 있기 때문입니다. 서로 다른 그룹이 각자 읽은 것은 정상적인 구독 동작일 수도 있고요.

----

### “처리했는지 조회”와 “한 번만 반영”은 다릅니다

기존 Outbox 글의 `existsById → 처리 → 처리 기록 저장`처럼 순서만 나열한 예시는 동시 실행과 트랜잭션 경계를 충분히 설명하지 못합니다. 두 실행이 동시에 조회하면 둘 다 “아직 처리하지 않음”을 볼 수 있습니다.

여기서는 DB가 중복을 판정하도록 **처리 주체와 이벤트 ID에 unique 제약**을 둡니다. 그리고 처리 기록을 넣는 일과 실제 포인트 변경을 같은 트랜잭션에 묶습니다.

```sql
CREATE TABLE processed_event (
    subscriber TEXT NOT NULL,
    event_id   TEXT NOT NULL,
    payload    TEXT NOT NULL,
    PRIMARY KEY (subscriber, event_id)
);
```

새 키를 넣었다면 포인트를 변경합니다. 이미 있는 키라면 이전 입력과 같은 이벤트인지 확인하고 변경을 건너뜁니다. 포인트 변경이 실패하면 처리 기록도 함께 롤백돼야 합니다. 기록부터 따로 커밋하면, 실제 업무는 실패했는데 다음 시도가 “처리 완료”로 건너뛸 수 있습니다.

핵심 흐름을 Python으로 줄이면 아래와 같습니다. 뒤에서 실행할 전체 예제의 일부이며, 이 조각만으로는 실행되지 않습니다.

```python
with db:
    inserted = db.execute("""
        INSERT INTO processed_event VALUES (?, ?, ?)
        ON CONFLICT(subscriber, event_id) DO NOTHING
    """, (subscriber, event_id, payload)).rowcount
    if not inserted:
        # 전체 예제는 기존 payload와 비교하고, 다르면 오류로 처리합니다.
        return False
    db.execute(
        'UPDATE account SET points=points+? WHERE id=?',
        (delta, account_id),
    )
# 이 DB 트랜잭션이 끝난 뒤 처리 완료 위치를 커밋합니다.
```

예제의 `ON CONFLICT`는 지정한 중복 키 충돌만 건너뜁니다. 모든 DB 오류를 중복으로 삼켜서는 안 됩니다. [SQLite UPSERT 문서](https://www.sqlite.org/lang_upsert.html)에서 이 문법의 범위를 확인할 수 있습니다. 운영 DB에 옮길 때는 해당 DB의 충돌 처리와 트랜잭션 동작을 다시 확인해야 합니다.

이벤트 ID는 재시도나 재발행 때도 유지돼야 합니다. 요청을 다시 시도할 때마다 새 ID를 만들면 중복 방지 키가 달라집니다. 반대로 같은 ID에 다른 내용을 보내는 경우는 정상 중복으로 숨기지 않고 오류로 다뤄야 하고요.

`subscriber`는 포인트 적립처럼 같은 업무 효과를 나타내는 안정된 이름입니다. 인스턴스마다 바꾸지 않습니다. `(topic, partition, offset)`도 특정 로그 레코드를 식별할 수 있지만, 같은 이벤트가 다른 토픽이나 offset으로 재발행되면 별개의 키가 된다는 차이가 있습니다.

----

### 작은 DB 실험으로 확인해봤습니다

[전체 실행 파일]({{ '/assets/code/kafka-idempotency-demo.py' | relative_url }})은 Python 표준 라이브러리와 임시 SQLite 파일만 사용합니다. 저장소를 내려받았다면 루트에서 실행할 수 있습니다. Python 3.8 이상, SQLite 3.24 이상이 필요합니다.

```bash
python3 assets/code/kafka-idempotency-demo.py
```

이 실험은 Kafka 브로커를 실행하지 않습니다. offset을 정수 변수로 두고, DB 커밋 뒤 그 값을 갱신하지 않은 채 연결을 닫았다 다시 열어 같은 이벤트를 호출합니다. 커밋 전 실패는 예외를 넣어 만듭니다. **실제 프로세스 강제 종료나 리밸런싱을 재현한 테스트는 아닙니다.**

작성 환경인 Python 3.12.14·SQLite 3.53.1에서 실행한 결과는 다음과 같습니다. 각 결과에는 상태를 확인하는 assertion이 있습니다.

```text
without dedup: points=20
replay after DB commit: points=10, receipts=1, offset=101
same ID, different payload: rejected, points=10
rollback then retry: points=10, receipts=1
```

중복 방지가 없으면 두 호출이 모두 반영됐습니다. 처리 기록을 함께 저장한 경우에는 DB 연결을 다시 열어도 두 번째 적립을 건너뛰었습니다. DB 커밋 전에 실패시킨 경우에는 포인트와 처리 기록이 모두 0으로 돌아갔고, 재시도 때 한 번 반영됐습니다.

이 결과가 보여주는 것은 **DB 트랜잭션 안의 효과를 중복 적용하지 않는 흐름**입니다. 브로커 장애, 여러 프로세스의 락 경합, 실제 운영 DB의 격리 수준은 이번 실험에서 확인하지 않았습니다. 동일 파티션을 병렬 처리한다면 완료되지 않은 앞선 레코드를 넘어 offset을 커밋하지 않는 설계도 별도로 필요합니다.

----

### Kafka의 exactly-once는 어디까지일까요?

비슷한 이름의 보장을 한데 묶으면 설정 하나로 모든 중복이 사라질 것처럼 보입니다. 적용되는 경계를 나눠보면 조금 명확해집니다.

| 기능 | 다루는 경계 |
| --- | --- |
| Producer idempotence | Producer의 전송 재시도로 같은 메시지가 로그에 중복 기록되는 것을 방지합니다. |
| Kafka transactions | 출력 Kafka 레코드와 소비 offset을 같은 Kafka 트랜잭션으로 커밋할 수 있습니다. |
| DB의 processed-event 패턴 | 같은 이벤트를 다시 처리해도 해당 DB의 비즈니스 효과가 중복 반영되지 않게 합니다. |

`enable.idempotence`는 소비자의 포인트 적립을 보호하는 옵션이 아닙니다. 애플리케이션이 같은 업무 이벤트를 새로운 전송으로 다시 발행하는 것까지 이벤트 ID로 찾아주는 기능도 아니고요. [Kafka producer 설정](https://kafka.apache.org/41/configuration/producer-configs/)

Kafka 안에서 읽고 처리해 다른 토픽에 쓰는 경우에는 출력과 offset을 트랜잭션으로 묶고, 결과 소비자가 `read_committed`로 읽는 구성을 사용할 수 있습니다. 그래도 처리 함수 자체가 한 번만 호출된다는 뜻은 아닙니다. Spring Kafka 4.1.2의 [Exactly Once Semantics 문서](https://docs.spring.io/spring-kafka/reference/kafka/exactly-once.html)도 읽기와 처리에는 at-least-once 의미가 남는다고 설명합니다.

외부 DB는 Kafka 트랜잭션에 자동으로 포함되지 않습니다. Spring의 트랜잭션 동기화도 두 시스템을 한 번에 커밋하는 보장과는 다릅니다. [Spring Kafka 트랜잭션 문서](https://docs.spring.io/spring-kafka/reference/kafka/transactions.html)는 주 트랜잭션이 커밋된 뒤 동기화된 트랜잭션의 커밋이 실패할 수 있으며, 이미 반영된 결과에 대응해야 한다고 명시합니다. 이번 예제는 Spring listener나 ack mode를 구성한 실험은 아닙니다.

DB에 처리 결과와 소비 위치를 함께 저장하고 재할당 시 그 위치에서 시작하도록 구현하는 방법도 있습니다. 다만 위치 복원과 파티션 소유권까지 애플리케이션이 관리해야 하므로, 단순히 DB 저장 후 Kafka offset을 커밋하는 코드와 같지는 않습니다.

메일 발송이나 외부 결제 API 호출 역시 로컬 DB 롤백으로 취소되지 않습니다. DB에 전송할 일을 함께 남기는 Outbox와 수신 시스템의 멱등성 키 등, 바깥 효과에 맞는 별도 계약이 필요합니다. 처리 기록의 보존 기간도 재처리 가능한 기간보다 짧으면 과거 이벤트가 다시 반영될 수 있습니다.

----

### 마치며

중복을 살펴볼 때는 “몇 번 보냈나”에 더해 **무엇이 커밋됐고, 어디부터 다시 실행될 수 있나**를 봐야 합니다. DB 반영과 Kafka offset 커밋이 분리돼 있으면 그 사이의 실패를 없다고 가정하기 어렵습니다.

핵심은 재시도를 무조건 막는 것이 아니라, 같은 이벤트가 다시 들어와도 업무 결과가 달라지지 않게 만드는 것입니다. 처리 기록의 unique key와 비즈니스 변경을 같은 트랜잭션에 두는 작은 경계부터 확인해보면 좋겠습니다.
