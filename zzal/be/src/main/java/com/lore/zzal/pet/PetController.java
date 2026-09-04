package com.lore.zzal.pet;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.pet.dto.PetRequests;
import com.lore.zzal.pet.dto.PetResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 펫 API v2 — 계약은 {@code zzal/docs/api-v2.md}.
 *
 * 주소 규칙 — 내 것은 `me` 밑. 주소에 남의 번호를 넣을 자리가 없어서 남의 데이터를 건드리는 실수가 불가능하다.
 * <b>모든 행동(POST)의 응답 = `PetDetail` 최신 상태.</b> 화면은 누른 뒤 다시 조회하지 않는다.
 * 채팅·동작 seen·앨범·미니게임 v2 는 PR-4·5 에서 이 밑에 붙는다.
 */
@Tag(name = "펫", description = "펫 생성·조회·돌보기·재우기·성격·배경·공유·보내기 (v2)")
@RestController
@RequestMapping("/api/zzal/v2/me/pets")
public class PetController {

    private final PetService petService;
    private final MotionCatalog catalog;

    public PetController(PetService petService, MotionCatalog catalog) {
        this.petService = petService;
        this.catalog = catalog;
    }

    private PetResponses.Detail detail(ZzalPet pet, String stepLabel, Instant real) {
        return PetResponses.Detail.from(pet, stepLabel, pet.now(real), catalog);
    }

    private PetResponses.Detail detail(PetService.Action action, Instant real) {
        return PetResponses.Detail.from(action.pet(), null, action.pet().now(real), catalog, action.justUnlocked());
    }

    @Operation(summary = "펫 생성", description = """
            그림·이름(12자)·세부사항을 받아 **부화를 시작**한다. 기다리지 않고 즉시 응답한다.
            imageKey 는 presign 으로 발급받은 **내 것이고 아직 안 쓴 키**여야 한다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "부화 시작"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "올바르지 않은 이미지(INVALID_UPLOAD_KEY) · 이미 사용한 이미지(UPLOAD_KEY_ALREADY_USED)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "부화 중(ZZAL_PET_ALREADY_HATCHING) · 자리 없음(ZZAL_PET_LIMIT_REACHED)")})
    @PostMapping
    public ApiResponse<PetResponses.Created> create(@LoginUser Long userId,
                                                    @Valid @RequestBody PetRequests.Create request) {
        Instant now = Instant.now();
        ZzalPet pet = petService.create(userId, request.name(), request.note(), request.imageKey(), now);
        return ApiResponse.ok(PetResponses.Created.from(pet, ZzalRules.HATCH_ESTIMATE.toSeconds()));
    }

    @Operation(summary = "내 펫 목록 조회")
    @GetMapping
    public ApiResponse<List<PetResponses.Detail>> list(@LoginUser Long userId) {
        Instant real = Instant.now();
        List<PetResponses.Detail> pets = petService.refreshAll(userId, real).stream()
                .map(p -> detail(p, petService.currentStepLabel(p.getId()), real))
                .toList();
        return ApiResponse.ok(pets);
    }

    @Operation(summary = "펫 상태 조회", description = """
            부화 중이든 함께 지내는 중이든 **이 API 하나로** 답한다. 조회 = 정산 + 그날 첫 조회면 함께한 날 +1.
            23:00 이 지났으면 잠들어 있고 10:00 이 지났으면 깨어 있다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND)")})
    @GetMapping("/{petId}")
    public ApiResponse<PetResponses.Detail> detail(@LoginUser Long userId, @PathVariable Long petId) {
        Instant real = Instant.now();
        ZzalPet pet = petService.refresh(userId, petId, real);
        return ApiResponse.ok(detail(pet, petService.currentStepLabel(petId), real));
    }

    // ── 돌보기 (정본 4·5장) ───────────────────────────────────────────────

