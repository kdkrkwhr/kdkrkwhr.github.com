---
layout: post
author: Kim, DongKi
title:  "FCM 서비스 이용, 푸시 서버 개발하기"
date:   2022-01-20
categories: Technology
comments: true
---

### TL;DR

안녕하세요, 이번 포스팅 주제로 Push 서버를 다뤄보려 합니다. 😊

----
### FCM(Firebase Cloud Messaging) 이란 ?

- Google에서 제공하는 클라우드 메시징 서비스 플랫폼 입니다.
- 무료로 서비스를 사용할 수 있으며, 안정적인 서비스를 제공하기에 많은 기업과 개인에서 푸시 서버를 구축 시 선택하여 사용하는 서비스 입니다.


### Google Firebase 플랫폼

#### FCM 서비스 세팅

**[Google Firebase Platform](https://firebase.google.com)**

1) 위 Firebase 접속 및 로그인 (Google 계정)

2) Firebase 프로젝트 생성

3) 프로젝트 설정, 클라우드 메시징 메뉴 내 **"서버 키"** 저장 보관 
   + (서버 사이드에서 FCM API 인증 시 이용)

4) 프로젝트 설정, 서비스 계정 메뉴 내 **"새 비공개 키 생성"** 저장 보관 
   + (클라이언트 영역에서 이용)


### Push Server 구성

#### Push API 개발

[build.gradle]
```gradle

dependencies {
   ...
   compile group: 'com.google.firebase', name: 'firebase-admin', version: '6.8.1'
   implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
   ...
}
```

[FcmSendRestApiController.java]
```java
@RestController
@RequestMapping("/api")
public class FcmSendRestApiController {

  static final Logger logger = LoggerFactory.getLogger(FcmSendRestApiController.class);

  @Autowired
  private FcmServerService fcmService;

  @RequestMapping(value = "/send/multi", method = RequestMethod.POST)
  public @ResponseBody ResponseEntity<Object> sendMultiDevice(@RequestBody FcmRequestDto req)
      throws Exception {
    HashMap<String, Object > responseMp = new HashMap<String, Object>();
    long resultCode;

    String serviceKey = req.getServiceKey() == null ? "" : req.getServiceKey();
    String[] tokens = req.getTokens().length == 0 ? new String[0] : req.getTokens();
    String title = req.getTitle() == null ? "" : req.getTitle();
    String message = req.getMessage() == null ? "" : req.getMessage();
    String notifications;

    notifications = FcmDataUtil.pushMultiDataProcessing(tokens, title, message);

    HttpHeaders headers = new HttpHeaders();
    MediaType mediaType = new MediaType("application", "json", StandardCharsets.UTF_8);
    headers.setContentType(mediaType);

    HttpEntity<String> request = new HttpEntity<>(notifications, headers);

    CompletableFuture<HashMap<String, Object>> pushNotification = fcmService.send(request, serviceKey);
    CompletableFuture.allOf(pushNotification).join();

    try {

      logger.debug("request :: {}", request);
      Object firebaseResponse = pushNotification.get();
      responseMp.put("fcmResponse", firebaseResponse);
      resultCode = CommonConstant.ResponseUtil.API_RESULT_CODE_SUCC;

    } catch (InterruptedException e) {
      responseMp.put("errorMessage", e.getMessage());
      resultCode = CommonConstant.ResponseUtil.API_RESULT_CODE_FAIL;

    } catch (ExecutionException e) {
      responseMp.put("errorMessage", e.getMessage());
      resultCode = CommonConstant.ResponseUtil.API_RESULT_CODE_FAIL;
    }

    responseMp.put(CommonConstant.ResponseUtil.API_RESULT_CODE_KEY, resultCode);
    return new ResponseEntity<>(responseMp, HttpStatus.OK);
  }
}
```
[BundleFcmRequestDto.java]
```java
@Getter
@Setter
@ToString
public class BundleFcmRequestDto {

  private List<FcmRequestDto> fcmBody;
  private String serviceKey;

}
```

