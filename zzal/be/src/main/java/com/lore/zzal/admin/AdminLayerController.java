package com.lore.zzal.admin;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 관리자 — 1층·2층 복구(#696). 잠금은 {@link AdminController} 와 같은 세 겹(스위치·{@link AdminGuard}·noindex).
 *
 * <pre>
 *   GET  /api/zzal/v1/admin/layer2                          목록(2층 실패·대기 + 1층 실패)
 *   POST /api/zzal/v1/admin/pets/{id}/layer2/flag           통과했지만 결함인 2층을 목록에 올림(FAILED·그림 유지)
 *   POST /api/zzal/v1/admin/pets/{id}/layer2/candidates     2층 후보 격자 1~3장 → 게이트+후처리
 *   POST /api/zzal/v1/admin/pets/{id}/layer2/pick           2층 후보 고르기 → 새 판·READY
 *   POST /api/zzal/v1/admin/pets/{id}/layer2/retry          운영 API 로 2층 다시 굽기(돈 듦)
 *   POST /api/zzal/v1/admin/pets/{id}/layer1/candidates     1층 후보 격자 1~3장
 *   POST /api/zzal/v1/admin/pets/{id}/layer1/pick           1층 후보 고르기 → 새 판·2층 PENDING(실패 알이면 살림)
 * </pre>
 */
@Tag(name = "관리자", description = "밤에 구운 움짤 검수. 운영에서는 꺼져 있어 존재하지 않는다")
@RestController
@RequestMapping("/api/zzal/v1/admin")
@ConditionalOnProperty(name = "app.zzal.admin.enabled", havingValue = "true")
public class AdminLayerController {

    private final AdminLayerService service;

    public AdminLayerController(AdminLayerService service) {
        this.service = service;
    }

    /** 후보 격자 키(presign 으로 올린 것). */
    public record CandidatesRequest(@NotEmpty @Size(max = AdminLayerService.MAX_CANDIDATES) List<@NotBlank String> gridKeys) {
    }

    public record PickRequest(@NotBlank String candidateId) {
    }

    public record FlagRequest(String reason) {
    }

    @Operation(summary = "1층·2층 복구 목록", description = """
            - layer=2: 살아 있는 펫 중 2층 `FAILED`(재시도 소진·수동 등록) 또는 30분 넘게 `PENDING`·`RUNNING`
            - layer=1: 부화에 실패한(`FAILED`) 알
            - 시트 키·생김새 문단·보존 격자 키(`rejected/`)·올려 둔 후보를 함께 준다""")
    @GetMapping("/layer2")
    public ApiResponse<List<AdminLayerService.Item>> list(@LoginUser Long userId) {
        return ApiResponse.ok(service.list(userId, Instant.now()));
    }

    @Operation(summary = "2층 수동 등록", description = "통과했지만 결함인 2층을 FAILED 로 둔다. 올라간 그림은 그대로이고 사용자에게는 2층이 '연습 중' 이 된다.")
    @PostMapping("/pets/{petId}/layer2/flag")
    public ApiResponse<AdminLayerService.State> flag(@LoginUser Long userId, @PathVariable Long petId,
                                                     @RequestBody(required = false) FlagRequest body) {
        return ApiResponse.ok(service.flag(userId, petId, body == null ? null : body.reason(), Instant.now()));
    }

    @Operation(summary = "2층 후보 올리기", description = """
            presign(`POST /api/v1/uploads/presign` domain=zzal, image/png)으로 올린 grid2.png 키 1~3장.
            서버가 자동 굽기와 같은 게이트+후처리(지금 1층 앵커 이어받기)를 돌려 후보별 결과를 준다.
            `gate`: PASS · REJECTED(격자 구조) · CRASHED(후처리 예외) · ERROR. PASS 만 고를 수 있다.""")
    @PostMapping("/pets/{petId}/layer2/candidates")
    public ApiResponse<List<AdminLayerService.Candidate>> layer2Candidates(@LoginUser Long userId, @PathVariable Long petId,
                                                                          @Valid @RequestBody CandidatesRequest body) {
        return ApiResponse.ok(service.candidates(userId, petId, 2, body.gridKeys(), Instant.now()));
    }

    @Operation(summary = "2층 후보 고르기", description = "고른 후보로 새 판을 올리고 2층 READY. 나머지 후보 격자는 rejected/ 에 보존.")
    @PostMapping("/pets/{petId}/layer2/pick")
    public ApiResponse<AdminLayerService.Picked> layer2Pick(@LoginUser Long userId, @PathVariable Long petId,
                                                           @Valid @RequestBody PickRequest body) {
        return ApiResponse.ok(service.pick(userId, petId, 2, body.candidateId(), Instant.now()));
    }

    @Operation(summary = "2층 다시 굽기(운영 API)", description = "시도 수 0부터 다시. 이미지 API 비용이 든다 — 기본은 후보 올리기.")
    @PostMapping("/pets/{petId}/layer2/retry")
    public ApiResponse<AdminLayerService.State> layer2Retry(@LoginUser Long userId, @PathVariable Long petId) {
        return ApiResponse.ok(service.retry(userId, petId, Instant.now()));
    }

    @Operation(summary = "1층 후보 올리기", description = "grid.png 키 1~3장. 살아 있거나 부화에 실패한 펫.")
    @PostMapping("/pets/{petId}/layer1/candidates")
    public ApiResponse<List<AdminLayerService.Candidate>> layer1Candidates(@LoginUser Long userId, @PathVariable Long petId,
                                                                          @Valid @RequestBody CandidatesRequest body) {
        return ApiResponse.ok(service.candidates(userId, petId, 1, body.gridKeys(), Instant.now()));
    }

    @Operation(summary = "1층 후보 고르기", description = """
            고른 후보로 새 판(1층 8종+앵커)을 올린다. 1층 앵커가 바뀌므로 2층은 PENDING 으로 돌아가 다시 잘린다
            (2층 격자가 있으면 자르기만 — 돈 안 듦). 부화에 실패한 알이면 이 자리에서 살린다(주인 자리 확인).""")
    @PostMapping("/pets/{petId}/layer1/pick")
    public ApiResponse<AdminLayerService.Picked> layer1Pick(@LoginUser Long userId, @PathVariable Long petId,
                                                           @Valid @RequestBody PickRequest body) {
        return ApiResponse.ok(service.pick(userId, petId, 1, body.candidateId(), Instant.now()));
    }
}