    @Operation(summary = "돌보기", description = """
            밥·간식·쓰다듬기·청소·목욕·약. **무엇을 눌렀는지만** 보내면 결과는 서버가 정한다.
            응답의 `justUnlocked` 에 이번 행동으로 열린 2층 동작 seq 가 실린다(폭죽).""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "돌봄 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "ZZAL_PET_NOT_ALIVE · ZZAL_PET_SLEEPING · ZZAL_NO_FOOD · ZZAL_CARE_NOT_NEEDED "
                            + "· ZZAL_BATH_DONE_TODAY · ZZAL_SICK_REFUSES")})
    @PostMapping("/{petId}/care")
    public ApiResponse<PetResponses.Detail> care(@LoginUser Long userId,
                                                 @PathVariable Long petId,
                                                 @Valid @RequestBody PetRequests.Care request) {
        Instant real = Instant.now();
        return ApiResponse.ok(detail(petService.care(userId, petId, request.action(), real), real));
    }

    // ── 잠 (정본 2·12장) ──────────────────────────────────────────────────

    @Operation(summary = "재우기", description = """
            KST 19:00~23:00 에 재운다(23:00 엔 저절로 잠든다). 아기 60분 안에는 낮잠 한 번.
            재우면 행복 +1·친밀도 +10. 자는 동안 수치는 멈추고 밥만 찬다. 밤잠은 하루의 경계다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재움"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "창 밖(ZZAL_NOT_SLEEP_TIME) · 이미 자는 중(ZZAL_PET_SLEEPING)")})
    @PostMapping("/{petId}/sleep")
    public ApiResponse<PetResponses.Detail> sleep(@LoginUser Long userId, @PathVariable Long petId) {
        Instant real = Instant.now();
        return ApiResponse.ok(detail(petService.sleep(userId, petId, real), real));
    }

    @Operation(summary = "깨우기", description = """
            KST 07:00~10:00 에 깨운다(10:00 엔 저절로 깬다 = 늦잠). 낮잠은 5분 뒤. 깨우면 친밀도 +10.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "깨어남"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "자고 있지 않음(ZZAL_PET_NOT_SLEEPING) · 창 밖(ZZAL_NOT_WAKE_TIME)")})
    @PostMapping("/{petId}/wake")
    public ApiResponse<PetResponses.Detail> wake(@LoginUser Long userId, @PathVariable Long petId) {
        Instant real = Instant.now();
        return ApiResponse.ok(detail(petService.wake(userId, petId, real), real));
    }

    // ── 성격·배경·공유 (정본 6·10·15장) ───────────────────────────────────

    @Operation(summary = "성격 고르기", description = "온순·활발·수줍음·응석·시크 + 세계관 한 줄(40자). 언제든, 자는 중에도.")
    @PostMapping("/{petId}/personality")
    public ApiResponse<PetResponses.Detail> personality(@LoginUser Long userId,
                                                        @PathVariable Long petId,
                                                        @Valid @RequestBody PetRequests.PersonalityChoice request) {
        Instant real = Instant.now();
        return ApiResponse.ok(detail(petService.choosePersonality(userId, petId, request.personality(), request.world(), real), real));
    }

    @Operation(summary = "배경 바꾸기", description = "2층 4종이 열린 뒤. 그 전엔 ZZAL_FEATURE_LOCKED.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "바꿈"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "아직 안 열림(ZZAL_FEATURE_LOCKED)")})
    @PostMapping("/{petId}/background")
    public ApiResponse<PetResponses.Detail> background(@LoginUser Long userId,
                                                       @PathVariable Long petId,
                                                       @Valid @RequestBody PetRequests.Background request) {
        Instant real = Instant.now();
        return ApiResponse.ok(detail(petService.changeBackground(userId, petId, request.background(), real), real));
    }

    @Operation(summary = "다운로드·공유 기록", description = """
            열린 동작 어느 것이든. 서버는 **횟수만 기록**한다(튜토리얼 25분의 "했다" 가 되는 서버 사실).
            파일 합성(워터마크)은 v2 판.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "기록"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "안 열린 동작(ZZAL_MOTION_NOT_OPEN)")})
    @PostMapping("/{petId}/share")
    public ApiResponse<PetResponses.Detail> share(@LoginUser Long userId,
                                                  @PathVariable Long petId,
                                                  @Valid @RequestBody PetRequests.Share request) {
        Instant real = Instant.now();
        return ApiResponse.ok(detail(petService.share(userId, petId, request.motionKey(), real), real));
    }

    // ── 보내기 ────────────────────────────────────────────────────────────

    @Operation(summary = "펫 보내기(놓아주기)", description = """
            지금 함께 지내는 아이를 보내고 **자리를 비운다**. 되돌릴 수 없다. 지우지는 않는다.
            부화 중에는 보낼 수 없고, 이미 떠난 아이에게 다시 불러도 성공으로 답한다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "보냈음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "부화 중이라 아직 보낼 수 없음(ZZAL_PET_RELEASE_NOT_ALLOWED)")})
    @PostMapping("/{petId}/release")
    public ApiResponse<PetResponses.Detail> release(@LoginUser Long userId, @PathVariable Long petId) {
        Instant real = Instant.now();
        ZzalPet pet = petService.release(userId, petId, real);
        return ApiResponse.ok(detail(pet, null, real));
    }
}
