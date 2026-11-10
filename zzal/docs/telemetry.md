# 짤 행동·업무 결과·호출·자원 기록

화면에서 시도한 행동, 서버에서 확정한 업무 결과, 외부 모델 호출을 구분한다.
복귀 후 직접 수행한 돌봄·게임·대화·설정 등의 행동은 활동이다. 채팅은 필수 조건이 아니다.
자동 조회·노출·자동 출석 지급은 직접 행동으로 세지 않는다. 실제 사용자 튜토리얼의 직접
행동은 활동에 포함하고, 샘플·테스트와 분리한다.

## 전송과 저장

`POST /api/v1/events`의 기존 입력에 선택적 `event_id`, `schema_version`, `session_id`,
`context`, `source_screen`, `actor_state`, `actor_id`를 추가했다. 새 프론트는 schema 2를 쓴다.
`context`는 `real`, `tutorial`, `sample`이다. 사용자 입력 원문은 일반 지표에 보내지 않는다.

- 202의 `data.status=persisted`와 `accepted_event_ids`는 트랜잭션 커밋 또는 기존 ID 확인을 뜻한다.
- `rejected_event_ids`는 재시도로 고칠 수 없는 입력이다. 429·503·응답 유실은 동일 ID로 재전송한다.
- 브라우저는 이벤트별 localStorage 키에 최대 500개를 보관한다. beacon 전송만으로 삭제하지 않는다.
- 저장 공간 거부·초과, 사이트 데이터 삭제, 마지막 방문 후 재방문 없음까지 배달을 보장하지 않는다.
- 서버 고유 키는 `(event_origin, event_id)`다. origin은 서버가 결정하므로 브라우저가 서버 결과를 선점할 수 없다.
- `reported_ts`는 원래 클라이언트 시각이다. 지연·잘못된 미래 시각을 `timestamp_quality`로 구분한다.

`actor_id`는 전송 시점에 검증한 로그인과 비교하는 힌트다. 힌트가 권한을 부여하지 않는다.
일치할 때만 `identity_status=matched`로 계정에 귀속한다. 계정 전환 후 이전 큐를 보내면
`mismatch`와 null user_id로 남긴다. mismatch·unverified 행을 익명 번호 조인으로 다시
다른 계정에 붙이지 않는다. 구형 `legacy_current`는 수신 당시 로그인 정보라는 한계를 갖는다.

## 서로 다른 기록의 의미

| 기록 | 의미 |
|---|---|
| `zzal_ui_shown` | 화면 노출, 직접 행동 아님 |
| `zzal_user_action` | 직접 행동 시도. 성공을 의미하지 않음 |
| `zzal_action_result` | 프론트에서 본 success/failure/uncertain/no_op |
| `zzal_tutorial_completion_observed` | 프론트가 관측한 안내 완료 |
| `zzal_server_*` | 업무 트랜잭션 커밋 뒤 기록한 결과 |
| `zzal_provider_usage` | 실제 제공자 요청 한 번. 재시도는 별도 호출 |
| `zzal_server_runtime` | 공유 API/JVM 자원과 짤 요청·생성 대기열 표본 |

기존 typed 행동도 남는다. 예를 들어 이미지 선택·온보딩 답변·USER 채팅은 이름과 속성을
대조해 활동에 합친다. 같은 계정의 generic·typed·서버 결과는 계정 집합으로 중복 제거한다.
구형 샘플과 mock 구분이 없는 행을 실제 사용자 활동으로 단정하지 않는다.

업무 결과의 최종 근거는 원래 업무 DB다. 이미지 작업 성공과 캐릭터 부화 완료는 다르다.
실패 작업 수를 실패 사용자 수로 표시하지 않는다. 다음 세트 지급·기억 저장 등 모든 업무
전환이 서버 이벤트로 export되는 것은 아니므로 원장과 이벤트의 범위를 구분한다.

제공자 기록에는 요청·응답 모델, 호출 ID, 상태·소요시간, input/output/cached 토큰,
숫자 usage가 들어간다. 프롬프트·대화·인증 헤더를 기록하지 않는다. 비용은 기존 서비스
계산식의 **추정값**이며 청구액이 아니다. 요율 버전을 기록하고 비용이 없으면 unknown이다.
기록 실패 때문에 유료 요청을 다시 실행하지 않는다.

## 자원·보관 설정

공유 API/JVM과 DB pool은 Lore 전체 자원이다. 짤만의 CPU·메모리로 해석하지 않는다.
짤 요청은 등록된 `/api/zzal/` URI 템플릿·method·status로 제한하며 원문 URL·쿼리를 저장하지 않는다.
카운터 초기 관측·재설정과 histogram 미수집을 명시한다. 샘플 개수나 창별 p95를 임의 합산하지 않는다.

| 설정 | 기본과 목적 |
|---|---|
| `ZZAL_METRICS_RUNTIME_ENABLED` | 앱 기본 false, dev/local compose 기본 true |
| `ZZAL_METRICS_RUNTIME_INTERVAL_MS` | 60000 이상, 자원 표본 간격 |
| `ZZAL_METRICS_QUEUE_ENABLED` | true, 기존 생성 작업 대기·실행 수. 최대 1001행/2초 |
| `ZZAL_METRICS_SPOOL_DIRECTORY` | 앱 기본 빈 값. dev/local은 전용 named volume의 절대경로 |

spool은 DB 커밋 전 기록을 fsync하고 재시작 시 같은 ID로 재전송한다. 손상 파일은 별도로
보존한다. 디스크 장애·용량 부족까지 무손실을 보장하지 않으며 오류·드롭 수를 확인한다.
운영·staging은 승격 전에 private persistent-volume 경로와 접근 권한을 별도로 검증한다.

커밋된 행은 기존 `zzal_event` S3 archive에 새 칼럼을 포함해 보관한다. DB 원본을 삭제하는
코드는 추가하지 않는다. S3 실제 버킷의 접근 정책·만료 정책과 보관 스케줄은 별도 배포 확인
항목이다. 코드가 있다는 이유로 모든 환경의 S3 보관이 활성화됐다고 판단하지 않는다.

## dev 실측

1. 새 마이그레이션 적용과 기존 계정·펫 조회를 확인한다.
2. 새 이미지→설정→부화→첫 만남→안내→일반 대화 및 돌봄만 하는 흐름을 구분해 플레이한다.
3. 네트워크 응답 유실 후 재전송에도 동일 ID가 한 행인지 확인한다.
4. 계정 전환 후 이전 큐가 새 계정 활동으로 붙지 않는지 확인한다.
5. 실제 업무 DB와 서버 완료 이벤트를 대조한다. 제공자 호출은 승인된 비용 범위에서만 실행한다.
6. runtime 활성화·최소 두 표본·shared scope·URI 템플릿·미수집 상태를 확인한다.
7. DB→gzip archive의 ID·시각·origin·usage와 실제 private 저장소 readback을 대조한다.
8. 미커밋 spool 재생은 격리 환경에서 장애를 주어 확인한다. 일반 QA 데이터베이스를 끊지 않는다.

`npm run test:zzal:telemetry`는 큐와 실제 hook의 통제된 테스트다.
`FOCUSED_TEST_URL=http://localhost:3203 npm run test:zzal:focused`는 현재 `/zzal` 컴포넌트에
엄격한 API 대역을 제공하는 320×568·390×844·430×932 브라우저 검사다. 실 서버·실 모델의
전체 플레이 검증과 구분한다. 기존 scrapbook 호환 테스트는 별도로 남긴다.
