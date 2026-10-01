package com.lore.webtoon.character;

import com.lore.common.auth.jwt.JwtProvider;
import com.lore.common.config.WebSecurityConfig;
import com.lore.webtoon.Admins;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공유 카드 링크 미리보기(og 태그)가 부르는 두 주소.
 *
 * 화면 서버가 미리보기를 만들 때 부르므로 <b>방문 보상을 세면 안 된다</b> — 세면 화면 서버가
 * "남이 연 것" 으로 잡혀 카드 주인에게 무료 횟수가 잘못 돌아간다(#332).
 */
@WebMvcTest(CharacterController.class)
@Import(WebSecurityConfig.class)
@TestPropertySource(properties = "jwt.secret=test-only-secret-that-is-long-enough-for-hs256")
class CardPreviewTest {

    private static final String BASE = "/api/webtoon/v1/characters/card1";

    @Autowired MockMvc mvc;

    @MockitoBean CharacterService characters;
    @MockitoBean CharacterOwner who;
    @MockitoBean ShareReward shareReward;
    @MockitoBean Admins admins;
    @MockitoBean JwtProvider jwtProvider;

    private WebtoonCharacter card(String artUrl) {
        WebtoonCharacter one = mock(WebtoonCharacter.class);
        when(one.getTwist()).thenReturn("마이크는 무대 소품입니다.");
        when(one.getName()).thenReturn("마이크");
        when(one.getWorldLabel()).thenReturn("아이돌");
        when(characters.sharedCard("card1")).thenReturn(one);
        when(characters.artUrl(one)).thenReturn(artUrl);
        return one;
    }

    @Test
    @DisplayName("미리보기는 카드 문장·이름·세계관을 주고, 방문 보상은 세지 않는다")
    void 미리보기() throws Exception {
        card("/images/webtoon/char/a.jpg");
        mvc.perform(get(BASE + "/card/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.twist").value("마이크는 무대 소품입니다."))
                .andExpect(jsonPath("$.name").value("마이크"))
                .andExpect(jsonPath("$.world_label").value("아이돌"))
                .andExpect(jsonPath("$.has_art").value(true));
        verifyNoInteractions(shareReward);
    }

    @Test
    @DisplayName("그림 주소는 지금 열리는 주소로 넘겨 준다")
    void 그림_302() throws Exception {
        card("https://dev.example/lore-content/private/webtoon/char/a.jpg?X-Amz-Signature=x");
        mvc.perform(get(BASE + "/card/art"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://dev.example/lore-content/private/webtoon/char/a.jpg?X-Amz-Signature=x"));
        verifyNoInteractions(shareReward);
    }

    @Test
    @DisplayName("그림이 없는 카드는 404")
    void 그림_없음() throws Exception {
        card(null);
        mvc.perform(get(BASE + "/card/art")).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/card/preview")).andExpect(jsonPath("$.has_art").value(false));
    }
}
