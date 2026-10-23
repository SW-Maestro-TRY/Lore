package com.lore.zzal.inquiry;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@Tag(name="문의", description="로그인 사용자의 비공개 텍스트 문의 접수")
@RestController
@RequestMapping("/api/zzal/v1/me/inquiries")
public class InquiryController {
    private final InquiryService service;
    public InquiryController(InquiryService service) { this.service = service; }

    @Schema(name="ZzalInquiryReceipt")
    public record Receipt(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long id,
                          @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant createdAt) { }

    @Operation(operationId="submitInquiry", summary="문의 접수", description="본문은 앞뒤 공백을 제거한 1~4000자이며 locale은 ko/en/ja다. 소유자는 로그인 계정으로 확정하고 petId는 선택적으로 소유권을 검증한다. 같은 requestId와 같은 정규화 내용의 재전송은 최초 접수를 반환한다. 자동 메일 발송이나 문의 목록 조회는 제공하지 않는다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="DB에 저장된 접수 번호와 최초 접수 시각"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400", description="본문/언어/UUID/petId 입력 오류"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="로그인 필요"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404", description="없는 펫 또는 남의 펫"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409", description="ZZAL_INQUIRY_CONFLICT: 같은 requestId에 다른 내용")
    })
    @PostMapping
    public ApiResponse<Receipt> submit(@LoginUser Long owner, @Valid @RequestBody InquiryRequests.Submit request) {
        return ApiResponse.ok(service.submit(owner, request, Instant.now()));
    }
}
