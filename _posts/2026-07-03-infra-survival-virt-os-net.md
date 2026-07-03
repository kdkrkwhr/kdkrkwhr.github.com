---
layout: post
author: Kim, DongKi
title:  "온프레미스 인프라 학습 ② — 가상화·OS·네트워크"
date:   2026-07-03
categories: devOps
comments: true
---

### TL;DR

* **Physical vs Logical** — 물리 서버 1대 ≠ 논리 서버 1대. VM·LPAR 여러 개가 한 베어메탈 위에 올라갑니다.
* **vCPU 착각** — vCPU를 늘린다고 다른 VM 코어를 빼오는 게 아닙니다. Hot-Add도 물리 한계 안에서만 의미가 있습니다.
* **Latency vs Bandwidth** — 지연(ms)과 대역(Gbps)은 별개입니다. 대역이 넉넉해도 Latency Δ가 100배면 장애 신호입니다.
* 2주차는 [인프라 생존기](https://infra-survival.tistory.com/) 11~13, 17~22, 44~50편으로 **숫자 읽기**를 잡습니다.

----
### 들어가며

1편에서 **AG → LDEV → LUN → Datastore** 계층을 잡았습니다. "VM 디스크가 어디에 물려 있는지"는 보이기 시작했죠. 그런데 장애 티켓이 "CPU 부족", "네트워크 느림"으로 들어오면, 다시 VM 콘솔만 보게 됩니다.

저도 개인 학습으로 [인프라 생존기 : 무너진 성벽의 기록](https://infra-survival.tistory.com/)을 이어 읽으며, **가상화·OS·네트워크** 층 용어를 정리했습니다. 2편 목표는 "vCPU 몇 개" 너머의 **논리/물리 구분**과, OS·네트워크에서 **팩트 확인 도구**를 손에 익히는 것입니다.

----
### 가상화 (VMware / LPAR)

| 용어 | 한 줄 정의 |
|------|------------|
| **Physical vs Logical** | 물리 서버 1대 ≠ 논리 서버 1대 (VM·LPAR 여러 개) |
| **vCPU / Hot-Add** | VM에 줄 CPU. **다른 VM 코어 빼오기** 불가 |
| **Datastore** | VM 디스크가 올라가는 **공유 스토리지** (80% 임계) |
| **Thin vs Thick** | 디스크 **얇게/두껍게** 프로비저닝 |
| **vMotion / P2V** | VM 이동 · 물리→가상 이전 |
| **LPAR** | IBM Power **논리 파티션** (AIX) |

**vCPU 착각**이 가장 흔합니다. "vCPU 8개로 늘렸는데 왜 느리지?" — 물리 코어가 16개인 호스트에 VM 20대가 깔려 있으면, 논리 CPU 합은 물리를 훨씬 넘습니다. **Overcommit** 상태에서 vCPU 숫자만 믿으면 안 됩니다.

**Thin vs Thick**도 Datastore 용량 착각의 원인입니다. Thin은 실제 쓴 만큼만 잡히고, Thick은 처음부터 전부 예약합니다. Datastore 사용률 80% 경고가 뜰 때, Thin 디스크의 **실제 growth**까지 같이 봐야 합니다.

**vMotion**은 VM을 다른 호스트로 옮기는 기능입니다. 스토리지가 공유되어 있어야 하고, 1편의 SAN Fabric·Datastore 계층과 직결됩니다. LPAR은 AIX 쪽 논리 파티션으로, VMware와 비슷하게 **한 물리 머신·여러 논리 서버** 구조입니다.

----
### OS · Unix / Linux

| 용어 | 한 줄 정의 |
|------|------------|
| **du / inode** | 디스크 **용량 vs 파일 개수** (quota 착각 방지) |
| **Swap / swappiness** | 메모리 부족 시 디스크 사용. **100% 장기 방치** = 문제 |
| **nmon** | CPU·I/O·메모리 **성능 스냅샷**. 중복 실행·rotation 주의 |
| **grep / kill** | 로그·프로세스 **팩트 확인** (감 vs 데이터) |
| **crontab** | 배치 스케줄. **MOP·백업** 없이 수정 금지 |

**du vs inode** — `df`는 용량, inode는 파일 개수입니다. "용량 남았는데 쓰기 실패"는 inode 고갈일 수 있습니다. quota를 용량으로만 이해하면 여기서 헛발질합니다.

**Swap 100%** — 스왑이 꽉 찬 채로 오래 가면, 디스크 I/O wait가 올라가고 전체가 느려집니다. 1편의 I/O wait·Write Latency와 연결됩니다. swappiness 값만 만지기 전에 **왜 스왑을 쓰는지**부터 봅니다.

**nmon**은 한 화면에 CPU·메모리·디스크·네트워크를 스냅샷으로 남깁니다. 다만 중복 실행·로그 rotation 설정을 모르면 "측정했다"는 착각만 남습니다.

**grep / kill** — "스토리지 탓"이라고 말하기 전에 alert log·syslog에서 **한 줄**이라도 근거를 잡는 습관. kill은 프로세스 정리용이지만, crontab·배치 작업은 **롤백 계획 없이** 건드리지 않습니다.

----
### 네트워크

| 용어 | 한 줄 정의 |
|------|------------|
| **Port Down / CRC** | SAN·라인 **물리 계층** 장애 |
| **Latency vs Bandwidth** | **지연**과 **대역**은 별개 |
| **L2 / L3** | 스위치 **MAC / 라우팅** 계층 |

**Port Down·CRC error**는 물리 층 문제입니다. SAN Fabric(1편) 스위치 포트가 down이거나 CRC가 올라가면, 위 VM·OS는 정상처럼 보여도 I/O가 흔들립니다.

**Latency vs Bandwidth** — 10Gbps 링크여도 Latency가 튀면 NAS·DB 응답이 느려집니다. 절대값 2ms가 "작다"고 안심하지 말고, **평소 대비 Δ**를 봅니다. 45편의 Write Latency 100배 사례가 대표적입니다.

**L2/L3** — L2는 MAC·스위치, L3는 IP·라우팅입니다. "핑은 되는데 앱이 느리다"면 L7까지 가기 전에 **어느 계층에서 지연·패킷 손실**이 나는지 좁혀야 합니다.

----
### 2주차 학습 로드맵

1주차(56~58)에서 계층 감각을 잡았다면, 2주차는 **성능·장애 숫자 읽기**입니다.

| 순서 | 글 | 주제 |
|------|-----|------|
| 1 | [11~13편](https://infra-survival.tistory.com/11) | I/O, swap, swappiness |
| 2 | [17~22편](https://infra-survival.tistory.com/17) | vCPU, APM |
| 3 | [44~50편](https://infra-survival.tistory.com/44) | NAS 대장애, grep |

11~13편에서 I/O wait·swap 패턴을, 17~22편에서 vCPU·APM(앱 트랜잭션 trace)으로 "스토리지 탓"을 반박하는 방법을, 44~50편에서 NAS Latency·grep 한 줄 디버깅을 익힙니다. 세 덩어리를 다 읽고 나면 **Latency·I/O wait·스왑 숫자**를 같은 언어로 말할 수 있어야 합니다.

----
### 마치며

2편에서는 **가상화(vCPU·Datastore·Thin/Thick·vMotion·LPAR)**, **OS 도구(du·swap·nmon·grep)**, **네트워크(Port Down·Latency vs Bandwidth·L2/L3)** 를 정리했습니다. 1편 스토리지 계층과 겹치는 지점 — Datastore 아래 LUN, I/O wait와 Write Latency — 을 의식하면 "VM만 보는" 습관이 줄어듭니다.

다음 3편에서는 **DB(RAC/ASM·alert log)**, **백업(NetBackup·스냅샷 착각)**, **운영(MOP·CMDB·흔한 착각 패턴)** 으로 시리즈를 마무리할 예정입니다. 2주차 로드맵부터 읽어 보시면 이어지기 좋습니다.
