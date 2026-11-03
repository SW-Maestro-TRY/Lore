package com.lore.zzal.generation.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P0-2 안전장치: 운영에서 OpenAI 주소를 바꾸면 기동 실패. */
class OpenAiBaseUrlGuardTest {
    @Test
    void prodWithStubFailsStartup() {
        assertThatThrownBy(() -> new OpenAiBaseUrl("http://127.0.0.1:8090", "prod"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodWithDefaultOrBlankStarts() {
        assertThatCode(() -> new OpenAiBaseUrl("https://api.openai.com", "prod")).doesNotThrowAnyException();
        assertThatCode(() -> new OpenAiBaseUrl("", "prod")).doesNotThrowAnyException();
    }

    @Test
    void devOrLocalMayPointAtStub() {
        assertThatCode(() -> new OpenAiBaseUrl("http://127.0.0.1:8090", "dev")).doesNotThrowAnyException();
        assertThatCode(() -> new OpenAiBaseUrl("http://127.0.0.1:8090", "")).doesNotThrowAnyException();
    }
}
