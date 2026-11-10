package com.lore.zzal.admin;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import com.lore.zzal.inquiry.InquiryRepository;
import com.lore.zzal.inquiry.InquiryRequests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * 관리자 — 문의·후기.
 *
 * <pre>
 *   GET  /api/zzal/v1/admin/inquiries?days=30        목록(미답 먼저, 최신순)
 *   GET  /api/zzal/v1/admin/inquiries/{id}           상세(사슬 전체 + 같은 사용자의 후기)
 *   POST /api/zzal/v1/admin/inquiries/{id}/reply     답장 {text ≤2000자}. 이미 있으면 409
 *   GET  /api/zzal/v1/admin/feedback?days=30         후기 목록(최신순)
 * </pre>
 *
 * ★ 봇 토큰({@code X-Admin-Token})이면 403 — {@link AdminInquiryService}.
 */
@Tag(name = "관리자", description = "밤에 구운 움짤 검수. 운영에서는 꺼져 있어 존재하지 않는다")
@RestController
@RequestMapping("/api/zzal/v1/admin")
@ConditionalOnProperty(name = "app.zzal.admin.enabled", havingValue = "true")
public class AdminInquiryController {

    private final AdminInquiryService service;

    public AdminInquiryController(AdminInquiryService service) {
        this.service = service;
    }

    @Operation(summary = "문의 목록", description = "최근 days 일(기본 30, 최대 365). 미답이 위, 그 안에서 최신순. 봇 토큰 불가")
    @GetMapping("/inquiries")
    public ApiResponse<List<InquiryRepository.AdminRow>> list(@LoginUser Long userId, @RequestParam(required = false) Integer days) {
        return ApiResponse.ok(service.list(userId, days, Instant.now()));
    }

    @Operation(summary = "문의 상세", description = "더 물어보기 사슬 전체와 같은 사용자의 후기. 봇 토큰 불가")
    @GetMapping("/inquiries/{id}")
    public ApiResponse<AdminInquiryService.Detail> detail(@LoginUser Long userId, @PathVariable long id) {
        return ApiResponse.ok(service.detail(userId, id));
    }

    @Operation(summary = "문의 답장", description = "1~2000자. 문의 한 건에 한 번 — 이미 답장이 있으면 409(ZZAL_INQUIRY_ALREADY_REPLIED). 사용자 메뉴에 점이 찍힌다. 봇 토큰 불가")
    @PostMapping("/inquiries/{id}/reply")
    public ApiResponse<AdminInquiryService.Detail> reply(@LoginUser Long userId, @PathVariable long id,
                                                         @Valid @RequestBody InquiryRequests.Reply body) {
        return ApiResponse.ok(service.reply(userId, id, body.text(), Instant.now()));
    }

    @Operation(summary = "후기 목록", description = "최근 days 일(기본 30, 최대 365), 최신순. 봇 토큰 불가")
    @GetMapping("/feedback")
    public ApiResponse<List<InquiryRepository.Feedback>> feedback(@LoginUser Long userId, @RequestParam(required = false) Integer days) {
        return ApiResponse.ok(service.feedback(userId, days, Instant.now()));
    }
}
