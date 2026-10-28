package com.lore.zzal.it;

import com.lore.common.s3.S3Storage;
import com.lore.zzal.admin.retry.AdminAutoRetry;
import com.lore.zzal.admin.retry.AdminHatchRetryApplier;
import com.lore.zzal.admin.retry.AdminRetry;
import com.lore.zzal.admin.retry.AdminRetryRepository;
import com.lore.zzal.admin.retry.AdminRetryResult;
import com.lore.zzal.admin.retry.AdminRetryService;
import com.lore.zzal.generation.GenerationRecorder;
import com.lore.zzal.generation.LayerCandidateBaker;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자동 CLI 요청 행(작업 1.9, 결정 7·8) — 켠 컨텍스트에서. since 는 고정 시각(2026-01-01)이라
 * 그보다 옛 실패는 행을 안 만든다.
 */
@ZzalIntegrationTest
@TestPropertySource(properties = {
        "app.zzal.admin.enabled=true",
        "app.zzal.admin.bot-token=" + AdminAutoRetryIT.TOKEN,
        "app.zzal.admin.bot-user-id=" + AdminAutoRetryIT.BOT,
        "app.zzal.admin.auto-cli.enabled=true",
        "app.zzal.admin.auto-cli.since=2026-01-01T00:00:00Z",
})
@DisplayName("관리자 자동 CLI — 켜짐")
class AdminAutoRetryIT extends ZzalItSupport {

    static final String TOKEN = "it-admin-auto-cli-token-0123456789abcdef";
    static final long BOT = 900003L;

    @Autowired GenerationRecorder recorder;
    @Autowired AdminAutoRetry auto;
    @Autowired AdminRetryRepository retries;
    @Autowired AdminRetryService retryService;
    @Autowired AdminHatchRetryApplier hatchApplier;
    @Autowired LayerCandidateBaker baker;
    @Autowired S3Storage storage;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private void bot() {
        Long made = newUserId();
        jdbc.update("update users set id = ?, role = 'ADMIN' where id = ?", BOT, made);
        ((ZzalItConfig.InMemoryS3Storage) storage).clear();
    }

    /** 굽는 중이던 알 — 마지막 HATCH 잡 활동이 at. */
    private Long hatching(String name, Instant at) {
        Long owner = newUserId();
        ZzalPet pet = petRepository.save(ZzalPet.draft(owner, newUploadedImageKey(owner), at));
        jdbc.update("update zzal_pet set phase = 'HATCHING', name = ? where id = ?", name, pet.getId());
        Long job = jdbc.queryForObject("""
                insert into zzal_gen_job (pet_id, kind, attempt, status, error_code, pipeline_version, started_at,
                                          running_at, finished_at)
                values (?, 'HATCH', 3, 'FAILED', 'TIMEOUT', ?, ?, ?, ?) returning id
                """, Long.class, pet.getId(), hatchService.currentVersion(), Timestamp.from(at), Timestamp.from(at),
                Timestamp.from(at));
        jdbc.update("""
                insert into zzal_gen_step (job_id, seq, name, status, started_at, finished_at, output_key)
                values (?, 1, 'sheet', 'SUCCEEDED', ?, ?, 'images/zzal/pets/sheet.png')
                """, job, Timestamp.from(at), Timestamp.from(at));
        return pet.getId();
    }

    private List<AdminRetry> autoRows() {
        return retries.findAll().stream().filter(AdminRetry::isAuto).toList();
    }

    @Test
    @DisplayName("부화 최종 실패 — 자동 행 1개(메모 · 요청자 없음), 같은 알이 또 실패해도 1개")
    void auto_enabled_createsOneRowOnHatchFailure_andNoSecond() {
        bot();
        Long pet = hatching("몽이", now.minusSeconds(600));
        recorder.markPetFailed(pet);
        assertThat(jdbc.queryForObject("select phase from zzal_pet where id = ?", String.class, pet)).isEqualTo("FAILED");

        List<AdminRetry> rows = autoRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getTargetId()).isEqualTo(pet);
        assertThat(rows.getFirst().getMemo()).isNull();
        assertThat(rows.getFirst().getRequestedBy()).isNull();

        jdbc.update("update zzal_pet set phase = 'HATCHING' where id = ?", pet);
        recorder.markPetFailed(pet);
        assertThat(autoRows()).hasSize(1);

