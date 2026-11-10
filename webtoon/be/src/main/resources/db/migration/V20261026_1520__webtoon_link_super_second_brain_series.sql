-- 「슈퍼 세컨드브레인」(멘토 계정 163)을 연재 하나로 잇는다(2026-10-26).
--
-- 멘토님이 1~34화를 「다음 화」 기능이 생기기 전·밖에서 한 편씩 따로 만들어서, 둘러보기에 34장의
-- 카드로 흩어져 보이고 완성본 아래 회차 목록도 한 화뿐이었다. 연재로 묶는 기준인
-- webtoon_series_chapter 에 2~34화를 1화(root) 아래로 적는다. 앞 화(source_job)는 바로 앞 번호다.
--
-- 작업 번호는 운영 DB 에서 제목(「슈퍼 세컨드브레인 N화 — …」)으로 하나씩 대조한 값이다
-- (2026-10-26, 같은 화가 두 번 만들어진 것 없음, 실패한 작업 6개는 제외).
-- dev · staging 에는 이 작업들이 없어서 아무 줄도 안 들어간다 — 있는 작업만 넣는다.
-- 이미 이어져 있으면(같은 job_id) 건너뛴다.
INSERT INTO webtoon_series_chapter (job_id, owner_id, root_job, source_job, episode_number, request_token, memory_json)
SELECT v.job_id, j.user_id, '157a8ebd5e454f22b175', v.source_job, v.episode_number,
       'link-ssb-' || lpad(v.episode_number::text, 2, '0'), '{}'
FROM (VALUES
    ('f3487e0f8b9f4375b01f', '157a8ebd5e454f22b175', 2),
    ('6e7e0dc00c9f43d2bd48', 'f3487e0f8b9f4375b01f', 3),
    ('c4f327d017ae4e8b80e2', '6e7e0dc00c9f43d2bd48', 4),
    ('776950226de949ea8e34', 'c4f327d017ae4e8b80e2', 5),
    ('c717d049fcb648338d87', '776950226de949ea8e34', 6),
    ('b9dd178bc9704c898d50', 'c717d049fcb648338d87', 7),
    ('fbe3a69616a24fb0be32', 'b9dd178bc9704c898d50', 8),
    ('5b31745ce14a491ab4ba', 'fbe3a69616a24fb0be32', 9),
    ('d5a6377866c74b34b837', '5b31745ce14a491ab4ba', 10),
    ('5ab530119b1949978553', 'd5a6377866c74b34b837', 11),
    ('15bc91f4a5ef45b2a548', '5ab530119b1949978553', 12),
    ('a4328b42f8a141a191be', '15bc91f4a5ef45b2a548', 13),
    ('7377fb15a1c84d3ba12b', 'a4328b42f8a141a191be', 14),
    ('9e397e48439f4a4796d4', '7377fb15a1c84d3ba12b', 15),
    ('a0bb1d3144b548ca802e', '9e397e48439f4a4796d4', 16),
    ('bee092e6ea8f4e1db3a1', 'a0bb1d3144b548ca802e', 17),
    ('b27982dda09e49e19311', 'bee092e6ea8f4e1db3a1', 18),
    ('be3989b0909848afa72c', 'b27982dda09e49e19311', 19),
    ('6444bb6daddc4562a1f4', 'be3989b0909848afa72c', 20),
    ('de1c10387093430b8755', '6444bb6daddc4562a1f4', 21),
    ('a4cae3e758224e89a1be', 'de1c10387093430b8755', 22),
    ('0bb97edc9dfe48379d93', 'a4cae3e758224e89a1be', 23),
    ('fb70b774ade1440b90bd', '0bb97edc9dfe48379d93', 24),
    ('1f004a167e294e4b92c4', 'fb70b774ade1440b90bd', 25),
    ('581446f67ed146038612', '1f004a167e294e4b92c4', 26),
    ('69c833889b1041ca9938', '581446f67ed146038612', 27),
    ('b960fd8d17de43cfa959', '69c833889b1041ca9938', 28),
    ('34474ff59dd34ae79f89', 'b960fd8d17de43cfa959', 29),
    ('43bac849230142e791b2', '34474ff59dd34ae79f89', 30),
    ('d4036347e0ae488eaa10', '43bac849230142e791b2', 31),
    ('6ca3f78b24e34e0f846d', 'd4036347e0ae488eaa10', 32),
    ('a9d84391fb9843a98635', '6ca3f78b24e34e0f846d', 33),
    ('7c6a05a74ccb4b9ead53', 'a9d84391fb9843a98635', 34)
) AS v(job_id, source_job, episode_number)
JOIN webtoon_job j ON j.public_id = v.job_id AND j.user_id = 163 AND j.status = 'DONE'
WHERE EXISTS (SELECT 1 FROM webtoon_job r WHERE r.public_id = '157a8ebd5e454f22b175' AND r.user_id = 163)
ON CONFLICT (job_id) DO NOTHING;
