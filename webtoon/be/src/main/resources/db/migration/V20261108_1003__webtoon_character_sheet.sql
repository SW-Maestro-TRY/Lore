-- haeun · 2026-11-08 10:03 KST
-- 목적: 캐릭터마다 캐릭터 시트 하나를 기억해 두고, 같은 캐릭터로 다음 작품을 만들 때 시트를 다시 그리지 않는다.
-- 시트는 제일 비싼 호출 하나인데 작품마다 새로 그려서 돈도 들고 얼굴도 조금씩 달라졌다(2026-11-08 실험: 같은 시트로
-- 그림체를 바꿔 그려도 얼굴이 유지된다 → 그림체와 무관하게 하나만 둔다).
--
-- sheet_key   : 시트 그림(sheet.png)의 비공개 창고 키. 비어 있으면 아직 없다
-- sheet_spec  : 시트 사양(sheet_spec.json) 원문 — 그림만 가져오고 사양이 다르면 장 그림이 다른 사람을 그린다
-- sheet_mode  : 어느 길의 작품에서 나온 시트인가(quick · own · play). 세계관 체험(play)은 옷을 세계에 맞게 바꾸므로
--               체험 시트는 체험끼리도 재사용하지 않고, 일반 작품에서도 쓰지 않는다
-- sheet_at    : 기억한 시각. 캐릭터 설명·그림을 바꾸면 전부 비운다(다음 작품에서 새로 그린다)
ALTER TABLE webtoon_character ADD COLUMN IF NOT EXISTS sheet_key  VARCHAR(300);
ALTER TABLE webtoon_character ADD COLUMN IF NOT EXISTS sheet_spec TEXT;
ALTER TABLE webtoon_character ADD COLUMN IF NOT EXISTS sheet_mode VARCHAR(20);
ALTER TABLE webtoon_character ADD COLUMN IF NOT EXISTS sheet_at   TIMESTAMPTZ;
