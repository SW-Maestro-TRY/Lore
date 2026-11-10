package com.lore.zzal.dev;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 개발 리모컨 — 굽기 지정과 진행 조회. 돈이 나가는 규칙은 {@link DevBakeService} 머리말.
 */
@Tag(name = "개발용", description = "시연·확인 전용. 운영에서는 꺼져 있어 존재하지 않는다")
@RestController
@RequestMapping("/api/zzal/v1/dev/pets")
@ConditionalOnProperty(name = "app.zzal.dev-tools", havingValue = "true")
public class DevBakeController {

    private final DevBakeService bakeService;

    public DevBakeController(DevBakeService bakeService) {
        this.bakeService = bakeService;
    }

    /** 굽기 요청. */
    @Schema(description = "굽기 요청")
    public record DevBakeRequest(
            @Schema(description = "hatch · layer2 · gift", example = "gift") String kind,
            @Schema(description = "진짜로 굽는 줄 아는가 — 서버 스위치(ZZAL_GENERATION_REAL)와 같아야 한다", example = "false") boolean real,
            @Schema(description = "gift 일 때 동작 seq(없으면 첫 선물)", example = "101") Integer seq) {
    }

    @Operation(summary = "굽기 시작", description = """
            부화(다시)·2층(처음부터)·선물/심화 한 판을 운영과 같은 길로 굽는다. real 이 서버 스위치와 다르면 409(과금 스위치 꺼짐 등).
            응답에 예상 비용(부화 $0.19 · 2층/동작 격자 $0.09, 가짜면 0)과 진행 조회 열쇠(jobId).""")
    @PostMapping("/{petId}/bake")
    public ApiResponse<DevResponses.DevBake> devBake(@LoginUser Long userId, @PathVariable Long petId,
                                               @RequestBody DevBakeRequest request) {
        return ApiResponse.ok(bakeService.bake(userId, petId, request.kind(), request.real(), request.seq(), Instant.now()));
    }

    @Operation(summary = "굽기 진행", description = "bake 응답의 jobId 로 지금 상태·단계·기록된 비용을 본다.")
    @GetMapping("/{petId}/bake/{jobId}")
    public ApiResponse<DevResponses.DevBakeProgress> devBakeProgress(@LoginUser Long userId, @PathVariable Long petId,
                                                           @PathVariable String jobId) {
        return ApiResponse.ok(bakeService.progress(userId, petId, jobId));
    }
}
