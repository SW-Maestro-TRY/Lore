package com.lore.zzal.returning;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/api/zzal/v1/me/pets/{petId}/returning-guide")
public class ReturningGuideController {
    private final ReturningGuideService service;
    public ReturningGuideController(ReturningGuideService service) { this.service = service; }
    @Schema(name="ZzalReturningGuideState")
    public record State(String version, boolean eligible, int step, boolean completed, Long boundPetId) { }
    @Schema(name="ZzalReturningGuideAdvance")
    public record Advance(@NotNull @Min(0) @Max(5) Integer step, Boolean complete,
                          @Size(max=64) String expectedPersonaHash) { }
    @Operation(operationId="getReturningGuide", summary="기존 사용자 업데이트 안내 진행 조회", description="계정·안내 버전 단위로 완료 여부를 조회한다. 기존 튜토리얼을 마친 구버전 캐릭터만 대상이며 신규 v4는 제외한다. 조회는 상태를 만들지 않는다.")
    @GetMapping
    public ApiResponse<State> get(@LoginUser Long owner, @PathVariable Long petId) {
        return ApiResponse.ok(service.get(owner, petId));
    }
    @Operation(operationId="advanceReturningGuide", summary="기존 사용자 업데이트 안내 진행 저장", description="0~5단계 진행을 단조 증가로 저장한다. 완료는 5단계와 최신 personaSourceHash 확인이 필요하다. 설정은 변경 없이 확인해도 되며 첫 안내·출석·후기 상태는 수정하지 않는다. 다른 기기에서도 계정당 한 번이다.")
    @PostMapping
    public ApiResponse<State> advance(@LoginUser Long owner, @PathVariable Long petId,
                                     @Valid @RequestBody Advance request) {
        return ApiResponse.ok(service.advance(owner, petId, request, Instant.now()));
    }
}
