---
layout: post
author: Kim, DongKi
title: "AI 에이전트에게 원본 저장소를 맡기지 않았다 — Git 작업 사본 격리 설계"
date: 2026-09-23
permalink: /development/2026/09/23/isolated-git-workspace-for-ai-agents.html
categories: Development
comments: true
description: "AI 코딩 에이전트의 파일 변경을 원본 저장소와 분리하고, 기준 커밋·파일 지문·패치 미리보기로 검증한 Agent Hub의 Git 작업 사본 설계 기록."
---

### TL;DR

* AI 에이전트는 원본 저장소가 아니라 **기준 커밋에서 만든 독립 Git 사본**에서 작업한다.
* 로컬 저장소를 복제할 때 `--no-local --no-hardlinks`를 사용해 Git 객체까지 원본과 공유하지 않는다.
* 작업 전후의 `HEAD`와 파일 지문을 확인하고, 결과는 patch·ZIP·manifest로 동결한다.
* 작업 완료, 원본 반영, commit, push를 서로 다른 상태로 관리한다.
* 이 구조는 Git 변경 범위를 격리한다. 운영체제 수준의 sandbox를 대신하지는 않는다.

----

### 들어가며

[Agent Hub를 만든 글]({% post_url 2026-09-18-agent-hub-radio %})에서 역할 분담 작업을 소개했다. 계획·구현·검토 담당자를 정하면 에이전트가 별도 작업 사본에서 파일을 수정하고, 사용자는 patch와 보고서를 확인한 뒤 원본 반영 여부를 선택한다.

그 글에서는 사용자에게 보이는 흐름을 설명했다. 이번에는 한 단계 아래로 내려가 **왜 원본 저장소에서 바로 실행하지 않았는지**, 작업 사본을 어떻게 만들고 결과를 어떤 조건으로 원본에 반영하는지 정리한다.

코딩 에이전트에게 쓰기 권한을 주면 파일 수정 자체는 어렵지 않다. 더 어려운 문제는 다음 질문에 답하는 일이다.

> 지금 보고 있는 변경이 정확히 어느 commit을 기준으로 만들어졌으며, 작업 도중 다른 변경과 섞이지 않았다고 말할 수 있는가?

나는 이 질문을 모델의 지시문만으로 해결하려 하지 않았다. Git 상태와 파일 내용을 호스트 프로그램이 직접 확인하도록 만들었다.

----

### 원본에서 바로 실행하면 경계가 흐려진다

처음 떠올리기 쉬운 방식은 대상 저장소를 작업 디렉터리로 지정하고 에이전트를 실행하는 것이다. 사람이 평소 개발하는 방식과 같고 별도의 복제 비용도 없다.

하지만 자동화에서는 다음 상태가 쉽게 섞인다.

| 상황 | 생기는 문제 |
|---|---|
| 사용자의 미커밋 변경이 있음 | 에이전트가 만든 변경과 기존 작업을 구분하기 어려움 |
| 두 작업을 같은 폴더에서 실행 | 한 작업의 결과가 다른 작업의 입력으로 들어감 |
| 에이전트가 commit·checkout 실행 | 작업의 기준 commit과 결과 범위가 달라짐 |
| 검토 중 파일이 다시 바뀜 | 검토한 결과와 내려받은 결과가 다를 수 있음 |
| 완료 직후 자동 push | 파일 생성, 검토, 공개가 한 번에 이어짐 |

프롬프트에 “기존 파일을 함부로 바꾸지 마라”, “commit하지 마라”라고 쓸 수는 있다. 다만 지시를 잘 따랐는지 확인하는 주체가 다시 같은 에이전트라면 경계가 약하다.

그래서 Agent Hub에서는 에이전트가 원본에 직접 쓰지 않게 했다. 원본은 시작 시점의 기준을 제공하고, 실제 파일 수정은 Hub가 소유한 별도 디렉터리에서 일어난다.

----

### 선택지: worktree인가, 독립 clone인가

Git에는 하나의 저장소에서 여러 작업 디렉터리를 사용하는 `git worktree`가 있다. 브랜치별 병렬 작업에는 좋은 도구다. 하지만 이번 설계에서는 독립 clone을 선택했다.

