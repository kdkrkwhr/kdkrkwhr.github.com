---
layout: post
author: Kim, DongKi
title: "AI가 구현하고 AI가 테스트했는데, 왜 요구사항은 빠졌을까?"
date: 2026-10-29 09:20:00 +0900
permalink: /ai/2026/10/29/ai-implementation-tests-requirement-gap.html
categories: AI
comments: true
description: "구현과 테스트가 같은 요구 해석을 공유할 때 생기는 누락을 image 객체 저장 예제로 살펴봅니다. 독립 fixture, DB 재조회, 인증 계약 확인과 요구사항 추적표로 검증 범위를 분명히 합니다."
excerpt: "id와 url만 저장하는 구현을 id와 url만 검사하는 테스트로 확인하면 무엇이 빠질까요? 요구사항을 구현 밖의 기대값에 연결하고 실제 저장 후 읽기까지 대조하는 방법을 정리했습니다."
---

### TL;DR

* 구현과 테스트가 같은 잘못된 해석에서 출발하면 테스트가 통과해도 원문 요구사항을 놓칠 수 있습니다.
* `image` 객체 전체의 범위는 필드·중첩·배열·`null`·누락·새 필드 정책까지 계약으로 정해야 합니다. 항상 원본 JSON 문자열을 그대로 저장해야 한다는 뜻은 아닙니다.
* 기대값은 구현의 DTO나 반환값에서 복사하지 말고 원문 계약과 별도 fixture에서 만듭니다. 저장 요구는 실제 DB에 쓴 뒤 다시 읽어 비교합니다.
* 담당 모델을 바꾸는 일보다 **같은 원문, 독립 기대값, 재현 가능한 실행 결과**를 건네는 일이 검토의 독립성을 높입니다.

----

### 들어가며

한 구현을 살펴보다가 `image` 객체 전체를 저장하라는 요구가 `id`와 `url`만 남기는 코드로 바뀐 것을 봤습니다. 인증 없이 호출하는 공개 JSON URL에 `api_key`가 필요하다고 가정한 부분도 있었습니다. 두 경우 모두 코드는 작성될 수 있지만, 무엇을 구현해야 하는지 해석하는 단계에서 원문과 멀어집니다.

당시의 전체 테스트 결과가 실제로 모두 통과했는지는 확인되지 않았습니다. 이 글의 제목과 아래 녹색 검사 결과는 **같은 오해로 구현과 테스트를 작성하면 생길 수 있는 실패 유형**을 설명합니다. 코드와 데이터는 그 유형을 보여주기 위해 새로 만든 **재구성 예제**이며, 당시 코드나 비공개 원문을 옮긴 것이 아닙니다.

어제 쓴 [프로젝트 구조와 개발 기준 글]({% post_url 2026-10-28-project-structure-standards-knowledge %})에서는 사람이 요청부터 저장과 테스트까지 따라 읽을 수 있어야 한다고 했습니다. 이번에는 그 길을 따라가도 **테스트의 기대값이 어디서 왔는지** 확인하지 않으면 어떤 빈틈이 남는지 살펴보려 합니다.

----

### 구현 정확성과 요구 적합성은 다른 질문입니다

“작성한 DTO를 저장하고 다시 읽으면 같은 `id`와 `url`이 나온다”는 검사는 그 두 필드에 대해 의미가 있습니다. 하지만 원문이 `image` 객체 전체를 요구했다면, 그 검사는 빠진 필드가 없다는 증거가 아닙니다. 코드를 기준으로 기대값을 만들면 구현이 줄여놓은 범위가 테스트의 범위도 됩니다.

<a href="{{ '/assets/images/requirement-gap/shared-misread.svg' | relative_url }}">
  <picture>
    <source media="(max-width: 600px)" srcset="{{ '/assets/images/requirement-gap/shared-misread-mobile.svg' | relative_url }}">
    <img src="{{ '/assets/images/requirement-gap/shared-misread.svg' | relative_url }}" alt="image 객체 전체 저장 요구를 id와 url만 저장한다는 해석으로 줄이고, 구현과 테스트가 모두 그 두 필드만 보면서 초록 테스트와 요구 누락이 함께 생기는 흐름도" loading="lazy">
  </picture>
</a>

*그림 1. **재구성한 실패 흐름**입니다. 구현과 검사가 같은 해석을 공유하면, 검사 통과는 그 해석 안에서만 유효합니다. 그림을 누르면 원본 크기로 볼 수 있습니다.*

