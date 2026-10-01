-- 온보딩 자유 입력 한도 확대 — 말투·장르 32 → 100, 세계관 100 → 200.
--
-- ★ 왜 넓히나 — 한 줄이라기엔 너무 좁았다. 말투 32자는 "무뚝뚝한 존댓말" 정도에서 끝나고,
--   세계관 100자는 칩(고른 낱말)과 직접 쓴 글이 <b>한 칸을 나눠 쓰기</b> 때문에 칩 몇 개만 붙여도
--   쓸 자리가 없다. 칸마다 넉넉하게 잡는다(상훈님 2026-09-22 결정).
--
-- ★ 세계관만 200인 이유 — 칩+글 합산 한 칸이라 다른 칸보다 두 배가 필요하다.
--   말투·장르는 낱말 한두 개짜리 칸이라 100 으로 충분하다. 그 밖에(note)는 200 그대로 둔다.
--
-- ★ 넓히기만 한다 — PostgreSQL 에서 varchar 의 길이를 늘리는 것은 테이블 재작성 없이 끝나고
--   기존 값은 한 글자도 건드리지 않는다(V12 에서 world 를 40 → 100 으로 넓힐 때와 같다).
--   좁히는 방향이었다면 이미 들어 있는 글을 자르는 문제가 생기지만 여기는 그 반대다.
--
-- ★ 상수와 같은 숫자여야 한다 — ZzalRules.TONE_MAX_CHARS · GENRE_MAX_CHARS · WORLD_MAX_CHARS 가
--   요청 검증(@Size) · 엔티티 칸(@Column) · 이 칸 · 문서를 함께 정한다. 갈리면 검증은 통과하고
--   저장에서 터져 사용자에게 400 이 아니라 500 이 간다(세계관 칸에서 실제로 났다).
--   ToneAndGenreLengthContractTest · WorldLengthContractTest 가 이 파일을 읽어 대조한다.
--
-- ★ 번호가 날짜인 이유는 V20260922_0100 과 같다 — 여러 갈래가 동시에 번호를 집으면 같은 번호가
--   둘 나오고, 머지된 뒤에야 한쪽이 안 돈다. 값은 20260922.0200 이라 바로 앞 판보다 크다.

ALTER TABLE zzal_pet ALTER COLUMN tone TYPE character varying(100);
ALTER TABLE zzal_pet ALTER COLUMN genre TYPE character varying(100);
ALTER TABLE zzal_pet ALTER COLUMN world TYPE character varying(200);
