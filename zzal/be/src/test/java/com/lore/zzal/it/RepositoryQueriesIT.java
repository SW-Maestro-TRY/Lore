package com.lore.zzal.it;

import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.ZzalChatCall;
import com.lore.zzal.chat.ZzalChatCallRepository;
import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenStepRecord;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시나리오 9 — 손으로 쓴 질의가 <b>섞인 데이터</b>에서 옳은 것만 고르는가(M-3 · M-10).
 *
 * <h3>★ {@code HandWrittenQueriesIT} 와 무엇이 다른가</h3>
 * 그쪽은 "돌긴 도는가"(문법·타입·트랜잭션)를 본다. 여기는 <b>답이 맞는가</b>를 본다 —
 * 한 펫에 v1·v2 와 모션 A·B 의 성공·실패·진행 중을 섞어 넣고, 그 버전·그 모션의
 * 성공 단계만 나오는지. 재개 조회가 틀리면 <b>다른 판의 산출물을 이어받아</b> 유료 단계를
 * 잘못 건너뛰고, 그림은 같은데 동작만 다른 결과가 사용자에게 간다.
 *
 * <h3>★ 경계 시각은 질의가 정한다</h3>
 * 복구의 유예(14분 59초 / 정확히 15분 / 15분 1초)는 서비스가 아니라 {@code …UpdatedAtBefore} 가
 * 가른다. {@code Before} 가 {@code <} 인지 {@code <=} 인지에 따라 <b>정상적으로 굽고 있는 것을
 * 회수해 같은 판을 두 번 굽는다.</b> 여기서 실제 Postgres 에 대고 그 한 초를 재 본다.
 */
@ZzalIntegrationTest
@DisplayName("시나리오 9 — 섞인 데이터에서 옳은 것만 고르는가")
class RepositoryQueriesIT extends ZzalItSupport {

    @Autowired GenJobRepository jobs;
    @Autowired GenStepRecordRepository steps;
    @Autowired ZzalMotionRepository motions;
    @Autowired ZzalChatCallRepository chatCalls;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long draftPet(Long userId) {
        return transactions.execute(status ->
                petRepository.save(ZzalPet.draft(userId, newUploadedImageKey(userId), Instant.now())).getId());
    }

    // ══ M-3. 재개 조회 — 버전과 모션을 가른다 ═══════════════════════════

    @Test
    @DisplayName("★★ 같은 펫에 v1·v2 를 섞어도 <b>그 버전의</b> 성공 단계만 나온다 — 섞이면 v1 격자를 v2 후처리가 자른다")
    void succeededStepsAreSplitByPipelineVersion() {
        Long petId = draftPet(newUserId());
        Long v1Job = hatchJob(petId, "v1");
        Long v2Job = hatchJob(petId, "v2");

        succeeded(v1Job, 0, "sheet", "v1-sheet.png");
        succeeded(v1Job, 1, "identity", null);
        succeeded(v2Job, 0, "sheet", "v2-sheet.png");
        failed(v2Job, 1, "identity");
        running(v2Job, 2, "grid");

        assertThat(steps.findSucceededByPetAndVersion(petId, GenKind.HATCH, "v2"))
                .extracting(GenStepRecord::getName)
                .as("v2 에서 성공한 것은 시트 하나뿐이다")
                .containsExactly("sheet");
        assertThat(steps.findSucceededByPetAndVersion(petId, GenKind.HATCH, "v2"))
                .extracting(GenStepRecord::getOutputKey)
                .containsExactly("v2-sheet.png");

        assertThat(steps.findSucceededByPetAndVersion(petId, GenKind.HATCH, "v1"))
                .extracting(GenStepRecord::getName)
                .containsExactly("sheet", "identity");

        assertThat(steps.findSucceededByPet(petId, GenKind.HATCH))
                .as("버전을 안 가르면 세 건이 한 덩어리로 나온다")
                .hasSize(3);
    }

