package com.lore.zzal.admin;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.s3.S3Service;
import com.lore.zzal.admin.dto.AdminResponses;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.motion.GateVerdict;
import com.lore.zzal.motion.HumanVerdict;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionSource;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 검수 후 공개 — 실패 주입(verify-failure-paths).
 *
 * ★ 여기서 지키는 것은 <b>"검수를 통과하지 않은 그림은 사용자에게 안 간다"</b> 하나다. 그 반대(v1 = 검수 전 지급)로
 *   돌아가도 예외도 로그도 안 난다 — 화면에 뜬 것을 사람이 봐야만 드러나는 종류다.
 *   그래서 상태 전이를 전부 여기에 못 박는다: OK → 공개 / REGENERATE → 맥미니 → 두 번 쓰면 그 밤 실패.
 */
@DisplayName("관리자 — 검수 후 공개")
class AdminServiceTest {

    private static final Long ADMIN = 1L;
    private static final Instant T0 = Instant.parse("2026-09-06T00:30:00Z");
    private static final LocalDate NIGHT = LocalDate.of(2026, 9, 5);

    private final MotionCatalog catalog = new MotionCatalog("", "", "v1");
    private final Map<Long, ZzalMotion> motions = new ConcurrentHashMap<>();

    private AdminGuard guard;
    private ZzalMotionRepository motionRepository;
    private ZzalPetRepository petRepository;
    private GenJobRepository jobRepository;
    private S3Service s3Service;
    private AdminService service;

    @BeforeEach
    void setUp() {
        guard = mock(AdminGuard.class);
        motionRepository = mock(ZzalMotionRepository.class);
        petRepository = mock(ZzalPetRepository.class);
        jobRepository = mock(GenJobRepository.class);
        s3Service = mock(S3Service.class);
        when(motionRepository.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(motions.get(i.<Long>getArgument(0))));
        when(motionRepository.findByIdForUpdate(anyLong())).thenAnswer(i -> Optional.ofNullable(motions.get(i.<Long>getArgument(0))));
        when(motionRepository.findByStatusOrderByIdAsc(any())).thenAnswer(i ->
                motions.values().stream().filter(m -> m.getStatus() == i.getArgument(0)).toList());
        when(jobRepository.sumCostByMotionIds(any())).thenReturn(new BigDecimal("0.1970"));
        service = new AdminService(guard, motionRepository, petRepository, jobRepository, catalog, s3Service, 2);
    }

    /** 검수 대기(REVIEW) 상태의 모션 하나. */
    private ZzalMotion reviewing(long id, int seq) {
        ZzalMotion m = ZzalMotion.forCatalog(7L, catalog.bySeq(seq).orElseThrow(), T0);
        ReflectionTestUtils.setField(m, "id", id);
        m.queue(NIGHT);
        m.toReview("images/zzal/pets/7/motions/%d/motion.webp".formatted(id),
                MotionSource.API, GateVerdict.REVIEW, "게이트 미적용", "g0");
        motions.put(id, m);
        return m;
    }

    // ── 판정 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★ OK → 공개 대기(OPEN). 그래도 아직 화면엔 안 뜬다 — 도착은 깨어 있는 첫 조회다")
    void okOpensButDoesNotArrive() {
        ZzalMotion m = reviewing(10L, 101);

        service.review(ADMIN, 10L, HumanVerdict.OK, "좋아요");

        assertThat(m.getStatus()).isEqualTo(MotionStatus.OPEN);
        assertThat(m.getHumanVerdict()).isEqualTo(HumanVerdict.OK);
        assertThat(m.getRevealedAt()).isNull();          // ★ 아직 도착 전
        assertThat(m.advancedImageKey()).isNull();       // 그래서 그림도 안 내려간다
    }

