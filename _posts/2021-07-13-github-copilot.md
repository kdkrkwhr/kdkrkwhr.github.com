---
layout: post
author: Kim, DongKi
title:  "(GitHub) Copilot AI 적용 하기"
date:   2021-07-13
categories: devOps
comments: true
---

### TL;DR

#### Copilot AI 란 ?
* 얼론 머스크가 설립한 AI연구소 OpenAI 와 GitHub가 공동 개발한 인공지능 Coding Support 도구 이며, 얼마전 6월 29일 GitHub에서 처음 발표를 하였습니다.
* 현재는 테크니컬 프리뷰 버전을 신청 후 VSCode 내 확장 플러그인을 설치 후 활용이 가능 합니다.
* 다양한 언어를 지원하지만 "Javascript", "Go" 언어가 비교적 잘 동작 한다고 합니다.

----
### 들어가며

이번에 공개 된 Copilot AI를 사용해 보니,, 정말 근 미래에는 지금과 같은 유형의 개발자는 사라지고 새로운 유형의 개발자가 나타나지 않을까 하는 생각이 듭니다. 🤔

당장 공개 된 프리뷰 버전만 이용을 해 봤음에도 인식률이 매우 뛰어나다고 생각이 들고, 개인적인 생각으로는 개발자 보다 비개발직군 사용자가 이용 하기 편한 AI도구가 아닐까 느껴집니다.

현재는 VSCode 확장 플러그인만 공개가 되었으나, 상용화가 된다면 간단한 GUI or 웹 화면 등에서도 접근 및 개발이 가능하지 않을까 싶네요.

AI가 설계 시 기반 데이터는 전세계 개발자들이 GitHub에 기여한 코드를 이용한다고 합니다.
제가 GitHub에 기여한 코드가 이런 흥미로운 프로젝트에 사용이 된다니 매우 기대가 되지만, 반대로 이런 상황이 불편한 이들도 있을 수 있다고 생각합니다. 그럴경우 자신의 코드를 공개하는걸 망설여지는 분들도 있지 않을까 싶습니다. 

![2021-07-13-github-copilot-1](/assets/2021-07-13-github-copilot-1.jpg)

----
### Copilot AI 프리뷰 저장소 참가

* Copilot 페이지 내 사용 신청
* GitHub, Copilot AI Preview Repository 초대 수락

--- 
#### Copilot 페이지 내 사용 신청

![2021-07-13-github-copilot-2](/assets/2021-07-13-github-copilot-2.jpg)

* [Copilot_AI](https://copilot.github.com/) 로 접근하시고, 로그인 후 사용 신청을 하시면 됩니다.
* 계정은 GitHub 계정을 이용하시면 됩니다.
* 신청이 완료 되지 않았다면 VSCode 내 플러그인을 설치를 하더라도 정상적으로 이용이 되지 않습니다.

![2021-07-13-github-copilot-4](/assets/2021-07-13-github-copilot-4.jpg)

----
#### GitHub, Copilot AI Preview Repository 초대 수락

![2021-07-13-github-copilot-3](/assets/2021-07-13-github-copilot-3.jpg)

* 신청을 하시고, 얼마 후 GitHub 계정과 연동 된 E-Mail 계정으로 GitHub "Copilot AI Preview Repository" 초대 메일을 받게 됩니다. (저 같은 경우 대략 2 ~ 3일 정도 소요가 되었습니다.)

* 참가를 위해서는 GitHub 계정 2단계 인증 활성화가 필요합니다.

* 해당 단계까지 완료가 되었다면, 드디어 Copilot AI를 사용하실 수 있습니다. 👏👏

----
### Copilot AI 사용

* VSCode 내 Copilot AI 플러그인 설치
* 실제 적용

----
#### VSCode 플러그인 설치 및 적용

* 이제 드디어 VSCode 내 Copilot 플러그인을 적용 할 수 있습니다.

* VSCode 내 확장 플러그인 ==> GitHub Copilot search ==> 설치

![2021-07-13-github-copilot-5](/assets/2021-07-13-github-copilot-5.jpg)

* 정상적으로 설치가 되었다면 VSCode 화면 우측 하단 Copilot 이 Active 상태인 것을 확인할 수 있습니다. 🤩

![2021-07-13-github-copilot-6](/assets/2021-07-13-github-copilot-6.jpg)

----
#### 실제 적용 

* 함수명에 따른 제안
![2021-07-13-github-copilot-7](/assets/2021-07-13-github-copilot-7.jpg)
위 내용과 같이 "function calculateDaysBetweenDates(begin, end) {"로 함수명만 작성 하였더니 명명에 맞는 코드를 제안하는 모습 입니다. 
![2021-07-13-github-copilot-8](/assets/2021-07-13-github-copilot-8.jpg)
위 상태에서 Tab을 누르니 AI에서 제안한 내용으로 코드가 생성된 것을 확인할 수 있습니다. wow 😲😲
이번엔 다른 다시 처음 상태에서 Ctrl + Enter를 입력 해 보겠습니다.
![2021-07-13-github-copilot-9](/assets/2021-07-13-github-copilot-9.jpg)
Ctrl + Enter를 누르니 위 이미지와 같이 이번엔 각자 다른 10개의 제안을 받았습니다. 😮😮

* 주석과 함수명에 따른 제안 (특정 Lib를 이용하고 싶은 경우)
![2021-07-13-github-copilot-10](/assets/2021-07-13-github-copilot-10.jpg)
위 같이 주석을 같이 인식을 하여 코드를 제안한 모습입니다. 👏

----
### 마무리

아직까지 미숙하지만, 적응을 하게 된다면 개발 시 정말 정말 많은 도움이 될 도구인 것 같습니다. 😍

또한 이용을 하면 할수록 코드 규칙을 고려한 함수 명명을 고민 하게 되는 것 같아, 비교적 깔끔하게 코드를 짤 수 있을 것 같습니다. 🤔


적용 끝 .

"Sure, why not !" 😀

----