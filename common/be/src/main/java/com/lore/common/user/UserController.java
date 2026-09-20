package com.lore.common.user;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.auth.dto.AuthResponses;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** 계정 API. 전부 로그인이 필요하다. */
@Tag(name = "사용자", description = "내 정보 조회·탈퇴·약관 동의")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "내 계정 정보 조회", description = "화면이 로그인 여부를 확인하는 곳이기도 하다")
    @GetMapping("/me")
    public ApiResponse<AuthResponses.Me> me(@LoginUser Long userId) {
        return ApiResponse.ok(AuthResponses.Me.from(userService.get(userId)));
    }

    @Operation(summary = "회원 탈퇴", description = "표시만 남기고 30일 뒤 실제 삭제. 토큰은 즉시 폐기")
    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(@LoginUser Long userId) {
        userService.withdraw(userId, Instant.now());
        return ApiResponse.ok();
    }

    @Operation(summary = "내 동의 내역 조회")
    @GetMapping("/me/agreements")
    public ApiResponse<List<AgreementView>> agreements(@LoginUser Long userId) {
        return ApiResponse.ok(userService.agreements(userId).stream().map(AgreementView::from).toList());
    }

    @Operation(summary = "약관 동의 기록", description = "약관 개정 시 새 판에 다시 동의받는 자리")
    @PostMapping("/me/agreements")
    public ApiResponse<Void> agree(@LoginUser Long userId, @Valid @RequestBody AgreeRequest request) {
        userService.agree(userId, request.type(), request.version(), request.agreed(), Instant.now());
        return ApiResponse.ok();
    }

    public record AgreeRequest(
            @NotNull AgreementType type,
            @NotBlank String version,
            boolean agreed) {
    }

    public record AgreementView(String type, String version, boolean agreed, Instant agreedAt) {

        public static AgreementView from(UserAgreement a) {
            return new AgreementView(a.getType().name(), a.getVersion(), a.isAgreed(), a.getAgreedAt());
        }
    }
}
