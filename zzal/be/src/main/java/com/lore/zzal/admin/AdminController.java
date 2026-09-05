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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 관리자 검수 API v2 (api-v2.md 5절) — 밤에 구운 움짤을 <b>공개 전에</b> 보고 판정한다.
 *
 * <h3>★★ 세 겹으로 잠근다. 하나로는 부족하다</h3>
 * <ol>
 *   <li><b>{@code @ConditionalOnProperty}</b> — {@code app.zzal.admin.enabled} 가 true 일 때만
 *       빈으로 올라온다(기본 false). 꺼져 있으면 매핑이 아예 등록되지 않아 <b>주소 자체가 없다(404)</b>.
 *       <b>주소에 {@code /admin/} 이 들어가는 것은 방어가 아니다.</b> 그건 이름일 뿐이고,
 *       코드가 올라와 있는 한 언젠가 누군가 그 주소를 부른다.</li>
 *   <li><b>{@link AdminGuard#require(Long)}</b> — 관리자 계정이 아니면 {@code ADMIN_ONLY}(403).
 *       스위치가 켜진 환경에서 로그인한 아무나가 남의 움짤을 보는 것을 막는 유일한 층이다.</li>
 *   <li><b>화면에 noindex</b> — 관리자 화면이 발견되는 <b>실제로 가장 흔한 경로가 검색</b>이다.</li>
 * </ol>
 *
 * <h3>★ 여기서 누른 판정이 공개를 정한다(v1 과 반대)</h3>
 * v1 은 굽자마자 열고 판정은 기록만 했다. 이제 {@code OK} 를 눌러야 {@code OPEN} 이 되고,
 * 실제 화면 도착은 그 뒤 <b>펫이 깨어 있는 첫 정산</b>이다(정본 2장).
 */
@Tag(name = "관리자", description = "밤에 구운 움짤 검수. 운영에서는 꺼져 있어 존재하지 않는다")
@RestController
@RequestMapping("/api/zzal/v2/admin/motions")
@ConditionalOnProperty(name = "app.zzal.admin.enabled", havingValue = "true")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @Operation(summary = "검수 대기 목록", description = """
            다 구워져 **검수를 기다리는** 움짤들을 오래된 순으로 준다(`REVIEW`).

            - **검수에 필요한 것만 담는다** — 펫 이름·주인 정보는 없다
            - 굽는 중·실패·이미 공개된 것은 목록에 없다
            - 관리자가 아니면 403(ADMIN_ONLY)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "관리자가 아님(ADMIN_ONLY)")})
    @GetMapping("/pending")
    public ApiResponse<List<AdminResponses.Pending>> pending(@LoginUser Long userId) {
        return ApiResponse.ok(adminService.pending(userId));
    }

    @Operation(summary = "판정 남기기", description = """
            그 움짤을 **공개할지 다시 만들지** 정한다. 기계 게이트의 판정은 지우지 않고 나란히 남는다.

            - `OK` → 공개(`OPEN`). 화면 도착은 그 펫이 **깨어 있는 첫 조회**다(정본 2장)
            - `REGENERATE` → 재생성 한도가 남았으면 맥미니로(`LOCAL_REQUESTED`), 다 썼으면 그 밤은 실패(`FAILED` — 다음 밤에 다시)
            - 이미 사용자에게 도착한 것은 되돌리지 않는다(도감에서 칸이 사라지면 "배운 게 없어졌다" 가 된다)""")
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

    @Operation(summary = "재생성 주문 목록(맥미니 폴링)", description = """
            맥미니(codex) 러너가 주기적으로 가져가는 목록. 시트·생김새 문단·**지시문 본문**을 함께 실어 보내
            러너가 레포도 DB 도 보지 않아도 되게 한다.

            - 다 만들면 presign 으로 올리고 `POST /{motionId}/upload` 로 등록한다
            - 관리자가 아니면 403(ADMIN_ONLY)""")
    @GetMapping("/regen-requests")
    public ApiResponse<List<AdminResponses.RegenRequest>> regenRequests(@LoginUser Long userId) {
        return ApiResponse.ok(adminService.regenRequests(userId));
    }

    @Operation(summary = "재생성 결과 등록(맥미니)", description = """
            맥미니가 다시 만든 그림을 등록한다 → 다시 **검수 대기**(바로 열지 않는다).

            - `imageKey` 는 presign 으로 받은 **내 것이고 아직 안 쓴 키**여야 한다
            - 재생성을 요청한 자리가 아니면 409(ZZAL_REGEN_NOT_REQUESTED)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "등록됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "올바르지 않은 이미지(INVALID_UPLOAD_KEY) · 이미 사용한 이미지(UPLOAD_KEY_ALREADY_USED)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "재생성 요청이 없는 모션(ZZAL_REGEN_NOT_REQUESTED)")})
    @PostMapping("/{motionId}/upload")
    public ApiResponse<Void> upload(@LoginUser Long userId,
                                    @PathVariable Long motionId,
                                    @Valid @RequestBody AdminRequests.Upload request) {
        adminService.upload(userId, motionId, request.imageKey());
        return ApiResponse.ok();
    }

    @Operation(summary = "그 밤 현황", description = """
            그 밤(`date`, KST)의 큐가 어떻게 됐나 — 모션 행을 직접 세어 만든다.
            밤 기록(`zzal_night_run`)의 숫자는 "집어서 넘긴 수" 라 실제 결과와 다르다.""")
    @GetMapping("/night/summary")
    public ApiResponse<AdminResponses.NightSummary> nightSummary(
            @LoginUser Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(adminService.nightSummary(userId, date));
    }
}