    @Test
    @DisplayName("★★ REGENERATE → 맥미니 재생성(LOCAL_REQUESTED), 두 번 쓰면 그 밤은 FAILED")
    void regenerateTwiceThenFailed() {
        ZzalMotion m = reviewing(11L, 101);

        service.review(ADMIN, 11L, HumanVerdict.REGENERATE, "발이 잘림");
        assertThat(m.getStatus()).isEqualTo(MotionStatus.LOCAL_REQUESTED);
        assertThat(m.getRegenRound()).isEqualTo(1);

        // 맥미니가 올림 → 다시 검수 대기
        service.upload(ADMIN, 11L, "images/zzal/tmp/a.webp");
        assertThat(m.getStatus()).isEqualTo(MotionStatus.REVIEW);

        service.review(ADMIN, 11L, HumanVerdict.REGENERATE, "여전히 잘림");
        assertThat(m.getStatus()).isEqualTo(MotionStatus.LOCAL_REQUESTED);
        assertThat(m.getRegenRound()).isEqualTo(2);

        service.upload(ADMIN, 11L, "images/zzal/tmp/b.webp");
        service.review(ADMIN, 11L, HumanVerdict.REGENERATE, "세 번째도 아님");

        // 한도(2)를 다 썼다 — 그 밤은 실패. 조각은 소모하지 않고 다음 밤에 같은 동작이 다시 오른다(정본 16장)
        assertThat(m.getStatus()).isEqualTo(MotionStatus.FAILED);
        assertThat(m.getNightOf()).isEqualTo(NIGHT);     // 밤은 지운다고 좋을 게 없다(이월 우선권)
        assertThat(m.getRevealedAt()).isNull();

        // ★★ 다음 밤에 다시 오르면 재생성 기회도 처음으로 돌아온다 — 안 그러면 그 동작은 영영 못 배운다(#224 중-1)
        m.queue(NIGHT.plusDays(1));
        assertThat(m.getStatus()).isEqualTo(MotionStatus.QUEUED);
        assertThat(m.getRegenRound()).isZero();
    }

    @Test
    @DisplayName("★★ 지난 밤에 재생성을 다 쓴 행도 다음 밤에는 기회가 돌아온다 — 안 그러면 그 동작은 영영 못 배운다")
    void regenChanceComesBackNextNight() {
        ZzalMotion m = reviewing(16L, 101);

        // 첫째 밤 — 재생성 두 번을 다 쓰고 실패로 끝난다
        service.review(ADMIN, 16L, HumanVerdict.REGENERATE, "1");
        service.upload(ADMIN, 16L, "images/zzal/tmp/n1.webp");
        service.review(ADMIN, 16L, HumanVerdict.REGENERATE, "2");
        service.upload(ADMIN, 16L, "images/zzal/tmp/n2.webp");
        service.review(ADMIN, 16L, HumanVerdict.REGENERATE, "3");
        assertThat(m.getStatus()).isEqualTo(MotionStatus.FAILED);
        assertThat(m.getRegenRound()).isEqualTo(2);

        // 둘째 밤 — 계획이 FAILED 를 다시 큐에 올린다(정본 16장 "조각을 소모하지 않는다")
        m.queue(NIGHT.plusDays(1));
        assertThat(m.getRegenRound()).isZero();
        assertThat(m.getNightOf()).isEqualTo(NIGHT.plusDays(1));

        // ★ 숫자만 0 이 아니라 실제로 두 번을 다시 쓸 수 있어야 한다
        m.toReview("images/zzal/pets/7/motions/16/motion.webp",
                MotionSource.API, GateVerdict.REVIEW, "게이트 미적용", "g0");
        service.review(ADMIN, 16L, HumanVerdict.REGENERATE, "다음 밤 1");
        assertThat(m.getStatus()).isEqualTo(MotionStatus.LOCAL_REQUESTED);
        assertThat(m.getRegenRound()).isEqualTo(1);
        service.upload(ADMIN, 16L, "images/zzal/tmp/n3.webp");
        service.review(ADMIN, 16L, HumanVerdict.REGENERATE, "다음 밤 2");
        assertThat(m.getStatus()).isEqualTo(MotionStatus.LOCAL_REQUESTED);
        assertThat(m.getRegenRound()).isEqualTo(2);
    }

