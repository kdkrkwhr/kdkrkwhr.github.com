# kdkrkwhr — DK 기술 블로그

Jekyll 정적 블로그. GitHub Pages 배포 (master 브랜치).

## 자동 포스팅 (Hermes cron)

- 토픽 큐: `.hermes/blog-topics.json`
- 스타일: `.hermes/blog-style-guide.md`
- cron 프롬프트: `.hermes/cron-prompt.txt`
- 잡 이름: `blog-auto-post` (이틀에 1회, `every 2d`)

새 주제 추가: `blog-topics.json`에 status pending 항목 추가.
시리즈·상세 지침: `.hermes/blog-prompt-<topic-id>.txt` 또는 `blog-prompt-*-partN.txt` (있으면 cron이 우선 참고).
소스 브리프: `.hermes/ssot-source-brief.md`, `.hermes/e2e-source-brief.md` 등.
인프라 3부작: `blog-prompt-infra-part{1,2,3}.txt` (발행 완료).
SSoT 2부작: `blog-prompt-ssot-part{1,2}.txt` + `ssot-source-brief.md` (대기 중).

## 로컬 미리보기

```bash
bundle install
bundle exec jekyll serve
```

## 배포

git push origin master → GitHub Pages 자동 빌드 (1~2분)
