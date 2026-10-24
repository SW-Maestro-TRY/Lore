-- haeun · 2026-10-24 21:17 KST
-- 목적: #778 분석 조회에서 멘토님 · 지인 계정을 가르는 표시.
--
-- 운영 데이터를 볼 때 멘토님과 지인의 활동이 사용자 숫자에 섞인다. 지금까지는
-- analytics.md 의 SQL 안에 계정 번호를 직접 적어 두고(in (135, 80, 81)) 쿼리마다 옮겨
-- 적었다. 이 표에 한 번 넣어 두면 어느 쿼리든 같은 명단으로 가른다.
-- 화면 · API 는 이 표를 읽지 않는다(조회용).
--
-- ── 한 줄이 한 계정이다 ───────────────────────────────────────────
-- kind  MENTOR(멘토님) | FRIEND(지인). 조회 때 「전체 / 이 둘을 뺀 것 / 이 둘만」을 고른다.
--
-- ── 이름 · 이메일을 넣지 않는 이유 ─────────────────────────────────
-- 계정 번호로 users 와 이어서 누구인지 알 수 있다. 이 표에 이름을 따로 두면 탈퇴 정리
-- (WebtoonPurge)가 지워야 할 개인정보가 하나 늘어난다. 남는 줄은 번호와 구분뿐이라
-- 외래키 없이 둔다.
--
-- 실제 계정은 마이그레이션에 적지 않고 표가 배포된 뒤 SQL 로 넣는다. dev · staging 에는 같은
-- 번호의 사람이 없고, 이메일을 저장소에 남기지 않기 위해서다.
CREATE TABLE IF NOT EXISTS webtoon_internal_user (
    user_id    BIGINT       PRIMARY KEY,
    kind       VARCHAR(16)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_webtoon_internal_user_kind CHECK (kind IN ('MENTOR', 'FRIEND'))
);
