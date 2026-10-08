package com.lore.zzal.admin;

import com.lore.common.exception.BusinessException;
import com.lore.common.s3.S3Service;
import com.lore.common.s3.S3Storage;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenStepRecord;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.generation.HatchPostures;
import com.lore.zzal.generation.HatchService;
import com.lore.zzal.generation.Layer2Service;
import com.lore.zzal.generation.LayerCandidateBaker;
import com.lore.zzal.generation.RejectedGridArchive;
import com.lore.zzal.generation.client.PostProcessor;
import com.lore.zzal.generation.steps.Layer2PostStep;
import com.lore.zzal.generation.steps.PostProcessStep;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionSeeder;
import com.lore.zzal.pet.Layer2Status;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자 1층·2층 복구(#696) — 후보 올리기 → 고르기.
 */
@DisplayName("관리자 층 복구 — 후보 → 고르기")
class AdminLayerServiceTest {

    private static final Long ADMIN = 1L;
    private static final Long OWNER = 9L;
    private static final Long PET = 7L;
    private static final Instant T0 = Instant.parse("2026-10-07T03:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-08T03:00:00Z");

    private ZzalPetRepository pets;
    private GenStepRecordRepository steps;
    private S3Service s3Service;
    private S3Storage storage;
    private PostProcessor post;
    private PostProcessor.Session session;
    private Layer2Service layer2;
    private MotionSeeder seeder;
    private RejectedGridArchive archive;
    private AdminLayerService service;
    private final List<String[]> uploads = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        pets = mock(ZzalPetRepository.class);
        steps = mock(GenStepRecordRepository.class);
        s3Service = mock(S3Service.class);
        storage = mock(S3Storage.class);
        doAnswer(inv -> {
            uploads.add(new String[]{inv.getArgument(0), inv.<Path>getArgument(1).toString()});
            return null;
        }).when(storage).upload(anyString(), any(), anyString());
        post = mock(PostProcessor.class);
        session = mock(PostProcessor.Session.class);
        when(post.open(anyString(), anyString())).thenReturn(session);
        layer2 = mock(Layer2Service.class);
        seeder = mock(MotionSeeder.class);
        archive = mock(RejectedGridArchive.class);
        HatchService hatch = mock(HatchService.class);
        when(hatch.currentVersion()).thenReturn("v1");
        UserRepository users = mock(UserRepository.class);
        User owner = User.signUp("layer-owner@example.invalid");
        ReflectionTestUtils.setField(owner, "id", OWNER);
        when(users.findById(OWNER)).thenReturn(Optional.of(owner));
        S3Client s3 = mock(S3Client.class);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("images/zzal/pets/7/rejected/11-grid2.png").build(),
                        S3Object.builder().key("images/zzal/pets/7/rejected/10-grid.png").build())
                .build());

        LayerCandidateBaker baker = new LayerCandidateBaker(post, new MotionCatalog("", "", "v1"), new HatchPostures());
        service = new AdminLayerService(mock(AdminGuard.class), pets, users, mock(GenJobRepository.class), steps,
                s3Service, storage, s3, "bucket", baker, layer2, hatch, seeder, archive,
                mock(PlatformTransactionManager.class));
    }

    private ZzalPet alivePet(Layer2Status status) {
        ZzalPet pet = ZzalPet.draft(OWNER, "images/zzal/src", T0);
        ReflectionTestUtils.setField(pet, "id", PET);
        pet.character("루나", null, null, null, null, null, T0);
        pet.markAlive("sheet.png", "문단", T0);
        pet.markBasicBaked(1);
        pet.resetLayer2(T0);
        if (status == Layer2Status.FAILED) {
            pet.startLayer2Attempt(T0);
            pet.markLayer2Failed("GRID_STRUCTURE_INVALID 열 개수", T0);
        } else if (status == Layer2Status.READY) {
            pet.markLayer2Ready(T0);
        }
        when(pets.findById(PET)).thenReturn(Optional.of(pet));
        when(pets.findByIdForUpdate(PET)).thenReturn(Optional.of(pet));
        return pet;
    }

    @Test
    @DisplayName("★★ 2층 후보 3장 → 게이트 판정(통과 2·거부 1) → 통과한 것 고르기 → 새 판·READY·나머지 보존·임시 정리")
    void layer2CandidatesThenPick() throws Exception {
        ZzalPet pet = alivePet(Layer2Status.FAILED);
        // 두 번째 후보만 게이트 거부
        doThrow(new IllegalStateException("후처리 실패(exit 1)\nGRID_STRUCTURE_INVALID 열 개수 5 != 4"))
                .when(session).split(eq("up/b.png"), any(), anyString());

        List<AdminLayerService.Candidate> cands =
                service.candidates(ADMIN, PET, 2, List.of("up/a.png", "up/b.png", "up/c.png"), NOW);

        assertThat(cands).extracting(AdminLayerService.Candidate::gate).containsExactly("PASS", "REJECTED", "PASS");
        assertThat(cands.get(1).message()).contains("열 개수 5 != 4");
        assertThat(cands.get(0).previewKeys()).hasSize(8).containsKey("eat_rice");
        assertThat(cands.get(0).previewKey()).endsWith("/eat_rice.webp")
                .startsWith("images/zzal/pets/7/candidates/layer2/");
        // 키는 관리자 본인 것·안 쓴 것만
        verify(s3Service).consume(ADMIN, "up/a.png", NOW);
        // 2층 후보는 지금 1층 앵커(판 1)를 깔고 자른다
        verify(session, org.mockito.Mockito.times(3)).seedAnchors("images/zzal/pets/7/basic/1/anchors.json");
        assertThat(pet.getLayer2Candidates()).contains("|PASS|1").contains("|REJECTED|1");

        // 거부된 후보는 못 고른다
        assertThatThrownBy(() -> service.pick(ADMIN, PET, 2, cands.get(1).candidateId(), NOW))
                .isInstanceOf(BusinessException.class);

        AdminLayerService.Picked picked = service.pick(ADMIN, PET, 2, cands.get(0).candidateId(), NOW);

        assertThat(picked.basicRound()).isEqualTo(2);
        assertThat(pet.getBasicRound()).isEqualTo(2);
        assertThat(pet.getLayer2Status()).isEqualTo(Layer2Status.READY);
        assertThat(pet.getLayer2Candidates()).isNull();
        List<String> to = uploads.stream().map(u -> u[0]).toList();
        // 새 판 = 1층 8종(옮겨 실음) + 후보 2층 8종 + 후보 앵커
        assertThat(to).contains("images/zzal/pets/7/basic/2/base.webp", "images/zzal/pets/7/basic/2/eat_rice.webp",
                "images/zzal/pets/7/basic/2/anchors.json");
        assertThat(to.stream().filter(k -> k.startsWith("images/zzal/pets/7/basic/2/"))).hasSize(17);
        // 고르지 않은 후보 격자는 rejected/ 로 보존
        assertThat(to).contains("images/zzal/pets/7/rejected/cand-" + cands.get(1).candidateId() + "-grid2.png",
                "images/zzal/pets/7/rejected/cand-" + cands.get(2).candidateId() + "-grid2.png");
        verify(storage).delete(any());
        verify(layer2, never()).schedule(any());
    }

    @Test
    @DisplayName("★ 2층 후보를 자른 뒤 판이 바뀌었으면 고르기를 막는다(앵커가 옛 판 기준)")
    void layer2PickRejectsStaleBase() {
        ZzalPet pet = alivePet(Layer2Status.FAILED);
        List<AdminLayerService.Candidate> cands = service.candidates(ADMIN, PET, 2, List.of("up/a.png"), NOW);
        pet.markBasicBaked(5);
        assertThatThrownBy(() -> service.pick(ADMIN, PET, 2, cands.get(0).candidateId(), NOW))
                .isInstanceOf(BusinessException.class).hasMessageContaining("다시 올리세요");
    }

    @Test
    @DisplayName("★★ 1층 후보로 부화 실패 알 살리기 → ALIVE · 새 판 · 2층 PENDING 으로 넘김")
    void layer1PickRevivesFailedPet() {
        ZzalPet pet = ZzalPet.draft(OWNER, "images/zzal/src", T0);
        ReflectionTestUtils.setField(pet, "id", PET);
        pet.character("루나", null, null, null, null, null, T0);
        pet.markHatchFailed();
        when(pets.findById(PET)).thenReturn(Optional.of(pet));
        when(pets.findByIdForUpdate(PET)).thenReturn(Optional.of(pet));
        GenStepRecord sheet = GenStepRecord.start(1L, 0, "sheet", T0);
        sheet.succeed("images/zzal/pets/7/sheet.png", null, "m", BigDecimal.ZERO, T0);
        GenStepRecord identity = GenStepRecord.start(1L, 1, "identity", T0);
        identity.succeed(null, "생김새 문단", "m", BigDecimal.ZERO, T0);
        when(steps.findSucceededByPet(PET, GenKind.HATCH)).thenReturn(List.of(sheet, identity));
        GenStepRecord post2 = GenStepRecord.start(2L, 1, Layer2PostStep.NAME, T0);
        GenStepRecord grid2 = GenStepRecord.start(2L, 0, PostProcessStep.GRID2, T0);
        when(steps.findSucceededByPet(PET, GenKind.LAYER2)).thenReturn(List.of(grid2, post2));

        List<AdminLayerService.Candidate> cands = service.candidates(ADMIN, PET, 1, List.of("up/g.png"), NOW);
        assertThat(cands.get(0).gate()).isEqualTo("PASS");
        AdminLayerService.Picked picked = service.pick(ADMIN, PET, 1, cands.get(0).candidateId(), NOW);

        assertThat(pet.getPhase()).isEqualTo(PetPhase.ALIVE);
        assertThat(pet.getDeathReason()).isNull();
        // ★ 부화 시각은 덮어쓰지 않는다(#702) — 시작 시각은 원래 값, 부화 시각은 비어 있었으니 지금, 복구 시각은 따로
        assertThat(pet.getHatchStartedAt()).isEqualTo(T0);
        assertThat(pet.getHatchedAt()).isEqualTo(NOW);
        assertThat(pet.getRecoveredAt()).isEqualTo(NOW);
        assertThat(pet.getSheetImageKey()).isEqualTo("images/zzal/pets/7/sheet.png");
        assertThat(pet.getIdentityText()).isEqualTo("생김새 문단");
        assertThat(pet.getBasicRound()).isEqualTo(1);
        assertThat(picked.layer2Status()).isEqualTo("PENDING");
        verify(seeder).seed(eq(PET), any());
        // 1층 앵커가 바뀌었으니 2층 자르기는 다시 — 격자는 남긴다(돈 안 듦)
        verify(steps).delete(post2);
        verify(steps, never()).delete(grid2);
        verify(layer2).schedule(PET);
        List<String> to = uploads.stream().map(u -> u[0]).toList();
        assertThat(to.stream().filter(k -> k.startsWith("images/zzal/pets/7/basic/1/"))).hasSize(9);
    }

    @Test
    @DisplayName("★ 살리기 — 부화 시각이 이미 있으면 그대로 둔다(비어 있을 때만 채움)")
    void reviveKeepsExistingHatchedAt() {
        ZzalPet pet = ZzalPet.draft(OWNER, "images/zzal/src", T0);
        pet.character("루나", null, null, null, null, null, T0);
        pet.markHatchFailed();
        Instant old = T0.plusSeconds(300);
        ReflectionTestUtils.setField(pet, "hatchedAt", old);

        pet.reviveByAdmin("s.png", "문단", NOW);

        assertThat(pet.getPhase()).isEqualTo(PetPhase.ALIVE);
        assertThat(pet.getHatchStartedAt()).isEqualTo(T0);
        assertThat(pet.getHatchedAt()).isEqualTo(old);
        assertThat(pet.getRecoveredAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> pet.reviveByAdmin("s.png", "문단", NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("★★ 살아 있는 펫의 1층·2층 고르기 — 부화 시각은 그대로, recovered_at 만 남는다(#702)")
    void pickKeepsHatchTimes() {
        ZzalPet pet = alivePet(Layer2Status.FAILED);
        Instant started = pet.getHatchStartedAt();
        Instant hatched = pet.getHatchedAt();
        assertThat(pet.getRecoveredAt()).isNull();

        List<AdminLayerService.Candidate> c2 = service.candidates(ADMIN, PET, 2, List.of("up/a.png"), NOW);
        service.pick(ADMIN, PET, 2, c2.get(0).candidateId(), NOW);
        assertThat(pet.getHatchStartedAt()).isEqualTo(started);
        assertThat(pet.getHatchedAt()).isEqualTo(hatched);
        assertThat(pet.getRecoveredAt()).isEqualTo(NOW);

        Instant later = NOW.plusSeconds(600);
        List<AdminLayerService.Candidate> c1 = service.candidates(ADMIN, PET, 1, List.of("up/g.png"), later);
        service.pick(ADMIN, PET, 1, c1.get(0).candidateId(), later);
        assertThat(pet.getHatchStartedAt()).isEqualTo(started);
        assertThat(pet.getHatchedAt()).isEqualTo(hatched);
        assertThat(pet.getRecoveredAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("★★ 결함 표시 — 사용자 노출 상태(READY)는 그대로, 목록에만 오른다 · READY 는 운영 재시도도 막는다(#702)")
    void flagDoesNotTouchExposure() {
        ZzalPet pet = alivePet(Layer2Status.READY);
        when(pets.findByPhaseAndLayer2FlaggedTrueOrderByIdDesc(PetPhase.ALIVE)).thenAnswer(inv ->
                pet.isLayer2Flagged() ? List.of(pet) : List.of());

        AdminLayerService.State flagged = service.flag(ADMIN, PET, "빈 칸", NOW);

        assertThat(flagged.layer2Status()).isEqualTo("READY");
        assertThat(flagged.flagged()).isTrue();
        assertThat(pet.getLayer2Status()).isEqualTo(Layer2Status.READY);
        assertThat(pet.isLayer2Ready()).isTrue();
        assertThat(pet.getLayer2UpdatedAt()).isEqualTo(T0);      // 상태 시각도 안 바뀐다
        assertThat(pet.getBasicRound()).isEqualTo(1);
        verify(storage, never()).delete(any());
        // 목록에 오른다 — 사유와 함께
        assertThat(service.list(ADMIN, NOW)).singleElement().satisfies(it -> {
            assertThat(it.petId()).isEqualTo(PET);
            assertThat(it.flagged()).isTrue();
            assertThat(it.layer2Status()).isEqualTo("READY");
            assertThat(it.lastError()).contains("빈 칸");
        });
        // READY 를 PENDING 으로 돌리는 운영 재시도는 막는다 — 사용자 2층이 잠기므로
        assertThatThrownBy(() -> service.retry(ADMIN, PET, NOW)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("READY");
        assertThat(pet.getLayer2Status()).isEqualTo(Layer2Status.READY);
        verify(layer2, never()).schedule(any());

        // 해제 — 목록에서 내려가고 상태는 그대로
        AdminLayerService.State unflagged = service.unflag(ADMIN, PET);
        assertThat(unflagged.flagged()).isFalse();
        assertThat(unflagged.layer2Status()).isEqualTo("READY");
        assertThat(service.list(ADMIN, NOW)).isEmpty();
    }

    @Test
    @DisplayName("★★ 노출 상태가 바뀌는 것은 고르기뿐 — 결함 표시 READY 펫에 후보를 올려도 그대로, 고르면 새 판·표시 해제")
    void onlyPickChangesExposure() {
        ZzalPet pet = alivePet(Layer2Status.READY);
        service.flag(ADMIN, PET, "빈 칸", NOW);

        List<AdminLayerService.Candidate> cands = service.candidates(ADMIN, PET, 2, List.of("up/a.png"), NOW);
        assertThat(pet.getLayer2Status()).isEqualTo(Layer2Status.READY);
        assertThat(pet.getBasicRound()).isEqualTo(1);
        assertThat(pet.isLayer2Flagged()).isTrue();

        service.pick(ADMIN, PET, 2, cands.get(0).candidateId(), NOW);
        assertThat(pet.getLayer2Status()).isEqualTo(Layer2Status.READY);
        assertThat(pet.getBasicRound()).isEqualTo(2);
        assertThat(pet.isLayer2Flagged()).isFalse();
    }

    @Test
    @DisplayName("★★ 다시 만들기 — LOCAL_REQUESTED 로 목록에 오르고 노출 상태는 그대로 · 후보가 오면 요청이 지워진다")
    void regenRequestThenCandidatesClear() {
        ZzalPet pet = alivePet(Layer2Status.READY);
        when(pets.findByPhaseAndLayer2FlaggedTrueOrderByIdDesc(PetPhase.ALIVE)).thenAnswer(inv ->
                pet.isLayer2Flagged() ? List.of(pet) : List.of());

        AdminLayerService.Regen r = service.requestRegen(ADMIN, PET, 2, NOW);
        assertThat(r.regenRequestedAt()).isEqualTo(NOW);
        assertThat(pet.getLayer2Status()).isEqualTo(Layer2Status.READY);
        assertThat(pet.isLayer2Flagged()).as("READY 펫이 목록에서 사라지지 않게 표시도 건다").isTrue();

        AdminLayerService.Item it = service.list(ADMIN, NOW).get(0);
        assertThat(it.recovery()).isEqualTo(AdminLayerService.LOCAL_REQUESTED);
        assertThat(it.regenRequestedAt()).isEqualTo(NOW);
        // 지금 보이는 2층 8종(결함이 있는 그 판)도 함께 준다 — 카드에서 견준다
        assertThat(it.currentKeys()).hasSize(8).containsEntry("eat_rice", "images/zzal/pets/7/basic/1/eat_rice.webp");

        service.candidates(ADMIN, PET, 2, List.of("up/a.png"), NOW);
        assertThat(pet.getRegenRequestedAt(2)).isNull();
        assertThat(service.list(ADMIN, NOW).get(0).recovery()).isEqualTo("CANDIDATES");
        assertThat(pet.getLayer2Status()).isEqualTo(Layer2Status.READY);
    }

    @Test
    @DisplayName("★ 다시 만들기 — 1층은 부화 실패 알만, 취소하면 지워진다 · 해제하면 2층 요청도 지워진다")
    void regenRules() {
        ZzalPet pet = alivePet(Layer2Status.FAILED);
        assertThatThrownBy(() -> service.requestRegen(ADMIN, PET, 1, NOW)).isInstanceOf(BusinessException.class);
        service.requestRegen(ADMIN, PET, 2, NOW);
        assertThat(pet.isLayer2Flagged()).as("FAILED 는 이미 목록에 있다 — 표시를 덧붙이지 않는다").isFalse();
        service.cancelRegen(ADMIN, PET, 2);
        assertThat(pet.getRegenRequestedAt(2)).isNull();
        service.requestRegen(ADMIN, PET, 2, NOW);
        service.unflag(ADMIN, PET);
        assertThat(pet.getRegenRequestedAt(2)).isNull();
    }

    @Test
    @DisplayName("★ 재시도 — FAILED 2층은 PENDING·시도 0 으로 돌리고 넘김(격자 보존)")
    void retryFailed() {
        alivePet(Layer2Status.FAILED);
        GenStepRecord grid2 = GenStepRecord.start(2L, 0, PostProcessStep.GRID2, T0);
        when(steps.findSucceededByPet(PET, GenKind.LAYER2)).thenReturn(List.of(grid2));
        AdminLayerService.State retried = service.retry(ADMIN, PET, NOW);
        assertThat(retried.layer2Status()).isEqualTo("PENDING");
        assertThat(retried.attempts()).isZero();
        verify(archive).preserve(PET, grid2);
        verify(steps).delete(grid2);
        verify(layer2).schedule(PET);
    }

    @Test
    @DisplayName("★ 목록 — 2층 FAILED(살아 있음) + 1층 실패 알, 보존 격자는 층별로")
    void listsBothLayers() {
        ZzalPet alive = alivePet(Layer2Status.FAILED);
        ZzalPet failed = ZzalPet.draft(OWNER, "images/zzal/src", T0);
        ReflectionTestUtils.setField(failed, "id", 8L);
        failed.markHatchFailed();
        when(pets.findByPhaseAndLayer2StatusInOrderByIdDesc(eq(PetPhase.ALIVE), any())).thenReturn(List.of(alive));
        when(pets.findByPhase(PetPhase.FAILED)).thenReturn(List.of(failed));

        List<AdminLayerService.Item> items = service.list(ADMIN, NOW);

        assertThat(items).extracting(AdminLayerService.Item::petId).containsExactly(8L, PET);
        AdminLayerService.Item l2 = items.get(1);
        assertThat(l2.layer()).isEqualTo(2);
        assertThat(l2.lastError()).contains("GRID_STRUCTURE_INVALID");
        assertThat(l2.sheetKey()).isEqualTo("sheet.png");
        assertThat(l2.rejectedKeys()).containsExactly("images/zzal/pets/7/rejected/11-grid2.png");
        assertThat(items.get(0).layer()).isEqualTo(1);
    }
}
