-- haeun · 2026-10-27 21:38 KST
-- 목적: 하루 생성 상한을 그날만 바꾼 기록. 관리자 화면 「오늘만 바꾸기」가 쓴다(SpendOverrides).
--   기본값은 설정(lore.webtoon.spend.*)이고 서버가 뜰 때 읽는다. 사람이 몰린 날 서버를 다시 띄우지 않고 올리려고 둔다.
--   지우지 않고 쌓는다 — 그날(한국 날짜)의 마지막 줄이 이기고, 금액 · 편수가 둘 다 비어 있는 줄은 「기본값으로」다.
create table if not exists webtoon_spend_override (
    id         bigserial   primary key,
    day        date        not null,
    daily_krw  bigint,
    daily_runs bigint,
    set_by     bigint,
    set_at     timestamptz not null default now()
);
create index if not exists webtoon_spend_override_day_idx on webtoon_spend_override (day, id);
