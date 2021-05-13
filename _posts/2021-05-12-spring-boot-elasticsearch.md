---
layout: post
author: Kim, DongKi
title:  "Spring-Boot Elasticsearch 적용"
date:   2021-05-12
categories: Technology
comments: true
---

----
### TL;DR

----
#### E.L.K 란 ? 

- 'E'lasticsearch
  - 모든 유형 데이터를 지원하고, 분산형 특징을 가진 검색 엔진
- 'L'ogstash
  - 오픈소스로 제공되는 로그 수집기
- 'K'ibana
  - Elasticsearch를 지원하는 데이터 시각화 툴 (그래프, 표, 지도 등 다양한 시각화 기능 제공)

----
### 들어가며

이번 포스팅은 Elasticsearch stack을 Spring Boot 프로젝트에서 직접 적용을 하는 방법을 주제로 해봤습니다. 
ELK S/W 설치는 아래 공식 사이트 설치 페이지에서 받으실 수 있습니다

- [Elasticsearch](https://www.elastic.co/kr/downloads/elasticsearch)
- [Logstash](https://www.elastic.co/kr/downloads/logstash)
- [Kibana](https://www.elastic.co/kr/downloads/kibana)

----
#### Environment

- Spring Boot
- Elasticsearch v7.x
- Logstash v7.x
- Kibana v7.x

----
### 프로젝트 log 수집하기

- 첫번째로는 프로젝트 내 로그를 수집해 Kibana화면에서 표출을 진행하겠습니다.

----
#### logstash 설정

[logstash.conf]

```
# [Beats input plugin]
# listen on port 5044 for incoming Beats connections
input {
  tcp {
    port => 4560
    codec => json_lines
  }
}

# [Elasticsearch output plugin]
# index into Elasticsearch
output {
  elasticsearch {
    hosts => "localhost:9200"
    manage_template => false
    index => "springboot-elk" 
  }
}
```

- 위 같이, Input, Output 설정이 되었다면, 이제 Spring Boot 프로젝트 내 설정만 추가를 하면 됩니다.

----
#### logstash logback 패키지 빌드

[build.gradle]

```gradle
dependencies {

  ...

  implementation group: 'net.logstash.logback', name: 'logstash-logback-encoder', version: '4.11'

  ...

}

```

----
#### Spring Boot 로그 Appender 추가

[logback.xml]

```

...
  <appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
    <destination>127.0.0.1:4560</destination> 
    <encoder class="net.logstash.logback.encoder.LogstashEncoder"></encoder>
  </appender>

  <root level="DEBUG">
    <appender-ref ref="LOGSTASH" />
  </root>
...

```

- 여기까지 완료가 됬다면 로그 수집은 정상적으로 처리가 되고 있을 것 입니다.

----
#### Kibana 화면 (로그 수집)

![2021-05-12-spring-boot-elasticsearch-1](/assets/2021-05-12-spring-boot-elasticsearch-1.jpg)

- 위 spring boot 내 수집한 로그를 Kibana에서 분석 표출한 결과 화면 입니다.

----
### Elasticsearch Data Mapping

- 이번에는 Elasticsearch를 이용하여 데이터 매핑 처리를 하는 내용 입니다.

----
#### 관련 패키지 빌드

[build.gradle]

```gradle
dependencies {

  ...

  implementation group: 'org.springframework.data', name: 'spring-data-elasticsearch', version: '4.1.3'

  ...

}
```

----
#### Elasticsearch 설정

[EsConfig.java]

```java

@Configuration
public class EsConfig extends AbstractElasticsearchConfiguration {

  @Override
  @Bean
  public RestHighLevelClient elasticsearchClient() {
    final ClientConfiguration clientConfiguration =
        ClientConfiguration.builder().connectedTo("localhost:9200").build();
    return RestClients.create(clientConfiguration).rest();
  }
}

```

----
#### 매핑 Index Object 생성

[Position.java]

```java

@Setter
@Getter
@Builder
@Document(indexName = "position")
public class Position {

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE)
  private Long id;

  @Field(type = FieldType.Text)
  private String userId;

  @Field(type = FieldType.Double)
  private Double lon;

  @Field(type = FieldType.Double)
  private Double lat;

  @Field(type = FieldType.Date)
  private Date logDate;

  public PositionResponseDto toResponseDto(Position position) {
    return PositionResponseDto.builder().userId(position.getUserId()).lon(position.getLon()).lat(position.getLat()).build();
  }
}

```

----
#### Elasticsearch Repository 생성

[PositionEsRepository.java]

```java

@Repository
public interface PositionEsRepository extends ElasticsearchRepository<Position, String> {}

```

----
#### Request, Response Oject 생성

[PositionRequestDto.java]

```java

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class PositionRequestDto {

  @NotBlank(message = "사용자 아이디를 알려주세요.")
  private String userId;

  @NotBlank(message = "경도 값 을 입력하세요.")
  private Double lon;

  @NotBlank(message = "위도 값 을 입력하세요.")
  private Double lat;
}

```

[PositionResponseDto.java]

```java

@Getter
@Builder
@Document(indexName = "position")
public class PositionResponseDto {

  private String userId;
  private Double lon;
  private Double lat;
}

```

----
#### Elasticsearch 활용 예시 (데이터 수집, 데이터 조회)

[PositionService.java]

```java

@Service
public class PositionService {

  @Autowired
  private PositionEsRepository repository;

  @Autowired
  private ElasticsearchRestTemplate esTemplate;

  public Map<String, Object> insertPositionData(PositionRequestDto position) {
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    long resultCode;

    try {

      repository.save(Position.builder().userId(position.getUserId()).lon(position.getLon())
          .lat(position.getLat()).logDate(new Date()).build());

      resultCode = CommonConstant.ResponseUtil.API_RESULT_CODE_SUCC;

    } catch (Exception e) {
      resultCode = CommonConstant.ResponseUtil.API_RESULT_CODE_FAIL;
      throw new SavingsException("");
    }

    result.put(CommonConstant.ResponseUtil.API_RESULT_CODE_KEY, resultCode);
    return result;
  }
  
  public SearchHits<PositionResponseDto> selectPositionData(String userId) {
    Query searchQuery = new NativeSearchQueryBuilder()
        .withQuery(QueryBuilders.matchQuery("userId", userId))
        .build();

    return esTemplate.search(searchQuery, PositionResponseDto.class);
  }
}

```

[PositionRestController.java]

```java

@Api(value = "PositionRestController")
@RestController
@RequestMapping("/api/position")
public class PositionRestController {

  @Autowired
  private PositionService service;

  @ApiOperation(value = "위치 데이터 추가", tags = "위치 데이터")
  @RequestMapping(value = "/insert", method = RequestMethod.POST)
  public ResponseEntity<Map<String, Object>> insertPositionData(@RequestBody PositionRequestDto position) {
    return new ResponseEntity<>(service.insertPositionData(position), HttpStatus.OK);
  }

  @ApiOperation(value = "위치 데이터 조회", tags = "위치 데이터")
  @RequestMapping(value = "/select", method = RequestMethod.GET)
  public ResponseEntity<?> selectPositionData(HttpServletRequest request) {
    String userId = request.getParameter("userId") == null ? "" : request.getParameter("userId");
    return new ResponseEntity<>(service.selectPositionData(userId), HttpStatus.OK);
  }
}

```

----
#### Kibana 결과 화면 (매핑 데이터 수집)

![2021-05-12-spring-boot-elasticsearch-2](/assets/2021-05-12-spring-boot-elasticsearch-2.jpg)

- 위 spring boot 내 API를 이용하여 수집한 데이터를 Kibana에서 분석 표출한 결과 화면 입니다.

----
#### Postman API 조회 결과 화면 (매핑 데이터 조회)

![2021-05-12-spring-boot-elasticsearch-3](/assets/2021-05-12-spring-boot-elasticsearch-3.jpg)

- 위 spring boot 내 API를 이용하여 수집한 데이터를 Kibana에서 분석 표출한 결과 화면 입니다.

----
#### 마지막으로,, 

음,, 오랜만에 블로그를 작성한 것이라 어색하긴 하네요. 
감사합니다.
