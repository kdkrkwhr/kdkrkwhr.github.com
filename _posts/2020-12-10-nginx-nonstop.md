---
layout: post
author: Kim, DongKi
title:  "Nginx를 활용한 무중단 배포 환경 설정하기"
date:   2020-12-10
categories: devOps
comments: true
---

### TL;DR

* 무중단 배포 (non-stop deploy) :: 서비스가 개선/수정되어 재배포 됨에 있어 서비스가 중지되지 않는 기술.

### 들어가며 ,,, 

이번에 프로젝트를 진행하며 접해 본 Nginx 를 이용한 무중단 배포 기술을 주제로 선정 해 보았습니다. 

적용을 하게 된 계기는 장비에서 매 분을 주기로 데이터를 전송하는 API에 있어 중단이 생겨 데이터 유실이 있어서는 안되기 때문입니다.

물론, 중간에 소스 수정을 하거나 변경이 되어 재배포를 할 이유 없이 처음부터 완벽한 설계/개발이 된다면 이러한 기술이 등장할 필요는 없었을 것 입니다.
하지만 그럴 일 은 확률상 적다고 판단하며 ,,,

적용해 본 결과 어렵지 않고 자주 사용을 할 기술이라 생각이 되어 접하게 되어 기술공유 목적으로 블로그 첫번째 포스팅 주제로 선택을 하게 되었습니다. 

### 서버 환경

* OS :: CentOS v7.x
* WEB Server :: Nginx v1.8.x
* F.W :: Spring-boot

![2020-12-10-nginx-non-stop-1](/assets/2020-12-10-nginx-non-stop-1.jpg)


### 배포 파일 준비

저는 배포를 Spring-boot를 이용하여 jar파일로 배포를 할 생각이기에 Spring-boot 기반 프로젝트를 우선 추가하도록 하겠습니다.

* Spring-boot 프로젝트 생성

![2020-12-10-nginx-non-stop-2](/assets/2020-12-10-nginx-non-stop-2.jpg)

* 프로젝트를 두 개 생성할 수 있으나 기껏 Spring-boot를 사용하고 활용 안하는건 개발자스럽지 못한 듯 하니 설정 파일인 profile 파일을 이용하도록 하겠습니다.

```conf
---
# 1번 서버
spring:
  profiles: prod1
server:
  port: 8080

---
# 2번 서버
spring:
  profiles: prod2
server:
  port: 8081
```
이제 위 프로젝트는 profile만 선택하여 활성화 해준다면 활성화된 profile대로 port가 변경이 되어 실행이 될 것 입니다.

### Nginx 설치

* 외부 저장소 추가
  * vi /etc/yum.repos.d/nginx.repo

```bash
[nginx.repo]

name=nginx repo
baseurl=http://nginx.org/packages/centos/7/$basearch/
gpgcheck=0
enabled=1
```

* yum install

```bash
$ yum install -y nginx
```

* Nginx 기본 명령어

```bash
// 버전(설치) 확인
$ ngnix -v
// 시작
$ systemctl start nginx
// 중지
$ systemctl stop nginx
// 재시작
$ systemctl reload nginx
```

### Nginx 설정

* 외부에서 Nginx를 바라보는 포트

```conf
[/etc/nginx/conf.d/default.conf]

server {

    listen       80;
    server_name  localhost;

    ... 

    include /etc/nginx/conf.d/service-url.inc;

    ...
}
```

* Port 변경 이용 include 파일 추가
 * vi /etc/nginx/conf.d/service-url.inc

```conf
[service-url.inc]

set $service_url http://localhost:8081
```

### 활성화 된 Profile 조회 RESTFull API 개발

```java

/**
 * @ FileName : ProfileRestController.java
 * @ document : 현재 구동중인 Profile check .
 */
@RestController
public class ProfileRestController {

  @Autowired
  private Environment environment;

  @RequestMapping(value = "/profile", method = RequestMethod.GET)
  public String profile() {
      return Arrays.asList(environment.getActiveProfiles()).stream().findFirst().orElse("");
  }
}

```

### jar 파일 실행

* build 생성 된 jar파일을 실행 . (build 과정 생략)

```bash
// prod1 profile 서비스 실행
java -jar -Dspring.profiles.active=prod1 ./kdk-dev.jar

// prod2 profile 서비스 실행
java -jar -Dspring.profiles.active=prod2 ./kdk-dev.jar
```
* 주의 :: 무중단 배포는, 항시 서비스 두개 중 하나는 실행 상태를 유지 하여야 합니다.


### 무중단 배포 과정

* 위 만든 /profile API를 이용하여 현재 활성화 된 profile 확인.

![2020-12-10-nginx-non-stop-3](/assets/2020-12-10-nginx-non-stop-3.jpg)

1. 수전된 버전으로 jar 파일 교체
2. conf 파일 수정
```bash
[/etc/nginx/conf.d/service-url.inc]
set $service_url http://localhost:8080
```
3. Nginx 재시작
```bash
$ systemctl reload nginx
```

### 결과

성공적으로 위 과정대로 진행이 되었다면 /profile API 호출 시 아래와 같이 "prod1" 으로 변경 된 결과가 출력된 것 을 확인 할 수 있습니다.

![2020-12-10-nginx-non-stop-4](/assets/2020-12-10-nginx-non-stop-4.jpg)

### 맺으며

첫 블로그 작성이라 많이 부족해 보이나, 끝까지 봐주셔서 감사합니다.
앞으로도 제가 습득/사용 한 기술지식을 남길 수 있도록 하겠습니다.



### References

* [Non-stop Deploy](https://perfectacle.github.io/2019/04/21/non-stop-deployment)
* [Nginx install](https://kscory.com/dev/nginx/install)
* [Nginx conf](https://opentutorials.org/module/384/4526)