        // 이름 없는 알은 만들지 않는다
        Long nameless = hatching("", now.minusSeconds(600));
        recorder.markPetFailed(nameless);
        assertThat(autoRows()).hasSize(1);
    }

    @Test
    @DisplayName("★ since 이전 실패는 부화 · 맞춤 둘 다 행을 안 만든다(리뷰 초점 3), 이후 실패는 맞춤 스캔이 만든다")
    void auto_ignoresFailuresBeforeSince() {
        bot();
        Instant old = Instant.parse("2025-12-01T00:00:00Z");
        Long oldPet = hatching("옛알", old);
        recorder.markPetFailed(oldPet);
        assertThat(autoRows()).isEmpty();

        ZzalPet oldCustom = aliveLayerTwoPet(newUserId());
        ZzalPet freshCustom = aliveLayerTwoPet(newUserId());
        for (var c : List.of(new Object[]{oldCustom, old}, new Object[]{freshCustom, now.minusSeconds(7200)})) {
            ZzalPet p = (ZzalPet) c[0];
            Instant last = (Instant) c[1];
            for (int i = 1; i <= 3; i++) {
                jdbc.update("""
                        insert into zzal_awake_visit (pet_id, program_version, cycle_key, event_id, observed_at)
                        values (?, 'first-v1', ?, ?, ?)
                        """, p.getId(), "c" + i, "ev-" + p.getId() + "-" + i, Timestamp.from(last.minus(Duration.ofDays(3 - i))));
            }
            jdbc.update("insert into zzal_attendance_reward_slot (pet_id, program_version, slot_key, eligible_day) "
                    + "values (?, 'first-v1', 'motion-1', 3)", p.getId());
            jdbc.update("""
                    insert into zzal_custom_bundle_job (pet_id, program_version, bundle_version, snapshot_json, input_hash,
                                                        state, claim_token, created_at)
                    values (?, 'first-v1', 'bundle-1', '{}', 'h', 'CLAIMED', 'tok', ?)
                    """, p.getId(), Timestamp.from(last));
        }
        assertThat(auto.scanCustom(now)).isEqualTo(1);
        assertThat(autoRows()).extracting(AdminRetry::getPetId).containsExactly(freshCustom.getId());
        assertThat(auto.scanCustom(now)).as("대상당 1회").isZero();
    }

    @Test
    @DisplayName("★ 자동 행 — 러너가 통과 후보를 올리면 사람 없이 살린다(applied_by 없음)")
    void auto_candidatesPass_appliesWithoutHuman() throws Exception {
        bot();
        Long pet = hatching("몽이", now.minusSeconds(600));
        recorder.markPetFailed(pet);
        AdminRetry row = autoRows().getFirst();
        assertThat(retryService.pending(BOT, now)).extracting(p -> p.retryId()).containsExactly(row.getId());
        retryService.claim(BOT, row.getId(), now);

        // 후보 id 는 호출 시각(초)의 36진수 + 순번이다 — 고를 때 옮길 그림을 미리 놓아 둔다(가짜 후처리는 파일을 안 만든다).
        String cid = Long.toString(now.getEpochSecond(), 36) + "-1";
        String prefix = LayerCandidateBaker.prefix(pet, 1, cid);
        Path tmp = Files.createTempFile("auto-it-", ".bin");
        Files.writeString(tmp, "x");
        for (String k : baker.keys(1)) {
            storage.upload("%s/%s.webp".formatted(prefix, k), tmp, "image/webp");
        }
        storage.upload(prefix + "/anchors.json", tmp, "application/json");
        Files.deleteIfExists(tmp);

        String grid = newUploadedImageKey(BOT);
        var result = hatchApplier.hatchCandidates(BOT, row.getId(),
                new AdminHatchRetryApplier.RetryCandidatesRequest(List.of(grid), null, null, null, "자동"), now);
        assertThat(result.autoApplied()).isTrue();
        assertThat(result.result()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("select phase from zzal_pet where id = ?", String.class, pet)).isEqualTo("ALIVE");
        AdminRetry after = retries.findById(row.getId()).orElseThrow();
        assertThat(after.getResult()).isEqualTo(AdminRetryResult.APPLIED);
        assertThat(after.getAppliedBy()).isNull();
        assertThat(after.getAppliedChoice()).isEqualTo(cid);
    }
}
