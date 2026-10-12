package com.lore.zzal.dev;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import com.lore.zzal.pet.DevPetPatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 개발 리모컨 — 상태 세팅과 이벤트 트리거(remote-control-spec-1011 1·2장 전수표).
 *
 * ★ 내 펫만(남의 펫은 404) · 운영에서는 이 컨트롤러 자체가 없다({@code app.zzal.dev-tools}).
 */
@Tag(name = "개발용", description = "시연·확인 전용. 운영에서는 꺼져 있어 존재하지 않는다")
@RestController
@RequestMapping("/api/zzal/v1/dev/pets")
@ConditionalOnProperty(name = "app.zzal.dev-tools", havingValue = "true")
public class DevStateController {

    private final DevService devService;

    public DevStateController(DevService devService) {
        this.devService = devService;
    }

    @Operation(summary = "내부 상태 읽기", description = "리모컨 상태 패널용 — 케어 미스·오늘 한도·평생 횟수·조각 칸·동작 행까지. 방문으로 치지 않는다.")
    @GetMapping("/{petId}/state")
    public ApiResponse<DevResponses.DevState> devState(@LoginUser Long userId, @PathVariable Long petId) {
        return ApiResponse.ok(devService.state(userId, petId, Instant.now()));
    }

    @Operation(summary = "상태 세팅", description = """
            준 칸만 값으로 바꾼다(부분 갱신). 검증은 범위만 — 넘으면 400 이고 아무것도 안 바뀐다.
            연쇄(병·떠남)는 돌리지 않는다. 시계를 넘기면 규칙대로 이어진다. 응답의 effects 에 바뀐 칸이 한 줄씩.""")
    @PatchMapping("/{petId}/state")
    public ApiResponse<DevResponses.DevResult> devPatchState(@LoginUser Long userId, @PathVariable Long petId,
                                                  @RequestBody DevPetPatch patch) {
        return ApiResponse.ok(devService.patch(userId, petId, patch, Instant.now()));
    }

    @Operation(summary = "이벤트 일으키기", description = """
            운영이 그 일을 일으키는 메서드를 그대로 부른다(시각·조건 무시). 응답 effects 에 그 사이 일어난 일이 한 줄씩.

            hatch-complete · hatch-fail · tutorial-next · tutorial-skip · tutorial-restart · clock-start · graduation ·
            graduation-reset · care-miss · sick(arg=NEGLECT|DIRTY|UPSET|NATURAL) · recover · sleep · nap · night-settle ·
            wake · wake-auto · leave-notice · visit · trip-start · postcard · call-back · day-plus · scene(arg=1~3) ·
            night-scene · chat-open · chat-close · chat-expire · chat-abandon · game-win · game-lose · today-reset ·
            unlock(arg=all|2층 key) · pieces-open · piece-add(arg=FOOD|PLAY|CLEAN|BOND) · piece-complete ·
            gift-review · gift-open · gift-arrive · gift-view(arg=seq, 기본 첫 선물) · release · revive""")
    @PostMapping("/{petId}/events/{kind}")
    public ApiResponse<DevResponses.DevResult> devEvent(@LoginUser Long userId, @PathVariable Long petId,
                                                  @PathVariable String kind,
                                                  @Parameter(description = "이벤트 인자 하나(병 종류·조각 칸·장면 수·seq·해금 대상)")
                                                  @RequestParam(required = false) String arg) {
        return ApiResponse.ok(devService.event(userId, petId, kind, arg, Instant.now()));
    }
}
