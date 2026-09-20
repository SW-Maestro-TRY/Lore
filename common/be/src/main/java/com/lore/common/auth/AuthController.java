package com.lore.common.auth;

import com.lore.common.auth.jwt.AuthCookies;
import com.lore.common.auth.jwt.JwtProvider;
import com.lore.common.auth.dto.AuthRequests;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 회원가입 / 로그인 API. 계정은 세 도메인이 공유하므로 common 에 둔다.
 *
 * 토큰은 전부 쿠키로만 오간다. 응답 본문에 담지 않는다 —
 * 본문에 실으면 자바스크립트가 읽을 수 있게 되어 HttpOnly 로 막은 의미가 사라진다.
 */
@Tag(name = "인증", description = "회원가입·로그인·토큰 갱신")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** 지금 시행 중인 약관 판. 문서를 고치면 이 값을 올리고 재동의를 받는다. */
    private static final String CURRENT_TERMS_VERSION = "2026-09-01";

    private final AuthService authService;
    private final AuthCookies cookies;
    private final JwtProvider jwtProvider;

    public AuthController(AuthService authService, AuthCookies cookies, JwtProvider jwtProvider) {
        this.authService = authService;
        this.cookies = cookies;
        this.jwtProvider = jwtProvider;
    }

    @Operation(summary = "회원가입", description = """
            이메일·비밀번호로 가입한다. **토큰을 발급하지 않으므로 로그인 상태가 되지 않는다.**
            화면은 가입 뒤 로그인 화면으로 보낸다.

            방금 정한 비밀번호를 한 번 더 입력하게 하는 셈이지만, 그 자리에서 비밀번호가
            맞는지 확인된다. 오타를 낸 채 가입한 사용자가 다음 접속에서야 들어오지 못하는
            상황을 막는다.

            필수 동의는 AGE_14 · TERMS · PRIVACY 세 가지다. MARKETING 은 선택이며
            false 도 기록으로 남긴다 — 묻지 않은 것과 거부한 것은 다른 사실이다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "가입 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "입력값 오류(INVALID_INPUT) · 필수 약관 미동의(REQUIRED_AGREEMENT_MISSING)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "이미 가입된 이메일(EMAIL_ALREADY_EXISTS)")})
    @PostMapping("/signup")
    public ApiResponse<Void> signUp(@Valid @RequestBody AuthRequests.SignUp request) {
        authService.signUp(request.email(), request.password(), request.agreements(),
                CURRENT_TERMS_VERSION, Instant.now());
        return ApiResponse.ok();
    }

    @Operation(summary = "로그인", description = """
            성공하면 토큰 2종이 쿠키로 발급된다.

            실패 사유를 나누지 않는다 — "그런 이메일 없음"과 "비밀번호 틀림"을 구분해 주면
            어떤 이메일이 가입돼 있는지 확인하는 수단이 되기 때문이다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "이메일 또는 비밀번호 불일치(LOGIN_FAILED)")})
    @PostMapping("/login")
    public ApiResponse<Void> login(@Valid @RequestBody AuthRequests.Login request,
                                   @RequestHeader(value = "User-Agent", required = false) String userAgent,
                                   HttpServletResponse response) {
        AuthService.Tokens tokens = authService.login(
                request.email(), request.password(), userAgent, Instant.now());
        writeCookies(response, tokens);
        return ApiResponse.ok();
    }

    @Operation(summary = "토큰 갱신", description = """
            access 가 만료됐을 때 부른다. refresh 쿠키를 자동으로 실어 보내므로 본문은 없다.

            **refresh 도 새 것으로 교체된다(회전).** 옛 refresh 는 즉시 폐기되므로,
            탈취당해도 한 번만 쓰인다. 이미 폐기된 refresh 가 다시 들어오면 탈취로 보고
            그 사용자의 모든 토큰을 폐기한다(= 전 기기 강제 로그아웃).""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "갱신 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "만료·폐기·위조된 refresh(INVALID_REFRESH_TOKEN) → 다시 로그인 필요")})
    @PostMapping("/refresh")
    public ApiResponse<Void> refresh(@CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken,
                                     @RequestHeader(value = "User-Agent", required = false) String userAgent,
                                     HttpServletResponse response) {
        AuthService.Tokens tokens = authService.refresh(refreshToken, userAgent, Instant.now());
        writeCookies(response, tokens);
        return ApiResponse.ok();
    }

    @Operation(summary = "로그아웃", description = """
            이 기기의 refresh 만 폐기하고 쿠키를 지운다(다른 기기는 유지).

            access 는 취소할 수 없어 최대 30분 남아 있지만, 새 access 를 받을 수 없으므로
            그 뒤로는 완전히 끊긴다. JWT 방식의 구조적 특성이다.""")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken,
                                    HttpServletResponse response) {
        authService.logout(refreshToken, Instant.now());
        cookies.clear(response);
        return ApiResponse.ok();
    }

    private void writeCookies(HttpServletResponse response, AuthService.Tokens tokens) {
        cookies.write(response, tokens.accessToken(), tokens.refreshToken(),
                jwtProvider.accessExpiry(), jwtProvider.refreshExpiry());
    }
}