    @Test
    @DisplayName("★★ 반려해 둔 자리(LOCAL_REQUESTED)에 OK → 409 — 퇴짜 맞은 옛 그림이 공개되면 안 된다")
    void okOnRejectedRowIsRefused() {
        ZzalMotion m = reviewing(12L, 101);
        service.review(ADMIN, 12L, HumanVerdict.REGENERATE, "발이 잘림");
        assertThat(m.getStatus()).isEqualTo(MotionStatus.LOCAL_REQUESTED);
        String rejected = m.getImageKey();          // 반려된 그림이 아직 붙어 있다

        assertThatThrownBy(() -> service.review(ADMIN, 12L, HumanVerdict.OK, "역시 괜찮네"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_NOT_IN_REVIEW);

        assertThat(m.getStatus()).isEqualTo(MotionStatus.LOCAL_REQUESTED);   // 안 열렸다
        assertThat(m.advancedImageKey()).isNull();                           // 그림도 안 내려간다
        assertThat(m.getImageKey()).isEqualTo(rejected);
    }

    @Test
    @DisplayName("★ 이미 도착한 동작·실패한 자리·굽기 전 자리에도 판정이 안 통한다(REVIEW 만)")
    void onlyReviewRowsAreJudgeable() {
        ZzalMotion arrived = reviewing(13L, 101);
        arrived.approve(T0);
        arrived.reveal(T0);
        ZzalMotion failed = reviewing(14L, 102);
        failed.markFailed();
        ZzalMotion untouched = ZzalMotion.forCatalog(7L, catalog.bySeq(1).orElseThrow(), T0);
        ReflectionTestUtils.setField(untouched, "id", 15L);
        motions.put(15L, untouched);

        for (long id : new long[]{13L, 14L, 15L}) {
            assertThatThrownBy(() -> service.review(ADMIN, id, HumanVerdict.OK, null))
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_NOT_IN_REVIEW);
        }
        assertThat(arrived.getStatus()).isEqualTo(MotionStatus.OPEN);
        assertThat(failed.getStatus()).isEqualTo(MotionStatus.FAILED);
        assertThat(untouched.getStatus()).isEqualTo(MotionStatus.NONE);
    }

    @Test
    @DisplayName("없는 모션에 판정하면 404")
    void reviewMissing() {
        assertThatThrownBy(() -> service.review(ADMIN, 999L, HumanVerdict.OK, null))
                .isInstanceOf(BusinessException.class);
    }

    // ── 목록 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 검수 대기 목록은 REVIEW 만 — 굽는 중·재생성 중·이미 공개된 것은 안 보인다")
    void pendingOnlyReview() {
        reviewing(20L, 101);
        ZzalMotion open = reviewing(21L, 102);
        open.approve(T0);
        ZzalMotion regen = reviewing(22L, 1);
        regen.requestLocalRegen();

        List<AdminResponses.Pending> pending = service.pending(ADMIN);

        assertThat(pending).singleElement().satisfies(p -> {
            assertThat(p.motionId()).isEqualTo(20L);
            assertThat(p.key()).isEqualTo("roll");
            assertThat(p.label()).isEqualTo("구르기");        // 한글 이름도 같이 준다
            assertThat(p.imageKey()).isNotBlank();
            assertThat(p.nightOf()).isEqualTo(NIGHT);
        });
    }

