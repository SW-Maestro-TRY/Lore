-- 캐릭터 휴지통(2026-10-26) — 지우면 바로 없애지 않고 15일 동안 휴지통에 둔다. 비어 있으면 살아 있는 캐릭터다.
alter table webtoon_character add column if not exists deleted_at timestamptz;
create index if not exists idx_webtoon_character_deleted on webtoon_character (deleted_at) where deleted_at is not null;
