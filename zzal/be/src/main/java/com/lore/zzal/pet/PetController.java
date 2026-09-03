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
        List<PetResponses.Detail> pets = petService.refreshAll(userId, now).stream()
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
        ZzalPet pet = petService.refresh(userId, petId, now);
        return ApiResponse.ok(PetResponses.Detail.from(pet, petService.currentStepLabel(petId), now));
    }

    // ── 돌보기와 성장 (#133) ──────────────────────────────────────────────

    @Operation(summary = "돌보기", description = """
            밥·쓰다듬·청소. **무엇을 눌렀는지만** 보내면 결과는 서버가 정한다.

            응답은 상태 조회와 같은 모양이다 — 누른 뒤 다시 조회할 필요가 없다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "돌봄 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "함께 지낼 수 없음(ZZAL_PET_NOT_ALIVE) · 자는 중(ZZAL_PET_SLEEPING) "
                            + "· 밥 없음(ZZAL_NO_FOOD) · 지금은 필요 없음(ZZAL_CARE_NOT_NEEDED)")})
    @PostMapping("/{petId}/care")
    public ApiResponse<PetResponses.Detail> care(@LoginUser Long userId,
                                                 @PathVariable Long petId,
                                                 @Valid @RequestBody PetRequests.Care request) {
        Instant now = Instant.now();
        ZzalPet pet = petService.care(userId, petId, request.action(), now);
        return ApiResponse.ok(PetResponses.Detail.from(pet, null, now));
    }

    @Operation(summary = "연습 시작", description = """
            연습은 즉시 끝나지 않고 시간이 걸린다. 도는 동안 밥·쓰다듬·청소는 계속 된다.

            몇 회분이 쌓일지는 **누른 순간의 행복**으로 정해진다(끝날 때 다시 재지 않는다).""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연습 시작"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "이미 연습 중(ZZAL_TRAIN_IN_PROGRESS) · 재우면 됨(ZZAL_TRAIN_ENOUGH) "
                            + "· 다 배움(ZZAL_ALL_UNLOCKED)")})
    @PostMapping("/{petId}/train")
    public ApiResponse<PetResponses.Detail> train(@LoginUser Long userId, @PathVariable Long petId) {
        Instant now = Instant.now();
        ZzalPet pet = petService.train(userId, petId, now);
        return ApiResponse.ok(PetResponses.Detail.from(pet, null, now));
    }

    @Operation(summary = "재우기", description = """
            연습 값을 다 치렀을 때만 재울 수 있다. 자는 동안 수치는 멈춘다.

            자는 시간이 곧 다음 움직임을 굽는 시간이다(#22 에서 연결).""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재움"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "연습이 부족(ZZAL_TRAIN_NOT_ENOUGH) · 연습 중(ZZAL_TRAIN_IN_PROGRESS) "
                            + "· 이미 자는 중(ZZAL_PET_SLEEPING)")})
    @PostMapping("/{petId}/sleep")
    public ApiResponse<PetResponses.Detail> sleep(@LoginUser Long userId, @PathVariable Long petId) {
        Instant now = Instant.now();
        ZzalPet pet = petService.sleep(userId, petId, now);
        return ApiResponse.ok(PetResponses.Detail.from(pet, null, now));
    }

    @Operation(summary = "깨우기", description = """
            다 자고 나서 깨우면 **새로운 움직임 하나가 열린다**.

            자동으로 깨우지 않는다 — 여는 순간을 사용자가 보게 하기 위해서다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "깨어남 · 해금"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "자고 있지 않음(ZZAL_PET_NOT_SLEEPING) · 아직 덜 잠(ZZAL_PET_STILL_SLEEPING)")})
    @PostMapping("/{petId}/wake")
    public ApiResponse<PetResponses.Detail> wake(@LoginUser Long userId, @PathVariable Long petId) {
        Instant now = Instant.now();
        ZzalPet pet = petService.wake(userId, petId, now);
        return ApiResponse.ok(PetResponses.Detail.from(pet, null, now));
    }
}