    @Test
    @DisplayName("★★ 모션 단위 조회는 <b>그 모션의</b> 성공만 — 펫으로 묶으면 첫 동작의 격자를 두 번째가 이어받는다")
    void succeededStepsAreSplitByMotion() {
        Long petId = draftPet(newUserId());
        Long motionA = 1001L;
        Long motionB = 1002L;
        Long jobA = motionJob(petId, motionA);
        Long jobB = motionJob(petId, motionB);

        succeeded(jobA, 0, "motion_grid", "a-grid.png");
        failed(jobA, 1, "motion_post");
        succeeded(jobB, 0, "motion_grid", "b-grid.png");

        assertThat(steps.findSucceededByMotion(motionA))
                .extracting(GenStepRecord::getOutputKey).containsExactly("a-grid.png");
        assertThat(steps.findSucceededByMotion(motionB))
                .extracting(GenStepRecord::getOutputKey).containsExactly("b-grid.png");
        assertThat(steps.findSucceededByMotion(999_999L)).isEmpty();
    }

    @Test
    @DisplayName("★ 비용 합계는 경계 시각 <b>정확히 그때</b> 시작한 작업을 포함한다(>=)")
    void costSinceIncludesTheBoundaryItself() {
        Long petId = draftPet(newUserId());
        Instant boundary = Instant.now().minus(Duration.ofHours(1));

        finishedJob(petId, boundary.minusSeconds(1), new BigDecimal("1.0000"));   // 경계 앞 — 빠진다
        finishedJob(petId, boundary, new BigDecimal("0.2500"));                   // 정확히 경계 — 들어간다
        finishedJob(petId, boundary.plusSeconds(1), new BigDecimal("0.0100"));    // 경계 뒤 — 들어간다

        assertThat(jobs.sumCostSince(boundary))
                .as("경계를 빼면 하루 비용이 과소 집계되고, 경계 앞을 넣으면 과대 집계된다")
                .isEqualByComparingTo("0.2600");
    }

    @Test
    @DisplayName("★ 최근 답 다섯 — 답한 것만, 최신순, 다른 펫은 섞이지 않는다")
    void latestFiveAnswersOnly() {
        Long petId = draftPet(newUserId());
        Long otherPetId = draftPet(newUserId());
        Instant base = Instant.now().minus(Duration.ofHours(10));

        for (int i = 1; i <= 6; i++) {
            answeredCall(petId, LocalDate.now().minusDays(i), "답 " + i, base.plusSeconds(i * 60L));
        }
        unansweredCall(petId, LocalDate.now(), base.plusSeconds(9999));
        answeredCall(otherPetId, LocalDate.now(), "남의 답", base.plusSeconds(99_999));

        List<ZzalChatCall> recent =
                chatCalls.findTop5ByPetIdAndAnsweredAtIsNotNullOrderByAnsweredAtDesc(petId);

        assertThat(recent).hasSize(5);
        assertThat(recent).extracting(ZzalChatCall::getAnswer)
                .as("최신 다섯 개, 최신이 앞")
                .containsExactly("답 6", "답 5", "답 4", "답 3", "답 2");
    }

    // ══ M-10. 유예의 한 초 ══════════════════════════════════════════════

    @Test
    @DisplayName("★★ 유예 경계 — cutoff 보다 <b>앞선 것만</b> 집는다. 정확히 cutoff 인 것은 안 집는다")
    void theGraceBoundaryIsStrict() {
        Long userId = newUserId();
        Long petId = draftPet(userId);
        motionSeeder.seed(petId, Instant.now());

        Instant cutoff = Instant.now().minus(Duration.ofMinutes(15)).minusMillis(Instant.now().getNano() / 1_000_000);
        Long justInside = bakingMotionUpdatedAt(petId, 1, cutoff.plusSeconds(1));   // 14분 59초 전 — 아직 굽는 중
        Long exactlyAt = bakingMotionUpdatedAt(petId, 2, cutoff);                   // 정확히 15분 전
        Long justOutside = bakingMotionUpdatedAt(petId, 3, cutoff.minusSeconds(1)); // 15분 1초 전 — 멈춘 것

        List<Long> found = motions.findByStatusAndUpdatedAtBefore(MotionStatus.BAKING, cutoff)
                .stream().map(ZzalMotion::getId).toList();

        assertThat(found)
                .as("정상적으로 굽고 있는 것을 회수하면 같은 판을 두 번 굽는다(돈이 두 배)")
                .containsExactly(justOutside);
        assertThat(found).doesNotContain(justInside, exactlyAt);
    }

