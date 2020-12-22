---
layout: post
author: Kim, DongKi
title:  "OpenTSDB 이용하기 (with, Docker)"
date:   2020-12-18
categories: devOps
comments: true
---

### TL;DR

#### OpenTSDB 란?
* 시간과 값을 하나의 세트로 데이터를 저장하고 서비스하는데 최적화 된 시계열 DB중 하나입니다.
* OpenTSDB는 주로 IoT 서비스를 구현하는데 이용이 되고, Hbase 위에서 구동이 됩니다.

![2020-12-18-docker-opents-1](/assets/2020-12-18-docker-opents-1.jpg)

#### Docker 란?
* Docker 란 간단히 컨테이너 기반의 오픈소스 가상화 플랫폼 입니다.
* 다양한 프로그램, 실행환경을 컨테이너로 추상화하고 동일한 인터페이스를 제공하여 프로그램의 배포 및 관리를 단순하게 해줍니다.

![2020-12-18-docker-opents-6](/assets/2020-12-18-docker-opents-6.jpg)

---
### 들어가며

안녕하세요, 김동기 입니다.
오늘은 NoSQL DB중 하나인 OpenTSDB를 포스팅 주제로 선정 하였습니다.

OpenTSDB 기반 API를 경험해 본 결과 정말 많은 양의 데이터를 빠르게 처리할 수 있었으며, 또한 서비스에 필요한 API도 다수 제공을 해주어 좋았던 경험이 있었기에 공유하고자 하는 목적으로 포스팅 주제로 선정 하였습니다.

본 포스팅은 Docker 컨테이너 이용 기준입니다. 
Docker를 사용한 이유는 OpenTSDB를 세팅/설치 하는데 많은 설정(Hbase install 등)이 필요하지만,, Docker 를 이용한다면 그런 많은 작업이 필요없이 이미 세팅완료된 이미지만 찿아와 컨테이너에 올려 사용하면 되기에 저는 Docker를 이용하였습니다.

( 제가 고래를 좋아하여 블로그 캐릭터도 고래를 디자인하여 사용하는데 Docker 메인 캐릭터도 고래라니 정말 정감이 가네요,,, )

![2020-12-18-docker-opents-2](/assets/2020-12-18-docker-opents-2.jpg)

----
### 서버 환경

* OS :: Window 10
* Use Platform :: Docker

----
### Docker 설치

* 제어판 > 프로그램 설치 및 제거 > Window 기능 켜기/끄기 클릭 > Hyper-V 체크 확인 후 리부팅
![2020-12-18-docker-opents-7](/assets/2020-12-18-docker-opents-7.jpg)

* [Docker](https://hub.docker.com/editions/community/docker-ce-desktop-windows/) 설치

* Docker 설치 완료 => Docker 계정이 없다면 회원가입 => 로그인
![2020-12-18-docker-opents-3](/assets/2020-12-18-docker-opents-3.jpg)

* 아래 같이 나온다면 Docker 설치 완료 상태

```bash
$ docker -v
Docker version 20.10.0, build 7287ab3

$ docker-compose -v
docker-compose version 1.27.4, build 40524192
```

----
### OpenTSDB 설치
 
* [Docker Hub](https://hub.docker.com/r/petergrace/opentsdb-docker) 접속

* OpenTSDB를 Docker Hub에서 검색하면 다수의 Hub들이 존재하나, 저는 petergrace/opentsdb-docker로 받도록 하겠습니다.
![2020-12-18-docker-opents-4](/assets/2020-12-18-docker-opents-4.jpg)

* Window CMD 창을 켠 후 OpenTSDB를 받을 폴더 생성 => 이동

* 아래 같이 OpenTSDB 설치

```bash
$ git clone https://github.com/jlferrer/opentsdb-docker.git
$ cd opentsdb-docker
$ docker-compose up -d
```

* 설치 확인

```bash
$ docker image ls
REPOSITORY                   TAG       IMAGE ID       CREATED       SIZE
petergrace/opentsdb-docker   latest    e27e35699c3f   2 weeks ago   683MB
```

* [http://localhost:4242](http://localhost:4242) 로 접속이 된다면 성공
![2020-12-18-docker-opents-5](/assets/2020-12-18-docker-opents-5.jpg)

저 같은 경우 OpenTSDB 기본포트인 4242 Port를 이용하지않고 7777 port로 변경을 하였습니다

```
[docker-compose.yml]
---
opentsdb:
  hostname: otsdb-host
  image: petergrace/opentsdb-docker:latest
  environment:
    - WAITSECS=30    
  ports:
    - 7777:4242 // 기본 4242 => 7777 변경
    - 60030:60030
  volumes:  
    - "./data:/data/hbase"
```

----
####  OpenTSDB 제공 API Example

**1. 데이터 추가 API**
- Url 
  + http://localhost:7777/api/put?details
- Method
  + POST
- Parameter
  + Type :: JSON
	```
	{
		"metric":  "kdk.test",
		"timestamp":  1608179650,
		"value":  11,
		"tags":  {
			"host":  "kdk1"
		}
	}
	```
- Response
  + Type :: JSON
	```
	{
		"success":  1,
		"failed":  0,
		"errors":  []
	}
	```

---

**2. 데이터 조회 API**
- Url 
  + http://localhost:7777/api/query?start=1D-ago&m=sum:kdk.test
- Method
  + GET
- Parameter
  + start (Ex, 1D-ago)
  + m (Ex, sum:kdk.test)
- Response
  + Type :: JSON
	```
  [
      {
          "metric": "kdk.test",
          "tags": {},
          "aggregateTags": [
              "host"
          ],
          "dps": {
              "1608179620": 20,
              "1608179630": 20,
              "1608179640": 29,
              "1608179650": 11,
              "1608179660": 18,
              "1608255494": 10
          }
      }
  ]
	```


* 그 외 관련 API에 대한 정보 입니다.
 [https://runebook.dev/ko/docs/opentsdb/api_http/query/index](https://runebook.dev/ko/docs/opentsdb/api_http/query/index)


---
### 맺으며

* 오늘은 Docker 컨테이너 위 OpenTSDB를 설치하는 것 까지가 완료가 되었네요, 다음 포스팅은 OpenTSDB 에서 제공한 서비스 API 를 이용하여 Spring Boot에서 이용을 해볼까 합니다.


**감사합니다.**
