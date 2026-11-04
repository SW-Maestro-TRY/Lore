-- haeun · 2026-11-04 19:06 KST
-- 목적: 작품 카드의 썸네일 — 장 그림 하나에서 잘라 낸 한 장면.
-- 표지가 없는 작품(세계관 체험 만화)은 장 그림을 통째로 보이면 흰 여백과 작은 컷이 섞여 누르고 싶지 않다(하은).
-- 기본은 서버가 가장 큰 컷을 골라 자르고(by_owner=false), 주인이 장과 자리를 골라 바꿀 수 있다(by_owner=true).
--
-- page          : 어느 장에서 잘랐나
-- x · y · w · h : 그 장 안에서 자른 자리(0~1 비율)
-- s3_key        : 잘라 낸 그림(JPEG). 늘 비공개 자리에 두고 잠깐 열리는 주소로 낸다 — 작품 공개 범위가 바뀌어도 옮길 필요가 없다
-- 작품(run_id)을 가리키지만 외래키는 안 건다(이 저장소 규칙).
CREATE TABLE IF NOT EXISTS webtoon_run_thumb (
    run_id     VARCHAR(64) PRIMARY KEY,
    page       INTEGER NOT NULL,
    x          REAL NOT NULL,
    y          REAL NOT NULL,
    w          REAL NOT NULL,
    h          REAL NOT NULL,
    s3_key     VARCHAR(300) NOT NULL,
    by_owner   BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