| 기준 | `git worktree` | 독립 clone |
|---|---|---|
| 객체 저장소 | 원본과 공유 | 별도 `.git/objects` 사용 가능 |
| Git 관리 경계 | 같은 저장소에 연결 | 별도 저장소 |
| 생성 비용 | 상대적으로 작음 | 저장 공간과 복제 시간 증가 |
| 정리 실패 영향 | 원본 worktree 목록에 흔적 가능 | 작업 폴더 단위로 관리 가능 |
| 용도 | 사람이 여러 브랜치를 병행 | 외부 실행기의 작업 단위를 분리 |

worktree가 위험한 기능이라는 뜻은 아니다. 이 프로젝트에서는 **작업 사본 하나를 독립된 실행 단위와 결과물 경계로 다루기 쉬운가**를 더 중요하게 봤다.

특히 로컬 경로를 `git clone`하면 Git은 기본적으로 로컬 최적화를 사용하며, 가능한 경우 `.git/objects`의 파일을 hard link로 만들 수 있다. Git 공식 문서에 따르면 `--no-local`은 이 로컬 최적화를 끄고 일반 전송 방식을 사용하며, `--no-hardlinks`는 객체를 hard link 대신 복사하도록 강제한다.

Agent Hub의 생성 명령은 다음과 같다.

```python
subprocess.run([
    "git", "clone",
    "--no-local",
    "--no-hardlinks",
    "--no-checkout",
    "--",
    source,
    target,
])
```

두 옵션은 의도를 코드에 명확히 남긴다. 원본과 Git 객체를 공유해 공간을 아끼는 것보다 작업 폴더의 생명주기를 분리하는 쪽을 선택했다. `--no-checkout` 뒤에는 작업 시작 시 기록한 commit으로 직접 이동한다.

```text
원본 저장소
  HEAD = A
      │
      ├─ 독립 clone
      ├─ origin 제거
      └─ checkout --detach A
             │
             └─ 에이전트 파일 수정
```

clone이 끝나면 `origin`을 제거하고 `git checkout --detach <base>`를 실행한다. 작업 사본에는 push할 원격 주소가 남지 않고, 에이전트가 특정 로컬 브랜치를 전진시키는 구조도 아니다.

----

### 시작 조건부터 좁혔다

작업 사본을 만든다고 모든 입력 상태를 안전하게 다룰 수 있는 것은 아니다. 첫 버전은 지원 범위를 의도적으로 줄였다.

작업 시작 전 Hub가 확인하는 조건은 다음과 같다.

1. 입력 경로가 Git 저장소인가
2. 실제 저장소 최상위 경로는 어디인가
3. tracked·untracked 파일을 포함해 미커밋 변경이 없는가
4. 현재 `HEAD` commit은 무엇인가
5. 기준 tree에 symbolic link나 submodule이 있는가

원본에 변경이 있으면 자동 stash나 자동 commit을 하지 않는다. 사용자에게 먼저 정리해 달라고 요청한다. 기존 변경을 보존하는 정책까지 자동화하면 “어떤 변경을 누구의 작업으로 볼 것인가”라는 별도 문제가 생기기 때문이다.

symbolic link와 submodule도 첫 버전에서는 거부했다. 둘 다 저장소 바깥의 파일이나 별도의 저장소 상태를 작업 결과에 끌어올 수 있다. 지원하지 않는 경계를 조용히 통과시키는 것보다 명시적으로 중단하는 편을 택했다.

clone 직후에는 원본의 `HEAD`를 다시 읽는다. 복제하는 사이 원본 commit이 바뀌었다면 새 작업으로 다시 시작한다. Git 공식 문서도 로컬 clone이 원본의 동시 변경과 경합할 수 있다고 설명한다.

----

### 에이전트가 바꿀 수 있는 것과 Hub가 확인하는 것

에이전트는 작업 사본의 일반 파일을 수정한다. Hub는 작업 전후에 다음 불변 조건을 확인한다.

```text
작업 사본 HEAD == 시작할 때 기록한 base commit
작업 파일은 작업 사본 디렉터리 안에 있음
결과물 생성 전후 파일 지문이 같음
파일 개수와 전체 크기가 설정한 한도 안에 있음
```

파일 지문은 경로, 파일 모드, 내용 또는 삭제 상태를 SHA-256에 순서대로 넣어 계산한다. Git status 문자열만 비교하지 않는 이유는 결과물을 만드는 동안 파일이 다시 바뀌는 경우까지 잡고 싶었기 때문이다.

