-- haeun · 2026-09-26 12:51 KST
-- 목적: 작품 지우기를 바로 영구 삭제하지 않고 휴지통에 넣는다(#157). 지운 시각을
--       적어 두고, 그 시각이 있는 작품은 목록·둘러보기·공유 주소에서 숨긴다.
--       정한 기간(기본 30일)이 지나면 예약 작업이 예전 영구 삭제로 지운다.
ALTER TABLE webtoon_work
    ADD COLUMN deleted_at TIMESTAMPTZ;   -- 휴지통에 넣은 시각(비어 있으면 살아 있는 작품)

CREATE INDEX idx_webtoon_work_deleted ON webtoon_work (deleted_at);
