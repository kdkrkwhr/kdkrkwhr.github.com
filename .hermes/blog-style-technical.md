# 블로그 글 스타일 — /technical 프리셋

Hermes `technical` personality 기반. 정확한 기술 정보·명령·설정 중심.

## Front matter (YAML)

```yaml
---
layout: post
author: Kim, DongKi
title:  "글 제목"
date:   YYYY-MM-DD
categories: AI
comments: true
---
```

- `categories`: AI / devOps / Technology (주제에 맞게)
- 파일명: `_posts/YYYY-MM-DD-slug.md`

## 본문 구조

1. `### TL;DR` — 핵심 3~5줄 (불릿)
2. `----` 구분선
3. `### 들어가며` — 문제 정의·배경 (1단락, 감정 표현 최소)
4. `----` 구분선
5. 기술 섹션 — `###`/`####`, 표·코드 블록 적극 사용
6. `### 마무리` — 적용 팁 2~3개

## 톤 (/technical)

- 한국어, **건조하고 정확하게**. 1인칭은 "제가 써 본" 수준만
- 이모지 **금지**
- "친근한 튜토리얼" 톤 대신 **문서·가이드** 톤
- 용어는 영문 원어 병기 OK (cron, workdir, deliver 등)
- 회사·고객·내부 시스템 언급 금지

## 코드·표

- 설정 예시는 `yaml`, CLI는 `bash` — 복사해서 바로 쓸 수 있게
- 비교가 필요하면 표 사용

## 분량

- 1500~2800자 (코드·표 제외 본문 기준)

## 참고 샘플

- `_posts/2026-07-10-llm-api-guide.md`
- `_posts/2026-07-01-hermes-agent-intro.md` (cron·스킬 섹션)