[FcmRequestDto.java]
```java
@Getter
@Setter
@ToString
public class FcmRequestDto {

  private String serviceKey;
  private String token;
  private String[] tokens;
  private String title;
  private String message;

}
```

[FcmDataUtil.java]
```java
public class FcmDataUtil {

  public static String pushMultiDataProcessing(String[] tokens, String title, String message)
      throws JSONException, UnsupportedEncodingException {
    JSONObject body = new JSONObject();
    JSONArray array = new JSONArray();

    for (String token : tokens) array.put(token);

    body.put("registration_ids", array);
    body.put("content_available", true);

    JSONObject notification = new JSONObject();
    notification.put("title", title);
    notification.put("body", message);

    body.put("notification", notification);

    return body.toString();
  }
}
```

[FcmServerService.java]
```java
@Service
public class FcmServerService {

  @Async
  public CompletableFuture<HashMap<String, Object>> send(HttpEntity<String> entity, String serviceKey) {
    RestTemplate restTemplate = new RestTemplate();
    ArrayList<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();

    interceptors.add(new HeaderRequestInterceptor("Authorization", "key=".concat(serviceKey)));
    interceptors.add(new HeaderRequestInterceptor("Content-Type", "application/json; UTF-8"));
    restTemplate.setInterceptors(interceptors);

    @SuppressWarnings("unchecked")
    HashMap<String, Object> firebaseResponse = restTemplate.postForObject(CommonConstant.FIREBASE_API_URL, entity, HashMap.class);

    return CompletableFuture.completedFuture(firebaseResponse);
  }
}
```

- 위 내용들로 FCM 서버 인증 Key 및 알림 메세지등을 담아 푸시를 전송 표현할 API가 개발되었습니다.

#### 전송 API 규격
```
# 푸시 전송 API

- url
 + (POST) /api/send/multi
- RequestType
 + JSON
- Parameter
 + serviceKey
  :: FCM 서비스 인증 Key
 + title
  :: 푸시 제목
 + message
  :: 푸시 내용
 + tokens (배열)
  :: 알림 받을 클라이언트 Token 값
```

![2022-01-20-push-server-02.jpg](/assets/2022-01-20-push-server-2.jpg)

#### 테스트용 화면 구현

[index.html]
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
	xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout">
<head>
	<script src="https://www.gstatic.com/firebasejs/6.5.0/firebase.js"></script>
	<script src="https://www.gstatic.com/firebasejs/6.5.0/firebase-app.js"></script>
	<script src="https://www.gstatic.com/firebasejs/6.5.0/firebase-messaging.js"></script>
	<script th:inline="javascript">
		const firebaseConfig = {
			  #{firebaseConfig}
			};

	    firebase.initializeApp(firebaseConfig);

	    const messaging = firebase.messaging();
	    messaging.requestPermission()
	        .then(function() {
	            return messaging.getToken();
	        })
	        .then(async function(token) {
	        	console.log(token);
	            messaging.onMessage(payload => {
	                console.log(payload);
	            })
	        })
	</script>
</head>
<body style="background: azure; text-align: center;">
	<h2>FCM - TEST</h2>
</body>
</html>
```
#### 클라이언트 Token 값 확인

![2022-01-20-push-server-05](/assets/2022-01-20-push-server-5.jpg)

### 결과 테스트

![2022-01-20-push-server-03](/assets/2022-01-20-push-server-3.jpg)
![2022-01-20-push-server-01](/assets/2022-01-20-push-server-1.jpg)
![2022-01-20-push-server-04](/assets/2022-01-20-push-server-4.jpg)

### Ending

이로써 푸시 서비스를 간단하게 구성해 봤햣습니다 :)
푸시 서버를 구성함에 있어, 클라이언트에서 발급한 토큰 값 을 어떻게 관리하느냐가 경험상 주요포인트가 될 것이라고 보며, 이는 고민을 많이해야하는 부분이라고 생각합니다. 

Thanks .