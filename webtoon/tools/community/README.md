# 작품 커뮤니티 — 로컬 구현 및 검증

## 구현 범위

- 공개된 완료 작품에서 작품 단위 구독/취소, 구독자 수, 회차별 댓글 작성·수정·삭제·신고.
- 마이페이지에서 새 화·새 댓글·공개 캐릭터 재사용 알림, 읽음 처리, 구독 목록.
- 내 캐릭터의 명시적 재사용 동의·공개·철회, 공개 캐릭터 목록과 생성 위저드 연결.
- 생성 요청에 캐릭터 출처 저장, 완성된 공개 작품에 원작 캐릭터 연결, 원작자 알림.
- 관리자 신고 처리, 비공개·삭제 작품의 내용/알림 차단, 영구삭제·탈퇴 연계 정리.

알림은 **서비스 내부 마이페이지 알림**이다. 이메일/브라우저 푸시는 포함하지 않는다. 완료 및 공개 상태를 15초마다 확인하고 트랜잭션 발행 장부로 중복 발송을 막는다. 마이페이지 진입 또는 새로고침으로 소식을 가져온다.

## 연재 모듈 계약

`JdbcSeriesIdentity`는 `webtoon_series_chapter(job_id, root_job, episode_number)`를 읽는다. job_id와 root_job은 webtoon_job.public_id 문자열이며 run_id나 숫자 DB PK가 아니다. 첫 화는 행이 없으므로 자체 job ID가 root, 회차 1이다. 후속 화는 별도 job/run을 가지며 root가 같다. 테이블이 없는 환경에서는 각각 단편으로 동작한다.

구독 키는 root_job, 댓글 키는 run_id다. 발행 시 `webtoon_job.status='DONE'`과 공개·미삭제 `webtoon_work`가 모두 필요하다. 비공개 완료 후 공개 전환도 새 발행으로 처리한다. 마이그레이션 이전 DONE 작업은 발행 장부에 넣어 소급 알림을 막는다.

이 작업은 다음 화 생성 자체를 구현하는 별도 연재 작업과 독립적으로 작성됐다. fixture의 최소 연재 테이블로 연동을 검증했으며 연재 담당이 최종 job_id/root_job/episode_number 계약 일치를 확인했다. 실제 연재 생성 코드와 합친 검증 및 유료 AI 생성은 별도다. 기존 ‘같은 캐릭터의 다른 편’ 추천은 진짜 연재 관계로 사용하지 않는다.

## 검증 결과 (2026-10-23)

- `npm run build --workspace lore-web`: 통과.
- 아래 선택 서버 테스트: 49개, 실패/오류/건너뜀 0.
- `scripts/check-migrations.sh`: 85개 마이그레이션, 경고 0.
- `api-smoke.py`: 로그인/소유권/비공개 차단, 동시 중복 구독, 댓글 길이·작성 간격·수정 소유권, 알림 읽음 소유권, 캐릭터 공개 동의·철회, 새 화 발행·중복 방지, 회차 댓글 분리, 출처, 신고/관리자 숨김 통과.
- 실제 앱에서 데스크톱/390px 모바일 확인: 구독 저장·댓글 저장, 마이페이지 알림→2화 이동·읽음 반영, 공개 캐릭터→위저드의 이름/설명/그림 전달.
- 공개 캐릭터 아트 읽기 실패 시 과금/작업 생성 전에 중단하고, 성공 시 생성 입력/출처 전달을 mock runner 테스트로 확인했다. 실제 모델은 호출하지 않았다.

## 재현

운영 DB를 사용하지 않는다. 기존 마이그레이션이 완료된 **비어 있는 전용 로컬 DB**를 준비하고 이 변경의 마이그레이션을 실행한다. `fixture.sql`은 가짜 사용자 91001–91003, community-* 작업/캐릭터를 추가한다. 최소 연재 테이블은 별도 연재 모듈이 없는 환경용이며 운영 마이그레이션이 아니다.

```sh
psql -h localhost -U mint -d lore_community_20261023 -v ON_ERROR_STOP=1 -f webtoon/tools/community/fixture.sql
COMMUNITY_TEST_API=http://localhost:8097 COMMUNITY_TEST_DB=lore_community_20261023 python3 webtoon/tools/community/api-smoke.py
COMMUNITY_TEST_DB=lore_community_20261023 ./gradlew test --tests 'com.lore.webtoon.community.*' --tests 'com.lore.webtoon.character.*' --tests 'com.lore.webtoon.job.JobServiceGuestTest' --tests 'com.lore.webtoon.job.PublicCharacterGenerationTest' --tests 'com.lore.webtoon.work.RunDeleteServiceTest' --tests 'com.lore.webtoon.retention.WebtoonPurgeTest' --no-daemon
```

API 테스트는 localhost URL과 lore_community_ 접두 DB만 허용한다. 로컬 개발용 JWT 키로 가짜 사용자를 인증한다. 서버 DB도 같은 격리 DB를 가리켜야 한다. 테스트는 fixture의 댓글·알림·공개 상태를 초기화하며, 출처/완료 결과는 시뮬레이션 데이터다. 실제 사용자 자료를 복사하지 않는다.

## 반영 상태와 제한

전용 `feature/webtoon-community` 브랜치의 변경이다. 운영 DB 마이그레이션과 배포는 실행하지 않았다. 서비스 전체에 닉네임/프로필이 없어 댓글과 출처는 `독자 <ID>`, `창작자 <ID>`로 표시한다. 새 UI·공개 동의·알림 문구는 기존 한국어/영어/일본어/중국어 언어 설정을 따른다. 알림/구독 목록은 최신 100개, 댓글은 30개씩 더 보기, 공개 캐릭터는 24개씩 페이지를 제공한다.
