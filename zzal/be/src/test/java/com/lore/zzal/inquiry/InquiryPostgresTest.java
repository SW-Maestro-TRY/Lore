package com.lore.zzal.inquiry;

import com.lore.common.exception.*;
import com.lore.zzal.pet.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="LORE_INQUIRY_DB_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:5403/lore_inquiry_[0-9]+_test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InquiryPostgresTest {
    JdbcTemplate db; InquiryService service; ZzalPetRepository pets;
    final Instant now = Instant.parse("2026-10-21T09:00:00Z");
    @BeforeAll void migrate() throws Exception {
        var ds = new DriverManagerDataSource(System.getenv("LORE_INQUIRY_DB_URL"),
                System.getenv("LORE_TEST_DB_USERNAME"), System.getenv("LORE_TEST_DB_PASSWORD"));
        try (var connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V20261021_0100__zzal_inquiry.sql"));
        }
        db = new JdbcTemplate(ds);
        pets = mock(ZzalPetRepository.class);
        var proxy = new ProxyFactory(new InquiryService(new InquiryRepository(db), pets));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
        service = (InquiryService) proxy.getProxy();
    }
    @BeforeEach void clean() { db.update("DELETE FROM zzal_inquiry"); reset(pets); }
    InquiryRequests.Submit request(UUID id, String body, InquiryRequests.Locale locale, Long pet) {
        return new InquiryRequests.Submit(id, body, locale, pet);
    }
    long count() { return db.queryForObject("SELECT count(*) FROM zzal_inquiry", Long.class); }
    @Test void migrationPersistsNormalizedTextAndReturnsOnlyTheStableReceipt() {
        UUID id = UUID.randomUUID();
        var first = service.submit(71L, request(id, " \n테스트 문의입니다.\n ", InquiryRequests.Locale.ko, null), now);
        var retry = service.submit(71L, request(id, "테스트 문의입니다.", InquiryRequests.Locale.ko, null), now.plusSeconds(1));
        assertThat(retry).isEqualTo(first); assertThat(count()).isEqualTo(1);
        var row = db.queryForMap("SELECT user_id,pet_id,body,locale,request_id,created_at FROM zzal_inquiry WHERE id=?", first.id());
        assertThat(row.get("user_id")).isEqualTo(71L); assertThat(row.get("pet_id")).isNull();
        assertThat(row.get("body")).isEqualTo("테스트 문의입니다."); assertThat(row.get("locale")).isEqualTo("ko");
        assertThat(row.get("request_id")).isEqualTo(id); assertThat(first.createdAt()).isEqualTo(now);
    }
    @Test void sameUuidIsScopedToOwnerAndDifferentPayloadNeverOverwritesTheOriginal() {
        UUID id = UUID.randomUUID();
        service.submit(71L, request(id, "원래 문의", InquiryRequests.Locale.ko, null), now);
        for (var changed : List.of(request(id, "변경된 문의", InquiryRequests.Locale.ko, null),
                request(id, "원래 문의", InquiryRequests.Locale.en, null))) {
            assertThatThrownBy(() -> service.submit(71L, changed, now))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ZZAL_INQUIRY_CONFLICT));
        }
        service.submit(72L, request(id, "다른 계정 문의", InquiryRequests.Locale.ja, null), now);
        assertThat(count()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT body FROM zzal_inquiry WHERE user_id=71", String.class)).isEqualTo("원래 문의");
    }
    @Test void optionalPetUsesPureOwnershipReadAndCannotAttachSomebodyElsesPet() {
        when(pets.findById(9L)).thenReturn(Optional.of(ZzalPet.draft(71L, "synthetic", now)));
        var id = UUID.randomUUID();
        service.submit(71L, request(id, "내 캐릭터 문의", InquiryRequests.Locale.en, 9L), now);
        assertThat(db.queryForObject("SELECT pet_id FROM zzal_inquiry", Long.class)).isEqualTo(9L);
        assertThatThrownBy(() -> service.submit(72L, request(UUID.randomUUID(), "타인 캐릭터 문의", InquiryRequests.Locale.en, 9L), now))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ZZAL_PET_NOT_FOUND));
        assertThatThrownBy(() -> service.submit(71L, request(id, "내 캐릭터 문의", InquiryRequests.Locale.en, null), now))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ZZAL_INQUIRY_CONFLICT));
        verify(pets, never()).findByIdForUpdate(any()); assertThat(count()).isEqualTo(1);
    }
    @Test void concurrentTransactionsCommitExactlyOneRowAndBothGetTheSameReceipt() throws Exception {
        var request = request(UUID.randomUUID(), "동시 재전송 테스트", InquiryRequests.Locale.ja, null);
        var start = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { start.await(); return service.submit(71L, request, now); });
            var second = executor.submit(() -> { start.await(); return service.submit(71L, request, now.plusSeconds(1)); });
            start.countDown(); assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
            assertThat(count()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
    @Test void databaseConstraintsRejectOversizedTextAndInvalidLocaleAndNullOwnerCannotWrite() {
        assertThatThrownBy(() -> db.update("INSERT INTO zzal_inquiry(user_id,request_id,body,locale,created_at) VALUES(71,?,?,?,now())",
                UUID.randomUUID(), "가".repeat(4001), "ko")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("INSERT INTO zzal_inquiry(user_id,request_id,body,locale,created_at) VALUES(71,?,?,?,now())",
                UUID.randomUUID(), "문의", "xx")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> service.submit(null, request(UUID.randomUUID(), "문의", InquiryRequests.Locale.ko, null), now))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        assertThat(count()).isZero();
    }
}