    @Test
    @DisplayName("★ 재생성 주문에는 지시문 본문이 실린다 — 러너가 레포를 안 봐도 되게")
    void regenRequestsCarryBlockText() {
        ZzalMotion m = reviewing(30L, 101);
        m.requestLocalRegen();
        when(petRepository.findAllById(any())).thenReturn(List.of(pet()));
        MotionCatalog withPrompt = mock(MotionCatalog.class);
        when(withPrompt.block("roll")).thenReturn("TASK: 구른다");
        when(withPrompt.byKey(any())).thenReturn(Optional.empty());
        AdminService svc = new AdminService(guard, motionRepository, petRepository, jobRepository,
                withPrompt, s3Service, 2);

        List<AdminResponses.RegenRequest> requests = svc.regenRequests(ADMIN);

        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r.motionId()).isEqualTo(30L);
            assertThat(r.petId()).isEqualTo(7L);
            assertThat(r.motionKey()).isEqualTo("roll");
            assertThat(r.blockText()).contains("구른다");
            assertThat(r.sheetImageKey()).isEqualTo("images/zzal/sheet");
            assertThat(r.identityText()).isEqualTo("생김새 문단");
            assertThat(r.regenRound()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("★ 지시문을 못 읽는 주문은 목록에서 빠진다 — 러너가 빈손으로 헤매지 않게")
    void regenRequestsSkipBroken() {
        ZzalMotion m = reviewing(31L, 101);
        m.requestLocalRegen();
        when(petRepository.findAllById(any())).thenReturn(List.of(pet()));
        MotionCatalog broken = mock(MotionCatalog.class);
        when(broken.block("roll")).thenThrow(new java.io.UncheckedIOException(new java.io.IOException("없음")));
        AdminService svc = new AdminService(guard, motionRepository, petRepository, jobRepository,
                broken, s3Service, 2);

        assertThat(svc.regenRequests(ADMIN)).isEmpty();
    }

    // ── 업로드 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 업로드는 재생성을 요청한 자리에만 — 아니면 409(아무 모션에나 그림을 밀어 넣을 수 없다)")
    void uploadOnlyWhenRequested() {
        reviewing(40L, 101);       // REVIEW 인 채로

        assertThatThrownBy(() -> service.upload(ADMIN, 40L, "images/zzal/tmp/x.webp"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_REGEN_NOT_REQUESTED);
        verify(s3Service, never()).consume(anyLong(), any(), any());
    }

    @Test
    @DisplayName("★ 업로드 뒤에는 다시 검수 대기 — 맥미니 것도 사람이 한 번 본다")
    void uploadGoesBackToReview() {
        ZzalMotion m = reviewing(41L, 101);
        m.requestLocalRegen();
        int before = m.getAttempts();

        service.upload(ADMIN, 41L, "images/zzal/tmp/y.webp");

        assertThat(m.getStatus()).isEqualTo(MotionStatus.REVIEW);
        assertThat(m.getSource()).isEqualTo(MotionSource.LOCAL);
        assertThat(m.getAttempts()).isEqualTo(before + 1);
        assertThat(m.getHumanVerdict()).isNull();        // 새 그림은 판정을 새로 받는다
        assertThat(m.getImageKey()).isEqualTo("images/zzal/tmp/y.webp");
        verify(s3Service).consume(eq(ADMIN), eq("images/zzal/tmp/y.webp"), any());
    }

    // ── 밤 현황 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("밤 현황은 모션 행을 직접 센다 — 밤 기록의 숫자는 '집기 완료'라 실제와 다르다")
    void nightSummaryCountsRows() {
        ZzalMotion a = reviewing(50L, 101);
        ZzalMotion b = reviewing(51L, 102);
        b.approve(T0);
        ZzalMotion c = reviewing(52L, 1);
        c.markFailed();
        when(motionRepository.findByNightOf(NIGHT)).thenReturn(List.of(a, b, c));

        AdminResponses.NightSummary summary = service.nightSummary(ADMIN, NIGHT);

        assertThat(summary.review()).isEqualTo(1);
        assertThat(summary.open()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.costUsd()).isEqualByComparingTo("0.1970");
    }

    @Test
    @DisplayName("★ 모든 길이 관리자 판정을 먼저 지난다 — 아니면 403")
    void everyPathIsGuarded() {
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.ADMIN_ONLY)).when(guard).require(anyLong());
        reviewing(60L, 101);

        assertThatThrownBy(() -> service.pending(2L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.review(2L, 60L, HumanVerdict.OK, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.regenRequests(2L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.upload(2L, 60L, "k")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.nightSummary(2L, NIGHT)).isInstanceOf(BusinessException.class);
        assertThat(motions.get(60L).getStatus()).isEqualTo(MotionStatus.REVIEW);   // 아무것도 안 바뀐다
    }

    private static ZzalPet pet() {
        ZzalPet p = ZzalPet.hatch(1L, "여울", null, "images/zzal/src", T0);
        p.markAlive("images/zzal/sheet", "생김새 문단", T0);
        ReflectionTestUtils.setField(p, "id", 7L);
        return p;
    }
}
