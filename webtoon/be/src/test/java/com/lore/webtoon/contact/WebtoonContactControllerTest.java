package com.lore.webtoon.contact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebtoonContactControllerTest {

    @Test
    @DisplayName("맨 앞(브라우저가 적어 보낸 값)이 아니라 설비가 덧붙인 칸을 건너뛴 자리를 쓴다")
    void 적어_보낸_주소로는_제한을_못_피한다() {
        // 브라우저가 1.1.1.1 이라 적어 보내고, CloudFront 가 진짜 접속자 9.9.9.9, ALB 가 CloudFront 를 덧붙인 줄
        assertThat(WebtoonContactController.fromForwarded("1.1.1.1, 9.9.9.9, 130.176.0.1", 1)).isEqualTo("9.9.9.9");
        assertThat(WebtoonContactController.fromForwarded("2.2.2.2, 9.9.9.9, 130.176.0.1", 1)).isEqualTo("9.9.9.9");
    }

    @Test
    @DisplayName("줄이 짧으면 남은 것 중 가장 우리 쪽에 가까운 것 — 비었거나 이상하면 null")
    void 짧거나_빈_줄() {
        assertThat(WebtoonContactController.fromForwarded("9.9.9.9", 1)).isEqualTo("9.9.9.9");
        assertThat(WebtoonContactController.fromForwarded("9.9.9.9", 0)).isEqualTo("9.9.9.9");
        assertThat(WebtoonContactController.fromForwarded(",", 1)).isNull();
        assertThat(WebtoonContactController.fromForwarded(null, 1)).isNull();
    }
}
