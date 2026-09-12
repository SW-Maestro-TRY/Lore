package com.lore.zzal.admin;

import com.lore.zzal.PetFixture;
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
import com.lore.zzal.motion.ZzalMotionCandidate;
import com.lore.zzal.motion.ZzalMotionCandidateRepository;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.admin.dto.AdminRequests;
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
import java.util.HashMap;
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
    private ZzalMotionCandidateRepository candidateRepository;
    private GenStepRecordRepository stepRepository;
    private AdminService service;
    private final Map<Long, List<ZzalMotionCandidate>> candidates = new HashMap<>();
    private long nextCandidateId = 1000L;

    @BeforeEach
    void setUp() {
        guard = mock(AdminGuard.class);
        motionRepository = mock(ZzalMotionRepository.class);
        petRepository = mock(ZzalPetRepository.class);
        jobRepository = mock(GenJobRepository.class);
        s3Service = mock(S3Service.class);
        candidateRepository = mock(ZzalMotionCandidateRepository.class);
        stepRepository = mock(GenStepRecordRepository.class);
        candidates.clear();
        nextCandidateId = 1000L;
        when(candidateRepository.save(any())).thenAnswer(i -> {
            ZzalMotionCandidate c = i.getArgument(0);
            ReflectionTestUtils.setField(c, "id", nextCandidateId++);
            candidates.computeIfAbsent(c.getMotionId(), k -> new java.util.ArrayList<>()).add(c);
            return c;
        });
        when(candidateRepository.findByMotionIdOrderByRoundAscIdAsc(anyLong()))
                .thenAnswer(i -> candidates.getOrDefault(i.<Long>getArgument(0), List.of()));
        when(candidateRepository.findByMotionIdInOrderByRoundAscIdAsc(any()))
                .thenAnswer(i -> i.<List<Long>>getArgument(0).stream()
                        .flatMap(id -> candidates.getOrDefault(id, List.<ZzalMotionCandidate>of()).stream())
                        .toList());
        when(motionRepository.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(motions.get(i.<Long>getArgument(0))));
        when(motionRepository.findByIdForUpdate(anyLong())).thenAnswer(i -> Optional.ofNullable(motions.get(i.<Long>getArgument(0))));
        when(motionRepository.findByStatusOrderByIdAsc(any())).thenAnswer(i ->
                motions.values().stream().filter(m -> m.getStatus() == i.getArgument(0)).toList());
        when(jobRepository.sumCostByMotionIds(any())).thenReturn(new BigDecimal("0.1970"));
        service = new AdminService(guard, motionRepository, candidateRepository, petRepository,
                jobRepository, stepRepository, catalog, s3Service, 2, 60);
    }

    /** 검수 대기(REVIEW) 상태의 모션 하나. */
    private ZzalMotion reviewing(long id, int seq) {
        ZzalMotion m = ZzalMotion.forCatalog(7L, catalog.bySeq(seq).orElseThrow(), T0);
        ReflectionTestUtils.setField(m, "id", id);
        m.queue(NIGHT);
        // ★ 굽는 중이던 줄만 검수 대기로 간다(1.9). 운영에서는 claim 이 DB 에서 BAKING 으로 집는다.
        ReflectionTestUtils.setField(m, "status", MotionStatus.BAKING);
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

        service.review(ADMIN, 10L, HumanVerdict.OK, "좋아요", null);

        assertThat(m.getStatus()).isEqualTo(MotionStatus.OPEN);
        assertThat(m.getHumanVerdict()).isEqualTo(HumanVerdict.OK);
        assertThat(m.getRevealedAt()).isNull();          // ★ 아직 도착 전
        assertThat(m.advancedImageKey()).isNull();       // 그래서 그림도 안 내려간다
    }

    @Test
    @DisplayName("★★ REGENERATE → 맥미니 재생성, 두 번 다 쓰면 HOLD — 자동으로 안 풀린다")
    void regenerateTwiceThenHold() {
        ZzalMotion m = reviewing(11L, 101);

        service.review(ADMIN, 11L, HumanVerdict.REGENERATE, "발이 잘림", null);
        assertThat(m.getStatus()).isEqualTo(MotionStatus.LOCAL_REQUESTED);
        assertThat(m.getRegenRound()).isEqualTo(1);

        // 맥미니가 올림 → 다시 검수 대기
        service.upload(ADMIN, 11L, List.of(new AdminRequests.Candidate("images/zzal/tmp/a.webp", null, null)));
        assertThat(m.getStatus()).isEqualTo(MotionStatus.REVIEW);

        service.review(ADMIN, 11L, HumanVerdict.REGENERATE, "여전히 잘림", null);
        assertThat(m.getStatus()).isEqualTo(MotionStatus.LOCAL_REQUESTED);
        assertThat(m.getRegenRound()).isEqualTo(2);

        service.upload(ADMIN, 11L, List.of(new AdminRequests.Candidate("images/zzal/tmp/b.webp", null, null)));
        service.review(ADMIN, 11L, HumanVerdict.REGENERATE, "세 번째도 아님", null);

        // ★★ 한도(2)를 다 썼다 = 후보 일곱 판이 전부 아니었다는 뜻이다 → 보류함(상훈님 2026-09-11).
        //   옛 규칙은 FAILED 로 내려 <b>다음 밤에 자동으로 다시 굽게</b> 했는데, 같은 지시문·같은 원본으로
        //   또 구우면 또 같은 것이 나온다. 돈만 쓰고 같은 자리로 돌아온다.
        assertThat(m.getStatus()).isEqualTo(MotionStatus.HOLD);
        assertThat(m.getNightOf()).isEqualTo(NIGHT);     // 밤은 지운다고 좋을 게 없다(이월 우선권)
        assertThat(m.getRevealedAt()).isNull();

        // ★ 자동으로는 안 풀린다 — 밤 계획과 스위프는 NONE·FAILED·QUEUED 만 본다.
        //   사람이 지시문이나 원본을 고친 뒤 다시 꺼낸다("어떻게든 노출시킬 거야").
        assertThat(MotionStatus.HOLD).isNotIn(MotionStatus.NONE, MotionStatus.FAILED, MotionStatus.QUEUED);
    }

    @Test
    @DisplayName("★ 사람이 보류함에서 꺼내면 재생성 기회가 처음으로 돌아온다")
    void regenChanceComesBackWhenReleased() {
        ZzalMotion m = reviewing(16L, 101);

        // 첫째 밤 — 재생성 두 번을 다 쓰고 실패로 끝난다
        service.review(ADMIN, 16L, HumanVerdict.REGENERATE, "1", null);
        service.upload(ADMIN, 16L, List.of(new AdminRequests.Candidate("images/zzal/tmp/n1.webp", null, null)));
        service.review(ADMIN, 16L, HumanVerdict.REGENERATE, "2", null);
        service.upload(ADMIN, 16L, List.of(new AdminRequests.Candidate("images/zzal/tmp/n2.webp", null, null)));
        service.review(ADMIN, 16L, HumanVerdict.REGENERATE, "3", null);
        assertThat(m.getStatus()).isEqualTo(MotionStatus.HOLD);
        assertThat(m.getRegenRound()).isEqualTo(2);

        // ★ 보류함에서 꺼내는 것은 사람이 한다(지시문·원본을 고친 뒤). 꺼내면 처음 조건으로 돌아가야
        //   한다 — 안 그러면 꺼내자마자 한 판 실패로 곧바로 다시 보류함이 되어 그 동작은 영영 못 배운다.
        // ★★ 자동 경로가 쓰는 queue() 로는 안 열린다 — 그게 "자동으로 안 풀린다" 를 지키는 방법이다.
        assertThat(m.queue(NIGHT.plusDays(1))).isFalse();
        assertThat(m.releaseFromHold(NIGHT.plusDays(1))).isTrue();
        assertThat(m.getRegenRound()).isZero();
        assertThat(m.getNightOf()).isEqualTo(NIGHT.plusDays(1));

        // ★ 숫자만 0 이 아니라 실제로 두 번을 다시 쓸 수 있어야 한다
        ReflectionTestUtils.setField(m, "status", MotionStatus.BAKING);
        m.toReview("images/zzal/pets/7/motions/16/motion.webp",
                MotionSource.API, GateVerdict.REVIEW, "게이트 미적용", "g0");
        service.review(ADMIN, 16L, HumanVerdict.REGENERATE, "다음 밤 1", null);
        assertThat(m.getStatus()).isEqualTo(MotionStatus.LOCAL_REQUESTED);
        assertThat(m.getRegenRound()).isEqualTo(1);
        service.upload(ADMIN, 16L, List.of(new AdminRequests.Candidate("images/zzal/tmp/n3.webp", null, null)));
        service.review(ADMIN, 16L, HumanVerdict.REGENERATE, "다음 밤 2", null);
        assertThat(m.getStatus()).isEqualTo(MotionStatus.LOCAL_REQUESTED);
        assertThat(m.getRegenRound()).isEqualTo(2);
    }

    @Test
    @DisplayName("★★ 나온 판이 전부 남는다 — 덮어쓰면 고를 수가 없다")
    void everyCandidateIsKept() {
        ZzalMotion m = reviewing(70L, 101);
        service.review(ADMIN, 70L, HumanVerdict.REGENERATE, "다시", null);

        // 맥미니가 한 라운드에 3판을 나란히 올린다
        service.upload(ADMIN, 70L, List.of(
                new AdminRequests.Candidate("images/zzal/tmp/c1.webp", "images/zzal/tmp/g1.png", 0.91),
                new AdminRequests.Candidate("images/zzal/tmp/c2.webp", "images/zzal/tmp/g2.png", 0.73),
                new AdminRequests.Candidate("images/zzal/tmp/c3.webp", null, null)));

        assertThat(m.getStatus()).isEqualTo(MotionStatus.REVIEW);
        assertThat(candidates.get(70L)).hasSize(3);
        assertThat(candidates.get(70L)).extracting(ZzalMotionCandidate::getImageKey)
                .containsExactly("images/zzal/tmp/c1.webp", "images/zzal/tmp/c2.webp", "images/zzal/tmp/c3.webp");
    }

    @Test
    @DisplayName("★★ 한 라운드에 세 판까지 — 문서가 말하는 상한을 코드가 지킨다")
    void perRoundCapIsEnforced() {
        reviewing(72L, 101);
        service.review(ADMIN, 72L, HumanVerdict.REGENERATE, "다시", null);

        // 러너가 우리 것이라 지금은 안 터지지만, 막지 않으면 "API 1 + 7 + 7 = 15판" 이 들어간다.
        assertThatThrownBy(() -> service.upload(ADMIN, 72L, List.of(
                new AdminRequests.Candidate("images/zzal/tmp/x1.webp", null, null),
                new AdminRequests.Candidate("images/zzal/tmp/x2.webp", null, null),
                new AdminRequests.Candidate("images/zzal/tmp/x3.webp", null, null),
                new AdminRequests.Candidate("images/zzal/tmp/x4.webp", null, null))))
                .isInstanceOf(BusinessException.class);

        assertThat(candidates.getOrDefault(72L, List.of()))
                .as("거절된 요청은 한 판도 안 남긴다")
                .isEmpty();

        // ★ 라운드 상한과 재생성 라운드 상한(2)이 곱해져 총량이 일곱으로 묶인다.
        //   총량을 따로 세지 않는 이유는 보류함에서 꺼낸 자리를 막지 않기 위해서다(서비스 주석).
        assertThat(AdminService.PER_ROUND_MAX).isEqualTo(3);
    }

    @Test
    @DisplayName("★★ 러너에게 한 번 내준 일감은 다시 안 내준다 — 같은 판을 N번 굽던 것 (P-14)")
    void agentJobsAreClaimedOnce() {
        ZzalMotion m = reviewing(80L, 7);
        when(petRepository.findAllById(any())).thenReturn(List.of(pet()));
        service.review(ADMIN, 80L, HumanVerdict.REGENERATE, "다시", null);

        assertThat(service.regenRequestsForAgent(ADMIN)).hasSize(1);
        assertThat(m.getAgentClaimedAt()).as("가져간 시각이 찍힌다").isNotNull();

        // 맥미니가 굽는 10분 동안 러너가 계속 폴링한다 — 예전에는 매번 같은 판을 받았다
        assertThat(service.regenRequestsForAgent(ADMIN)).isEmpty();
        assertThat(service.regenRequestsForAgent(ADMIN)).isEmpty();

        // ★ 사람이 보는 목록은 집기와 무관하다 — 관리자 화면이 러너의 일감을 뺏지 않고, 가려지지도 않는다
        assertThat(service.regenRequests(ADMIN)).hasSize(1);
    }

    @Test
    @DisplayName("★ 결과가 올라오면 집기를 지운다 — 다음 라운드가 유예만큼 막히지 않게")
    void uploadClearsTheAgentClaim() {
        ZzalMotion m = reviewing(81L, 7);
        when(petRepository.findAllById(any())).thenReturn(List.of(pet()));
        service.review(ADMIN, 81L, HumanVerdict.REGENERATE, "다시", null);
        assertThat(service.regenRequestsForAgent(ADMIN)).hasSize(1);

        service.uploadForAgent(ADMIN, 81L, List.of(new AdminRequests.Candidate("images/zzal/tmp/r.webp", null, null)));

        assertThat(m.getAgentClaimedAt()).isNull();
    }

    @Test
    @DisplayName("★ 빌려주는 것이지 영영 주는 것이 아니다 — 유예를 넘긴 집기는 없는 것으로 치고 다시 내준다")
    void staleClaimIsHandedOutAgain() {
        ZzalMotion m = reviewing(82L, 7);
        when(petRepository.findAllById(any())).thenReturn(List.of(pet()));
        service.review(ADMIN, 82L, HumanVerdict.REGENERATE, "다시", null);
        assertThat(service.regenRequestsForAgent(ADMIN)).hasSize(1);

        // 러너가 가져간 뒤 죽었다 — 유예(60분)를 넘겼다
        ReflectionTestUtils.setField(m, "agentClaimedAt", Instant.now().minus(java.time.Duration.ofMinutes(61)));

        assertThat(service.regenRequestsForAgent(ADMIN)).hasSize(1);
    }

    @Test
    @DisplayName("★★ 빈 후보 목록은 400 계열로 거절한다 — 500 이 아니다 (P-8)")
    void emptyCandidateListIsRejected() {
        reviewing(73L, 101);
        service.review(ADMIN, 73L, HumanVerdict.REGENERATE, "다시", null);

        // @Valid 를 안 거치는 호출자(러너 경로 재사용 등)가 빈 목록으로 부르는 자리다.
        // 고치기 전에는 candidates.get(0) 이 IndexOutOfBoundsException 으로 터져 500 이었다.
        assertThatThrownBy(() -> service.upload(ADMIN, 73L, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> service.uploadForAgent(ADMIN, 73L, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

        assertThat(candidates.getOrDefault(73L, List.of())).isEmpty();

        // 실패 뒤에 정상 한 판을 다시 올리면 성공한다 — 거절이 자리를 잠그지 않는다
        service.upload(ADMIN, 73L, List.of(new AdminRequests.Candidate("images/zzal/tmp/ok.webp", null, null)));
        assertThat(candidates.get(73L)).hasSize(1);
    }

    @Test
    @DisplayName("★★ 고른 판이 대표가 된다 — 여기를 빠뜨리면 고르지 않은 판이 공개된다")
    void chosenCandidateBecomesTheOneShown() {
        ZzalMotion m = reviewing(71L, 101);
        service.review(ADMIN, 71L, HumanVerdict.REGENERATE, "다시", null);
        service.upload(ADMIN, 71L, List.of(
                new AdminRequests.Candidate("images/zzal/tmp/a.webp", null, null),
                new AdminRequests.Candidate("images/zzal/tmp/b.webp", null, null)));

        ZzalMotionCandidate second = candidates.get(71L).get(1);
        service.review(ADMIN, 71L, HumanVerdict.OK, "이게 낫다", second.getId());

        assertThat(m.getStatus()).isEqualTo(MotionStatus.OPEN);
        assertThat(m.getImageKey()).isEqualTo("images/zzal/tmp/b.webp");
        assertThat(second.isChosen()).isTrue();
        assertThat(candidates.get(71L).get(0).isChosen()).as("한 모션에 고른 판은 하나뿐").isFalse();
    }

    @Test
    @DisplayName("남의 모션의 판은 못 고른다 — 아무 그림이나 아무 도감에 들어가면 안 된다")
    void cannotChooseForeignCandidate() {
        reviewing(72L, 101);
        service.review(ADMIN, 72L, HumanVerdict.REGENERATE, "다시", null);
        service.upload(ADMIN, 72L, List.of(new AdminRequests.Candidate("images/zzal/tmp/z.webp", null, null)));

        assertThatThrownBy(() -> service.review(ADMIN, 72L, HumanVerdict.OK, null, 9999L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★★ 반려해 둔 자리(LOCAL_REQUESTED)에 OK → 409 — 퇴짜 맞은 옛 그림이 공개되면 안 된다")
    void okOnRejectedRowIsRefused() {
        ZzalMotion m = reviewing(12L, 101);
        service.review(ADMIN, 12L, HumanVerdict.REGENERATE, "발이 잘림", null);
        assertThat(m.getStatus()).isEqualTo(MotionStatus.LOCAL_REQUESTED);
        String rejected = m.getImageKey();          // 반려된 그림이 아직 붙어 있다

        assertThatThrownBy(() -> service.review(ADMIN, 12L, HumanVerdict.OK, "역시 괜찮네", null))
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
            assertThatThrownBy(() -> service.review(ADMIN, id, HumanVerdict.OK, null, null))
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_NOT_IN_REVIEW);
        }
        assertThat(arrived.getStatus()).isEqualTo(MotionStatus.OPEN);
        assertThat(failed.getStatus()).isEqualTo(MotionStatus.FAILED);
        assertThat(untouched.getStatus()).isEqualTo(MotionStatus.NONE);
    }

    @Test
    @DisplayName("없는 모션에 판정하면 404")
    void reviewMissing() {
        assertThatThrownBy(() -> service.review(ADMIN, 999L, HumanVerdict.OK, null, null))
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
        AdminService svc = new AdminService(guard, motionRepository, candidateRepository, petRepository,
                jobRepository, stepRepository, withPrompt, s3Service, 2, 60);

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
        AdminService svc = new AdminService(guard, motionRepository, candidateRepository, petRepository,
                jobRepository, stepRepository, broken, s3Service, 2, 60);

        assertThat(svc.regenRequests(ADMIN)).isEmpty();
    }

    // ── 업로드 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 업로드는 재생성을 요청한 자리에만 — 아니면 409(아무 모션에나 그림을 밀어 넣을 수 없다)")
    void uploadOnlyWhenRequested() {
        reviewing(40L, 101);       // REVIEW 인 채로

        assertThatThrownBy(() -> service.upload(ADMIN, 40L, List.of(new AdminRequests.Candidate("images/zzal/tmp/x.webp", null, null))))
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

        service.upload(ADMIN, 41L, List.of(new AdminRequests.Candidate("images/zzal/tmp/y.webp", null, null)));

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
        assertThatThrownBy(() -> service.review(2L, 60L, HumanVerdict.OK, null, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.regenRequests(2L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.upload(2L, 60L, List.of(new AdminRequests.Candidate("k", null, null)))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.nightSummary(2L, NIGHT)).isInstanceOf(BusinessException.class);
        assertThat(motions.get(60L).getStatus()).isEqualTo(MotionStatus.REVIEW);   // 아무것도 안 바뀐다
    }

    private static ZzalPet pet() {
        ZzalPet p = PetFixture.hatching(1L, "여울", null, "images/zzal/src", T0);
        p.markAlive("images/zzal/sheet", "생김새 문단", T0);
        p.skipTutorial(T0);
        ReflectionTestUtils.setField(p, "id", 7L);
        return p;
    }
}
