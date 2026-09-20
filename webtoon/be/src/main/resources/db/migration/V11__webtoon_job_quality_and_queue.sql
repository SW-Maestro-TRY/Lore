-- 웹툰 작업에 「무엇으로 그렸나」와 「얼마나 기다렸나」를 적을 칸을 단다.
--
-- ── 왜 Flyway 인가 ──────────────────────────────────────────────────
-- 이 앱은 `ddl-auto: validate` 로 뜬다. 칸을 코드에만 더하면 **테스트는 전부
-- 통과하는데 서버가 아예 안 뜬다**:
--
--     Schema validation: missing column [finished_at] in table [webtoon_job]
--
-- 실제로 이 파일을 쓰기 직전에 그렇게 멈춰 섰다. 웹툰 쪽 칸이라 마이그레이션도
-- 웹툰 폴더에 둔다 — gradle 이 도메인별 resources 를 한 classpath 로 합치므로
-- (`build.gradle` 의 sourceSets), `apps/api` 를 안 건드려도 같은
-- `classpath:db/migration` 으로 들어간다.
--
-- ★ 번호(V11)는 **도메인이 같이 쓰는 한 줄**이다. 다른 도메인이 같은 번호를
--   쓰면 둘 중 하나는 영영 안 돈다. 새 마이그레이션을 만들 때는 이 폴더와
--   `apps/api/.../db/migration` 을 **둘 다** 보고 다음 번호를 잡는다.
--
-- ── IF NOT EXISTS 인 이유 ───────────────────────────────────────────
-- 예전에 `ddl-auto: update` 로 돌던 DB 에는 `quality` 가 이미 생겼을 수 있다.
-- 그런 DB 에서도 그냥 지나가야 한다.

-- 얼마나 촘촘히 그렸나 — wave(물결) · surf(파도) · swell(너울).
-- 옛 작업은 비어 있다. 읽는 쪽(WebtoonQuality.normalize)이 기본값으로 돌린다.
ALTER TABLE webtoon_job ADD COLUMN IF NOT EXISTS quality VARCHAR(20);

-- 줄에서 빠져나와 **실제로 돌기 시작한 때.** created_at 과의 차이가 기다린
-- 시간이다. 이걸 안 적으면 "얼마나 밀리는가" 를 영영 못 잰다 — 만들기는 한
-- 번에 한 편씩 도는데 그 대기가 아무 데도 안 남아 있었다.
ALTER TABLE webtoon_job ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ;

-- 끝난 때(다 됐거나 실패했거나). started_at 과의 차이가 만든 시간이다.
-- 사람이 시트·이야기 앞에서 멈춘 시간이 여기 섞인다.
ALTER TABLE webtoon_job ADD COLUMN IF NOT EXISTS finished_at TIMESTAMPTZ;

-- 줄 설 때 앞에 몇 개 있었나 — 화면에 「앞에 3명」이라고 적어 준 그 숫자.
-- 나중에 실제로 기다린 시간과 맞춰 보면 우리 예상이 맞았는지 알 수 있다.
ALTER TABLE webtoon_job ADD COLUMN IF NOT EXISTS queued_ahead INTEGER;

-- 줄을 셀 때마다 도는 조회다(화면이 0.8초마다 묻는다).
-- status 로 거르고 created_at 으로 줄을 세우므로 둘을 같이 잡는다.
CREATE INDEX IF NOT EXISTS idx_webtoon_job_queue
    ON webtoon_job (status, created_at);
