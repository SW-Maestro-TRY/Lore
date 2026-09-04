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
@Tag(name = "펫", description = "펫 생성·조회·돌보기·보내기")
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
            - 지금은 한 사람이 한 마리만 키울 수 있다(유료 슬롯은 나중)
            - 자리를 세는 것은 **부화 중·함께 지내는 중**인 아이뿐이다. 보낸 아이(DEAD)와
              태어나지 못한 알(FAILED)은 자리를 먹지 않으므로, 보낸 뒤에는 바로 새로 만들 수 있다""")
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
                .map(p -> PetResponses.Detail.from(
                        p, petService.currentStepLabel(p.getId()), now, petService.totalMotions()))
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
        return ApiResponse.ok(PetResponses.Detail.from(
                pet, petService.currentStepLabel(petId), now, petService.totalMotions()));
    }

    // ── 첫날 순서(튜토리얼) ───────────────────────────────────────────────

    @Operation(summary = "튜토리얼 완료", description = """
            첫날 순서를 끝냈다고 알린다. **이 순간부터 수치(포만감·행복·쓰레기)가 흐르기 시작한다.**

            - 끝내기 전까지는 시간이 아무리 지나도 수치가 줄지 않는다. 안내를 따라가는 사이에
              값이 어긋나면 "쓰다듬 → 행복 4칸 → 연습 2회분" 이라는 첫날 순서의 숫자가 맞지 않아,
              튜토리얼이 자기 규칙을 못 보여주게 되기 때문이다
            - **안 끝내고 떠난 펫은 굶지 않는다.** 며칠 뒤에 돌아와도 처음 그대로다(의도한 동작)
            - **두 번 눌러도 안전하다.** 이미 끝난 상태면 에러 대신 지금 상태를 그대로 돌려준다

            응답은 상태 조회와 같은 모양이다. `tutorialDone` 이 true 가 된다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "완료(이미 끝난 상태였어도 200)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "함께 지낼 수 없음(ZZAL_PET_NOT_ALIVE)")})
    @PostMapping("/{petId}/tutorial-done")
    public ApiResponse<PetResponses.Detail> tutorialDone(@LoginUser Long userId,
                                                         @PathVariable Long petId) {
        Instant now = Instant.now();
        ZzalPet pet = petService.completeTutorial(userId, petId, now);
        return ApiResponse.ok(PetResponses.Detail.from(pet, null, now, petService.totalMotions()));
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
        return ApiResponse.ok(PetResponses.Detail.from(pet, null, now, petService.totalMotions()));
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
        return ApiResponse.ok(PetResponses.Detail.from(pet, null, now, petService.totalMotions()));
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
        return ApiResponse.ok(PetResponses.Detail.from(pet, null, now, petService.totalMotions()));
    }

    @Operation(summary = "깨우기", description = """
            다 자고 나서 깨우면 **자는 동안 익힌 움직임이 열린다**.

            - 자동으로 깨우지 않는다 — 여는 순간을 사용자가 보게 하기 위해서다
            - **못 배웠어도 깨어나기는 한다.** 그때는 `learned.learned` 가 false 이고
              `learned.message` 에 화면에 띄울 말이 담긴다. 치른 연습은 그대로 남아 다음에 다시 시도한다""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "깨어남"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "자고 있지 않음(ZZAL_PET_NOT_SLEEPING) · 아직 덜 잠(ZZAL_PET_STILL_SLEEPING)")})
    @PostMapping("/{petId}/wake")
    public ApiResponse<PetResponses.Detail> wake(@LoginUser Long userId, @PathVariable Long petId) {
        Instant now = Instant.now();
        PetService.WakeResult r = petService.wake(userId, petId, now);
        return ApiResponse.ok(PetResponses.Detail.from(
                r.pet(), null, now, petService.totalMotions(), r.outcome()));
    }

    // ── 보내기 ────────────────────────────────────────────────────────────

    @Operation(summary = "펫 보내기(놓아주기)", description = """
            지금 함께 지내는 아이를 보내고 **자리를 비운다**. 다른 그림으로 새로 시작하기 위한 길이다.

            - 되돌릴 수 없다. 화면에서 한 번 더 물어본 뒤에 부르는 것을 전제로 한다
            - **지우지 않는다.** 만들어 둔 움짤과 배운 움직임은 그대로 남는다
              (이미 만들어진 결과물이고, 나중에 다시 만날 수 있게 하기 위해서다)
            - **부화 중에는 보낼 수 없다** — 굽고 있는 작업이 붕 뜬다. 끝난 뒤에 보낸다
            - 이미 떠난 아이에게 다시 불러도 성공으로 답한다(두 번 눌러도 안전하다)

            응답은 상태 조회와 같은 모양이다. `phase` 는 `DEAD`, `deathReason` 은 `RELEASED` 가 된다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "보냈음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "부화 중이라 아직 보낼 수 없음(ZZAL_PET_RELEASE_NOT_ALLOWED)")})
    @PostMapping("/{petId}/release")
    public ApiResponse<PetResponses.Detail> release(@LoginUser Long userId, @PathVariable Long petId) {
        Instant now = Instant.now();
        ZzalPet pet = petService.release(userId, petId, now);
        return ApiResponse.ok(PetResponses.Detail.from(pet, null, now, petService.totalMotions()));
    }
}
