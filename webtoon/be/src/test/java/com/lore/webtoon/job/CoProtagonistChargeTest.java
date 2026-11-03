package com.lore.webtoon.job;
import com.lore.common.s3.S3Service;
import com.lore.webtoon.credit.*;
import com.lore.webtoon.usage.SpendGuard;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 함께 주인공 시트 값(#899) — 둘째 주인공부터 한 사람당 조연 시트 값을 한 편 값과 따로 받는다. 모델 호출 없음. */
class CoProtagonistChargeTest {
    JobService jobs=mock(JobService.class);
    CreditGate credits=mock(CreditGate.class);
    JobQueue queue=mock(JobQueue.class);
    SpendGuard guard=mock(SpendGuard.class);
    JobController controller;
    @BeforeEach void setup() {
        controller=new JobController(jobs,queue,mock(RunArt.class),guard,mock(GuestGate.class),credits,
                mock(S3Service.class),mock(com.lore.webtoon.character.CharacterOwner.class),
                mock(com.lore.common.user.UserRepository.class));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L,null,List.of()));
        when(jobs.create(any(),eq(7L),any(),any())).thenReturn("job-1");
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    JobService.CreateRequest request(List<String> co) {
        return new JobService.CreateRequest("하윤","정비공","","","frost",null,null,null,true,true,
                "uid","first",null,"surf","ko","quick","","","",null,co);
    }

    @Test void addedProtagonistsAreChargedSeparately() {
        controller.create(new MockHttpServletRequest(),request(List.of("a","b")));
        int base=WebtoonQuality.creditsOf("surf");
        verify(credits).whyBlocked(7L,base+2*JobService.CAST_SHEET_CREDITS);
        verify(credits).charge(eq(7L),eq(base),eq("job-1"),any());
        verify(credits).charge(eq(7L),eq(2*JobService.CAST_SHEET_CREDITS),eq("job-1:co-sheets"),contains("주인공 시트 추가"));
    }

    @Test void oneProtagonistPaysOnlyTheEpisode() {
        controller.create(new MockHttpServletRequest(),request(null));
        verify(credits).whyBlocked(7L,WebtoonQuality.creditsOf("surf"));
        verify(credits,never()).charge(anyLong(),anyInt(),eq("job-1:co-sheets"),any());
    }

    @Test void notEnoughForTheAddedSheetsBlocksBeforeCreating() {
        int need=WebtoonQuality.creditsOf("surf")+3*JobService.CAST_SHEET_CREDITS;
        when(credits.whyBlocked(7L,need)).thenReturn("크레딧 잔액이 부족해요");
        ResponseEntity<?> got=controller.create(new MockHttpServletRequest(),request(List.of("a","b","c")));
        assertThat(got.getStatusCode().value()).isEqualTo(402);
        verify(jobs,never()).create(any(),any(),any(),any());
    }

    @Test void refundRefIsSeparateFromTheEpisode() {
        assertThat(JobRunner.coSheetsRef("job-1")).isEqualTo("job-1:co-sheets").isNotEqualTo("job-1");
    }
}
