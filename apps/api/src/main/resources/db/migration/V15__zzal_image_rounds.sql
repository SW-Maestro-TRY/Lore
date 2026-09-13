-- 그림 주소에 판 번호를 넣기 위한 칸들.
--
-- ★ 왜 판 번호인가 — 판이 없으면 다시 구운 그림이 같은 주소로 올라간다. 업로드도 DB 도 맞는데
--   CDN 이 1년 캐시를 들고 있어 옛 그림이 계속 나간다. 주소에 판이 들어가면 다시 구운 것은
--   언제나 새 주소가 되고, 그 1년 캐시가 오히려 맞는 설정이 된다.
--
-- ★ 전부 기본값이 있거나 nullable 이다 — 행이 있는 표에 칸을 더할 때의 규칙.
-- ★ 소급 UPDATE 는 없다. 0 은 "판 번호 이전의 옛 주소" 를 뜻하고, 조립하는 쪽
--   (MotionImageKeys)이 그때는 판 칸을 아예 빼서 옛 주소를 그대로 준다.

-- 이 펫의 기본 그림을 몇 번 구웠나. 후처리가 성공해 올린 판까지만 센다.
-- 부화 재시도로 다시 구우면 하나 오르고, 그 순간부터 새 주소가 나간다.
ALTER TABLE zzal_pet ADD COLUMN basic_round integer DEFAULT 0 NOT NULL;

-- 심화 움짤의 캔버스 크기(px). 판마다 다르다 — 화면이 상수로 가정하면 안 된다.
--
-- ★ nullable 인 이유 — 맥미니가 올린 재생성본은 서버가 파일을 열어 보지 않아 크기를 모른다.
--   모르는 것을 0 으로 적으면 화면이 그 값을 믿고 0px 로 그린다. 비워 두면 "모른다" 가 전달된다.
ALTER TABLE zzal_motion ADD COLUMN image_width integer;
ALTER TABLE zzal_motion ADD COLUMN image_height integer;
