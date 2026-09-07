package com.lore.common.auth;

import com.lore.common.auth.jwt.AuthCookies;
import com.lore.common.auth.jwt.JwtProvider;
import com.lore.common.config.WebSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>가입은 로그인이 아니다</b>(상훈님 2026-09-08 결정).
 *
 * 전에는 가입 응답에 access·refresh 쿠키가 그대로 붙어 가입하는 순간 로그인됐다.
 * 그 상태로는 "가입 뒤 로그인" 화면이 아무 의미가 없다 — 이미 로그인된 사람에게 폼을 보이게 된다.
 *
 * 이 검사가 깨졌다면 누군가 signup 에 writeCookies 를 되돌린 것이다.
 * 고치기 전에 그 결정을 먼저 확인할 것.
 */
@WebMvcTest(AuthController.class)
@Import(WebSecurityConfig.class)
@TestPropertySource(properties = {
        "jwt.secret=test-only-secret-that-is-long-enough-for-hs256",
})
class SignUpDoesNotSignInTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AuthService authService;
    @MockitoBean
    AuthCookies authCookies;
    @MockitoBean
    JwtProvider jwtProvider;

    @Test
    @DisplayName("가입 응답에는 쿠키가 붙지 않는다")
    void signupSetsNoCookie() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"a@example.com","password":"password123",
                                 "agreements":{"TERMS":true,"PRIVACY":true,"MARKETING":false}}"""))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("가입만으로는 내 정보를 볼 수 없다 — 401")
    void meIsUnauthorizedRightAfterSignUp() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"b@example.com","password":"password123",
                                 "agreements":{"TERMS":true,"PRIVACY":true,"MARKETING":false}}"""))
                .andExpect(status().isOk());

        // 쿠키를 못 받았으니 다음 요청에 실을 것이 없다 = 비로그인.
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }
}
