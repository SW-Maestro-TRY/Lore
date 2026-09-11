package com.lore.zzal.agent;

import com.lore.common.response.ApiResponse;
import com.lore.common.s3.S3Service;
import com.lore.common.s3.UploadController;
import com.lore.zzal.admin.AdminService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 맥미니(codex 러너) 전용 문 — <b>사람이 아니라 기계가</b> 부른다.
 *
 * <h3>★ 관리자 API 와 왜 갈랐나</h3>
 * 판정은 사람이 눈으로 보는 일이라 <b>계정</b>이 맞고, 결과 올리기는 새벽에 혼자 도는 일이라
 * <b>만료 없는 열쇠</b>가 맞다. 한 문에 몰면 둘 중 하나가 어긋난다 — 사람 토큰으로 기계를 돌리면
 * 자다가 만료돼 그 밤이 날아가고, 기계 열쇠로 판정 화면을 열면 그 열쇠가 브라우저로 나간다.
 *
 * <h3>★★ 세 겹으로 잠근다</h3>
 * <ol>
 *   <li>{@code @ConditionalOnProperty} — {@code app.zzal.agent.enabled} 가 true 일 때만 빈이 올라온다.
 *       꺼져 있으면 <b>주소 자체가 없다(404)</b>. 주소에 {@code /agent/} 가 들어가는 것은 방어가 아니다</li>
 *   <li>{@link AgentGuard} — 열쇠가 없거나 틀리면 401. 시간이 일정한 비교를 쓴다</li>
 *   <li>열쇠는 <b>사람 하나에 묶인다</b> — 올린 그림도 그 사람의 presign 키여야 한다</li>
 * </ol>
 *
 * <h3>★ 맥미니가 스스로 할 수 있게 presign 도 여기 둔다</h3>
 * 업로드 주소를 받는 길이 사람 로그인뿐이면 기계가 혼자 못 올린다. 그러면 만료 없는 열쇠를 만든 의미가 없다.
 */
@Tag(name = "러너", description = "맥미니 재생성 러너 전용. 운영에서는 꺼져 있어 존재하지 않는다")
@RestController
@RequestMapping("/api/zzal/v1/agent")
@ConditionalOnProperty(name = "app.zzal.agent.enabled", havingValue = "true")
public class AgentController {

    /** 열쇠를 싣는 자리. 표준 인증 헤더를 안 쓰는 이유 — 사람 토큰과 섞이지 않게. */
    public static final String KEY_HEADER = "X-Zzal-Agent-Key";

    private final AgentGuard guard;
    private final AdminService adminService;
    private final S3Service s3Service;

    public AgentController(AgentGuard guard, AdminService adminService, S3Service s3Service) {
        this.guard = guard;
        this.adminService = adminService;
        this.s3Service = s3Service;
    }

    @Operation(summary = "가져갈 일감", description = """
            맥미니가 주기적으로 물어보는 목록. **서버가 맥미니를 부르지 않는다** —
            맥미니가 꺼져 있거나 인터넷이 끊겨도 서버는 아무 일 없이 돌고,
            돌아오면 밀린 것부터 이어서 한다.

            시트·생김새 문단·**지시문 본문**을 함께 실어 보내 러너가 레포도 DB 도 안 봐도 되게 한다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "없으면 빈 목록"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "열쇠가 없거나 틀림(UNAUTHORIZED)")})
    @GetMapping("/jobs")
    public ApiResponse<List<AdminResponses.RegenRequest>> jobs(
            @RequestHeader(value = KEY_HEADER, required = false) String key) {
        return ApiResponse.ok(adminService.regenRequestsForAgent(guard.require(key)));
    }

    @Operation(summary = "업로드 주소 발급", description = """
            맥미니가 그림을 올릴 자리를 받는다. 발급된 키의 주인은 **열쇠에 묶인 사용자**이고,
            결과 등록 때 같은 주인인지 다시 확인한다.""")
    @PostMapping("/uploads/presign")
    public ApiResponse<S3Service.PresignedUpload> presign(
            @RequestHeader(value = KEY_HEADER, required = false) String key,
            @Valid @RequestBody UploadController.PresignRequest request) {
        Long userId = guard.require(key);
        return ApiResponse.ok(s3Service.createUploadUrl(userId, request.domain(), request.contentType()));
    }

    @Operation(summary = "구운 판 올리기", description = """
            한 라운드에 나온 판을 **전부 한 번에** 올린다(맥미니는 3판을 나란히 굽는다).

            한 판씩 올리면 첫 판이 올라오는 순간 검수 대기로 바뀌어, 아직 두 판이 오는 중인데
            사람이 먼저 보게 된다 — "나온 판을 전부 보여 주고 고른다" 가 깨진다.

            등록하면 다시 **검수 대기**다. 다시 구운 것도 사람이 한 번 본다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "등록됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "올바르지 않은 이미지(INVALID_UPLOAD_KEY) · 이미 사용한 이미지(UPLOAD_KEY_ALREADY_USED)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "열쇠가 없거나 틀림(UNAUTHORIZED)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "재생성 요청이 없는 모션(ZZAL_REGEN_NOT_REQUESTED)")})
    @PostMapping("/jobs/{motionId}/candidates")
    public ApiResponse<Void> candidates(
            @RequestHeader(value = KEY_HEADER, required = false) String key,
            @PathVariable Long motionId,
            @Valid @RequestBody AdminRequests.Upload request) {
        adminService.uploadForAgent(guard.require(key), motionId, request.candidates());
        return ApiResponse.ok();
    }
}
