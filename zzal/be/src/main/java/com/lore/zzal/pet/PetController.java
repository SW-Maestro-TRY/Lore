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
 * 주소 규칙 — 내 것은 `me` 밑, 남의 것은 `users/{userId}` 밑(친구 구경은 나중).
 * `me` 를 쓰면 주소에 남의 번호를 넣을 자리가 없어서, 남의 데이터를 건드리는 실수 자체가 불가능해진다.
 */
@Tag(name = "펫", description = "펫 생성·조회")
@RestController
@RequestMapping("/api/zzal/v1/me/pets")
public class PetController {

    private final PetService petService;

    public PetController(PetService petService) {
        this.petService = petService;
    }

    @Operation(summary = "펫 생성", description = """
            그림·이름·세부사항을 받아 **부화를 시작**한다.

            - 기다리지 않고 즉시 응답한다. 생성은 뒤에서 계속되며, 진행 상황은 상태 조회로 본다
            - imageKey 는 presign 으로 발급받은 **내 것이고 아직 안 쓴 키**여야 한다
            - 지금은 한 사람이 한 마리만 키울 수 있다(유료 슬롯은 나중)""")
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
        Instant now = Instant.now();
        List<PetResponses.Detail> pets = petService.list(userId).stream()
                .map(p -> PetResponses.Detail.from(p, petService.currentStepLabel(p.getId()), now))
                .toList();
        return ApiResponse.ok(pets);
    }

    @Operation(summary = "펫 상태 조회", description = """
            부화 중이든 함께 지내는 중이든 **이 API 하나로** 답한다.
            부화 중에는 몇 초마다 불러 진행 상황을 확인하면 된다(`ready` 가 true 가 되면 완료).""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND)")})
    @GetMapping("/{petId}")
    public ApiResponse<PetResponses.Detail> detail(@LoginUser Long userId, @PathVariable Long petId) {
        Instant now = Instant.now();
        ZzalPet pet = petService.get(userId, petId);
        return ApiResponse.ok(PetResponses.Detail.from(pet, petService.currentStepLabel(petId), now));
    }
}
