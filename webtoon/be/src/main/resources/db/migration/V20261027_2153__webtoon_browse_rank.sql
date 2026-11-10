-- 둘러보기 「인기순」을 관리자가 정한다(인기순 = 관리자 순서, 기본 정렬).
-- 작을수록 앞. 비어 있는 작품은 순서가 정해진 작품 뒤에 조회순으로 온다.
ALTER TABLE webtoon_work ADD COLUMN IF NOT EXISTS browse_rank INTEGER;
