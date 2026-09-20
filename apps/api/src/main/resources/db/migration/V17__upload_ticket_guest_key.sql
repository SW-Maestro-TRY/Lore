-- 게스트(비로그인)도 presign 으로 사진을 올릴 수 있게 한다.
--
-- 지금까지는 upload_ticket.user_id 가 NOT NULL 이라 로그인한 사람만 티켓을
-- 받을 수 있었다. 그래서 게스트는 사진을 매번 data URL(base64)로 요청
-- 본문에 실어 보냈는데, 사진 하나만 커져도 본문이 1MB 를 넘어 CloudFront
-- 앞단 WAF(AWS-AWSManagedRulesCommonRuleSet 의 SizeRestrictions_BODY)에
-- 막혀 403 이 났다(2026-09-17 dev 실측).
--
-- user_id 대신 guest_key(=GuestGate 가 쓰는 IP 해시)로도 티켓을 발급받을 수
-- 있게 열어 준다 — 로그인 사람과 같은 "발급받은 사람만 그 키를 쓸 수 있다"
-- 보호를 게스트에게도 그대로 적용한다.
ALTER TABLE upload_ticket
    ALTER COLUMN user_id DROP NOT NULL,
    ADD COLUMN guest_key character varying(64);

ALTER TABLE upload_ticket
    ADD CONSTRAINT ck_upload_ticket_owner CHECK (
        (user_id IS NOT NULL AND guest_key IS NULL) OR
        (user_id IS NULL AND guest_key IS NOT NULL)
    );

CREATE INDEX idx_upload_ticket_guest_key ON upload_ticket (guest_key);
