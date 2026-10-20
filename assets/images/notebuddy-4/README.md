# 노트버디 개발기 4 이미지 출처

아래 세 이미지는 notebook-pets 저장소 `414a859a73624f3075d3c85bfc5cced555dcbbd8`에서 편집 없이 복사했다. 원래 캡처 버전은 Anna 1.4.10이다.

| 블로그 파일 | 원본 경로 | 내용 |
| --- | --- | --- |
| `home-garden.png` | `anna/store/07-home-garden.png` | 기본 마당, 집과 길, Mori |
| `home-collection.png` | `anna/store/09-home-collection.png` | 데모 가구를 배치한 마당과 보유 목록 |
| `pixel-dungeon.png` | `anna/store/05-pixel-dungeon.png` | 심해 궁전 입장 전 미리보기 |

`home-inside.png`는 2026-10-20에 같은 저장소의 1.4.15 UI로 새로 캡처한 실내 화면이다. 기존 `anna/scripts/capture_store.mjs`의 읽기 전용 SDK와 `store_fixture.py`의 임시 게임 상태를 사용했다. 시작 화면에서 홈을 열고 실내 탭으로 전환해 1280×1000 화면을 그대로 저장했다. SDK 기록은 `status` 한 번뿐이며, 외부 요청·AI 호출·운영 저장 변경은 차단했다. 원본 캡처: 개발 환경 `.local/blog-part4-home-inside.png`.

모든 화면의 Mori는 기존 Anna 생성 초상화에서 무료 픽셀 변환한 기본 모습이다. 방향별 AI 스프라이트 예시로 표시하지 않는다. 가구 배치는 임시 코인으로 구매하고 게임 규칙을 통과한 후보를 적용한 데모이며, 실제 사용자 소유 내역이나 새 LLM 응답이 아니다. 본문 캡션에도 이 구분을 표시했다.

이번 글을 위해 새 AI 이미지를 생성하거나 운영 데이터를 수정하지 않았다. 1.4.13 공개 버전부터 검사 시점까지 `home-sprite.mjs`, `home-scene.mjs`, `home-walk.mjs`, `dungeon-avatar.mjs`의 내용은 동일하다.

- [스토어 캡처 출처와 재현 방식](https://github.com/kdkrkwhr/notebook-pets/blob/414a859a73624f3075d3c85bfc5cced555dcbbd8/anna/store/README.md)
- [Mori 참고 그림·초상화 생성 기록](https://github.com/kdkrkwhr/notebook-pets/blob/414a859a73624f3075d3c85bfc5cced555dcbbd8/anna/store/demo/provenance.json)
