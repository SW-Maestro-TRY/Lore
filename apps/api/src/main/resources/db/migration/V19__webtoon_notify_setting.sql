-- 로그인한 사람이 "웹툰 완성 메일"을 받을지 스스로 켜고 끄게 한다.
--
-- 지금까지는 계정 이메일로 항상 보냈다(JobNotice — 마케팅 수신과 성격이
-- 달라 따로 묻지 않았다). 그런데 끄고 싶은 사람에게 끌 자리가 없었다.
-- 기본값을 true 로 둬서, 이 표에 아직 행이 없는(한 번도 안 만진) 계정은
-- 지금까지와 똑같이 계속 받는다 — 새 기능이 조용히 동작을 바꾸면 안 된다.
CREATE TABLE webtoon_notify_setting (
    user_id             BIGINT PRIMARY KEY REFERENCES users(id),
    notify_on_complete  BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
