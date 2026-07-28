---
layout: post
author: Kim, DongKi
title:  "GitHub Pages + Actions로 정적 앱 배포하기"
date:   2026-07-28
categories: Technology
comments: true
---

### TL;DR
* 서버 없이 **GitHub Pages**에 HTML/JS 앱을 올리고, **Actions**로 데이터·빌드를 주기적으로 갱신할 수 있다.
* 정적 사이트면 `main` 브랜치 루트(또는 `/docs`)만 Pages 소스로 지정하면 된다. SPA가 아니면 Jekyll도 끌 수 있다(`.nojekyll`).
* 스케줄 cron은 **UTC** 기준이다. KST로 환산해서 요일·장 시간대를 맞춰야 한다.
* 시세·목록처럼 자주 바뀌는 데이터는 Actions가 `fetch → commit → push` 하고, UI는 그 JSON만 읽게 두면 배포 파이프라인이 단순해진다.

----

### 들어가며

예전에 [Jenkins 설치](/2020/12/14/jenkins-install/), [Nginx 무중단 배포](/2020/12/10/nginx-nonstop/)로 “서버에 올리는” 쪽을 정리했다면, 요즘 사이드 프로젝트는 **서버를 안 쓰는** 경우가 많다. HTML 한 장 + 브라우저 API(또는 공개 JSON)면 충분할 때, VPS·Nginx·SSL을 또 깔 이유가 없다.

최근에 만든 정적 앱 두 개 — 종목 픽 모니터, 브라우저에서 엑셀 취합·검증 — 를 모두 GitHub Pages에 올렸다. 한쪽은 **10분마다 Actions가 데이터를 갱신**하고, 다른 쪽은 **푸시만 하면 즉시 배포**된다. 이 글은 그 최소 구성을 정리한다. (예전 CI/CD 글의 “가벼운 후속편”으로 보면 된다.)

----

### 1. Pages에 올리는 최소 조건

공개 저장소 기준으로 필요한 건 대략 이것이다.

| 항목 | 내용 |
|------|------|
| 소스 | Settings → Pages → Branch: `main` / folder: `/ (root)` 또는 `/docs` |
| 정적 파일 | `index.html`, CSS/JS, `manifest.json`(PWA면) |
| Jekyll 끄기 | 루트에 빈 `.nojekyll` — `_`로 시작하는 경로·순수 HTML을 안 깨먹게 |
| 커스텀 경로 | user/org 사이트(`username.github.io`)가 아니면 URL은 `https://<user>.github.io/<repo>/` |

프로젝트 사이트면 상대 경로/`base`에 주의한다. `fetch('./data/kospi.json')`처럼 **repo 루트 기준 상대 경로**를 쓰면 Pages 하위 경로에서도 같이 동작한다.

```text
repo/
  .nojekyll
  index.html
  sw.js
  manifest.json
  data/          # Actions가 갱신하는 JSON 샤드
  .github/workflows/update.yml
```

----

### 2. “푸시 = 배포” vs “스케줄 = 데이터 갱신”

역할을 나누면 헷갈리지 않는다.

* **UI·로직 변경** → `main`에 push → Pages가 새 `index.html`을 서빙
* **외부 API로 모은 데이터** → Actions가 스케줄로 스크립트 실행 → `data/*.json` 커밋·푸시 → 같은 Pages가 최신 JSON을 서빙

백엔드 서버가 없어도, “주기적으로 파일이 바뀌는 정적 호스팅”이 된다. 클라이언트가 60초마다 `fetch`만 하면 화면이 따라온다.

----

### 3. Actions 워크플로 예시 (데이터 갱신)

종목 목록·시세처럼 파일을 주기적으로 갈아끼울 때의 뼈대다. (실제 저장소의 스케줄·스크립트명만 프로젝트에 맞게 바꾸면 된다.)

```yaml
name: Update data

on:
  schedule:
    # 평일 장중 예시 — cron은 UTC
    # KST 09~15 ≈ UTC 00~06
    - cron: "*/10 0-6 * * 1-5"
  workflow_dispatch:   # 수동 실행용

permissions:
  contents: write

jobs:
  fetch:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-python@v5
        with:
          python-version: "3.11"

      - name: Fetch → data/*.json
        run: python fetch_data.py
        timeout-minutes: 25

      - name: Commit if changed
        run: |
          git config user.name "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
          git add data/
          if git diff --cached --quiet; then
            echo "No changes — skip commit"
            exit 0
          fi
          git commit -m "chore: update data shards"
          git push
```

포인트만 짚으면:

* `permissions.contents: write` — 기본 GITHUB_TOKEN으로 push하려면 명시하는 편이 안전하다.
* **변경 없으면 커밋 스킵** — 빈 커밋으로 Actions·Pages 노이즈를 안 만든다.
* API가 비거나 실패했을 때 빈 파일로 덮어쓰면 배포가 망가진다. 스크립트에서 “실패 시 기존 샤드 유지”를 넣는 게 좋다.
* 공개 API 한도가 있으면 `schedule` 간격을 늘리거나, 장외 시간대 cron을 줄인다.

순수 정적 앱(엑셀 취합처럼 브라우저만 도는 도구)은 워크플로 없이 **푸시만**으로도 충분하다. Actions는 “서버 대신 돌릴 배치”가 필요할 때만 붙이면 된다.

----

### 4. 로컬에서 확인하는 순서

```bash
# 1) 데이터 스크립트가 있으면 로컬에서 한 번
python fetch_data.py

# 2) 정적 서버로 경로/SW 확인 (예: Python)
python -m http.server 8080
# http://localhost:8080

# 3) push
git add -A && git commit -m "feat: …" && git push
```

PWA·Service Worker를 쓰면 **캐시가 옛 `index.html`을 붙잡는** 경우가 많다. CACHE 버전을 올리고, “새 배포 있음 → 새로고침” 배너를 넣거나, 개발 중엔 SW를 잠시 끄는 편이 디버깅이 빠르다.

----

### 5. 예전에 쓰던 배포랑 비교

| | Nginx + Jenkins (예전) | Pages + Actions (지금) |
|--|------------------------|-------------------------|
| 호스팅 | 내 서버·SSL·방화벽 | GitHub가 HTTPS 제공 |
| 배포 트리거 | 잡/훅 | `git push` / `schedule` |
| 동적 API | 서버 프로세스 필요 | 브라우저·공개 API·사전 생성 JSON |
| 적합한 것 | 인증·DB·사내망 | 데모, 개인 도구, 공개 대시보드 |

“항상 켜 둔 API 서버”가 필요하면 Pages만으로는 부족하다. 반대로 **파일만 바꾸면 끝나는 도구**면 Pages가 훨씬 싸다.

----

### 마무리

* 사이드 앱은 먼저 **정적 + Pages**로 올리고, 주기 작업만 Actions에 맡기는 구성이 유지보수 비용이 가장 낮다.
* cron은 UTC, 커밋은 “변경 있을 때만”, 실패 시 **기존 데이터 유지** — 이 세 가지면 운영 사고의 대부분이 줄어든다.
* AI 자동화 글만 쌓이기 쉬운데, 배포·호스팅 같은 **Technology/devOps** 기록도 같이 남겨 두는 편이 나중에 찾기 좋다. 다음엔 브라우저만으로 엑셀 취합·검증하는 패턴도 정리할 예정이다.
