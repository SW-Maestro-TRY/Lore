package com.lore.zzal.it;

import com.lore.zzal.admin.retry.AdminRetry;
import com.lore.zzal.admin.retry.AdminRetryKind;
import com.lore.zzal.admin.retry.AdminRetryRepository;
import com.lore.zzal.admin.retry.AdminRetryResult;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관리자 실패 추적·재시도 — 진짜 DB 로. 설계 spec-zzal-admin-failures-retry-20261027.
 *
 * <h3>작업 1.1 — 요청 표가 스스로 막는 것</h3>
 * 열린 요청(결과 없음 · 후보 도착 후 고르기 전) 중복과 자동 요청 2회는 서비스 코드가 아니라 DB 가 막는다.
 * 서비스가 확인을 빠뜨리거나 두 요청이 동시에 와도 행이 두 개 생기지 않아야 한다.
 */
@ZzalIntegrationTest
@DisplayName("관리자 실패 추적·재시도")
class AdminFailuresIT extends ZzalItSupport {

    @Autowired AdminRetryRepository retries;

    private Long admin;
    private ZzalPet pet;

    private void setUpPet() {
        admin = newUserId();
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin);
        pet = aliveLayerTwoPet(newUserId());
    }

    @Test
    @DisplayName("같은 대상에 열린 수동 요청은 하나만")
    void retryTable_rejectsSecondOpenRequest() {
        setUpPet();
        Instant now = Instant.now();
        retries.saveAndFlush(AdminRetry.manual(AdminRetryKind.HATCH, pet.getId(), pet.getId(), "첫 요청", admin, now));

        assertThatThrownBy(() -> retries.saveAndFlush(
                AdminRetry.manual(AdminRetryKind.HATCH, pet.getId(), pet.getId(), "둘째 요청", admin, now.plusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(retries.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("취소하면 같은 대상에 새 요청을 낼 수 있다")
    void retryTable_allowsNewRequestAfterCancel() {
        setUpPet();
        Instant now = Instant.now();
        AdminRetry first = retries.saveAndFlush(
                AdminRetry.manual(AdminRetryKind.HATCH, pet.getId(), pet.getId(), "첫 요청", admin, now));
        first.cancel(now.plusSeconds(5));
        retries.saveAndFlush(first);
        assertThat(retries.findOpen(AdminRetryKind.HATCH, pet.getId())).isEmpty();

        AdminRetry second = retries.saveAndFlush(
                AdminRetry.manual(AdminRetryKind.HATCH, pet.getId(), pet.getId(), "다시", admin, now.plusSeconds(10)));
        assertThat(retries.findOpen(AdminRetryKind.HATCH, pet.getId())).get()
                .extracting(AdminRetry::getId).isEqualTo(second.getId());
        assertThat(retries.findByPetIdOrderByRequestedAtDesc(pet.getId())).hasSize(2);
    }

    @Test
    @DisplayName("자동 요청은 대상당 한 번 — 닫힌 뒤에도 두 번째는 막힌다")
    void retryTable_autoOncePerTarget() {
        setUpPet();
        Instant now = Instant.now();
        AdminRetry auto = retries.saveAndFlush(AdminRetry.auto(AdminRetryKind.HATCH, pet.getId(), pet.getId(), now));
        auto.applyFailed("게이트 미통과", now.plusSeconds(60));
        retries.saveAndFlush(auto);
        assertThat(auto.isOpen()).isFalse();
        assertThat(retries.existsByKindAndTargetIdAndAutoTrue(AdminRetryKind.HATCH, pet.getId())).isTrue();

        assertThatThrownBy(() -> retries.saveAndFlush(
                AdminRetry.auto(AdminRetryKind.HATCH, pet.getId(), pet.getId(), now.plusSeconds(120))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(retries.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("자동 요청에는 요청자가 없다 — requested_by 를 넣으면 CHECK 위반")
    void retryTable_autoHasNoRequester() {
        setUpPet();
        Instant now = Instant.now();
        assertThatThrownBy(() -> jdbc.update("""
                insert into zzal_admin_retry (kind, target_id, pet_id, auto, requested_by, requested_at)
                values ('HATCH', ?, ?, true, ?, now())
                """, pet.getId(), pet.getId(), admin))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 수동 요청인데 요청자가 없는 것도 같은 CHECK 가 막는다.
        assertThatThrownBy(() -> jdbc.update("""
                insert into zzal_admin_retry (kind, target_id, pet_id, auto, requested_by, requested_at)
                values ('CUSTOM', ?, ?, false, null, now())
                """, 1L, pet.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(retries.count()).isZero();

        // 엔티티 생성자는 규칙대로 만든다 — 자동이면 요청자 null.
        AdminRetry saved = retries.saveAndFlush(AdminRetry.auto(AdminRetryKind.CUSTOM, 77L, pet.getId(), now));
        assertThat(saved.getRequestedBy()).isNull();
        assertThat(saved.isAuto()).isTrue();
        assertThat(saved.getResult()).isNull();
        assertThat(retries.findPending()).extracting(AdminRetry::getId).containsExactly(saved.getId());

        // 러너가 집으면 pending 에서 빠지고, 후보가 오면 아직 열린 요청이다.
        AdminRetry locked = transactions.execute(s -> {
            AdminRetry r = retries.findByIdForUpdate(saved.getId()).orElseThrow();
            r.claim(now.plusSeconds(1));
            return r;
        });
        assertThat(locked.getPickedAt()).isNotNull();
        assertThat(retries.findPending()).isEmpty();
        transactions.executeWithoutResult(s -> retries.findByIdForUpdate(saved.getId()).orElseThrow()
                .deliver(AdminRetryResult.CANDIDATES, "후보 1", java.util.Map.of("keys", java.util.List.of("a.webp")),
                        now.plusSeconds(2)));
        AdminRetry delivered = retries.findById(saved.getId()).orElseThrow();
        assertThat(delivered.isOpen()).isTrue();
        assertThat(delivered.getCandidateKeys()).containsKey("keys");
    }
}