테스트가 거짓이라는 뜻은 아닙니다. `id/url` 검사는 정말로 통과할 수 있습니다. 문제는 그 결과를 “객체 전체 저장을 검증했다”로 넓혀 읽는 순간 생깁니다. 코드가 스스로 정한 작은 계약에 맞는지와, 바깥에서 주어진 요구에 맞는지를 나눠 봐야 합니다.

----

### 먼저 “객체 전체”의 경계를 적어봅니다

`image` 객체를 전부 저장하라는 한 문장만으로 DB 컬럼이나 DTO 형태가 정해지지는 않습니다. 먼저 **어떤 정보를 잃지 않아야 하는지**를 합의해야 합니다. 아래는 이 글의 가상 계약입니다. 실제 사례의 데이터 구조를 재현한 것은 아닙니다.

| 항목 | 이 글의 예시 계약에서 확인할 점 |
| --- | --- |
| 알려진 필드 | `id`, `url`, `dimensions`, `variants`, `attribution`을 보존합니다. |
| 중첩·배열 | `dimensions`의 값과 `variants`의 원소·순서를 보존합니다. |
| `null`과 누락 | `attribution: null`이 있는 상태와 키가 없는 상태를 구분합니다. |
| 미지 필드 | 이 예시 계약은 새 필드도 보존합니다. `sourceHint`가 이를 시험하는 필드입니다. |
| 비교 기준 | JSON 객체의 의미를 비교합니다. 공백과 객체 키의 출력 순서까지 같아야 하는 바이트 비교는 요구하지 않습니다. |

