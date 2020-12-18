---
layout: post
author: Kim, DongKi
title:  "프로젝트에 Jenkins 적용하기"
date:   2020-12-14
categories: devOps
comments: true
---

### TL;DR

#### Jenkins 란?
* 젠킨스는 소프트웨어 개발 시 지속적으로 통합 서비스를 제공하는 툴이다. CI(Continuous Integration) 툴 이라고 표현한다.
* 다수의 개발자들이 하나의 프로그램을 개발할 때 버전 충돌을 방지하기 위해 각자 작업한 내용을 공유영역에 있는 저장소에 빈번히 업로드함으로써 지속적 통합이 가능하도록 해준다.

----
### 들어가며

이번에 많은 IT 기업에서 자동 빌드 & 배포 툴 로써 이용을 하는 Jenkin를 주제로 포스팅을 해보았습니다.
 포스팅 주제로 선정한 이유는 이전 "[Nginx를 활용한 무중단 배포 환경 설정하기](https://kdkrkwhr.github.io/devops/2020/12/10/nginx-nonstop.html)" 와 관련된 포스팅을 진행함에 있어 Nginx 와 같이 사용하면 괜찮겠다 싶어 선정을 하게되었습니다. 

Jenkins 는 이전에 적용을 해보고 관련 설정을 자세히 다룬 경험이 적어, 포스팅을 작성하는 동안 조금 해매긴 하였습니다,,, 
하지만 이번 포스팅을 다루는 동안 정리하는 시간을 가지게 되어 다행이라고 생각이 됩니다.

![2020-12-14-jenkins-install-1](/assets/2020-12-14-jenkins-install-1.jpg)

----
### 서버 환경

* OS :: CentOS v7.x
* F.W :: Spring-boot v2.3.x
* Build :: Gradle v6.7
* SVC :: GitHub

----
### Jekins 설치

1. 적용할 서버 구성 환경에 맞는 Jenkins 설치

2. JENKINS_PORT 변경 (기존 지정 된 8080 Port의 경우 충돌 위험이 있음)

3. 변경한 Port 방화벽 Open

4. http://{ServerHost}:{변경 Port}/ 접근

5. */jenkins/secrets/initialAdminPassword 경로 내 임시 비밀번호 확인

6. (Install suggested plugins) 선택 하여 Plugin 설치

7. 관리자 계정 생성  

----


위 같은 Jenkins 설치 및 기본 환경설정 작업이 필요하나, 포스팅에 추가를 하진 않겠습니다.

대신, Jenkins 설치와 관련하여 정리가 잘 되어 있는 포스팅을 소개드리겠습니다.

[( 갓대희-Jenkins 설치 )](https://goddaehee.tistory.com/82)

----
### 테스트 프로젝트 준비

* 프로젝트 생성 (spring boot 환경)

![2020-12-14-jenkins-install-2](/assets/2020-12-14-jenkins-install-2.jpg)

* 프로젝트 Build

![2020-12-14-jenkins-install-3](/assets/2020-12-14-jenkins-install-3.jpg)

----
### 프로젝트 자동 배포 환경 설정

#### 이용 플러그인 추가 설치 

* Gradle 플러그인
	Jenkins 관리 => 플러그인 관리 => Gradle Plugin

* Shell 실행 플러그인
	Jenkins 관리 => 플러그인 관리 => Post build task Plugin


#### New Item 생성

![2020-12-14-jenkins-install-4](/assets/2020-12-14-jenkins-install-4.jpg)

**[Item 구성]**
 
1. 소스 코드 관리
 자동 배포를 적용할 Proj  설정
 
2. Build  Point (배포 시점)
 일정 주기 or Trigger 설정 가능

3. Build  Environment (환경)
 Gradle  등 Build 시 이용할 도구 정의

4. Build  After Action (이후 실행)
 Build 실행 이후 취할 행동
 ( Ex. Send to Email, Start and Stop )

#### GitHub 연동

* GitHub 계정 / 권한 설정

![2020-12-14-jenkins-install-6](/assets/2020-12-14-jenkins-install-6.jpg)

* Repository 설정

![2020-12-14-jenkins-install-5](/assets/2020-12-14-jenkins-install-5.jpg)

#### Build Point

* Cron 예약 구문을 활용한 Build Point 설정 .

![2020-12-14-jenkins-install-7](/assets/2020-12-14-jenkins-install-7.jpg)

#### Build Environment

* Build 도구 설정 (Gradle 이용)

![2020-12-14-jenkins-install-8](/assets/2020-12-14-jenkins-install-8.jpg)

#### Build After Action

* 빌드 후 bash 명령어를 통한 실행

![2020-12-14-jenkins-install-9](/assets/2020-12-14-jenkins-install-9.jpg)

#### Build Check

* 빌드 정상 동작 확인

![2020-12-14-jenkins-install-10](/assets/2020-12-14-jenkins-install-10.jpg)

----
### 맺으며

이번에 진행한 "프로젝트에 Jenkins 적용하기" 를 포스팅하며 다시 한번 Jenkins를 짚고 넘어갈 수 있었으며, 앞으로 적극 활용하게 될 거 같군요. 감사합니다.

-끝-

----
### References

* [Jenkins](https://krksap.tistory.com/1377)
* [Jenkins Install](https://kutar37.tistory.com/entry/%EC%9C%88%EB%8F%84%EC%9A%B0-Jenkins-%EC%84%A4%EC%B9%98-%EA%B8%B0%EB%B3%B8%EC%84%A4%EC%A0%95)