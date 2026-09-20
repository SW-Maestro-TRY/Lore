package com.lore.webtoon.runs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 편집실의 다시 그리기 창이 <b>딱 이 한 줄</b>만 읽는다
 * ({@code editorCore.ts} 의 {@code cfg.feedback_tags.scene}).
 */
class ConfigControllerTest {

    @Test
    @DisplayName("scene 항목표를 준다 — 다시 그리기 창이 이것만 읽는다")
    void scene_항목표() {
        Map<String, Object> config = new ConfigController().config();

        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, String>>> tags =
                (Map<String, List<Map<String, String>>>) config.get("feedback_tags");
        List<Map<String, String>> scene = tags.get("scene");

        assertThat(scene).extracting(t -> t.get("id"))
                .contains("character", "background", "text", "etc");
    }

    @Test
    @DisplayName("네 단계 다 있다 — 항목을 늘릴 때 여기 한 곳만 고치면 된다")
    void 네_단계() {
        Map<String, Object> config = new ConfigController().config();
        @SuppressWarnings("unchecked")
        Map<String, Object> tags = (Map<String, Object>) config.get("feedback_tags");
        assertThat(tags.keySet()).containsExactlyInAnyOrder("sheet", "story", "board", "scene");
    }
}
