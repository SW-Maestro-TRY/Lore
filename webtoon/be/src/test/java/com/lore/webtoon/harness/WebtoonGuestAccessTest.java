package com.lore.webtoon.harness;

import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.credit.GuestGate;
import com.lore.webtoon.job.AfterRun;
import com.lore.webtoon.job.JobController;
import com.lore.webtoon.job.JobService;
import com.lore.webtoon.job.RunArt;
import com.lore.webtoon.runs.BakeService;
import com.lore.webtoon.runs.EpisodeExport;
import com.lore.webtoon.runs.OverlayStore;
import com.lore.webtoon.runs.RegenService;
import com.lore.webtoon.runs.RunController;
import com.lore.webtoon.runs.RunService;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.usage.SpendGuard;
import com.lore.webtoon.work.MyWebtoonController;
import com.lore.webtoon.work.MyWebtoonService;
import com.lore.webtoon.work.WorkLedger;
import com.lore.common.auth.jwt.JwtProvider;
import com.lore.common.config.WebSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 웹툰 스튜디오는 <b>로그인 없이 끝까지 만들 수 있는 화면</b>이다.
 *
 * 공용 보안 설정(common 의 WebSecurityConfig)은 `anyRequest().authenticated()`
 * 로 끝난다. 거기서 `/api/webtoon/v1/**` 을 열어 두는 한 줄이 빠지면 만들기·
 * 둘러보기·편집실이 <b>전부 401</b> 이 된다 — 화면을 열어 보기 전에는 아무도
 * 모르고, 열어 보면 전부 안 된다.
 *
 * 그 한 줄이 사라지면 여기서 먼저 걸린다. (공용 파일이라 남이 고칠 수 있어서
 * 더 필요한 검사다.)
 *
 * <p><b>보는 것은 「401 이 아니다」 뿐이다.</b> 200 을 요구하면 이 검사가
 * 컨트롤러 속사정(목이 무엇을 돌려주는지)까지 묶게 되고, 그쪽이 바뀔 때마다
 * 보안과 무관한 이유로 빨개진다. 여기서 지키려는 것은 <b>문이 열려 있는가</b>
 * 하나다.
 */
@WebMvcTest({JobController.class, RunController.class, MyWebtoonController.class})
@Import(WebSecurityConfig.class)
@TestPropertySource(properties = {
        // JwtProvider 는 목이라 안 쓰지만, 설정 바인딩은 값이 있어야 뜬다.
        "jwt.secret=test-only-secret-that-is-long-enough-for-hs256",
        // RunController 는 이 스위치가 켜져야 뜬다(@ConditionalOnProperty).
        "lore.webtoon.python.direct=true",
})
class WebtoonGuestAccessTest {

    @Autowired MockMvc mvc;

    /* 이 조각에는 DB 도 파이썬도 안 뜬다 — 컨트롤러가 생성자에서 찾는 것만
       가짜로 채운다. 가짜는 기본으로 null 을 주므로 문지기는 전부 통과로
       읽히고, 막는 쪽 동작은 SpendGuardTest·GuestGateTest 가 본다. */
    @MockitoBean JobService jobService;
    @MockitoBean RunArt runArt;
    @MockitoBean SpendGuard spendGuard;
    @MockitoBean GuestGate guestGate;
    @MockitoBean CreditGate creditGate;
    @MockitoBean RunService runService;
    @MockitoBean PageStore pageStore;
    @MockitoBean EpisodeExport episodeExport;
    @MockitoBean OverlayStore overlayStore;
    @MockitoBean BakeService bakeService;
    @MockitoBean StoryStore storyStore;
    @MockitoBean RegenService regenService;
    @MockitoBean AfterRun afterRun;
    @MockitoBean PrivateArt privateArt;
    @MockitoBean WorkLedger workLedger;
    @MockitoBean JwtProvider jwtProvider;
    @MockitoBean MyWebtoonService myWebtoonService;

    @Test
    @DisplayName("「내」 것을 다루는 주소는 로그인이 있어야 한다")
    void 내_주소는_잠겨_있다() throws Exception {
        // 게스트 규칙(`/api/webtoon/v1/**` permitAll)이 이 주소까지 열어 버리면
        // 남의 목록을 아무나 부를 수 있게 된다. 순서가 뒤집히면 여기서 걸린다.
        mvc.perform(get("/api/webtoon/v1/my/runs")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/webtoon/v1/my/link")
                        .contentType("application/json").content("{\"uid\":\"u1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그인 없이도 웹툰 주소가 열린다 — 401 이 아니다")
    void 게스트가_부를_수_있다() throws Exception {
        int browse = mvc.perform(get("/api/webtoon/v1/runs"))
                .andReturn().getResponse().getStatus();
        int create = mvc.perform(post("/api/webtoon/v1/nh/create")
                        .contentType("application/json").content("{}"))
                .andReturn().getResponse().getStatus();

        assertThat(browse).as("둘러보기가 로그인을 요구하면 안 된다").isNotEqualTo(401);
        assertThat(create).as("만들기가 로그인을 요구하면 안 된다").isNotEqualTo(401);
    }
}
