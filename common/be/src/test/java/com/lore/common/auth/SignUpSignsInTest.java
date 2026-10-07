package com.lore.common.auth;

import com.lore.common.auth.dto.AuthRequests;
import com.lore.common.auth.jwt.AuthCookies;
import com.lore.common.auth.jwt.JwtProvider;
import com.lore.common.user.AgreementType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 회원가입은 바로 로그인시킨다(2026-10-08 #690 — 이전 규칙 "가입은 로그인시키지 않는다"를 뒤집음).
 *
 * <h3>★ 왜 바꿨나</h3>
 * 가입 뒤 로그인 화면으로 보내 비밀번호를 한 번 더 치게 했더니, 운영(10/6~7)에서 그 단계가
 * 그대로 이탈·실패로 남았다. 오타는 화면의 비밀번호 확인 칸·보기 토글이 먼저 거른다.
 *
 * <h3>★ 지키는 것</h3>
 * 로그인과 <b>같은 발급 경로</b>를 쓴다 — 쿠키 두 개(access·refresh), refresh 는 기기(User-Agent)별 저장.
 */
@DisplayName("회원가입 — 바로 로그인시킨다")
class SignUpSignsInTest {

    @Test
    @DisplayName("★ 가입 응답에 Set-Cookie 2개(access·refresh)가 실린다")
    void signUpWritesBothCookies() {
        AuthService service = mock(AuthService.class);
        JwtProvider jwt = mock(JwtProvider.class);
        when(jwt.accessExpiry()).thenReturn(Duration.ofMinutes(30));
        when(jwt.refreshExpiry()).thenReturn(Duration.ofDays(14));
        when(service.signUp(anyString(), anyString(), anyMap(), anyString(), anyString(), any(Instant.class)))
                .thenReturn(new AuthService.Tokens("access-x", "refresh-y"));
        AuthController controller = new AuthController(service, new AuthCookies(true), jwt);

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.signUp(new AuthRequests.SignUp("a@b.co", "abcd1234",
                Map.of(AgreementType.AGE_14, true, AgreementType.TERMS, true, AgreementType.PRIVACY, true)),
                "phone-ua", response);

        List<String> cookies = response.getHeaders("Set-Cookie");
        assertThat(cookies).hasSize(2);
        assertThat(cookies.get(0)).startsWith(AuthCookies.ACCESS + "=access-x").contains("HttpOnly");
        assertThat(cookies.get(1)).startsWith(AuthCookies.REFRESH + "=refresh-y").contains("Path=/api/v1/auth");
        // 기기별 refresh 저장에 쓰이는 User-Agent 가 서비스까지 그대로 간다.
        verify(service).signUp(eq("a@b.co"), eq("abcd1234"), anyMap(), anyString(), eq("phone-ua"), any(Instant.class));
    }

    @Test
    @DisplayName("signUp 은 로그인과 같은 Tokens 를 돌려준다")
    void signUpReturnsTokens() throws Exception {
        assertThat(AuthService.class.getMethod("signUp", String.class, String.class, Map.class,
                String.class, String.class, Instant.class).getReturnType())
                .isEqualTo(AuthService.Tokens.class);
    }

    @Test
    @DisplayName("필수 동의는 AGE_14 · TERMS · PRIVACY 세 가지다")
    void requiredAgreements() {
        assertThat(AgreementType.values())
                .containsExactly(AgreementType.AGE_14, AgreementType.TERMS,
                        AgreementType.PRIVACY, AgreementType.MARKETING);
    }
}
