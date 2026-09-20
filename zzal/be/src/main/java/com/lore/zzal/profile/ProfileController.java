package com.lore.zzal.profile;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import com.lore.zzal.profile.dto.ProfileRequests;
import com.lore.zzal.profile.dto.ProfileResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 부화를 기다리는 동안 받는 6문항.
 *
 * <h3>★ 왜 PATCH 인가</h3>
 * 여울이 한 문항씩 묻는다. POST 로 전체를 받으면 <b>여섯 번째에서 나간 사람의 답 다섯 개가
 * 통째로 사라진다.</b> PATCH 는 보낸 칸만 덮어쓰므로 답한 데까지 남는다.
 *
 * <h3>구현이 복잡해지면 화면을 옮겨도 된다</h3>
 * 서버는 "답 묶음 하나 받기" 하나뿐이라 이 질문들이 어느 화면에 있는지 모른다(정본 15장).
 */
@Tag(name = "사용자 정보", description = "부화 대기 중 수집하는 6문항. 호칭·방문 시간대·관계·그림 여부·연령대·유입 경로")
@RestController
@RequestMapping("/api/zzal/v1/me/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @Operation(summary = "사용자 정보 조회", description = """
            현재까지 저장된 응답을 반환한다. 아직 응답하지 않은 항목은 null 이다.

            응답 이력이 없으면 빈 값으로 구성한 결과를 반환한다.""")
    @GetMapping
    public ApiResponse<ProfileResponses.Profile> get(@LoginUser Long userId) {
        return ApiResponse.ok(ProfileResponses.Profile.of(profileService.get(userId, Instant.now())));
    }

    @Operation(summary = "사용자 정보 등록", description = """
            6문항 중 전달한 항목만 저장한다. 한 항목씩 보내도 되고 한 번에 보내도 된다.

            PATCH 를 사용하는 이유는 부분 저장을 보장하기 위해서다. 문항을 순차로 제시하는
            구조이므로 중도 이탈이 발생하며, 전체 저장 방식이면 그때까지의 응답이 모두 사라진다.

            전달하지 않은 항목은 기존 값을 유지한다. null 은 삭제 요청이 아니라 미전달로 처리한다.""")
    @PatchMapping
    public ApiResponse<ProfileResponses.Profile> patch(@LoginUser Long userId,
                                                       @Valid @RequestBody ProfileRequests.Patch request) {
        ZzalUserProfile saved = profileService.patch(userId, request.callMe(), request.visitTime(),
                request.relation(), request.draws(), request.ageBand(), request.cameFrom(), Instant.now());
        return ApiResponse.ok(ProfileResponses.Profile.of(saved));
    }
}
