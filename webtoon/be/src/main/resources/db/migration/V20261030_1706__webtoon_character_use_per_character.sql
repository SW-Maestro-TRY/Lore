-- 작품 하나가 공개 캐릭터를 둘까지 쓸 수 있다(주인공 여러 명, #899).
-- 전에는 job_id 가 기본 키라 두 번째 캐릭터의 사용 기록이 on conflict do nothing 으로 조용히 버려졌다.
-- 기존 행은 작품마다 하나뿐이라 그대로 새 키를 만족한다.
ALTER TABLE webtoon_character_use DROP CONSTRAINT webtoon_character_use_pkey;
ALTER TABLE webtoon_character_use ADD PRIMARY KEY (job_id, character_id);
