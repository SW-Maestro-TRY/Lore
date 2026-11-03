-- haeun · 2026-11-03 23:55 KST
-- 목적: 웹툰 API 호출과 뒤에서 도는 작업(세계관 AI 채우기)을 한 줄씩 남긴다.
-- 광고로 들어온 사람이 무엇을 불렀고, 얼마나 걸렸고, 왜 실패했는지를 나중에 찾으려고.
--
-- kind          : api(웹툰 API 요청 한 번) · task(뒤에서 도는 작업 한 번)
-- method · path : 요청 방식과 주소. shape 는 작품·작업 번호를 {id} 로 접은 모양(묶어 세기용)
-- status · ms   : 응답 코드와 걸린 밀리초. task 는 성공 200 · 실패 500
-- user_id · browser_uid : 누가. 로그인 전이면 브라우저 번호만
-- request_body  : JSON 본문(8,000자까지). 비밀번호·토큰·카드·전화·이메일 칸은 *** 로 가린다
-- response_body : 오류 응답, 그리고 GET 이 아닌 요청의 응답 앞부분(2,000자까지)
-- error         : 예외 첫 줄, task 실패 이유
-- ref           : task 가 쓴 비용 기록(webtoon_usage.run_id) — 토큰·원가는 거기서 이어 본다
-- 보관 90일(ApiLogRetention). users 를 가리키지만 외래키는 안 건다(이 저장소 규칙). 탈퇴 정리(WebtoonPurge)가 지운다.

CREATE TABLE IF NOT EXISTS webtoon_api_log (
    id             BIGSERIAL PRIMARY KEY,
    at             TIMESTAMPTZ NOT NULL,
    kind           VARCHAR(10) NOT NULL,
    method         VARCHAR(16) NOT NULL,
    path           VARCHAR(300) NOT NULL,
    shape          VARCHAR(160) NOT NULL,
    query          VARCHAR(500),
    status         INTEGER NOT NULL,
    ms             INTEGER NOT NULL,
    user_id        BIGINT,
    browser_uid    VARCHAR(64),
    request_body   TEXT,
    response_body  TEXT,
    error          TEXT,
    ref            VARCHAR(120)
);
CREATE INDEX IF NOT EXISTS idx_webtoon_api_log_at ON webtoon_api_log (at);
CREATE INDEX IF NOT EXISTS idx_webtoon_api_log_shape ON webtoon_api_log (shape, at);
CREATE INDEX IF NOT EXISTS idx_webtoon_api_log_uid ON webtoon_api_log (browser_uid, at);
CREATE INDEX IF NOT EXISTS idx_webtoon_api_log_user ON webtoon_api_log (user_id, at);
