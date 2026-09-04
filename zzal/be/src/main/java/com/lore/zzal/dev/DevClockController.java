package com.lore.zzal.dev;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.response.ApiResponse;
import com.lore.zzal.pet.AwakeClock;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
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
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * 개발용 시계 — 이 펫의 "지금" 을 민다.
 *
 * <h3>★★ v1 과 다른 점 — 앵커가 아니라 시계를 민다</h3>
 * v1 은 게이지 앵커를 과거로 밀었다. v2 규칙은 "지금이 KST 23:00 인가" 로 자동 취침을 정하고
 * "19:00~23:00 인가" 로 재우기를 허락한다. 앵커를 밀어서는 그 판정을 만들 수 없다.
 * 그래서 펫마다 오프셋({@code devClockOffsetSeconds})을 두고 <b>모든 계산과 응답의 {@code serverNow} 가 그 시계</b>를 쓴다.
 * 규칙은 한 글자도 안 바뀌고 기다림만 사라진다 — 여기서 확인한 동작이 곧 운영에서 도는 동작이다.
 *
 * <h3>★★ 운영에서는 이 컨트롤러 자체가 없다</h3>
 * {@code app.zzal.dev-tools} 가 true 일 때만 빈으로 올라온다(기본 false). 꺼져 있으면 주소 자체가 없다(404).
 * 주소에 {@code /dev/} 가 들어가는 것은 방어가 아니다. 로그인도 필요하고 남의 펫은 돌봄 API 와 같은 판정으로 막힌다.
 */
@Tag(name = "개발용", description = "시연·확인 전용. 운영에서는 꺼져 있어 존재하지 않는다")
@RestController
@RequestMapping("/api/zzal/v2/dev/pets")
@ConditionalOnProperty(name = "app.zzal.dev-tools", havingValue = "true")
public class DevClockController {

    /** 한 번에 당길 수 있는 상한. 실수로 0 을 하나 더 붙였을 때 몇 년이 밀리면 아무것도 검증하지 못한다. */
    private static final Duration MAX_ADVANCE = Duration.ofDays(30);

    private final PetService petService;

    public DevClockController(PetService petService) {
        this.petService = petService;
    }

    @Operation(summary = "시간 당기기", description = """
            이 펫의 시계를 준 만큼 **앞으로** 민다. 게이지·잠·창·자동 취침이 그 시각 기준으로 정산된다.

            - **규칙은 그대로다.** 값을 짧게 바꾸는 것이 아니라 시계만 밀므로 여기서 확인한 동작이 곧 운영 동작이다
            - 응답의 `serverNow` 도 밀린 시계다
            - 내 펫만(남의 펫은 404) · 운영에서는 이 API 가 존재하지 않는다""")
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
        Instant real = Instant.now();
        ZzalPet pet = petService.advanceClock(userId, petId, by, real);
        return ApiResponse.ok(PetResponses.Detail.from(pet, null, pet.now(real)));
    }

    @Operation(summary = "시계 맞추기", description = """
            이 펫의 시계를 특정 시각으로 맞춘다. `at`(ISO) · `sinceHatchMinutes`(부화 뒤 N분) · `localTime`("19:00", 오늘 KST) 중 하나.
            과거로도 갈 수 있지만 이미 정산된 것은 되돌리지 않는다 — 과거로 맞추면 그 시각까지 아무것도 안 흐른 것이 된다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "맞춤"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "셋 중 하나가 아님 · 형식 오류 · 부화 전(INVALID_INPUT)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND)")})
    @PostMapping("/{petId}/set-clock")
    public ApiResponse<PetResponses.Detail> setClock(@LoginUser Long userId,
                                                     @PathVariable Long petId,
                                                     @Valid @RequestBody DevRequests.SetClock request) {
        if (request.given() != 1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "at · sinceHatchMinutes · localTime 중 하나만 주세요");
        }
        Instant real = Instant.now();
        Instant target;
        if (request.at() != null) {
            target = request.at();
        } else if (request.sinceHatchMinutes() != null) {
            ZzalPet pet = petService.get(userId, petId);
            if (pet.getHatchedAt() == null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "아직 부화하지 않았어요");
            }
            target = pet.getHatchedAt().plus(Duration.ofMinutes(request.sinceHatchMinutes()));
        } else {
            try {
                LocalTime t = LocalTime.parse(request.localTime());
                target = AwakeClock.dateOf(real).atTime(t).atZone(ZzalRules.ZONE).toInstant();
            } catch (DateTimeParseException e) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "localTime 은 HH:mm 형식이에요");
            }
        }
        ZzalPet pet = petService.setClock(userId, petId, target, real);
        return ApiResponse.ok(PetResponses.Detail.from(pet, null, pet.now(real)));
    }
}
