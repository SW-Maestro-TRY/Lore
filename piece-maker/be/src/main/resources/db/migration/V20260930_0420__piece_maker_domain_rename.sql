-- Piece Maker 브랜드 전환: 기존 크레딧과 업로드 기록의 서비스 식별자를 옮긴다.
-- Flyway의 PostgreSQL 트랜잭션 안에서 제약 변경과 데이터 변경을 함께 확정한다.
-- 이전 애플리케이션과 동시에 쓰지 않고 새 애플리케이션 시작 시 적용한다.
LOCK TABLE credit_event, upload_ticket IN ACCESS EXCLUSIVE MODE;

ALTER TABLE credit_event DROP CONSTRAINT credit_event_domain_check;

UPDATE credit_event
SET domain = 'PIECE_MAKER'
WHERE domain = 'TRAILER';

ALTER TABLE credit_event
    ADD CONSTRAINT credit_event_domain_check
    CHECK (domain IN ('COMMON', 'WEBTOON', 'ZZAL', 'PIECE_MAKER'));

-- 기존 객체 주소는 실제 S3 파일을 가리키므로 s3_key는 변경하지 않는다.
-- 새 업로드부터 images/piece-maker/ 경로를 쓰고 기존 URL은 계속 유효하다.
UPDATE upload_ticket
SET domain = 'piece-maker'
WHERE domain = 'trailer';
