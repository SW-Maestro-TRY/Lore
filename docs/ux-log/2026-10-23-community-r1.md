# 작품 커뮤니티 UI 구현·검증 기록

기준: develop 03386cc8, 작업 경로 `/Users/mint/.codex/worktrees/webtoon-community/Lore`.
사용자 확인: 기획 제안이 아니라 실제 기능 구현. 연재 별도 채팅에 연동 내용 전달 승인.

## 기존 화면 확장 방향

기존 PRODUCT.md와 바다 팔레트·버튼·타이포 토큰을 유지했다. 읽기 화면에는 작품을 읽은 뒤 구독/회차 댓글을, 마이페이지에는 알림과 구독 목록을, 캐릭터 화면에는 공개 설정과 공개 캐릭터 선택을 추가했다. 별도 랜딩/비주얼 체계 교체는 없다.

- READ/OPERATE: 본문 감상 다음에 짧은 구독 안내와 버튼, 회차 제목, 댓글 입력과 목록.
- 명확한 상태: 로그인 유도, 빈 목록, 로딩/오류, 저장 중, 댓글 글자 수, 구독 상태, 미읽음 수.
- 제어: 구독 취소, 댓글 수정/삭제 확인, 캐릭터 공개 철회, 신고.
- 원작 권한: 로그인 창작자의 명시적 재사용 동의 후 공개. 원본 사진을 공개하지 않으며 이미 생성된 작품은 철회 후에도 남는다는 안내.
- 기기: 데스크톱 1280px, 모바일 390px. 기존 고정 하단 버튼은 full-page 캡처에서 중간에 찍히므로 실제 스크롤로 조작 가능 여부를 함께 확인.
- 자산: 기존 샘플 이미지를 로컬 테스트 fixture에서만 사용. 신규 이미지 생성 없음.

이는 구현 완료 상태에서 기록한 확장 근거이며 사전 승인된 시안이 있었다고 주장하지 않는다.

## 실행한 검증

Impeccable context 실행, PRODUCT.md 직접 확인, detect 1회 결과 `[]`. 실제 앱에서 구독/댓글 저장, 마이페이지 알림 이동과 읽음 반영, 공개 캐릭터의 위저드 전달을 확인했다. 캡처는 `/Users/mint/project/Lore/haeun/community-2026-10-23/`에 저장했다. UX heuristics 독립 평가 및 Impeccable finish review 결과는 아래에 기록한다. 사용자 인터뷰나 사용성 테스트 참가자는 없으며 검증은 개발자의 조작과 휴리스틱 평가다.

## 독립 검토와 수정 배치

finish reviewer: fix. UX heuristics 7/10 (다국어 일관성 severity 3, 처리 상태와 터치 영역 severity 2). 기존 조작 화면의 local extension이므로 new-work.md의 “Never run the script for a local extension ... shape those directly”에 따라 concept roll/FORM seed/QUALITY BAR 카드는 비대상이며 존재하지 않는 seed를 만들지 않았다.

수정 배치 1: 기존 useT/registerDict에 EN/JA/ZH 전체 화면·동의·알림·오류 사전 연결. 처리 중 role=status 및 주요 버튼 문구 추가. 댓글/신고/취소 보조 버튼의 터치 영역 최소 44px 적용. 빌드 통과 후 같은 뷰포트와 언어별 화면을 재확인한다.

## 디자인 시스템 문서화 확인

Impeccable documenter가 기존 시각 체계의 일반 확장으로 판정했다. 새로운 world 또는 승인된 전역 시스템 변경이 없으므로 DESIGN.md와 .impeccable/design.json을 만들지 않았다. document.md, PRODUCT.md, 공통 타입 토큰, webtoon.css, Community.css와 연결 컴포넌트를 대조했다. documenter의 파일 쓰기는 sandbox로 차단되어 주 작업자가 검토 내용을 이 로그에 반영했다.

- 실제 .wt의 ink/sea-deep/paper/muted/line을 계승한다. Noto Sans KR 기반, 공통 14/15/16px, 커뮤니티 제목20px, 캐릭터명18px, 보조12/13/14px. 기존 pill 버튼과12px 모서리, 선 중심 구분을 사용한다.
- 만화 뒤 커뮤니티, 마이페이지 알림, 캐릭터 공개·재사용 흐름. 공개 캐릭터3열→680px 이하2열→380px 이하1열.
- 상태는 처리중 status, 오류 alert, 동의·취소, 긴 번역 줄바꿈. 미정의 sea-sand 참조는 기존 paper로 수정했고 주 작업자가 파일에서 확인했다.
- PRODUCT 팔레트 명칭과 실제 선언 차이, 기존 반복 소제목, 작은 버튼과 전역 zoom은 이번 전역 재설계 범위 밖 관찰이며 새 규칙으로 채택하지 않는다.

문서화 담당은 일본어 동의 캡처를 직접 열어 확인했고 다른 캡처는 파일 존재만 확인했다. 이는 실제 조작과 finish verdict를 대신하지 않는다.

일본어 재검증: 상속 keep-all과 긴 버튼의 줄바꿈을 해당 화면에 한정해 수정했다. viewport390/scrollWidth375로 가로 넘침이 해소됐다. 한·영·일·중 알림/공개 동의 문구를 실제 앱에서 확인했다. 느린 응답 검증은 전용 DB의 가짜 사용자/캐릭터/신고 행에 짧은 트랜잭션 잠금을 걸어 수행했다. 댓글·신고·공개 변경의 처리 중 상태를 캡처하고 응답 완료도 확인했다. 공개 설정 변경 시 공개 목록을 다시 불러오도록 연결했다.

## 최종 판정

finish reviewer의 최종 disposition은 **ship**, 앞서 지적한 네 수정 항목(기존 확장 근거, 다국어, 처리 상태, 터치 영역)을 모두 resolved로 판정했다. 이 판정은 해당 수정 목록의 검증이며 전체 서비스/운영 배포 승인이 아니다. 처리 상태는 busy-comment/report/publication/inbox-mobile.png의 실제 지연응답 캡처로 확인했다. 최종 알림 읽음 응답 후 미읽음 표시가 사라지는 것도 직접 확인했다. 문서화까지 완료했다.
