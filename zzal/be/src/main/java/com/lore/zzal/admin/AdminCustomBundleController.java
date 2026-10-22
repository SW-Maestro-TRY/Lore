package com.lore.zzal.admin;
import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
/** Read-only review; does not approve, retry, reset, generate or publish a bundle. */
@RestController @RequestMapping("/api/zzal/v1/admin/custom-bundles")
@ConditionalOnProperty(name="app.zzal.admin.enabled",havingValue="true")
public class AdminCustomBundleController {
 private final AdminCustomBundleService service;
 public AdminCustomBundleController(AdminCustomBundleService service){this.service=service;}
 @Operation(summary="맞춤8 검수 목록",description="관리자 전용. failed는후처리·파일검증 실패, all은대기/진행/정상생성도포함. 조회는상태나지급을바꾸지않는다.")
 @GetMapping public ApiResponse<AdminCustomBundleService.Page> list(@LoginUser Long userId,@RequestParam(defaultValue="failed")String status,@RequestParam(required=false)Long beforeId,@RequestParam(defaultValue="20")int limit){return ApiResponse.ok(service.list(userId,status,beforeId,limit));}
 @Operation(summary="맞춤8 기본·맞춤 A/B 이어보기",description="관리자 전용 읽기. 실제PNG32와원본출처만반환하며검수자료없음은UNAVAILABLE로표시한다.")
 @GetMapping("/{jobId}/sequence") public ApiResponse<com.lore.zzal.attendance.CustomReviewSequence.Sequence> sequence(@LoginUser Long userId,@PathVariable long jobId){return ApiResponse.ok(service.sequence(userId,jobId));}
}
