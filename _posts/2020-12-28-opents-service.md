---
layout: post
author: Kim, DongKi
title:  "OpenTSDB 기반 서비스 개발하기"
date:   2020-12-28
categories: Development
comments: true
---

### 들어가며

이번 시간에는 이전 포스팅에서 소개한 OpenTSDB를 활용한 서비스를 만들어 보겠습니다.
어떤 서비스를 만들 수 있을까 고민을 해봤는데요,,,
일단 공공데이터 포털에 들어가 실시간 활용 API를 찿아봤습니다. 

이번에 이용하게된 "Open API는 한국공항공사_전국공항 주차장 혼잡도 API" 입니다. 

----
### Environment

- Spring Boot
- OpenTSDB

----
### Development

#### Open API Data

![2020-12-28-opents-service-1](/assets/2020-12-28-opents-service-1.jpg)

- 위 이미지와 같이 공항 코드별로 조회가 가능하고, 조회한 Response data는 주차장별로 분류가 되어 있습니다.
- 이를 분류하기 위해 저는 정적데이터라고 판단한 주차장 데이터를 분류하기 위해 json 파일을 추가 하여 주었습니다.

[airport_parking_data.json]

```
{
	"datas" : [
		{
			"airportName" : "김포국제공항",
			"airportCode" : "GMP",
			"parkings" [
				{
					"pName" : "[상세]예약주차장",
					"pCode" : "001"
				},
				{
					"pName" : "국내선 제1주차장",
					"pCode" : "002"
				},
				{
					"pName" : "국내선 제2주차장(일반/예약)",
					"pCode" : "003"
				},
				{
					"pName" : "국제선주차장",
					"pCode" : "004"
				},
				{
					"pName" : "화물청사 주차장",
					"pCode" : "005"
				}
			]
		},
        
        ,,,,

```

#### BackEnd source

```java
@Component
public class MainScheduler {

  static final Logger logger = LoggerFactory.getLogger(MainScheduler.class);

  @Autowired
  private RealTimeService service;

  @Scheduled(fixedDelay = 1000 * 60)
  public void minuteInsertDataScheduled() throws Exception {
    logger.debug("=== START :: minuteInsertDataScheduled ===");
    logger.debug("Result :: {}", service.minuteScheduleF(System.currentTimeMillis()));
    logger.debug("=== END :: minuteInsertDataScheduled ===");
  }
}
```

- OpenAPI를 호출하여 TSDB 내 1분 주기 실시간 데이터 수집을 진행하기 위한 스케쥴러 입니다.
- service class의 minuteScheduleF()를 분 단위로 호출을 하고 있습니다.

---
```java
  public int minuteScheduleF(long timestamp) {
    int result = 0;

    try {

      JSONObject tags = new JSONObject();
      tags.put("host", "kdk");

      JSONArray datas = getAirportJsonArrayDatas();

      for (int i = 0; i < datas.size(); i++) {
        JSONObject data = (JSONObject) ((JSONObject) datas.get(i));
        List<Map<String, Object>> apiDatas = airportApiCall(data.get("airportCode").toString());

        for (Map<String, Object> apiData : apiDatas) {
          String parkingName = apiData.get("parkingAirportCodeName").toString();
          System.out.println("parkingName :: " + parkingName);
          String metric = "kdk." + data.get("airportCode") + "";
          result =+ openTSDataInsert(metric, timestamp, Integer.parseInt(apiData.get("parkingOccupiedSpace").toString()), tags);
        }
      }

    } catch(Exception e) {
      result = 0;
    }

    return result;
  }
}
```

- 1분을 주기로 scheduler에서 호출하는 함수 입니다. (main 역할)

---
```java
  public JSONArray getAirportJsonArrayDatas() throws Exception {
    JSONParser jsonParser = new JSONParser();
    ClassPathResource resource = new ClassPathResource("static/json/airport_parking_data.json");
    
    Object obj = jsonParser.parse(new FileReader(resource.getFile()));
    JSONObject jsonObject = (JSONObject) obj;
    JSONArray datas = (JSONArray) jsonObject.get("datas");

    return datas;
  }
```

- 앞서 만들었던 JSON 파일에서 정적 데이터를 호출하도록 작성된 함수 입니다.

---
```java
  @Value("${open-api.key}")
  private String apiKey;

  public List<Map<String, Object>> airportApiCall(String airportCode) throws Exception {
    String url = CommonConstant.OPEN_API_DOMAIN + CommonConstant.OPEN_API_TYPE 
        + "?serviceKey=" + apiKey + "&schAirportCode=" + airportCode;

    DocumentBuilderFactory dbFactoty = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactoty.newDocumentBuilder();
    Document doc = dBuilder.parse(url);

    NodeList nList = doc.getElementsByTagName("item");

    List<Map<String, Object>> dataList = new ArrayList<Map<String, Object>>();

    for (int temp = 0; temp < nList.getLength(); temp++) {
      Node nNode = nList.item(temp);
      if (nNode.getNodeType() == Node.ELEMENT_NODE) {
        Map<String, Object> dataMap = new LinkedHashMap<String, Object>();

        Element eElement = (Element) nNode;
        dataMap.put("parkingAirportCodeName", getTagValue("parkingAirportCodeName", eElement)); // 주차장명
        dataMap.put("parkingTotalSpace", getTagValue("parkingTotalSpace", eElement)); // 전체 주차면 수
        dataMap.put("parkingOccupiedSpace", getTagValue("parkingOccupiedSpace", eElement)); // 입고된 차량 수

        dataList.add(dataMap);
      }
    }

    return dataList;
  }

```

- Open API를 호출하여 data가져올 함수 입니다.

---
```java

  private static String getTagValue(String tag, Element eElement) {
    NodeList nlList = eElement.getElementsByTagName(tag).item(0).getChildNodes();
    Node nValue = (Node) nlList.item(0);
    if (nValue == null)
      return null;
    return nValue.getNodeValue();
  }

```

- Open API로 조회한 XML Tag를 분류하기 위한 함수 입니다.

---
```java
  public int openTSDataInsert(String metric, long timestamp, int value, JSONObject tags) throws Exception {
    RestTemplate restTemplate = new RestTemplate();
    URI url = URI.create(CommonConstant.TSDB_DOMAIN + CommonConstant.TSDB_TYPE_PUT + "?details");

    JSONObject jsonReq = new JSONObject();
    jsonReq.put("metric", metric);
    jsonReq.put("timestamp", timestamp);
    jsonReq.put("value", value);
    jsonReq.put("tags", tags);

    HttpHeaders headers = new HttpHeaders();
    headers.add("Accept", "*/*");

    HttpEntity<String> entity = new HttpEntity<String>(jsonReq.toString(), headers);
    ResponseEntity<String> res = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

    return (res.getStatusCode() == HttpStatus.OK) ? 1 : 0;
  }
```

- TSDB에 가공된 데이터를 최종적으로 입력해주는 함수 입니다.

### 결과 확인

![2020-12-28-opents-service-2](/assets/2020-12-28-opents-service-2.jpg)

- 데이터 확인 결과 공항코드로 분류된 metric에 데이터가 수집이 되는게 확인 됩니다.
- 다음 포스팅은 위 실시간 주차장 데이터를 이용한 서비스를 한번 구현해 보도록 하겠습니다. (아이디어 부족,,,)


전체 소스는 [GitHub](https://github.com/kdkrkwhr/airport-parking) 에서 확인 가능 합니다.
