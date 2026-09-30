package com.lore.piecemaker.credit;

import com.lore.piecemaker.support.PieceMakerIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 기존 데이터가 있는 상태에서 실제 이름 전환 SQL의 보존 범위를 확인한다. */
@PieceMakerIntegrationTest
class PieceMakerRenameMigrationIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeAll
    static void requireDedicatedTestDatabase() {
        String url = System.getenv("LORE_TEST_DB_URL");
        if (url == null || url.isBlank()) {
            return;
        }
        assertThat(url.replaceAll("[?].*$", "").replaceAll("^.*/", ""))
                .as("마이그레이션 검사는 _test 데이터베이스에서만 실행한다")
                .endsWith("_test");
    }

    @Test
    @DisplayName("브랜드 전환은 기존 차감·환급·업로드의 식별자만 바꾸고 금액과 객체 주소를 보존한다")
    void renamePreservesCreditHistoryAndExistingObjectKeys() throws IOException {
        String migration = new ClassPathResource(
                "db/migration/V20260930_0420__piece_maker_domain_rename.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).endsWith("_test");
            // 세션 임시 테이블이 같은 이름의 실제 테이블을 가린다. 커밋/롤백 때 제거된다.
            jdbc.execute("""
                    CREATE TEMP TABLE credit_event (
                        id bigint PRIMARY KEY, user_id bigint NOT NULL,
                        delta integer NOT NULL, reason varchar(30) NOT NULL,
                        domain varchar(20) NOT NULL, ref_id varchar(100) NOT NULL,
                        CONSTRAINT credit_event_domain_check
                            CHECK (domain IN ('COMMON', 'WEBTOON', 'ZZAL', 'TRAILER')),
                        UNIQUE (user_id, reason, domain, ref_id)
                    ) ON COMMIT DROP;
                    CREATE TEMP TABLE upload_ticket (
                        id bigint PRIMARY KEY, domain varchar(20) NOT NULL,
                        s3_key varchar(300) NOT NULL
                    ) ON COMMIT DROP;
                    INSERT INTO credit_event VALUES
                        (1, 10, -5, 'SPEND', 'TRAILER', 'hypothesis:7'),
                        (2, 10, 5, 'REFUND', 'TRAILER', 'hypothesis:7'),
                        (3, 10, 12, 'SIGNUP', 'COMMON', 'signup:10'),
                        (4, 10, -4, 'SPEND', 'WEBTOON', 'webtoon:1');
                    INSERT INTO upload_ticket VALUES
                        (1, 'trailer', 'images/trailer/existing-object'),
                        (2, 'webtoon', 'images/webtoon/existing-object');
                    """);
            var originalCredits = jdbc.queryForList(
                    "SELECT id, user_id, delta, reason, ref_id FROM credit_event ORDER BY id");
            var originalKeys = jdbc.queryForList("SELECT id, s3_key FROM upload_ticket ORDER BY id");

            jdbc.execute(migration);

            assertThat(jdbc.queryForList(
                    "SELECT id, user_id, delta, reason, ref_id FROM credit_event ORDER BY id"))
                    .isEqualTo(originalCredits);
            assertThat(jdbc.queryForList("SELECT domain FROM credit_event ORDER BY id", String.class))
                    .containsExactly("PIECE_MAKER", "PIECE_MAKER", "COMMON", "WEBTOON");
            assertThat(jdbc.queryForList("SELECT id, s3_key FROM upload_ticket ORDER BY id"))
                    .isEqualTo(originalKeys);
            assertThat(jdbc.queryForList("SELECT domain FROM upload_ticket ORDER BY id", String.class))
                    .containsExactly("piece-maker", "webtoon");
            assertThat(jdbc.queryForObject("SELECT SUM(delta) FROM credit_event", Integer.class)).isEqualTo(8);

            jdbc.update("INSERT INTO credit_event VALUES (5, 10, -5, 'SPEND', 'PIECE_MAKER', 'hypothesis:8')");
            jdbc.execute("SAVEPOINT retired_domain_check");
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO credit_event VALUES (6, 10, -5, 'SPEND', 'TRAILER', 'hypothesis:9')"))
                    .hasMessageContaining("credit_event_domain_check");
            jdbc.execute("ROLLBACK TO SAVEPOINT retired_domain_check");
            jdbc.execute("RELEASE SAVEPOINT retired_domain_check");
        });
    }
}
