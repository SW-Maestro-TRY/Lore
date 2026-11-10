package com.lore.zzal.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-2 (설계 1-2): 실패 이유를 처방별로 가른다. 지금까지는 게이트 거부·끼어들기·5xx 가 전부 UNKNOWN 에 섞였다.
 */
@DisplayName("실패 이유 코드 — 가짜 예외 5종이 각자 제 코드로")
class GenErrorCodeClassifyTest {

    @Test
    @DisplayName("끼어들기 → INTERRUPTED")
    void interrupted() {
        assertThat(GenerationRunner.classify(new InterruptedException("stop"))).isEqualTo(GenErrorCode.INTERRUPTED);
    }

    @Test
    @DisplayName("429(한도) → QUOTA")
    void quota() {
        assertThat(GenerationRunner.classify(new com.lore.zzal.openai.ProviderQuotaException(429,
                java.time.Duration.ofSeconds(5), "{\"error\":{\"code\":\"rate_limit_exceeded\"}}"))).isEqualTo(GenErrorCode.QUOTA);
        // 문구만 429 인 옛 예외는 더 이상 한도로 읽지 않는다(창구를 거치지 않은 것이라 타입이 진실).
        assertThat(GenerationRunner.classify(new IllegalStateException("이미지 생성 실패(HTTP 429): rate_limit")))
                .isEqualTo(GenErrorCode.UNKNOWN);
    }

    @Test
    @DisplayName("5xx → PROVIDER_ERROR")
    void serverError() {
        assertThat(GenerationRunner.classify(new com.lore.zzal.openai.ProviderServerException(520, "bad gateway", null)))
                .isEqualTo(GenErrorCode.PROVIDER_ERROR);
        assertThat(GenerationRunner.classify(new java.net.ConnectException("refused"))).isEqualTo(GenErrorCode.PROVIDER_ERROR);
    }

    @Test
    @DisplayName("격자 구조 게이트 표식 → GATE_REJECTED")
    void gate() {
        assertThat(GenerationRunner.classify(new IllegalStateException(
                "[격자=grid] 후처리 거부 " + GenerationRunner.GRID_STRUCTURE_MARK))).isEqualTo(GenErrorCode.GATE_REJECTED);
    }

    @Test
    @DisplayName("후처리 비정상 종료 코드 → POSTPROCESS_FAILED")
    void postprocess() {
        assertThat(GenerationRunner.classify(new IllegalStateException(GenerationRunner.POSTPROCESS_EXIT_MARK + "3): boom")))
                .isEqualTo(GenErrorCode.POSTPROCESS_FAILED);
    }

    @Test
    @DisplayName("옛 값은 그대로 — 거부는 MODERATION_BLOCKED, HTTP 요청 시간 초과는 TIMEOUT, 나머지는 UNKNOWN")
    void legacy() {
        assertThat(GenerationRunner.classify(new IllegalStateException("이미지 생성 실패(HTTP 400): moderation_blocked")))
                .isEqualTo(GenErrorCode.MODERATION_BLOCKED);
        assertThat(GenerationRunner.classify(new HttpTimeoutException("request timed out"))).isEqualTo(GenErrorCode.TIMEOUT);
        assertThat(GenerationRunner.classify(new IllegalStateException("응답에 이미지가 없습니다"))).isEqualTo(GenErrorCode.UNKNOWN);
    }
}
