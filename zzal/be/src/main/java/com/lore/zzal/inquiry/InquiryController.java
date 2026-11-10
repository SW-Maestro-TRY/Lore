package com.lore.zzal.inquiry;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;

@Tag(name="문의", description="로그인 사용자의 비공개 텍스트 문의 접수·내 문의와 운영팀 답장 보기")
@RestController
@RequestMapping("/api/zzal/v1/me/inquiries")
public class InquiryController {
    private final InquiryService service;
    public InquiryController(InquiryService service) { this.service = service; }

    @Schema(name="ZzalInquiryReceipt")
    public record Receipt(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long id,
                          @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant createdAt) { }

    @Schema(name="ZzalInquiryReply", description="운영팀 답장. 화면은 캐릭터가 아니라 「로어 운영팀」 시스템 문구로 그린다")
    public record Reply(String text, Instant repliedAt) { }

    @Schema(name="ZzalInquiryItem")
    public record Item(long id, @Schema(nullable=true) Long petId, String body, Instant createdAt,
                       @Schema(nullable=true) Reply reply, @Schema(nullable=true) Long parentId,
                       @Schema(description="답장이 있고 아직 안 봤으면 true — 시트를 열면 seen 으로 지운다") boolean replyUnseen) {
        static Item of(InquiryRepository.Row r) {
            return new Item(r.id(), r.petId(), r.body(), r.createdAt(),
                    r.replyText() == null ? null : new Reply(r.replyText(), r.repliedAt()), r.parentId(),
                    r.replyText() != null && r.replySeenAt() == null);
        }
    }

    @Operation(operationId="submitInquiry", summary="문의 접수", description="본문은 앞뒤 공백을 제거한 1~4000자이며 locale은 ko/en/ja다. 소유자는 로그인 계정으로 확정하고 petId는 선택적으로 소유권을 검증한다. parentId(선택)는 더 물어보기 — 본인 문의 번호만 받는다(아니면 404). 같은 requestId와 같은 정규화 내용의 재전송은 최초 접수를 반환한다. 자동 메일 발송은 하지 않는다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="DB에 저장된 접수 번호와 최초 접수 시각"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400", description="본문/언어/UUID/petId 입력 오류"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="로그인 필요"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404", description="없는 펫·남의 펫, 또는 없는·남의 앞 문의(parentId)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409", description="ZZAL_INQUIRY_CONFLICT: 같은 requestId에 다른 내용")
    })
    @PostMapping
    public ApiResponse<Receipt> submit(@LoginUser Long owner, @Valid @RequestBody InquiryRequests.Submit request) {
        return ApiResponse.ok(service.submit(owner, request, Instant.now()));
    }

    @Operation(operationId="listMyInquiries", summary="내 문의와 답장", description="로그인 계정의 문의를 오래된 것부터(최근 200건). 답장이 없으면 reply 는 null. 남의 문의는 나오지 않는다.")
    @GetMapping
    public ApiResponse<List<Item>> list(@LoginUser Long owner) {
        return ApiResponse.ok(service.listMine(owner));
    }

    @Operation(operationId="inquiryReplySeen", summary="답장 봤음", description="문의 화면에서 이 문의의 답장을 보여 줬다고 기록한다. 본문 없는 204, 멱등. 답장이 아직 없으면 아무것도 안 바뀐다. 상태 조회의 unreadInquiryReplyCount 가 줄어든다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="204", description="기록됨(본문 없음)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="로그인 필요"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404", description="없는 문의 또는 남의 문의(ZZAL_INQUIRY_NOT_FOUND)")
    })
    @PostMapping("/{id}/seen")
    public ResponseEntity<Void> seen(@LoginUser Long owner, @PathVariable long id) {
        service.markSeen(owner, id, Instant.now());
        return ResponseEntity.noContent().build();
    }
}
