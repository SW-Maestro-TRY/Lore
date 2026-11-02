-- haeun · 2026-10-30 16:59 KST
-- 목적: 편집실에서 장 위에 직접 그린 것(붓·지우개 선)을 장마다 담는다.
--   말풍선(webtoon_overlay)과 같은 줄에 넣지 않는다 — 둘 다 자동 저장으로 통째로 덮어써서, 한 줄을
--   같이 쓰면 거의 같은 때 온 두 저장이 서로의 것을 지운다. 원본 그림은 그대로 두고 굽기 때만 합친다.
create table if not exists webtoon_overlay_paint (
    id         bigserial    primary key,
    run_id     varchar(120) not null,
    episode    integer      not null,
    page_no    integer      not null,
    data_json  text         not null,
    updated_at timestamptz  not null,
    constraint uk_webtoon_overlay_paint unique (run_id, episode, page_no)
);
