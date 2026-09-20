-- 운영 경보 — <b>무엇을 이미 알렸는가</b>를 남기는 표.
--
-- ★★ 왜 DB 인가 — 메모리에 두면 배포할 때마다 같은 메일이 다시 간다.
--   비용 $10 단위 알림이 특히 그렇다. "지금까지 보낸 금액" 이 메모리에 있으면 서버가 뜰 때마다
--   0 에서 다시 세어, 이미 알린 $10·$20·$30 이 통째로 한 번 더 나간다. 배포는 하루에도 여러 번
--   일어나므로 그 메일은 곧 무시당하고, 정작 중요한 알림도 같이 무시당한다.
--
-- ★ 줄이 아주 적다(경보 종류 수만큼, 지금 넷). 이력을 쌓지 않고 <b>마지막 값</b>만 덮어쓴다 —
--   보낸 이력은 메일함에 남고, 여기 필요한 것은 "또 보내도 되나" 하나뿐이다.
--
-- ★★ 판정은 이 표에 쓰는 UPSERT 한 문장이 한다(코드가 읽고 나서 쓰는 것이 아니다).
--   읽고 나서 쓰면 두 스레드가 같은 값을 읽고 둘 다 보낸다. 값이 바뀔 때만 갱신되는
--   INSERT … ON CONFLICT … WHERE 가 1 을 돌려준 쪽만 메일을 보낸다.

CREATE TABLE zzal_alert_state (
    -- 경보 종류. cost · hatch_fail_streak · service_daily_cap · night_bake_failed
    alert_key character varying(40) PRIMARY KEY,
    -- 그 종류로 <b>마지막에 알린 지점</b>. 무엇이 들어가는지는 종류마다 다르다:
    --   cost              = 마지막으로 알린 임계 금액(달러, 정수 문자열) — "30"
    --   hatch_fail_streak = 알린 연속 실패 구간의 <b>첫 펫 id</b>(구간이 이어지는 동안 안 바뀐다)
    --   service_daily_cap = 상한에 닿은 날(KST) — "2026-09-18"
    --   night_bake_failed = 실패를 알린 밤(KST 날짜)
    last_value character varying(60) NOT NULL,
    sent_at timestamp(6) with time zone NOT NULL
);
