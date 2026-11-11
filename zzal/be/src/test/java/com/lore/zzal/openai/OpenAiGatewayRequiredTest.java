package com.lore.zzal.openai;

import com.lore.zzal.alert.ZzalAlerts;
import com.lore.zzal.chat.line.ChatLineConfig;
import com.lore.zzal.chat.line.OpenAiChatLineClient;
import com.lore.zzal.chat.stage.StageSpeechBatchGenerator;
import com.lore.zzal.generation.GenerationConfig;
import com.lore.zzal.generation.GenerationRecorder;
import com.lore.zzal.generation.GenerationRunner;
import com.lore.zzal.generation.client.OpenAiImageClient;
import com.lore.zzal.generation.client.OpenAiTextClient;
import com.lore.zzal.generation.client.PersistentProviderUsageRecorder;
import com.lore.zzal.generation.client.ProviderUsageRecorder;
import com.lore.zzal.generation.client.ProviderUsageSpool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1 검토 표 3번: 운영 경로는 창구가 <b>필수</b>다. 창구 빈이 없으면 조용히 혼자 쓰는 창구로 빠지지 말고 기동이 실패한다.
 *
 * <h3>★ 왜 필요한가</h3>
 * 혼자 쓰는 창구로 빠지면 그 기능만 줄 상한·429 차단 밖으로 나가고, 기동·시험은 다 통과한다
 * (설정 이름이 어긋나면 조용히 죽는다는 원칙과 같은 모양). 뜨지 않는 편이 낫다.
 */
@DisplayName("창구 필수 — 빈이 없으면 기동 실패, 운영 생성자는 null 창구를 받지 않는다")
class OpenAiGatewayRequiredTest {

    private static PersistentProviderUsageRecorder usage() {
        var r = mock(PersistentProviderUsageRecorder.class);
        when(r.forFeature(anyString())).thenReturn(ProviderUsageRecorder.NO_OP);
        return r;
    }

    /**
     * 설정 클래스를 @Bean 메서드 없이 평범한 빈으로 둔다 — 주입(창구 필드)만 본다. 다른 빈(S3 등)이 없어 실패하는 것과
     * 창구가 없어 실패하는 것을 가르려는 것이다.
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class GenerationConfigAsBean {
        @org.springframework.context.annotation.Bean GenerationConfig generationConfigUnderTest() { return new GenerationConfig(); }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class ChatLineConfigAsBean {
        @org.springframework.context.annotation.Bean ChatLineConfig chatLineConfigUnderTest() { return new ChatLineConfig(); }
    }

    /** 창구 빈 없이. */
    private final ApplicationContextRunner noGateway = new ApplicationContextRunner()
            .withBean(PersistentProviderUsageRecorder.class, OpenAiGatewayRequiredTest::usage)
            .withBean(ProviderUsageSpool.class, () -> mock(ProviderUsageSpool.class))
            .withBean(GenerationRecorder.class, () -> mock(GenerationRecorder.class))
            .withBean(ZzalAlerts.class, () -> mock(ZzalAlerts.class));

    @Test
    @DisplayName("★ 그림·문단 설정(GenerationConfig)은 창구 빈이 없으면 뜨지 않는다")
    void generationConfigNeedsGateway() {
        noGateway.withUserConfiguration(GenerationConfigAsBean.class).run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("★ 채팅 설정(ChatLineConfig)은 창구 빈이 없으면 뜨지 않는다")
    void chatLineConfigNeedsGateway() {
        noGateway.withUserConfiguration(ChatLineConfigAsBean.class).run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("★ 생성 실행기(GenerationRunner)는 창구 빈이 없으면 뜨지 않는다")
    void runnerNeedsGateway() {
        noGateway.withBean(GenerationRunner.class).run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("★ 튜토리얼 묶음 대사(StageSpeechBatchGenerator)는 창구 빈이 없으면 뜨지 않는다")
    void stageBatchNeedsGateway() {
        noGateway.withPropertyValues("app.zzal.openai.api-key=SYNTHETIC_NOT_A_KEY")
                .withBean(StageSpeechBatchGenerator.class).run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("★ 창구 빈이 있으면 같은 문맥이 뜬다(위 실패가 다른 이유가 아님을 확인)")
    void withGatewayItStarts() {
        noGateway.withUserConfiguration(OpenAiGatewayConfig.class)
                .withUserConfiguration(GenerationConfigAsBean.class, ChatLineConfigAsBean.class)
                .withBean(GenerationRunner.class)
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("★ 운영 생성자 셋은 null 창구를 받지 않는다 — 혼자 쓰는 창구로 빠지지 않는다")
    void productionConstructorsRejectNullGateway() {
        assertThatThrownBy(() -> new OpenAiImageClient(null, "SYNTHETIC_NOT_A_KEY", 5, ProviderUsageRecorder.NO_OP, "", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OpenAiTextClient(null, "SYNTHETIC_NOT_A_KEY", 64, ProviderUsageRecorder.NO_OP, "", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OpenAiChatLineClient("SYNTHETIC_NOT_A_KEY", "none", 64, ProviderUsageRecorder.NO_OP, "",
                null, Lane.DIALOGUE_URGENT)).isInstanceOf(NullPointerException.class);
    }
}
