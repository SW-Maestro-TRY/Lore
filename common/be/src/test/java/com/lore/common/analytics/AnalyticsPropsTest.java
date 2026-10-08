package com.lore.common.analytics;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** props 허용 키 — 도감 저장·공유 기록의 동작 키·층(#705). */
class AnalyticsPropsTest {

    @Test
    void 도감_기록의_동작_키와_층은_남고_목록_밖_키는_버린다() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", "saved");
        props.put("motion", "sweep");
        props.put("layer", 2);
        props.put("char_name", "여울이");

        assertThat(AnalyticsService.sanitizeProps(props))
                .isEqualTo("{\"action\":\"saved\",\"motion\":\"sweep\",\"layer\":2}");
    }

    @Test
    void 선물_층은_글자로_남는다() {
        assertThat(AnalyticsService.sanitizeProps(Map.of("layer", "gift")))
                .isEqualTo("{\"layer\":\"gift\"}");
    }
}
