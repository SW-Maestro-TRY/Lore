package com.lore.webtoon.series;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 다음 화 요청의 「바로 만들기」 — 화면이 보낸 값을 그대로 읽고, 안 보내면 확인하며 만든다(2026-10-26). */
class SeriesFormJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("checkpoints:false 를 보내면 바로 만들기로 읽는다")
    void 바로_만들기() throws Exception {
        var form = mapper.readValue("""
                {"story":"이번 화","revision":3,"confirmed":true,"request_token":"t1","uid":"u1","checkpoints":false}""",
                SeriesService.Form.class);
        assertThat(form.checkpoints()).isFalse();
        assertThat(form.revision()).isEqualTo(3);
    }

    @Test
    @DisplayName("안 보내면 비어 있다 — 서버는 확인하며 만드는 쪽으로 본다")
    void 안_보내면() throws Exception {
        var form = mapper.readValue("""
                {"story":"이번 화","revision":0,"confirmed":true,"request_token":"t1","uid":"u1"}""",
                SeriesService.Form.class);
        assertThat(form.checkpoints()).isNull();
    }
}
