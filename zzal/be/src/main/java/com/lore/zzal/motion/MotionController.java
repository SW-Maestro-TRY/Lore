package com.lore.zzal.motion;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import com.lore.zzal.motion.dto.MotionResponses;
import com.lore.zzal.pet.PetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 도감 API — 그 펫이 지금까지 연 동작들.
 *
 * <h3>왜 펫 상태 조회에 얹지 않았나</h3>
 * 펫 상태(PetResponses.Detail)는 3초마다 폴링하는 응답이다. 거기에 목록을 얹으면
 * 부화를 기다리는 동안에도 매번 같은 목록이 실려 나간다. 도감은 사용자가 그 자리로
 * 내려갔을 때 한 번 보면 되는 것이라 따로 뒀다.
 *
 * <h3>★ 소유권은 펫 API 와 <b>같은</b> 판정을 탄다</h3>
 * {@link PetService#get} 을 그대로 부른다. 여기서 자체 검사를 새로 짜면 한쪽만 고쳐질 수 있고,
 * 그 순간 이 API 가 남의 도감을 여는 구멍이 된다(DevClockController 가 같은 이유로 findMine 을 공유한다).
 * 남의 펫이면 403 이 아니라 <b>404</b> 다 — 403 은 "그 번호의 펫이 존재한다" 는 사실을 알려주는 셈이라,
 * 번호를 1부터 훑으면 남이 몇 마리 키우는지 셀 수 있게 된다.
 */
@Tag(name = "도감", description = "펫이 연 동작 목록")
@RestController
@RequestMapping("/api/zzal/v1/me/pets/{petId}/motions")
public class MotionController {

    private final PetService petService;
    private final MotionService motionService;

    public MotionController(PetService petService, MotionService motionService) {
        this.petService = petService;
        this.motionService = motionService;
    }

    @Operation(summary = "도감 조회", description = """
            그 펫이 **연 동작만** 돌려준다. 굽는 중(PENDING)·실패(FAILED)는 사용자에게 안 보인다.

            - `total` 은 다 모으면 몇 개인가 = 설정(`app.zzal.motions`)에 적힌 개수.
              화면은 `total` 칸을 그리고 앞에서부터 `opened` 로 채운다
            - **안 연 자리의 이름은 내려가지 않는다.** 생성이 실패하면 다른 동작으로 갈아끼워야 하는데,
              이름을 미리 약속하면 그때 어길 말이 생긴다. 잠긴 칸은 이름 없는 빈 칸이다
            - 아직 무엇을 열지 안 정했으면 `total` 이 0 이고 `opened` 도 빈다.
              그것은 고장이 아니라 정상 상태다(재우면 하나씩 늘어난다)
            - `imageKey` 는 전체 주소가 아니라 뒷부분이다. 앞에 붙는 CDN 주소는 화면이 정한다""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND)")})
    @GetMapping
    public ApiResponse<MotionResponses.Dex> dex(@LoginUser Long userId, @PathVariable Long petId) {
        // 소유권 판정이 먼저다. 통과하지 못하면 목록을 읽지도 않는다.
        petService.get(userId, petId);
        return ApiResponse.ok(MotionResponses.Dex.of(
                motionService.opened(petId), petService.totalMotions()));
    }
}