```python
h.update(name.encode())
h.update(b"\0")

if not path.exists():
    h.update(b"DELETED")
else:
    h.update(str(path.stat().st_mode).encode())
    # 파일 내용을 일정 크기로 읽어 hash에 추가
```

검증 명령도 shell 문자열로 그대로 실행하지 않는다. 한 개의 실행 파일과 인자 목록으로 해석하고 `&&`, `|`, redirection 같은 shell 연결은 허용하지 않는다. 표준 입력은 닫고, 출력은 작업별 로그 파일로 보낸다. 취소되거나 5분을 넘기면 프로세스 트리를 종료한다.

이 방식이 모든 명령을 안전하게 만든다는 뜻은 아니다. 실행 파일 자체가 무엇을 하는지는 별도 권한 정책의 영역이다. 여기서 확보하려는 것은 shell 해석 범위를 줄이고 실행 결과와 종료 코드를 남기는 것이다.

----

### 결과는 patch 하나로만 보지 않았다

작업이 끝나면 Hub는 다음 네 파일을 만든다.

| 결과물 | 용도 |
|---|---|
| `changes.patch` | 기준 commit과 작업 결과의 binary diff |
| `changed-files.zip` | 변경된 일반 파일 묶음 |
| `report.md` | 계획·구현·검토 내용과 검증 결과 |
| `manifest.json` | 기준 commit, 파일 지문, 변경 파일 목록 |

patch는 원본 반영에 적합하지만 삭제된 파일의 최종 모습을 보여주지는 않는다. ZIP은 결과 파일을 직접 확인하기 쉽지만 Git 변경 의미를 모두 표현하지 못한다. report는 판단 과정과 테스트 여부를 담지만 실제 파일의 증거는 아니다. manifest는 이 결과들이 어느 기준에서 만들어졌는지 연결한다.

결과물 생성 직전과 직후의 파일 지문이 다르면 내보내기를 실패시킨다. 에이전트가 계속 파일을 쓰는 동안 patch와 ZIP이 서로 다른 순간을 담는 일을 피하기 위한 조건이다.

----

### 미리보기는 원본 index를 건드리지 않고 계산한다

완료된 patch를 원본에 적용하기 전에는 두 가지를 확인해야 한다.

* 지금 원본이 여전히 시작 당시의 commit인가
* patch를 적용했을 때 만들어질 Git tree가 검토한 결과와 같은가

예상 tree는 임시 index에서 계산한다.

```text
GIT_INDEX_FILE=<temporary index>
git read-tree <base>
git apply --cached <changes.patch>
git write-tree
```

`read-tree`로 기준 tree를 임시 index에 읽고, `git apply --cached`로 patch를 작업 트리가 아닌 index에 적용한 뒤, `write-tree`로 예상 tree 식별자를 얻는다. 이 단계에서는 원본 파일과 원본 index를 수정하지 않는다.

사용자가 보는 diff와 patch의 SHA-256, 원본 branch, remote 주소도 함께 기록한다. 미리보기에는 10분짜리 일회용 token을 발급한다. 실제 반영 요청에서 patch·branch·remote가 달라졌거나 token이 만료됐다면 미리보기를 다시 열어야 한다.

----

### 완료, 반영, commit, push는 같은 상태가 아니다

에이전트가 작업을 마쳤다는 사실과 원본 저장소에 변경이 들어갔다는 사실은 다르다. 원격 저장소에 공개됐다는 사실은 또 다르다.

Agent Hub는 다음 단계를 따로 기록한다.

```text
ready
  ↓ patch 적용
applied
  ↓ commit
committed
  ↓ push
pushed
```

기본 반영은 patch 적용과 staging까지만 수행한다. commit과 push는 사용자가 별도로 선택한다. 각 mutation 직전에는 `applying`, `committing` 같은 의도 상태를 먼저 저장한다. 프로그램이 중간에 종료되더라도 같은 명령을 무조건 다시 실행하지 않고 원본 상태를 직접 확인하게 한다.

단계별 검증도 다르다.

| 상태 | 확인하는 조건 |
|---|---|
| `ready` | 원본 `HEAD == base`, working tree와 index가 깨끗함 |
| `applied` | `HEAD == base`, staged tree가 예상 tree와 같음, 추가 변경 없음 |
| `committed` | 새 commit의 parent가 base이며 commit tree가 예상 tree와 같음 |
| `pushed` 직전 | 원격 branch tip이 base 또는 방금 만든 commit과 같음 |

