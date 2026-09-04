package com.lore.zzal.pet;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
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
 * 펫 API.
 *
 * 주소 규칙 — 내 것은 `me` 밑. 주소에 남의 번호를 넣을 자리가 없어서 남의 데이터를 건드리는 실수가 불가능하다.
 *
 * ⚠️ 경로가 아직 v1 이다 — 시계 엔진(PR-2)만 갈아 끼운 과도기. v2 경로·`PetDetail` v2 전체는 PR-3(#192).
 *    훈련·튜토리얼 완료 API 는 정본에 없어 이 PR 에서 사라졌다.
 */
@Tag(name = "펫", description = "펫 생성·조회·돌보기·재우기·보내기")
@RestController
@RequestMapping("/api/zzal/v1/me/pets")
public class PetController {

    private final PetService petService;

    public PetController(PetService petService) {
        this.petService = petService;
    }

    @Operation(summary = "펫 생성", description = """
            그림·이름·세부사항을 받아 **부화를 시작**한다. 기다리지 않고 즉시 응답한다.
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
                .map(p -> PetResponses.Detail.from(p, petService.currentStepLabel(p.getId()), p.now(real)))
                .toList();
        return ApiResponse.ok(pets);
    }

    @Operation(summary = "펫 상태 조회", description = """
            부화 중이든 함께 지내는 중이든 **이 API 하나로** 답한다. 조회가 곧 정산이다 —
            흐른 시간(깨어 있는 시간만)이 반영되고, 23:00 이 지났으면 잠들어 있고 10:00 이 지났으면 깨어 있다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND)")})
    @GetMapping("/{petId}")
    public ApiResponse<PetResponses.Detail> detail(@LoginUser Long userId, @PathVariable Long petId) {
        Instant real = Instant.now();
        ZzalPet pet = petService.refresh(userId, petId, real);
        return ApiResponse.ok(PetResponses.Detail.from(pet, petService.currentStepLabel(petId), pet.now(real)));
    }

    // ── 돌보기 (정본 4·5장) ───────────────────────────────────────────────

    @Operation(summary = "돌보기", description = """
            밥·간식·쓰다듬기·청소·목욕·약. **무엇을 눌렀는지만** 보내면 결과는 서버가 정한다.
            응답은 상태 조회와 같은 모양이다 — 누른 뒤 다시 조회할 필요가 없다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "돌봄 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "함께 지낼 수 없음(ZZAL_PET_NOT_ALIVE) · 자는 중(ZZAL_PET_SLEEPING) · 밥 없음(ZZAL_NO_FOOD) "
                            + "· 지금은 필요 없음(ZZAL_CARE_NOT_NEEDED) · 오늘 목욕 함(ZZAL_BATH_DONE_TODAY) · 아파서 거부(ZZAL_SICK_REFUSES)")})
    @PostMapping("/{petId}/care")
    public ApiResponse<PetResponses.Detail> care(@LoginUser Long userId,
                                                 @PathVariable Long petId,
                                                 @Valid @RequestBody PetRequests.Care request) {
        Instant real = Instant.now();
        ZzalPet pet = petService.care(userId, petId, request.action(), real);
        return ApiResponse.ok(PetResponses.Detail.from(pet, null, pet.now(real)));
    }

    // ── 잠 (정본 2·12장) ──────────────────────────────────────────────────

    @Operation(summary = "재우기", description = """
            KST 19:00~23:00 에 재운다(23:00 엔 저절로 잠든다). 아기 60분 안에는 낮잠 한 번.
            재우면 행복 +1·친밀도 +10. 자는 동안 수치는 멈추고 밥만 찬다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재움"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "창 밖(ZZAL_NOT_SLEEP_TIME) · 이미 자는 중(ZZAL_PET_SLEEPING)")})
    @PostMapping("/{petId}/sleep")
    public ApiResponse<PetResponses.Detail> sleep(@LoginUser Long userId, @PathVariable Long petId) {
        Instant real = Instant.now();
        ZzalPet pet = petService.sleep(userId, petId, real);
        return ApiResponse.ok(PetResponses.Detail.from(pet, null, pet.now(real)));
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
        ZzalPet pet = petService.wake(userId, petId, real);
        return ApiResponse.ok(PetResponses.Detail.from(pet, null, pet.now(real)));
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
        return ApiResponse.ok(PetResponses.Detail.from(pet, null, pet.now(real)));
    }
}