JSON의 객체·배열·`null`은 서로 다른 값이며 객체 멤버의 순서에 의존하지 않는 편이 상호 운용에 안전합니다. [RFC 8259의 JSON 정의](https://www.rfc-editor.org/rfc/rfc8259)를 참고했습니다. 다만 **미지 필드를 보존할지 거부할지**는 JSON 규격이 대신 결정해주지 않습니다. API 버전과 소비자가 요구하는 호환성에 맞춰 별도 계약으로 정해야 합니다.

이 계약이라면 JSON 트리를 직렬화해 저장하는 방법이 간단합니다. 알려진 필드만 필요한 계약이라면 명시적인 DTO와 컬럼으로 저장해도 됩니다. 반대로 새 필드까지 보존해야 한다면 `id/url`만 가진 DTO로 한 번 변환하는 순간 정보가 사라집니다. 어떤 방법을 고르든 저장 후 다시 읽었을 때 합의한 정보가 유지되는지가 기준입니다.

예제의 독립 [image fixture]({{ '/assets/code/image-contract-fixture.json' | relative_url }})는 다음과 같습니다. 이 파일은 구현 결과에서 추출하지 않고, 위 표의 계약을 사람이 먼저 적은 입력 겸 기대값입니다.

```json
{
  "image": {
    "id": "img-7",
    "url": "https://example.test/images/7.jpg",
    "dimensions": {"width": 640, "height": 480},
    "variants": [{"kind": "thumbnail", "url": "https://example.test/images/7-small.jpg"}],
    "attribution": null,
    "sourceHint": "public-catalog"
  }
}
```

----

### Java 21 예제: 두 필드 검사만으로는 누락을 못 봅니다

다음은 [전체 Java 예제]({{ '/assets/code/RequirementGapDemo.java' | relative_url }})의 핵심입니다. Jackson으로 fixture를 읽고 H2에 JSON을 저장합니다. `ImageProjection`은 객체 전체를 두 필드로 줄여버리는 **의도적인 잘못된 구현**입니다.

```java
public record ImageProjection(String id, String url) {}

var projection = new ImageProjection(id, expected.required("url").asText());
save(id, JSON.valueToTree(projection));
JsonNode fromDb = read(id);

// 같은 두 필드만 기대값으로 삼으면 이 검사는 통과합니다.
if (!id.equals(fromDb.required("id").asText())
        || !projection.url().equals(fromDb.required("url").asText())) {
    throw new AssertionError("projection check failed");
}
```

검사 자체는 `id/url` 저장 여부를 확인합니다. 그러나 `dimensions`, `variants`, `attribution`, `sourceHint`는 입력에서 사라졌고, 검사에는 등장하지 않습니다. 테스트를 구현 직후 만들면서 `ImageProjection`의 필드 목록을 그대로 기대값으로 삼았다면 이런 일이 쉬워집니다. **테스트의 출처가 원문 계약이 아니라 구현의 선택**이 된 셈입니다.

이제 비교 대상을 바꿉니다. 기대값은 앞서 고정한 fixture의 `image`이고, 실제값은 저장 메서드가 반환한 객체가 아니라 **새 JDBC 연결에서 `SELECT image_json`으로 읽은 값**입니다. 예제의 `save`는 `MERGE`로 쓰고 연결을 닫으며, `read`는 새 연결을 열어 조회합니다.

```java
JsonNode expected = JSON.readTree(Files.readString(Path.of(args[0]))).required("image");

save(id, expected);
JsonNode fromDb = read(id); // 새 JDBC 연결에서 SELECT
if (!expected.equals(fromDb)) {
    throw new AssertionError("full image was not read back");
}
```

예제의 H2 URL은 이름 있는 메모리 DB에 `DB_CLOSE_DELAY=-1`을 붙여 같은 JVM에서 연결을 다시 열어도 데이터를 읽게 합니다. 이는 [H2의 메모리 DB 설정](https://www.h2database.com/html/features.html)에 따른 **실제 DB 조회**이지만, 프로세스를 재시작해도 남는 파일 DB나 운영 DB를 시험한 것은 아닙니다. 구조적 JSON 비교이므로 직렬화된 문자열의 공백까지 같을 필요도 없습니다.

작성 환경에서 **OpenJDK 21.0.12, H2 2.3.232, Jackson 2.18.2**의 이미 설치된 파일로 전체 예제를 컴파일·실행했습니다. 블로그 저장소를 내려받고 같은 버전의 JAR 네 개가 `assets/code/`에 있다면 macOS·Linux에서는 다음처럼 재현할 수 있습니다. `DEMO_CP`에는 H2, Jackson databind·core·annotations를 모두 넣습니다.

```bash
cd assets/code
DEMO_CP='h2-2.3.232.jar:jackson-databind-2.18.2.jar:jackson-core-2.18.2.jar:jackson-annotations-2.18.2.jar'
javac -cp "$DEMO_CP" RequirementGapDemo.java
java -cp ".:$DEMO_CP" RequirementGapDemo image-contract-fixture.json
```

실행 출력은 다음과 같았습니다. 두 번째 줄은 잘못된 구현을 독립 fixture가 **정상적으로 거부했다**는 의미입니다.

```text
projection-only check: PASS
independent fixture rejects projection: PASS
full image, saved and read through H2: PASS
removing variants is detected: PASS
```

마지막 줄은 전체 저장 구현에서 `variants`만 일부러 제거한 변형을 다시 저장·조회했을 때, fixture 비교가 차이를 발견했는지 본 결과입니다. 필드를 하나 지워도 검사가 계속 초록색이라면 그 검사는 해당 필드를 보호하지 못합니다.

JPA를 쓰는 프로젝트에서는 `save()` 직후 같은 영속성 컨텍스트에서 `find()`만 호출하면 관리 중인 객체가 그대로 반환될 수 있습니다. [Jakarta Persistence의 `EntityManager` 문서](https://jakarta.ee/specifications/persistence/3.2/apidocs/jakarta.persistence/jakarta/persistence/EntityManager.html)는 `flush()`가 DB와 동기화하고 `clear()`가 관리 객체를 분리하며, `find()`가 컨텍스트 안의 객체를 먼저 반환할 수 있음을 설명합니다. `flush()` 후 `clear()`와 재조회, 또는 별도 읽기 경계와 직접 SQL 조회 등으로 **무엇을 실제 DB에서 읽었는지** 분명히 해야 합니다. 위 실행은 JDBC/H2 예제이며 JPA 동작을 직접 시험한 결과는 아닙니다.

----

### 공개 JSON URL에 인증을 덧씌우기 전에

다른 누락은 인증 가정이었습니다. 공개 JSON URL을 인증 없이 호출하는 계약인데, 구현이 `api_key`를 필수로 상정하면 데이터를 가져오는 경로 자체가 달라집니다. “보통 외부 API에는 키가 필요하다”는 일반론은 이 URL의 계약을 대신하지 못합니다.

이 경우에는 먼저 제공 문서와 공개 범위, 실제 응답을 확인합니다. 어떤 URL과 메서드에 인증이 필요한지, 실패할 때 HTTP 상태와 본문이 무엇인지, 키가 있다면 어디서 주어지는지 확인한 뒤 요구사항에 적습니다. 계약이 공개 호출이라면 없는 키를 찾게 하거나 임의의 헤더를 만들어 넣지 않습니다. 응답이 실패했는데 빈 목록이나 기본 이미지를 성공 결과로 돌려도 안 됩니다. 실패 원인과 재시도 가능 여부를 구분해 드러내야 합니다.

예컨대 이 글의 **가상** `catalog.json`에 대해 “인증 없이 `GET`했을 때 정상 JSON을 받아 파싱한다”가 R2라면, 테스트의 기대값도 공개 호출의 상태와 필수 필드에서 나와야 합니다. 401/403을 본다면 그 상태를 기록하고 URL·접근 조건·환경 차이를 다시 확인할 일입니다. “키가 없으니 빈 데이터로 계속 진행”은 R2 검증에 성공한 것이 아닙니다. 이 글에서는 실제 외부 URL을 호출하지 않았으므로 특정 서비스의 인증 정책을 검증했다고 주장하지 않습니다.

응답 정규화에도 같은 원칙이 적용됩니다. 저장 전에 `image`의 일부 필드를 걷어내거나 `null`을 없애는 변환이 편리할 수 있습니다. 하지만 요구사항에 그 변환의 근거가 없다면, 보기 좋은 출력이나 간단한 DTO를 위해 정보를 삭제한 것일 수 있습니다. 허용한 변환과 보존할 필드를 먼저 문서화한 다음 그 결과를 fixture로 고정해야 합니다.

----

### 기대값을 구현 밖에 놓는 검증 흐름

<a href="{{ '/assets/images/requirement-gap/independent-evidence.svg' | relative_url }}">
  <picture>
    <source media="(max-width: 600px)" srcset="{{ '/assets/images/requirement-gap/independent-evidence-mobile.svg' | relative_url }}">
    <img src="{{ '/assets/images/requirement-gap/independent-evidence.svg' | relative_url }}" alt="원문 계약과 수용 조건에서 구현과 독립 fixture를 나누어 만들고, 실제 DB에 저장한 뒤 새 연결에서 전체 객체를 읽어 비교하며 요구 ID별 결과를 남기는 세로 흐름도" loading="lazy">
  </picture>
</a>

*그림 2. **재구성 예제의 검증 흐름**입니다. 구현과 독립 기대값이 같은 원문 계약에서 출발하고, 실제 저장 경계에서 다시 만납니다. 그림을 누르면 원본 크기로 볼 수 있습니다.*

검증을 앞에서부터 만들면 순서가 바뀝니다. 구현을 보고 테스트를 만들기 전에 요구를 작게 나누고, 각 요구를 깨뜨리는 입력을 먼저 고릅니다.

| 층위 | 이 예시에서 묻는 질문 | 기대값의 근거 |
| --- | --- | --- |
| 계약 | `image`의 어떤 필드와 새 필드를 보존하나? 공개 URL에 인증이 필요한가? | 원문 요구·공개 API 문서·합의한 수용 조건 |
| 회귀 | 전에 빠졌던 `dimensions`·`variants`가 새 코드에서도 남나? | 구현과 별도로 보관한 fixture |
| 경계 | 빈 배열, `null`, 키 누락, 중첩 값, 미지 필드는 각각 어떻게 되나? | 필드별 보존 정책 |
| 실패 | 원격 응답 오류, JSON 형식 오류, DB 쓰기 실패를 성공으로 감추지 않나? | 오류 계약과 관찰한 상태 |
| 변형 | `variants` 저장 코드를 없애면 검사가 실패하나? | R1을 일부러 위반한 변경 |

여기서 테스트 우선은 무조건 테스트 파일을 먼저 타이핑하는 순서가 아닙니다. **수용 조건과 독립 기대값을 구현보다 먼저 확정하는 일**입니다. 테스트 파일을 나중에 작성하더라도 그 기준이 구현 결과를 따라 바뀌지 않아야 합니다. 코드 커버리지가 높아도 존재하지 않는 기대값을 대신 만들어주지 못합니다.

요구사항을 추적할 때는 큰 문장 하나를 “완료”로 표시하기보다 다음처럼 연결합니다. 아래 표도 실제 과거 프로젝트의 상태표가 아닌 **이 글의 예시 대조표**입니다.

| 요구 ID | 구현 위치 | 검증 사례 | 이 글에서 확인한 결과 |
| --- | --- | --- | --- |
| R1 객체 보존 | 예제 `save` / `read` | 독립 fixture 전체 비교, `variants` 제거 변형 | H2 예제에서 통과. `id/url` 투영은 거부됨 |
| R2 공개 호출 | 가상 JSON 호출부 | 인증 없이 호출, 401/403 처리 | 미실행. 실제 URL·계약 없음 |
| R3 결과 변환 | 가상 응답 매핑부 | 허용된 변환만 적용, 필드 삭제 감지 | 미실행. 변환 계약 먼저 필요 |

`요구 ID → 파일·심볼 → 검증 사례 → 실행 결과`가 이어지면 “테스트했습니다”보다 훨씬 구체적인 보고가 됩니다. 실패나 미실행도 결과입니다. 녹색이라는 색 하나로 서로 다른 검증 범위를 합치지 않습니다.

----

### 구현자와 검토자의 역할을 나눌 때

다른 에이전트에게 검토를 맡기는 일은 도움이 될 수 있습니다. 다만 모델 이름만 바꾼다고 기대값이 독립적으로 생기지는 않습니다. 검토자가 앞선 구현 설명과 테스트만 읽으면 같은 축약을 다시 받아들일 수 있습니다. 반대로 원문 요구와 고정 fixture를 주지 않은 채 “문제 찾아줘”라고 하면, 검토자는 무엇이 누락인지 판단할 기준이 부족합니다.

구현자에게는 선택한 저장 방식과 이유, 변경 파일, 실행한 명령을 남기게 합니다. 검토자에게는 **같은 원문 요구·수용 조건, 고정한 소스 커밋, fixture, 재현 명령, 기대 결과**를 먼저 줍니다. 그리고 구현자가 만든 테스트가 놓칠 수 있는 반례를 독립적으로 고르게 합니다. 검토자는 새 모델이어도 좋고 같은 도구를 쓰는 다른 세션이어도 됩니다. 핵심은 담당자 수보다 기대값의 출처와 검증 경계입니다.

에이전트끼리 무제한으로 토론시키는 것도 해결책이 아닙니다. 두 사람이 같은 잘못된 전제를 세련되게 설명할 수 있으니까요. 의견이 갈리면 원문 요구, 응답 예시, DB에서 다시 읽은 값, 실패 재현 결과로 돌아갑니다. 사람이 마지막에 볼 질문은 다음과 같습니다.

#### 발행·병합 전에 사람이 대조할 질문

* [ ] 원문 요구를 R1·R2처럼 나눴고 각 요구의 수용 조건을 적었나요?
* [ ] `image` 전체의 필드·중첩·배열·`null`·누락·미지 필드 정책을 합의했나요?
* [ ] 테스트 기대값이 구현 DTO나 실제 출력에서 복사된 것은 아닌가요?
* [ ] 저장 성공이라는 말에 DB 재조회 증거가 포함되나요? 같은 객체나 영속성 컨텍스트만 본 것은 아닌가요?
* [ ] 필드 하나를 지우거나 값 하나를 바꾸면 관련 검사가 실패하나요?
* [ ] 공개 URL의 인증 여부를 계약과 실제 응답으로 확인했나요? 실패를 기본값으로 감추지 않나요?
* [ ] 결과 정규화가 필드를 삭제한다면 그 변환을 허용한 요구 근거가 있나요?
* [ ] 요구 ID마다 구현 위치, 테스트 이름, 명령, 통과·실패·미실행을 연결했나요?

[engineering-standards의 공통 원칙](https://github.com/kdkrkwhr/engineering-standards/blob/0ab26dea2102fbb801740ba9f853a004761b7e0c/standards/core.md)은 요구와 수용 조건을 먼저 확인하고 테스트 기대값을 구현과 독립적으로 두라고 합니다. [구현 지침](https://github.com/kdkrkwhr/engineering-standards/blob/0ab26dea2102fbb801740ba9f853a004761b7e0c/agents/implementation.md)도 요구→파일·심볼→검증 결과를 연결하게 합니다. 이 글의 예제는 그 원칙이 왜 필요한지를 한 가지 누락으로 좁혀 본 것입니다. 모든 프로젝트에 JSON 컬럼이나 동일한 테스트 프레임워크를 강제하는 이야기는 아닙니다.

----

### 마치며

AI가 구현을 돕고 테스트도 만들 수 있습니다. 하지만 둘이 같은 해석에 기대면 결과가 정교해 보여도 원문의 한 문장을 놓칠 수 있습니다. 그래서 먼저 요구를 쪼개고, **구현 밖에서 기대값을 고정하고, 실제 저장과 읽기 경계에서 다시 대조**하려 합니다.

이번 H2 예제는 투영 누락을 fixture가 잡고 전체 JSON이 새 연결의 조회까지 유지되는지만 확인했습니다. 실제 외부 API, JPA, 운영 DB, 당시 작업의 테스트 결과까지 보증하지는 않습니다. 검증하지 않은 범위를 그렇게 적어두는 것 역시 요구사항을 놓치지 않는 과정의 일부라고 생각합니다.
