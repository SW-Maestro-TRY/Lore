package com.lore.webtoon.job;

import com.lore.common.email.EmailService;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.webtoon.story.StoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 완성 알림 — <b>누구에게, 몇 번 보내는가.</b>
 *
 * 이 파일이 지키는 것 둘.
 * <ul>
 *   <li><b>안 물어본 사람에게 안 보낸다.</b> 게스트가 주소를 안 적었으면
 *       보낼 데가 없다. 없는데 보낸 척하면 화면만 거짓말을 한다.</li>
 *   <li><b>한 번만 보낸다.</b> 끝나는 자리가 여럿이고 화면은 0.8초마다
 *       묻는다 — 같은 메일이 여러 통 오면 그건 알림이 아니라 고장이다.</li>
 * </ul>
 */
class JobNoticeTest {

    private JobStore store;
    private UserRepository users;
    private EmailService mail;
    private JobNotice notice;

    @BeforeEach
    void 세운다() {
        store = mock(JobStore.class);
        users = mock(UserRepository.class);
        mail = mock(EmailService.class);
        StoryStore stories = mock(StoryStore.class);
        when(stories.chosenOf(anyString())).thenReturn(Optional.empty());
        // 수신 설정은 켜 둔 사람 기준으로 본다 — 끈 사람은 NotifySettingService 쪽에서 따로 본다.
        NotifySettingService notifySettings = mock(NotifySettingService.class);
        when(notifySettings.isOn(any())).thenReturn(true);
        notice = new JobNotice(store, users, stories, mail, notifySettings, "https://lorecomic.com/");
    }

    private WebtoonJob 작업(Long userId, String typed) {
        WebtoonJob job = WebtoonJob.queued("job-1", userId, "uid-a", null,
                "webtoon_lock_bg", "surf", false, "{}", Instant.now());
        if (typed != null) {
            job.notifyTo(typed, Instant.now());
        }
        job.learnRun("20260913T032543-aecc64", Instant.now());
        when(store.byId(1L)).thenReturn(job);
        return job;
    }

    @Test
    @DisplayName("주소를 안 적은 게스트에게는 안 보낸다 — 보낼 데가 없다")
    void 안_적으면_안_보낸다() {
        작업(null, null);

        notice.finished(1L);

        verify(mail, never()).send(any(), any(), any());
        // 집지도 않는다 — 안 보냈는데 보냈다고 적으면 나중에 못 보낸다.
        verify(store, never()).claimNotice(any());
    }

    @Test
    @DisplayName("로그인한 사람에게는 안 물어보고 계정 주소로 보낸다")
    void 계정_주소로_간다() {
        작업(7L, null);
        when(users.findById(7L)).thenReturn(Optional.of(User.signUp("hae@lorecomic.com")));
        when(store.claimNotice(1L)).thenReturn(true);

        notice.finished(1L);

        verify(mail).send(eq("hae@lorecomic.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("적어 넣은 주소가 계정 주소를 이긴다 — 굳이 적었으면 그쪽으로 받고 싶다는 뜻")
    void 적은_주소가_이긴다() {
        작업(7L, "other@lorecomic.com");
        when(users.findById(7L)).thenReturn(Optional.of(User.signUp("hae@lorecomic.com")));
        when(store.claimNotice(1L)).thenReturn(true);

        notice.finished(1L);

        verify(mail).send(eq("other@lorecomic.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("두 번 불러도 한 통만 나간다")
    void 한_통만() {
        작업(null, "guest@lorecomic.com");
        // 처음 부른 쪽만 집는다 — 두 번째는 DB 가 0 을 준다.
        when(store.claimNotice(1L)).thenReturn(true, false);

        notice.finished(1L);
        notice.finished(1L);

        verify(mail, times(1)).send(any(), any(), any());
    }

    @Test
    @DisplayName("결과 링크를 담는다 — 게스트가 자기 작품으로 돌아오는 유일한 길")
    void 링크를_담는다() {
        작업(null, "guest@lorecomic.com");
        when(store.claimNotice(1L)).thenReturn(true);

        notice.finished(1L);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(any(), any(), body.capture());
        // 끝의 / 를 두 번 찍지 않는다 — "https://lorecomic.com//webtoon" 이 되면 안 된다.
        assertThat(body.getValue())
                .contains("https://lorecomic.com/webtoon?run=20260913T032543-aecc64")
                .doesNotContain("com//webtoon");
    }

    @Test
    @DisplayName("메일이 안 가도 만들기는 안 깨진다")
    void 메일이_터져도_안_깨진다() {
        작업(null, "guest@lorecomic.com");
        when(store.claimNotice(1L)).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("smtp 가 죽었다"))
                .when(mail).send(any(), any(), any());

        notice.finished(1L);            // 여기서 터지면 다 만든 작품이 실패로 적힌다
        notice.failed(1L, "그림을 만들지 못했습니다", Refunded.CREDIT);
    }

    @Test
    @DisplayName("실패 메일은 무엇을 돌려줬는지 적는다 — 사람마다 돌려주는 것이 다르다")
    void 돌려준_것을_적는다() {
        작업(null, "guest@lorecomic.com");
        when(store.claimNotice(1L)).thenReturn(true);

        notice.failed(1L, "그림을 만들지 못했습니다", Refunded.FREE);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(any(), any(), body.capture());
        assertThat(body.getValue())
                .contains("무료 생성 횟수")
                .doesNotContain("크레딧은 자동으로 환불");
    }

    /* 위 검사들은 "불렀을 때 무엇을 하나" 를 본다. **아무도 안 부르면 전부
       통과하면서 메일은 한 통도 안 나간다** — 실제로 이 프로젝트에서 정리
       코드가 「되살리기」 길에만 붙어 있어 정상 길에서는 영영 안 돌던 일이
       있었다. 그래서 부르는 자리 자체를 못 박는다. */
    @Test
    @DisplayName("만들기가 끝나는 자리에서 실제로 부른다 — 안 부르면 한 통도 안 나간다")
    void 부르는_자리가_있다() throws Exception {
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("webtoon/be/src/main/java/com/lore/webtoon/job/JobRunner.java"));
        // 다 됐다고 적은 **뒤에** 알린다 — 먼저 보내면 링크로 들어온 사람이
        // 아직 안 끝난 작품을 본다.
        assertThat(src).contains("store.done(jobId);");
        assertThat(src.indexOf("notice.finished(jobId)"))
                .isGreaterThan(src.indexOf("store.done(jobId);"));
        // 사람이 스스로 그만둔 것은 안 알린다.
        assertThat(src).contains("if (!CANCELLED.equals(why)) {");
    }

    @Test
    @DisplayName("주소처럼 안 생긴 것은 안 받는다 — 담아 두고 보낸 척하면 안 된다")
    void 주소_모양을_본다() {
        assertThat(JobNotice.clean("hae@lorecomic.com")).isEqualTo("hae@lorecomic.com");
        assertThat(JobNotice.clean("  hae@lorecomic.com  ")).isEqualTo("hae@lorecomic.com");
        assertThat(JobNotice.clean("hae@lorecomic")).isNull();      // 점이 없다
        assertThat(JobNotice.clean("lorecomic.com")).isNull();      // @ 가 없다
        assertThat(JobNotice.clean("hae @lorecomic.com")).isNull(); // 사이가 벌어졌다
        assertThat(JobNotice.clean("")).isNull();
        assertThat(JobNotice.clean(null)).isNull();
    }
}
