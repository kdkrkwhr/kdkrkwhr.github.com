# 블로그 글 스타일 가이드 (DK 기술 블로그)

기존 `_posts/` 글들과 동일한 톤·구조를 따른다.

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

- `categories`: AI 글은 `AI`, 인프라/DevOps 성격이면 `devOps`, 일반 기술이면 `Technology`
- `date`: 실행 당일 날짜
- 파일명: `_posts/YYYY-MM-DD-slug.md` (slug는 영문 소문자·하이픈)

## 본문 구조

1. `### TL;DR` — 3~5줄 요약 (불릿 `*` 또는 짧은 문단)
2. `----` 구분선
3. `### 들어가며` — 주제 도입, 개인 경험·느낌 (1~2단락)
4. `----` 구분선
5. 본문 섹션 — `###`, `####` 헤딩, `----`로 구역 분리
6. 코드 블록은 언어 태그 명시 (```bash, ```yaml 등)
7. 이모지 가끔 사용 OK — 과하지 않게

## 톤

- 한국어, 1인칭 경험담 혼합
- 튜토리얼이지만 딱딱하지 않게
- 회사·고객·내부 시스템 언급 금지 — 일반적 개인 학습·오픈소스 경험만

## 참고 샘플

- `_posts/2021-07-13-github-copilot.md`
- `_posts/2022-01-20-push-server.md`

## 분량

- 최소 800자, 권장 1500~3000자
