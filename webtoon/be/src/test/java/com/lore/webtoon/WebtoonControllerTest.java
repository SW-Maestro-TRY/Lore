package com.lore.webtoon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
// Boot 4 에서 자리가 바뀌었다 (3.x 의 boot.test.autoconfigure.web.servlet 아님).
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.lore.common.auth.jwt.JwtProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import java.nio.charset.StandardCharsets;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프록시가 <b>무엇을 안 하는지</b>가 중요한 자리라 그것부터 본다.
 *
 * 경로를 바꾸지 않고, 응답을 감싸지 않고, 실패도 그대로 전한다 — 셋 중
 * 하나라도 어기면 화면이 하네스가 준 것과 다른 것을 읽는다.
 */
@WebMvcTest(WebtoonController.class)
class WebtoonControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean HarnessGateway gateway;
    /* 만들기 앞을 지키는 둘. 이 조각에는 DB 가 안 떠서 진짜를 못 만든다.
       가짜는 기본으로 null(=통과)을 주므로, 여기 검사들은 지금까지처럼
       그냥 지나간다 — 막는 쪽 동작은 SpendGuardTest·GuestGateTest 가 본다. */
    @MockitoBean SpendGuard spendGuard;
    @MockitoBean GuestGate guestGate;
    /* 이 조각(@WebMvcTest)에는 컨트롤러와 **필터**만 뜬다. 공용 인증 필터
       (JwtAuthenticationFilter)가 그 필터라 같이 뜨는데, 그것이 기대는
       JwtProvider 는 안 뜬다 — 없으면 컨텍스트가 아예 안 올라온다.
       여기서 보는 것은 프록시가 경로·본문·응답을 어떻게 다루는가지 인증이
       아니므로, 자리만 채워 준다(토큰이 없으면 필터는 그냥 흘려보낸다). */
    @MockitoBean JwtProvider jwtProvider;

    @Test
    @DisplayName("접두사만 갈아 끼우고 뒤는 그대로 넘긴다")
    void 경로매핑() {
        assertThat(WebtoonController.harnessPath("/api/webtoon/nh/jobs/abc"))
                .isEqualTo("/api/nh/jobs/abc");
        assertThat(WebtoonController.harnessPath("/api/webtoon/runs/r1/scenes/3/regen"))
                .isEqualTo("/api/runs/r1/scenes/3/regen");
        // 접두사가 없으면 건드리지 않는다 (이 컨트롤러로 올 일은 없지만,
        // substring 이 엉뚱한 자리를 자르지 않는다는 것을 못 박아 둔다)
        assertThat(WebtoonController.harnessPath("/api/config")).isEqualTo("/api/api/config");
    }

    @Test
    @DisplayName("메서드·본문·쿼리를 그대로 넘긴다")
    void 그대로넘김() throws Exception {
        when(gateway.forward(any(), any(), any(), any(), any()))
                .thenReturn(ResponseEntity.ok().body("{\"id\":\"x\"}".getBytes()));

        mvc.perform(post("/api/webtoon/nh/create?ep=1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"도하람\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(gateway).forward(eq(HttpMethod.POST), eq("/api/nh/create"), eq("ep=1"),
                                body.capture(), any(HttpHeaders.class));
        assertThat(new String(body.getValue())).contains("도하람");
    }

    @Test
    @DisplayName("응답을 감싸지 않는다 — 하네스가 준 그대로 나간다")
    void 안감쌈() throws Exception {
        // ApiResponse 로 감싸면 화면이 못 읽는다. 프로토타입에서 옮겨 온
        // 화면들이 하네스 응답 모양을 그대로 읽고 있어서다.
        when(gateway.forward(any(), any(), any(), any(), any()))
                .thenReturn(ResponseEntity.ok().body("{\"status\":\"done\"}".getBytes()));

        mvc.perform(get("/api/webtoon/nh/jobs/j1"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"status\":\"done\"}"));
    }

    @Test
    @DisplayName("하네스가 준 실패 상태와 사유를 삼키지 않는다")
    void 실패도그대로() throws Exception {
        // "크레딧이 모자랍니다" 같은 사유가 여기서 사라지면 화면은
        // "알 수 없는 오류" 밖에 못 띄운다.
        byte[] said = "{\"error\":\"크레딧이 모자랍니다\"}".getBytes(StandardCharsets.UTF_8);
        when(gateway.forward(any(), any(), any(), any(), any()))
                .thenReturn(ResponseEntity.status(402)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(said));

        // **바이트로 견준다.** 이 프록시가 지키는 것은 "바이트를 안 건드린다"
        // 이지 "어떤 글자로 읽힌다" 가 아니다. 문자열로 견주면 테스트가
        // 응답 헤더의 charset 을 어떻게 읽느냐에 걸려서, 실제로는 멀쩡한
        // 것이 깨져 보인다(하네스는 charset=utf-8 을 붙여 보낸다).
        byte[] got = mvc.perform(post("/api/webtoon/nh/create"))
                .andExpect(status().is(402))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(got).isEqualTo(said);
    }
}
