package com.lore.zzal.dev;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 개발 리모컨 — 온보딩 다시 밟기(내 펫 지우기)와 굽기 단계 강제. 규칙은 {@link DevOnboardingService} 머리말.
 */
@Tag(name = "개발용", description = "시연·확인 전용. 운영에서는 꺼져 있어 존재하지 않는다")
@RestController
@ConditionalOnProperty(name = "app.zzal.dev-tools", havingValue = "true")
public class DevOnboardingController {

    private final DevOnboardingService service;

    public DevOnboardingController(DevOnboardingService service) {
        this.service = service;
    }

    /** 굽기 단계 강제 요청. */
    @Schema(description = "굽기 단계 강제 요청")
    public record DevHatchStageRequest(
            @Schema(description = "1..전체 단계 수 · done · failed", example = "2") String stage) {
    }

    @Operation(summary = "내 펫 지우고 처음부터", description = """
            로그인한 내 계정의 펫 전부와 딸린 행(동작·후보·소원·엽서·장면·채팅·게임·조각·굽기 기록·공유)을 한 트랜잭션으로 지운다.
            피드백·행동 기록은 남긴다. 되돌릴 수 없다. 응답에 표별 지운 행 수.""")
    @PostMapping("/api/zzal/v1/dev/me/reset")
    public ApiResponse<DevResponses.DevReset> devResetMe(@LoginUser Long userId) {
        return ApiResponse.ok(service.resetMe(userId));
    }

    @Operation(summary = "굽기 단계 강제", description = """
            알 화면의 굽기 진행을 강제한다. 숫자 N = 앞의 N 단계만 끝난 척(나머지는 아직) · done = 모두 끝내고 운영과 같은 길로 살린다
            · failed = 부화 실패. 단계 기록의 상태만 바꾸고 그림을 새로 굽지 않는다(돈 0). 지금 굽는 중이면 409.
            가짜 생성 서버에서는 살아난 아이(ALIVE)도 알로 되돌린다. 진짜 생성 서버에서는 거절(되살릴 때 2층이 다시 구워져 돈이 나간다).""")
    @PostMapping("/api/zzal/v1/dev/pets/{petId}/hatch/stage")
    public ApiResponse<DevResponses.DevHatchStage> devHatchStage(@LoginUser Long userId, @PathVariable Long petId,
                                                         @RequestBody DevHatchStageRequest request) {
        return ApiResponse.ok(service.hatchStage(userId, petId, request.stage()));
    }
}
