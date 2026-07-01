# kdkrkwhr — DK 기술 블로그

Jekyll 정적 블로그. GitHub Pages 배포 (master 브랜치).

## 자동 포스팅 (Hermes cron)

- 토픽 큐: `.hermes/blog-topics.json`
- 스타일: `.hermes/blog-style-guide.md`
- cron 프롬프트: `.hermes/cron-prompt.txt`
- 잡 이름: `blog-auto-post` (주 1회)

새 주제 추가: `blog-topics.json`에 status pending 항목 추가.

## 로컬 미리보기

```bash
bundle install
bundle exec jekyll serve
```

## 배포

git push origin master → GitHub Pages 자동 빌드 (1~2분)
