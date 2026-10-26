-- AI 캐릭터 카드를 사용자가 고친 때. 비어 있으면 AI 가 지은 그대로다.
-- 반전 한 줄(twist)을 사용자가 지워도 카드로 남게 하려고 둔다 — 전에는 twist 가 비면 카드가 없는 것으로 봤다.
alter table webtoon_character add column if not exists card_edited_at timestamptz;
