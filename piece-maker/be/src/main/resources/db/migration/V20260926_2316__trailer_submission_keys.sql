-- qwer · 2026-09-26 23:16 KST
ALTER TABLE hypotheses ADD COLUMN request_key varchar(36);
ALTER TABLE hypotheses ADD COLUMN request_digest varchar(64);
CREATE UNIQUE INDEX uk_hypotheses_request ON hypotheses(user_id, request_key);
