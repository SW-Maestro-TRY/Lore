-- haeun · 2026-09-26 KST
-- 목적: 사용자가 넣은 설정이 카드 어디에 반영됐는지 보여 주려면(#329) 「넣은 것」과
--       「나온 것」이 나란히 남아 있어야 한다. 지금은 이름이 모델이 지은 이름으로
--       덮이고, 세계관은 프리셋 키만 남아 직접 적은 한 줄이 사라지며, 종은 안 남는다.
ALTER TABLE webtoon_character
    ADD COLUMN asked_name  varchar(60),   -- 사람이 적은 이름 그대로(비어 있으면 안 적음)
    ADD COLUMN asked_world varchar(120),  -- 사람이 고르거나 적은 세계관 그대로
    ADD COLUMN species     varchar(40);   -- 카드가 읽어 낸 종(사람 · 강아지 …)
