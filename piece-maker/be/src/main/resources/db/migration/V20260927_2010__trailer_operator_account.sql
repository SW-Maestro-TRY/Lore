-- qwer · 2026-09-27 20:10 KST
-- 판정 운영 스크립트가 로그인하는 운영자 계정. 없으면 넣고 있으면 건너뛴다.
-- 비밀번호는 저장소에 없다. 운영자 PC의 NA .env(LORE_ADMIN_PASSWORD)에만 있다.
INSERT INTO users (email, role, status, created_at, updated_at)
VALUES ('operator@lore.com', 'ADMIN', 'ACTIVE', now(), now())
ON CONFLICT (email) DO NOTHING;

INSERT INTO user_credential (user_id, provider, password_hash, created_at, updated_at)
SELECT u.id, 'LOCAL', '$2a$10$gkp6RaLY15AV5g6oDREwd.QkPMuFLJSsie9icIb5S7Z5ysWmWP49G', now(), now()
FROM users u
WHERE u.email = 'operator@lore.com'
  AND NOT EXISTS (
    SELECT 1 FROM user_credential c
    WHERE c.user_id = u.id AND c.provider = 'LOCAL'
  );
