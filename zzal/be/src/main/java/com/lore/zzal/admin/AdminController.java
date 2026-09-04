package com.lore.zzal.admin;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import com.lore.zzal.admin.dto.AdminRequests;
import com.lore.zzal.admin.dto.AdminResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 관리자 검수 API — 구워진 움짤을 상훈님이 보고 "좋음 / 다시 굽기" 를 남긴다.
 *
 * <h3>★★ 세 겹으로 잠근다. 하나로는 부족하다</h3>
 * <ol>
 *   <li><b>{@code @ConditionalOnProperty}</b> — {@code app.zzal.admin.enabled} 가 true 일 때만
 *       빈으로 올라온다(기본 false). 꺼져 있으면 매핑이 아예 등록되지 않아 <b>주소 자체가 없다(404)</b>.
 *       {@code DevClockController} 와 같은 방식이고, 그 주석이 정확하다 —
 *       <b>주소에 {@code /admin/} 이 들어가는 것은 방어가 아니다.</b> 그건 이름일 뿐이고,
 *       코드가 올라와 있는 한 언젠가 누군가 그 주소를 부른다.</li>
 *   <li><b>{@link AdminGuard#require(Long)}</b> — 관리자 계정이 아니면 {@code ADMIN_ONLY}(403).
 *       스위치가 켜진 환경에서 로그인한 아무나가 남의 움짤을 보는 것을 막는 유일한 층이다.
 *       (로그인 자체는 {@code WebSecurityConfig} 의 {@code anyRequest().authenticated()} 가 요구한다)</li>
 *   <li><b>화면에 noindex</b> — {@code apps/web/app/(domains)/zzal/admin/page.tsx} 의
 *       {@code metadata.robots}. 관리자 화면이 발견되는 <b>실제로 가장 흔한 경로가 검색</b>이다.
 *       앞의 두 겹은 서버를 지키고, 이 한 겹은 "존재를 알게 되는 것" 을 지킨다.</li>
 * </ol>
 *
 * <h3>★ 여기서 누른 판정은 사용자 화면을 바꾸지 않는다</h3>
 * 모션은 상훈님 확인 전에 이미 열려 있다(2026-09-03 확정). {@link AdminService} 주석 참고.
 * 판정은 기록으로만 쌓인다.
 */
@Tag(name = "관리자", description = "구워진 움짤 검수. 운영에서는 꺼져 있어 존재하지 않는다")
@RestController
@RequestMapping("/api/zzal/v1/admin/motions")
@ConditionalOnProperty(name = "app.zzal.admin.enabled", havingValue = "true")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @Operation(summary = "검수 대기 목록", description = """
            아직 판정하지 않은 움짤들을 오래된 순으로 준다.

            - **검수에 필요한 것만 담는다** — 펫 이름·주인 정보는 없다. 이 화면은 남의 데이터를 보므로
              "잘 구워졌나" 를 판단하는 데 안 쓰이는 칸은 아예 내려보내지 않는다
            - 아직 그림이 없는 것(굽는 중·실패)은 목록에 없다. 볼 것이 없으면 판정할 수도 없다
            - 관리자가 아니면 403(ADMIN_ONLY)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "관리자가 아님(ADMIN_ONLY)")})
    @GetMapping
    public ApiResponse<List<AdminResponses.Pending>> pending(@LoginUser Long userId) {
        return ApiResponse.ok(adminService.pending(userId));
    }

    @Operation(summary = "판정 남기기", description = """
            그 움짤에 대한 판정을 기록한다. 기계 게이트의 판정은 지우지 않고 나란히 남는다.

            ⚠️ **판정만 기록된다. 사용자 화면은 바뀌지 않는다.**
            모션은 확인 전에 이미 열려 있어서(밤에 잠든 사용자가 아침에 갇히지 않도록),
            REGENERATE 를 눌러도 사용자는 계속 그 움짤을 본다. 다시 굽기는 별도 작업이다.

            - 관리자가 아니면 403(ADMIN_ONLY)
            - 없는 모션이면 404(NOT_FOUND)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "기록됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "관리자가 아님(ADMIN_ONLY)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 모션(NOT_FOUND)")})
    @PostMapping("/{motionId}/verdict")
    public ApiResponse<Void> verdict(@LoginUser Long userId,
                                     @PathVariable Long motionId,
                                     @Valid @RequestBody AdminRequests.Verdict request) {
        adminService.review(userId, motionId, request.verdict(), request.note());
        return ApiResponse.ok();
    }
}
