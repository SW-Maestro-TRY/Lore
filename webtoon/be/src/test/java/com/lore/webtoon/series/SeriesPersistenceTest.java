package com.lore.webtoon.series;

import com.lore.webtoon.retention.WebtoonPurgeRepository;
import com.lore.webtoon.work.RunDeleteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

/** 별도 빈 PostgreSQL에서만 실행. 운영/기존 개발 DB URL을 사용하지 않는다. */
@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=SeriesPersistenceTest.Config.class)
@EnabledIfEnvironmentVariable(named="SERIES_TEST_DB_URL",matches=".+")
class SeriesPersistenceTest {
    @SpringBootConfiguration @EnableAutoConfiguration
    @EntityScan("com.lore") @EnableJpaRepositories("com.lore")
    static class Config {}
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("SERIES_TEST_DB_URL"));
        p.add("spring.datasource.username",()->System.getenv().getOrDefault("SERIES_TEST_DB_USER","mint"));
        p.add("spring.datasource.password",()->"");
    }
    @Autowired com.lore.webtoon.lorebook.LoreExtractionRepository extractions;
    @Autowired SeriesDraftRepository drafts;
    @Autowired SeriesChapterRepository chapters;
    @Autowired WebtoonPurgeRepository purge;
    @Autowired RunDeleteRepository runDelete;
    @Test void migrationMappingsAndOwnerDeletionQueriesWork() {
        var draft=new SeriesDraft("series-source");draft.ownerId=700L;
        draft.fixedSettings="고정";draft.progressState="현재";draft.revision=1;
        drafts.saveAndFlush(draft);
        var chapter=new SeriesChapter("series-next","series-root","series-source",2,"request","{}");chapter.ownerId=700L;
        chapters.saveAndFlush(chapter);
        assertThat(chapters.episodeOf("series-next")).contains(2);
        assertThat(chapters.findByRootJobAndRequestToken("series-root","request")).isPresent();
        assertThat(chapters.findByRootJobOrderByEpisodeNumberAsc("series-root")).hasSize(1);
        assertThat(runDelete.deleteSeriesDraft("missing-run")).isZero();
        assertThat(runDelete.deleteSeriesChapter("missing-run")).isZero();
        assertThat(purge.deleteSeriesDrafts(700L)).isEqualTo(1);
        assertThat(purge.deleteSeriesChapters(700L)).isEqualTo(1);
    }
    @Test void loreQuotaIsBoundedAndCanBePurged() {
        var day=java.time.LocalDate.of(2026,10,23);
        assertThat(extractions.reserve(701L,day,2)).isEqualTo(1);
        assertThat(extractions.reserve(701L,day,2)).isEqualTo(1);
        assertThat(extractions.reserve(701L,day,2)).isZero();
        assertThat(extractions.used(701L,day)).isEqualTo(2);
        assertThat(purge.deleteLoreQuota(701L)).isEqualTo(1);
        assertThat(purge.deleteLoreExtractions(701L)).isZero();
    }
    @Test void duplicateRequestCannotCreateSecondChapter() {
        var a=new SeriesChapter("next-a","root","source",2,"same-request","{}");a.ownerId=700L;
        var b=new SeriesChapter("next-b","root","source",2,"same-request","{}");b.ownerId=700L;
        chapters.saveAndFlush(a);
        assertThatThrownBy(()->chapters.saveAndFlush(b)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.lore.webtoon.lorebook.PrivateLorebookRepository privateBooks;
    @Test void privateBookQueriesAndPurgeStayWithinOwner() {
        jdbc.update("insert into webtoon_private_lorebook(id,owner_id,created_at,updated_at,book_json) values ('book-a',711,now(),now(),'{}'),('book-b',712,now(),now(),'{}')");
        assertThat(privateBooks.findAllByOwnerIdOrderByUpdatedAtDesc(711L)).hasSize(1);
        assertThat(privateBooks.findByIdAndOwnerId("book-a",712L)).isEmpty();
        assertThat(privateBooks.findByIdAndOwnerId("book-a",711L)).isPresent();
        assertThat(purge.deletePrivateLorebooks(711L)).isEqualTo(1);
        assertThat(privateBooks.findAllByOwnerIdOrderByUpdatedAtDesc(711L)).isEmpty();
        assertThat(privateBooks.findByIdAndOwnerId("book-b",712L)).isPresent();
    }

}
