# Git SSoT — 블로그용 소스 브리프 (2부작)

> 원본 참고: `D:\develop\ssot\README.md`, `D:\develop\project_management\docs\ai-e2e-workflow-presentation\index.html` (SSoT 슬라이드), `D:\develop\git\agent-skill\shared\mediplexus-e2e\workflows\validate-ssot.md`, `convert-to-ssot.md`
> 블로그 글 작성 시 **회사명·내부 시스템명·고객사·실명·내부 URL·조직 repo 이름** 절대 금지. 개인/소규모 팀의 Git SSoT 운영 경험으로 일반화.

## 1편 각도 — 왜·무엇·구조

### 핵심 메시지

- 조직(또는 개인)의 기억은 **채팅창이 아니라 Git 정본**에 둔다.
- SSoT = Single Source of Truth. 티켓·에이전트·cron이 **같은 파일**을 읽고 쓴다.
- E2E 루프 ⑦단계에서 adr/spec/prd 등으로 **맥락을 commit**하고, ①②에서 다시 읽는다.
- 티켓에는 SSoT **경로 링크**만 — 본문 복사 금지.

### 문서 타입 5종

| Type | 폴더 | 다루는 것 |
|------|------|-----------|
| adr | `adr/` | 아키텍처 의사결정, 트레이드오프 |
| rfc | `rfcs/` | 사전 제안, 검토 중 변경 |
| spec | `specs/` | API·데이터 모델·인터페이스 |
| prd | `prds/` | 제품 요구사항, 인수기준 |
| policy | `policies/` | 조직 정책·프로세스 |

### scope 분기 (일반화 예)

| Scope | 영역 |
|-------|------|
| platform | 횡단 — 인증, CI/CD, 인프라 |
| compliance | 도메인 횡단 규정 (해당 시) |
| products/* | 제품별 고유 |
| ops | 운영·행정 정책 |

### 폴더 구조 (예시)

```
ssot/
├── adr/{platform,products/...}/
├── rfcs/
├── specs/
├── prds/
├── policies/
├── drafts/          # convert 전 초안 (보통 gitignore)
└── templates/       # adr.md, spec.md 등
```

### E2E와의 관계

- **읽기**: 작업 시작(/start·brainstorming), 티켓에 링크된 파일 직접 로드
- **쓰기**: 작업 완료 후 add-decision, convert-to-ssot finalize
- **횡단층**: Model~Harness 위를 가로지르는 맥락 공급

### 티켓 vs SSoT

| 영역 | 위치 |
|------|------|
| 태스크·상태 | 티켓 트래커 (Notion 등) |
| 의사결정·스펙 본문 | Git SSoT |
| 실시간 드래프트 | ephemeral (채팅·협업 도구) |

### 1편 필수 다이어그램

- 티켓 → Agent → SSoT commit 흐름 (mermaid 1개)
- 또는 문서 타입·scope 트리

---

## 2편 각도 — 운영·검증·컨텍스트 다이어트

### 핵심 메시지

- SSoT가 커져도 LLM 컨텍스트는 **가볍게** — INDEX·요약·링크만 주입.
- **validate-ssot**로 메타데이터·본문 규칙 게이트 → 쓰레기 유입 방지.
- **한 사실 한 곳** — 중복 복사 대신 `related:` 링크.
- `superseded` / `archived`로 역사는 남기고 정본은 하나.

### front matter 필수 (요약)

title, type, scope, author, created, updated, status, stakeholders, related, tags, summary — type·scope와 **폴더 경로 일치** 필수.

### validate-ssot 검사 항목

- 메타데이터 완전성
- type-folder / scope-path 일치
- status 워크플로 (adr: proposed→accepted→superseded 등)
- ADR: 대안 ≥2 + 기각 사유
- PRD: 검증 가능한 인수기준, out of scope
- 중복·supersedes 체인

### 컨텍스트 다이어트 FAQ

**Q: SSoT가 무거워지면 LLM도 무거워지지 않나?**
- A: 전체 repo를 넣지 않음. INDEX 스캔 → 관련 ADR·spec **요약만** context에. 티켓에 경로 있으면 그 파일 우선.

**Q: 채팅에 이미 정리했는데 또 SSoT?**
- A: 채팅은 ephemeral. 다음 세션·다른 에이전트·cron이 읽을 수 없음.

### 읽기·쓰기 주체별

| 시점 | 주체 | 방법 |
|------|------|------|
| 작업 중 | LLM Agent | 티켓 SSoT 경로 → 해당 md 직접 읽기 |
| 아침/저녁 | Hermes cron | INDEX + validate-ssot (읽기·검증) |
| 문서화 | Agent/Cowork | add-decision, convert-to-ssot |
| CI | GitHub Actions | 변경된 md 자동 검증 |

### convert-to-ssot 흐름

PDF·초안 → `drafts/` → 사용자 검토 → finalize → 정식 adr/spec/prd commit

### 2편 필수 다이어그램

- 전체 ssot repo → INDEX/요약 → Agent context (창고 vs 서랍 비유)
- validate-ssot 게이트 흐름

## 기존 블로그와의 관계

- `_posts/2026-07-01-ai-e2e-ouroboros.md`에서 SSoT를 **횡단층**으로 소개함 → 이 시리즈는 **SSoT 단독 심화**
- 1편: 개념·구조 / 2편: 운영·검증·FAQ
- Ouroboros L1~L4 상세 반복은 최소화
