---
layout: post
author: Kim, DongKi
title: "노트버디 개발기 7: 소스에서만 되면 부족했다, CLI 패키징"
date: 2026-11-04 15:50:00 +0900
permalink: /ai/2026/11/04/notebuddy-cli-packaging.html
categories: AI
comments: true
description: "노트버디의 공통 Python 게임 엔진을 CLI wheel에 담고, 소스 체크아웃 밖에서 설치본을 검증한 과정을 정리합니다. Windows·macOS·Linux 확인 범위와 PyPI 공개 전 상태도 구분합니다."
excerpt: "같은 게임 규칙을 터미널에서도 쓰고 싶었습니다. 소스에서 import되는 것만으로는 부족해 wheel 내용물을 제한하고, 새 환경에 설치한 파일로 세 운영체제에서 실행해봤습니다."
---

### TL;DR

* 노트버디 CLI는 기존 `engine/`의 게임 규칙을 빌드할 때 wheel에 담습니다. 규칙을 CLI용으로 다시 작성하지 않았습니다.
* 소스 아카이브와 wheel에 들어갈 파일을 명시하고, 개인 저장 파일·생성 이미지·Anna 전용 코드는 제외했습니다.
* 설치한 wheel을 소스 체크아웃 밖에서 실행하는 테스트가 Windows·macOS·Linux CI에서 통과했습니다. 소스 import 테스트만으로는 알기 어려운 문자 인코딩과 경로 문제도 이 과정에서 고쳤습니다.
* **2026년 11월 4일 기준 PyPI 업로드는 확인되지 않았습니다.** 아래 설치·검증 이야기는 저장소에서 빌드한 패키지에 관한 것입니다.

----

### 들어가며

[지난 6편]({% post_url 2026-10-25-notebuddy-equipment-recovery %})까지는 Anna 앱에서 친구를 키우고 꾸미는 경험을 이야기했습니다. 이번에는 같은 친구를 터미널에서도 만날 수 있도록 만든 Python CLI를 살펴보려 합니다.

