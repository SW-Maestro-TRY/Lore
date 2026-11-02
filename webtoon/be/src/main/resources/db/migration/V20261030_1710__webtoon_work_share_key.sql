-- 「링크 있는 사람만」 공개 단계(#898).
-- 링크 공개 = is_public 이 false 이고 share_key 가 있는 작품. 둘러보기·추천·남의 목록은 지금처럼 is_public 만 보므로
-- 거기서는 비공개와 똑같이 빠지고, 그림도 비공개 자리에 머문다. 열쇠가 맞는 주소로 온 사람만 읽는다.
-- 비어 있으면 예전 그대로(공개 또는 비공개)라 이미 있는 작품은 바뀌지 않는다.
ALTER TABLE webtoon_work ADD COLUMN IF NOT EXISTS share_key VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS uq_webtoon_work_share_key ON webtoon_work (share_key);
