package com.lore.webtoon;

import com.lore.common.auth.jwt.JwtProvider;
import com.lore.common.config.WebSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 웹툰 스튜디오는 <b>로그인 없이 끝까지 만들 수 있는 화면</b>이다.
 *
 * 공용 보안 설정(common 의 WebSecurityConfig)은 `anyRequest().authenticated()`
 * 로 끝난다. 거기서 `/api/webtoon/**` 을 열어 두는 한 줄이 빠지면 만들기·
 * 둘러보기·편집실이 <b>전부 401</b> 이 된다 — 화면을 열어 보기 전에는 아무도
 * 모르고, 열어 보면 전부 안 된다.
 *
 * 그 한 줄이 사라지면 여기서 먼저 걸린다. (공용 파일이라 남이 고칠 수 있어서
 * 더 필요한 검사다.)
 */
@WebMvcTest({WebtoonController.class, MyWebtoonController.class})
@Import(WebSecurityConfig.class)
@TestPropertySource(properties = {
        // JwtProvider 는 목이라 안 쓰지만, 설정 바인딩은 값이 있어야 뜬다.
        "jwt.secret=test-only-secret-that-is-long-enough-for-hs256",
})
class WebtoonGuestAccessTest {

    @Autowired MockMvc mvc;
    @MockitoBean HarnessGateway gateway;
    /* 만들기 앞을 지키는 둘. 이 조각에는 DB 가 안 떠서 진짜를 못 만든다.
       가짜는 기본으로 null(=통과)을 주므로, 여기 검사들은 지금까지처럼
       그냥 지나간다 — 막는 쪽 동작은 SpendGuardTest·GuestGateTest 가 본다. */
    @MockitoBean SpendGuard spendGuard;
    @MockitoBean GuestGate guestGate;
    @MockitoBean JwtProvider jwtProvider;
    @MockitoBean MyWebtoonService myWebtoonService;

    @Test
    @DisplayName("「내」 것을 다루는 주소는 로그인이 있어야 한다")
    void 내_주소는_잠겨_있다() throws Exception {
        // 게스트 규칙(`/api/webtoon/**` permitAll)이 이 주소까지 열어 버리면
        // 남의 목록을 아무나 부를 수 있게 된다. 순서가 뒤집히면 여기서 걸린다.
        mvc.perform(get("/api/webtoon/my/runs")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/webtoon/my/link")
                        .contentType("application/json").content("{\"uid\":\"u1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그인 없이도 웹툰 주소가 열린다 — 401 이 아니다")
    void 게스트가_부를_수_있다() throws Exception {
        when(gateway.forward(any(HttpMethod.class), any(), any(), any(), any(HttpHeaders.class)))
                .thenReturn(ResponseEntity.ok("{}".getBytes()));

        mvc.perform(get("/api/webtoon/runs")).andExpect(status().isOk());
        mvc.perform(post("/api/webtoon/nh/create")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
    }
}