처음부터 게임 규칙을 새로 만들고 싶지는 않았습니다. Anna와 CLI가 각자 코인이나 경험치를 계산하면 시간이 지나면서 결과가 달라질 수 있으니까요. [기존 공통 엔진](https://github.com/kdkrkwhr/notebook-pets/tree/9536314cc22fa65c8306f51a509609e68d7d089c/engine)을 그대로 쓰되, **저장소를 내려받지 않은 사용자에게도 필요한 코드가 도착하는지**가 패키징의 핵심이었습니다.

----

### 개발 폴더에 있다는 것과 설치 파일에 있다는 것은 다릅니다

저장소 루트에서 실행하면 옆에 있는 `engine/`과 `data/`를 읽을 수 있습니다. 하지만 `pip`으로 설치한 뒤에는 그 폴더 구조가 없죠. CLI가 우연히 현재 작업 폴더의 파일을 읽고 있다면 개발 중에는 잘 되다가 설치본에서 실패할 수 있습니다.

그래서 [패키징 설정](https://github.com/kdkrkwhr/notebook-pets/blob/9536314cc22fa65c8306f51a509609e68d7d089c/pyproject.toml)은 두 단계를 따로 정합니다. `sdist`에는 필요한 원본 파일만 넣고, wheel에는 `cli/notebook_pets` 패키지와 선택한 공통 파일을 `_runtime/` 아래로 포함합니다.

```toml
[tool.hatch.build.targets.wheel]
packages = ["cli/notebook_pets"]

[tool.hatch.build.targets.wheel.force-include]
"engine/engine.py" = "notebook_pets/_runtime/engine/engine.py"
"engine/game_api.py" = "notebook_pets/_runtime/engine/game_api.py"
"data/game_data.json" = "notebook_pets/_runtime/data/game_data.json"
```

위 코드는 실제 매핑 중 일부입니다. 게임 규칙 파일마다 대응 경로를 명시해 **원본을 빌드 시점에 담고**, CLI용 규칙 사본을 따로 유지하지 않습니다. 기존 stdio MCP 어댑터와 규칙 JSON도 같은 방식으로 포함합니다. 반대로 사용자 저장 파일, 생성된 그림, 자격 증명, Anna 전용 어댑터는 배포 파일 대상에서 뺐습니다.

설치된 [진입점 코드](https://github.com/kdkrkwhr/notebook-pets/blob/9536314cc22fa65c8306f51a509609e68d7d089c/cli/notebook_pets/cli.py)는 자기 패키지 안의 `_runtime` 경로를 찾습니다. 공통 엔진을 그 경로에서 읽도록 연결하고, 필요한 파일이 없으면 설치된 wheel을 확인하라는 오류를 냅니다. 사용자의 현재 작업 폴더에 엔진이 있기를 기대하지 않는 구조입니다.

----

### 테스트 대상은 소스가 아니라 설치된 wheel입니다

검증 흐름도 [CI 설정](https://github.com/kdkrkwhr/notebook-pets/blob/9536314cc22fa65c8306f51a509609e68d7d089c/.github/workflows/pypi.yml)에 맞춰 나눴습니다.

```text
소스 → sdist → wheel 생성 → 메타데이터 검사
                    ↓
           Windows·macOS·Linux에 같은 wheel 설치
                    ↓
              설치본 CLI·MCP 테스트
```

[설치본 테스트](https://github.com/kdkrkwhr/notebook-pets/blob/9536314cc22fa65c8306f51a509609e68d7d089c/cli/tests/test_installed.py)는 임시 저장 폴더에서 `python -I -m notebook_pets`를 실행합니다. `-I`는 현재 폴더나 `PYTHONPATH`를 통해 소스가 우연히 import되는 일을 막는 데 도움이 됩니다. 테스트는 설치된 파일의 내용과 패키지 목록을 원본·허용 목록과 비교하고, 실제 명령 진입점, 친구 생성과 재실행, 저장 분리, MCP 읽기 전용 기본값과 같은 요청의 재처리도 확인합니다.

여기서 운영체제별 차이가 드러났습니다. Windows의 CP949 출력 환경에서 `--help`가 실패해 CLI 출력 인코딩을 UTF-8로 맞췄습니다. 저장 경로를 비교하는 테스트에서는 Windows의 짧은 경로 이름과 macOS의 심볼릭 링크 때문에 동일한 위치를 다르게 볼 수 있어, 양쪽 경로를 정규화한 뒤 비교하도록 바꿨고요. 두 사례 모두 “내 컴퓨터의 소스에서 실행됐다”는 확인만으로는 충분하지 않다는 점을 보여줬습니다.

2026년 10월 26일 [패키지 CI 실행](https://github.com/kdkrkwhr/notebook-pets/actions/runs/40286427561)은 커밋 `9536314`에서 빌드·메타데이터 검사와 세 운영체제의 설치본 테스트를 통과했습니다. 각 운영체제에서 일곱 개 테스트가 실행됐습니다. 이는 당시 패키지의 터미널·MCP 경로를 확인한 결과이며, 이후 바뀐 모든 엔진 코드나 네이티브 앱 UI를 검증했다는 뜻은 아닙니다.

이 글을 준비하면서는 더 최근 커밋 `3739336`에서도 소스 아카이브와 wheel을 다시 빌드했습니다. 두 파일의 메타데이터 검사를 통과했고, Linux의 Python 3.12 새 가상 환경에 해당 wheel과 MCP 선택 항목을 설치해 일곱 개 테스트를 실행했습니다. 이 추가 확인은 Linux 한 환경의 결과입니다.

----

### 저장 위치도 운영체제마다 달라야 합니다

설치만 되면 끝나는 CLI가 아닙니다. 친구의 상태를 다음 실행에서 다시 읽어야 하니까요. [CLI 기본 저장 경로](https://github.com/kdkrkwhr/notebook-pets/blob/9536314cc22fa65c8306f51a509609e68d7d089c/cli/notebook_pets/cli.py)는 운영체제에 맞춰 분기합니다.

| 운영체제 | 기본 데이터 폴더 |
| --- | --- |
| Windows | `%LOCALAPPDATA%\NotebookPetsCLI` |
| macOS | `~/Library/Application Support/NotebookPetsCLI` |
| Linux | `$XDG_DATA_HOME/notebook-pets`, 설정하지 않았다면 `~/.local/share/notebook-pets` |

`--data-dir`로 별도 폴더를 지정할 수도 있습니다. 테스트는 공백이 들어간 임시 경로에 저장해보고, `--help`나 저장 위치 확인만으로 저장 파일이 생기지 않는지도 봅니다. 설치본은 Anna·데스크톱·Android의 기존 저장 데이터를 자동으로 가져오거나 수정하지 않습니다.

----

### 배포 가능한 패키지와 공개된 패키지는 다릅니다

이 CLI는 Python 3.11 이상용 `0.1.0a1` 알파 패키지로 준비했습니다. 기본 CLI에는 외부 런타임 의존성이 없고, MCP 연결은 선택 항목입니다. 게임 규칙은 공통 엔진이 결정하며 CLI 자체가 AI 호출이나 그림 생성을 하지는 않습니다.

CI에서 `publish` 단계는 건너뛰었습니다. [배포 절차](https://github.com/kdkrkwhr/notebook-pets/blob/9536314cc22fa65c8306f51a509609e68d7d089c/cli/PUBLISHING.md)에 따르면 PyPI 신뢰할 수 있는 게시자 설정 후, `release` 브랜치에서 `publish` 옵션을 켜 수동 실행해야 업로드됩니다. 2026년 11월 4일에 확인한 PyPI 프로젝트 API도 404를 반환했습니다. 따라서 지금은 PyPI에서 설치할 수 있다고 안내하거나 다운로드 수를 말할 단계가 아닙니다.

이번에 남긴 교훈은 단순합니다. **패키징은 코드를 압축하는 일이 아니라, 소스가 없는 곳에서 실행에 필요한 파일과 상태 경계를 확인하는 일**이었습니다. 다음에 배포를 진행한다면 업로드 결과와 공개 설치까지 별도로 확인해보려 합니다.

----

{% include notebuddy-series.html %}
