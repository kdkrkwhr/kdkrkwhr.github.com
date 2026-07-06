# AI E2E 워크플로우 — 블로그용 소스 브리프

> 원본: `D:\develop\project_management\docs\ai-e2e-workflow-presentation\index.html`
> 블로그 글 작성 시 **회사명·내부 시스템명·고객사·실명 동료·내부 URL** 절대 금지. 개인 학습·일반 오픈소스 도구 경험으로 일반화.

## 핵심 메시지

AI 코딩 도구만 쓰면 "세션 끝나면 맥락 소멸"이 반복된다. **E2E(End-to-End) 워크플로우**는 하루 단위로 사람·에이전트·기록·스케줄이 맞물리는 **24시간 순환 루프**다. 중심에는 Git 기반 **SSoT(Single Source of Truth)** 가 있고, Harness의 **Ouroboros 패턴**으로 품질·맥락·규칙이 진화한다.

## E2E AI Loop — 10단계 (하루 1회 순환)

| 단계 | 이름 | 주체 | 요약 |
|------|------|------|------|
| ① | 검수 | HUMAN | 아침 브리핑·전날 야간 산출물 확인, 티켓·알림 검토 |
| ② | Skill 파악 | AGENT+HUMAN | 신규 업무 시 brainstorming, Human이 방향 제시 |
| ③ | 설계 | HUMAN+AGENT | 인수기준·SSoT 참고, Human 확정·Agent 초안 |
| ④ | 승인 | HUMAN | 계획·민감 작업 Human OK 후 진행 |
| ⑤ | 실행 | AGENT | 티켓 구현, MCP·Harness Skill. 기존 티켓이면 ②~④ 생략 가능 |
| ⑥ | 게이트 | AGENT | lint, test, ponytail-review 등 Harness Skill로 자동 검증 |
| ⑦ | SSoT | AGENT | adr, docs, context commit으로 맥락 기록 |
| ⑧ | Done | HUMAN | 티켓 완료·SSoT 링크. 다음 티켓이면 ⑤부터 반복 |
| ⑨ | 위임 | AGENT | 퇴근 시 Hermes batch job 등록·실행, 야간 작업 트리거 |
| ⑩ | cron | AGENT | 08:00 work-briefing, 22:00 eod-review → 익일 ①로 복귀 |

**페이즈**: 출근(①) → 업무(②~⑧) → 퇴근(⑨) → 야간/cron(⑩) → 익일 ①

## 아키텍처 계층 (아래→위)

1. **LLM Model** — 추론·코드 생성 엔진 (Claude, GPT, Gemini, Composer 등)
2. **LLM Agent** — Cursor, Claude Code 등. 모델+도구+세션으로 실행
3. **LLM Orchestration** — Hermes gateway, cron, job으로 여러 Agent·하루 루프 조율
4. **Harness** — instructions, Skills, Ouroboros로 "어떻게 일할지" 통일
5. **SSoT (횡단)** — Git의 adr, docs, context, Skills 레시피. 시작 시 읽고(①②), 완료 시 씀(⑦)

**계층 보완 관계**:
- Model(추론) + Agent(실행) = 채팅만이 아닌 티켓·코드·SSoT까지 연결
- Agent(한 세션) + Orchestration(하루 조율) = E2E 루프 유지
- Orchestration(언제·무엇) + Harness(어떻게) = 품질·방식 통일
- Harness(자동 검증) + Human(승인·배포) = 안전 게이트
- SSoT ↔ 전 계층 = 조직(또는 개인) 기억 공유

## Ouroboros 패턴 — 5단계 원형 루프

1. **Interview** — 인터뷰·맥락 수렴, 숨은 가정·요구 파악 → `brainstorming` Skill
2. **Seed** — 스펙 고정, Seed Spec 저장 → `writing-plans` + SSoT(adr/spec)
3. **Execute** — 스펙 기반 구현 → LLM Agent (Cursor, Claude Code)
4. **Evaluate** — 품질 검증, Quality Gate → **L1~L4로 심화**
5. **Evolve** — 피드백 루프, 평가 결과→다음 Seed → **L1~L4로 심화**

Evolve → Seed 피드백 화살표로 다음 세대 스펙 업데이트.

### L1~L4 Harness 루프 (Evaluate + Evolve 심화)

| 레벨 | 이름 | 내용 |
|------|------|------|
| L1 | 검증 루프 (Quality Gate) | lint·test·인수기준·ponytail-review. 실패 시 완료 차단 |
| L2 | 회고 주입 (Context Loop) | collect-history → inject-history → context.md 자동 주입 |
| L3 | 메타 학습 (Instructions Loop) | /reflect → instructions·agents 개선. **Human 승인 필수** |
| L4 | 자율 진화 (Autonomous) | Hermes GEPA·SKILL.md 자율 생성. L3과 달리 자동 반영 가능 |

## 도구 역할 (일반화)

| 도구 | 역할 | 시점 |
|------|------|------|
| 티켓·승인 | 태스크 관리, Human 승인 게이트 | 출근, 완료 |
| LLM Agent | IDE/CLI 코드 편집, MCP | 업무 실행 |
| Hermes | gateway, Skills, cron | 퇴근 job, 08/22 cron |
| Git SSoT | adr, docs, skills, 맥락 기록 | 업무 완료 후 누적 |
| Skills | brainstorming, ponytail, e2e 등 레시피 | 단계별 호출 |
| Cowork | 비개발 산출물, 문서 E2E | 기획 doc, 리포트 |

## 블로그 글 각도 제안

- 제목 예: "AI E2E 워크플로우 — Ouroboros 패턴으로 맥락을 잃지 않기"
- 개인 프로젝트에서 Hermes cron + Git SSoT + Cursor/Claude Code를 조합한 경험담
- 10단계 루프를 "왜 채팅만으로는 부족한가"에서 도입
- Ouroboros 5단계와 L1~L4를 실무에 어떻게 매핑했는지
- Mermaid 다이어그램 1~2개 OK (10단계 루프, Ouroboros 5단계)
- Hermes cron 08:00/22:00 예시는 일반 설정 예시로 (회사 cron 이름·채널 ID 금지)
