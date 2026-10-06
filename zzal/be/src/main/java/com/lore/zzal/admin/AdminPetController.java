package com.lore.zzal.admin;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 관리자 — 펫 단위 조작(2026-10-07 신설: 부화 실패 재굽기).
 *
 * ★ 잠금은 {@link AdminController} 와 <b>같은 세 겹</b>을 그대로 쓴다 — 스위치({@code app.zzal.admin.enabled},
 *   꺼져 있으면 주소 자체가 없다) · {@link AdminGuard}(관리자 계정이 아니면 403) · 화면 noindex.
 *   새 인증을 만들지 않는다.
 */
@Tag(name = "관리자", description = "밤에 구운 움짤 검수. 운영에서는 꺼져 있어 존재하지 않는다")
@RestController
@RequestMapping("/api/zzal/v1/admin/pets")
@ConditionalOnProperty(name = "app.zzal.admin.enabled", havingValue = "true")
public class AdminPetController {

    private final AdminRehatchService rehatchService;

    public AdminPetController(AdminRehatchService rehatchService) {
        this.rehatchService = rehatchService;
    }

    @Operation(summary = "부화 실패 재굽기", description = """
            부화에 실패한(`FAILED`) 알을 **새 시도로** 다시 굽는다.

            - 격자 두 장만 버리고 시트·생김새 문단은 이어받는다
            - 이름을 지은 알은 `HATCHING`, 이름이 없던 알은 `DRAFT` 로 돌아간다
            - 재시도 상한(`app.zzal.max-hatch-attempts`)이 새로 적용된다
            - 실패한 알이 아니면 409(ZZAL_PET_ALREADY_HATCHING), 주인 자리가 차 있으면 409(ZZAL_PET_LIMIT_REACHED)
            - 관리자가 아니면 403(ADMIN_ONLY)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "굽기 시작"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "관리자가 아님(ADMIN_ONLY)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫(ZZAL_PET_NOT_FOUND)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "실패한 알이 아님(ZZAL_PET_ALREADY_HATCHING) · 주인 자리가 참(ZZAL_PET_LIMIT_REACHED)")})
    @PostMapping("/{petId}/rehatch")
    public ApiResponse<AdminRehatchService.Rehatch> rehatch(@LoginUser Long userId, @PathVariable Long petId) {
        return ApiResponse.ok(rehatchService.rehatch(userId, petId, Instant.now()));
    }
}