    @Test
    @DisplayName("★ 집는 상태도 가른다 — 같은 시각에 멈춰 있어도 REVIEW·OPEN 은 회수 대상이 아니다")
    void onlyTheAskedStatusIsReturned() {
        Long petId = draftPet(newUserId());
        motionSeeder.seed(petId, Instant.now());
        Instant longAgo = Instant.now().minus(Duration.ofHours(3));

        Long baking = motionAt(petId, 1, MotionStatus.BAKING, longAgo);
        motionAt(petId, 2, MotionStatus.REVIEW, longAgo);
        motionAt(petId, 3, MotionStatus.OPEN, longAgo);
        Long local = motionAt(petId, 4, MotionStatus.LOCAL_REQUESTED, longAgo);

        Instant cutoff = Instant.now().minus(Duration.ofMinutes(1));
        assertThat(motions.findByStatusAndUpdatedAtBefore(MotionStatus.BAKING, cutoff))
                .extracting(ZzalMotion::getId).containsExactly(baking);
        assertThat(motions.findByStatusAndUpdatedAtBefore(MotionStatus.LOCAL_REQUESTED, cutoff))
                .extracting(ZzalMotion::getId).containsExactly(local);
    }

    // ── 재료 ──────────────────────────────────────────────────────────────

    private Long hatchJob(Long petId, String version) {
        return transactions.execute(status ->
                jobs.save(GenJob.start(petId, GenKind.HATCH, 1, version, Instant.now())).getId());
    }

    private Long motionJob(Long petId, Long motionId) {
        return transactions.execute(status ->
                jobs.save(GenJob.startMotion(petId, motionId, 1, "v1", Instant.now())).getId());
    }

    private void finishedJob(Long petId, Instant startedAt, BigDecimal cost) {
        transactions.executeWithoutResult(status -> {
            GenJob job = jobs.save(GenJob.start(petId, GenKind.HATCH, 1, "v2", startedAt));
            job.succeed(cost, startedAt.plusSeconds(30));
        });
    }

    private void succeeded(Long jobId, int seq, String name, String outputKey) {
        transactions.executeWithoutResult(status -> {
            GenStepRecord rec = steps.save(GenStepRecord.start(jobId, seq, name, Instant.now()));
            rec.succeed(outputKey, outputKey == null ? "문단" : null, "m", BigDecimal.ZERO, Instant.now());
        });
    }

    private void failed(Long jobId, int seq, String name) {
        transactions.executeWithoutResult(status -> {
            GenStepRecord rec = steps.save(GenStepRecord.start(jobId, seq, name, Instant.now()));
            rec.fail(com.lore.zzal.generation.GenErrorCode.TIMEOUT, BigDecimal.ZERO, Instant.now());
        });
    }

    private void running(Long jobId, int seq, String name) {
        transactions.executeWithoutResult(status -> steps.save(GenStepRecord.start(jobId, seq, name, Instant.now())));
    }

    private void answeredCall(Long petId, LocalDate day, String answer, Instant answeredAt) {
        transactions.executeWithoutResult(status -> {
            ZzalChatCall call = chatCalls.save(ZzalChatCall.call(petId, day, ChatSlot.MORNING, "부름", answeredAt, null));
            call.answer(answer, "대답", "pet", answeredAt);
        });
    }

    private void unansweredCall(Long petId, LocalDate day, Instant calledAt) {
        transactions.executeWithoutResult(status ->
                chatCalls.save(ZzalChatCall.call(petId, day, ChatSlot.EVENING, "부름", calledAt, null)));
    }

    /**
     * 그 seq 의 모션 행을 {@code status} 로 두고 {@code updatedAt} 을 직접 앉힌다.
     *
     * ★ {@code updatedAt} 은 {@code @UpdateTimestamp} 라 엔티티로는 원하는 시각을 못 넣는다.
     *   회수 여부를 가르는 것이 바로 그 칸이므로, 여기서만 SQL 로 심는다.
     */
    private Long motionAt(Long petId, int seq, MotionStatus status, Instant updatedAt) {
        Long id = transactions.execute(s -> motions.findByPetIdOrderBySeqAsc(petId).stream()
                .filter(m -> m.getSeq() == seq).findFirst().orElseThrow().getId());
        jdbcTemplate.update("update zzal_motion set status = ?, updated_at = ? where id = ?",
                status.name(), Timestamp.from(updatedAt), id);
        return id;
    }

    private Long bakingMotionUpdatedAt(Long petId, int seq, Instant updatedAt) {
        return motionAt(petId, seq, MotionStatus.BAKING, updatedAt);
    }
}
