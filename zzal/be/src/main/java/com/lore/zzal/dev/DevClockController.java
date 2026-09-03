package com.lore.zzal.dev;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.response.ApiResponse;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.dto.PetResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * 개발용 시간 당기기.
 *
 * <h3>★★ 왜 규칙을 짧게 바꾸지 않고 시계를 당기는가</h3>
 * 서버가 수치의 정본이 되면서 프론트의 시연용 빨리감기가 안 통한다. 그렇다고 확인하려고
 * {@code ZzalRules} 의 값(잠 5분~3시간, 포만감 4시간)을 줄이면 <b>테스트와 실제가 다른 규칙으로
 * 돌게 되어, 확인한 것이 실제로 확인한 게 아니게 된다.</b> 칸 계산의 나머지·앵커 밀기처럼
 * "짧은 값에서만 맞는" 버그는 바로 그 차이에 숨는다.
 * 여기서는 규칙을 한 글자도 건드리지 않고 <b>앵커만 과거로 민다</b> — 기다림만 사라진다.
 *
 * <h3>★★ 운영에서는 이 컨트롤러 자체가 없다</h3>
 * {@code app.zzal.dev-tools} 가 true 일 때만 빈으로 올라온다(기본 false). 꺼져 있으면
 * 매핑이 아예 등록되지 않아 404 다. <b>주소에 {@code /dev/} 가 들어가는 것은 방어가 아니다</b> —
 * 그건 이름일 뿐이고, 코드가 올라와 있는 한 언젠가 누군가 그 주소를 부른다.
 * 여기에 더해 로그인도 필요하고({@code WebSecurityConfig} 의 {@code anyRequest().authenticated()}),
 * 남의 펫은 돌봄 API 와 같은 판정으로 막힌다.
 */
@Tag(name = "개발용", description = "시연·확인 전용. 운영에서는 꺼져 있어 존재하지 않는다")
@RestController
@RequestMapping("/api/zzal/v1/dev/pets")
@ConditionalOnProperty(name = "app.zzal.dev-tools", havingValue = "true")
public class DevClockController {

    /**
     * 한 번에 당길 수 있는 상한.
     *
     * 실수로 0을 하나 더 붙였을 때 앵커가 몇 년 전으로 밀려도 예외 하나 안 나고, 그 뒤
     * 확인한 결과는 전부 "이미 다 떨어진 값" 이라 아무것도 검증하지 못한다. 가장 긴 확인
     * 대상(잠 3시간·포만감 4시간)보다 넉넉한 30일에서 끊는다.
     */
    private static final Duration MAX_ADVANCE = Duration.ofDays(30);

    private final PetService petService;

    public DevClockController(PetService petService) {
        this.petService = petService;
    }

    @Operation(summary = "시간 당기기", description = """
            그 펫의 모든 시각 앵커를 준 만큼 **과거로** 민다 = 그만큼 시간이 흐른 것으로 만든다.
            잠·연습·포만감·행복·쓰레기·밥 충전이 한꺼번에 그만큼 진행된 상태가 된다.

            - **규칙은 그대로다.** 값을 짧게 바꾸는 것이 아니라 시계만 당기므로,
              여기서 확인한 동작이 곧 운영에서 도는 동작이다
            - 내 펫만 당길 수 있다(남의 펫은 404)
            - **운영에서는 이 API 가 존재하지 않는다** — `app.zzal.dev-tools=true` 일 때만 열린다""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "당김"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "0 이하이거나 30일을 넘김(INVALID_INPUT)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND)")})
    @PostMapping("/{petId}/advance-clock")
    public ApiResponse<PetResponses.Detail> advanceClock(@LoginUser Long userId,
                                                         @PathVariable Long petId,
                                                         @Valid @RequestBody DevRequests.AdvanceClock request) {
        Duration by = request.toDuration();
        if (by.isZero() || by.isNegative()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "당길 시간을 초 또는 분으로 주세요");
        }
        if (by.compareTo(MAX_ADVANCE) > 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "한 번에 %d일까지만 당길 수 있어요".formatted(MAX_ADVANCE.toDays()));
        }
        Instant now = Instant.now();
        ZzalPet pet = petService.advanceClock(userId, petId, by, now);
        return ApiResponse.ok(PetResponses.Detail.from(pet, null, now, petService.totalMotions()));
    }
}