원본 commit이 달라졌다고 자동 merge하지 않는다. 원격 branch가 앞서갔다고 force push하지도 않는다. 자동화가 해결해야 할 문제가 아니라 새로운 변경을 기준으로 다시 검토해야 할 상황으로 본다.

----

### hooks와 인증 정보도 경계에 포함했다

파일을 읽고 비교하는 Git 명령에는 `core.hooksPath=/dev/null`을 사용한다. 작업 사본을 준비하거나 patch를 검사하는 과정에서 원본 저장소의 hook을 뜻하지 않게 실행하지 않기 위해서다.

반면 실제 commit과 push에서는 사용자의 정상적인 Git 정책이 작동해야 하므로 hook을 무조건 끄지 않는다. push는 `GIT_TERMINAL_PROMPT=0`, `GCM_INTERACTIVE=never` 환경에서 실행해 백그라운드 작업이 인증 입력을 기다린 채 멈추지 않게 했다.

원격 URL에 자격 증명이 직접 포함돼 있으면 UI에 보여주지 않고 반영을 중단한다. 자격 증명 관리자를 사용하는 원격 주소를 요구한다.

이 구분도 완전한 보안 sandbox는 아니다. Git hook 자체를 신뢰할 수 없는 저장소라면 commit 단계의 정책을 더 제한해야 한다.

----

### 이 설계가 해결하지 않는 것

독립 clone은 Git 작업 경계를 분명하게 하지만 다음 문제까지 해결하지는 않는다.

| 해결하는 범위 | 해결하지 않는 범위 |
|---|---|
| 원본과 작업 파일 분리 | 프로세스의 OS 파일 접근 권한 제한 |
| 기준 commit과 변경 파일 추적 | 생성된 코드의 정확성 보장 |
| 검토한 patch와 적용 tree 대조 | 검증 명령이 다루지 않은 동작 보장 |
| 원본 변경 시 자동 중단 | 자동 merge와 충돌 해결 |
| push 대상 tip 확인 | 원격 서버의 권한·보호 규칙 대체 |

에이전트 CLI가 작업 사본 밖의 절대 경로를 읽거나 쓸 수 있는지는 각 CLI와 운영체제 sandbox 설정에 달려 있다. 이 글의 격리는 **Git 상태와 결과물의 경계**에 관한 것이다.

대형 저장소에서는 매 작업마다 독립 clone을 만드는 시간과 저장 공간도 부담이 된다. symbolic link와 submodule을 지원하지 않는 제한도 있다. 필요하다면 저장소 신뢰 수준에 따라 worktree, container, VM 같은 다른 격리 단계를 선택해야 한다.

----

### 마치며

AI 코딩 에이전트를 붙이면서 가장 먼저 정한 것은 어떤 모델을 쓸지가 아니었다. **어느 상태에서 시작하고, 무엇을 결과로 인정하며, 언제 원본에 반영할지**였다.

현재 구조는 다음 원칙으로 요약할 수 있다.

1. 깨끗한 원본의 commit을 기준으로 작업을 시작한다.
2. Git 객체까지 분리된 사본에서 파일을 수정한다.
3. 결과를 patch·파일·보고서·manifest로 동결한다.
4. 임시 index에서 적용 후 tree를 미리 계산한다.
5. 반영·commit·push 사이마다 원본 상태를 다시 확인한다.
6. 예상과 다르면 자동으로 합치지 않고 멈춘다.

에이전트가 “완료했다”고 말하는 것보다 Git이 보여주는 base, diff, tree가 더 좁고 검증 가능한 계약이었다. 자동화를 늘릴수록 이 계약을 모델의 프롬프트가 아니라 실행 경계에 두는 편이 낫다고 생각한다.

구현은 [Agent Hub Radio 저장소](https://github.com/kdkrkwhr/agent-hub)의 `pipeline_workspace.py`와 `publication.py`에서 볼 수 있다.

### 관련 글과 문서

* [Agent Hub를 만든 이유]({% post_url 2026-09-18-agent-hub-radio %})
* [에이전트 완료 보고와 교차검수]({% post_url 2026-08-24-agent-self-report-cross-review %})
* [Git clone 공식 문서](https://git-scm.com/docs/git-clone)
* [Git apply 공식 문서](https://git-scm.com/docs/git-apply)
* [Git read-tree 공식 문서](https://git-scm.com/docs/git-read-tree)
