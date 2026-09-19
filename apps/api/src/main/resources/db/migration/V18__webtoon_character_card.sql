-- 「캐릭터 만들어보기」 — 캐릭터가 그 세계관 웹툰의 한 컷과 카드 글을 갖는다.
-- 예전 캐릭터(직접 만들기·기본 제공)는 전부 비어 있다. 값이 있는 것만 카드로 보여 준다.
ALTER TABLE webtoon_character
    ADD COLUMN world       varchar(80),
    ADD COLUMN world_label varchar(20),
    ADD COLUMN genre       varchar(40),
    ADD COLUMN role_name   varchar(40),
    ADD COLUMN twist       varchar(300),
    ADD COLUMN quote       varchar(300),
    ADD COLUMN fate        text,
    ADD COLUMN style       varchar(40);
