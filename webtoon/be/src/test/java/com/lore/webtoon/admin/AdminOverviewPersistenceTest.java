package com.lore.webtoon.admin;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 개요 · 유입 출처가 실제 PostgreSQL 에서 끝까지 도는지(2026-11-05 운영 「서버 오류」).
 * 별도 빈 PostgreSQL 에서만 실행 — 운영/기존 개발 DB URL 을 쓰지 않는다.
 */
@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = AdminOverviewPersistenceTest.Config.class)
@Import(AdminOverview.class)
@EnabledIfEnvironmentVariable(named = "SERIES_TEST_DB_URL", matches = ".+")
class AdminOverviewPersistenceTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.lore")
    @EnableJpaRepositories("com.lore")
    static class Config {}

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv("SERIES_TEST_DB_URL"));
        p.add("spring.datasource.username", () -> System.getenv().getOrDefault("SERIES_TEST_DB_USER", "mint"));
        p.add("spring.datasource.password", () -> "");
    }

    @Autowired AdminOverview overview;
    @Autowired EntityManager em;

    @Test
    void overviewAndSourcesRunOnRealDb() {
        LocalDate today = LocalDate.now(AdminOverview.KST);
        em.createNativeQuery("""
                insert into webtoon_event (uid, name, source, occurred_at, received_at)
                values ('u-ad', 'session_start', 'meta-ig/paid/c1/d1', now(), now()),
                       ('u-ad', 'create_started', null, now(), now()),
                       ('u-look', 'session_start', 'meta-ig/paid/c1/d1', now(), now())
                """).executeUpdate();

        Map<String, Object> o = overview.overview(today.minusDays(6), today, AdminOverview.Who.EXTERNAL);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> daily = (List<Map<String, Object>>) o.get("daily");
        assertThat(daily).hasSize(7);

        Map<String, Object> s = overview.sources(today.minusDays(6), today, AdminOverview.Who.EXTERNAL);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) s.get("rows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("visitors", 2L).containsEntry("engaged", 1L);
    }
}
