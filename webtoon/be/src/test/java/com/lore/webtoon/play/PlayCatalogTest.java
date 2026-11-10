package com.lore.webtoon.play;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.assertj.core.api.Assertions.assertThat;

import com.lore.webtoon.lorebook.LoreBook;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlayCatalogTest {

    @Test
    void 스토리네이션_세_세계는_사건과_그림이_있고_공개_응답에는_비밀이_없다() {
        var catalog = new PlayCatalog();
        for (String id : List.of("e1d640412ec94d82bf9b", "sne-bardo-express", "sne-bunker-068")) {
            var world = catalog.world(id);
            assertThat(world).isNotNull();
            assertThat(world.get("secret")).isNotNull();
            assertThat(((List<?>) world.get("events"))).isNotEmpty();
            assertThat(((List<?>) world.get("events"))).allSatisfy(e ->
                    assertThat(((Map<?, ?>) e).get("no")).isNotEqualTo("EP 00"));
            var shown = new PlayController(catalog, null, null, null, null, null).shown(world);
            assertThat(shown).doesNotContainKeys("secret", "lorebook");
            assertThat((List<Map<String, Object>>) shown.get("cast")).allSatisfy(p ->
                    assertThat(p).doesNotContainKey("secret"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 모든_세계의_설정집이_만들기_검사를_통과하고_사건이_있다() {
        var worlds = new PlayCatalog().worlds();
        assertFalse(worlds.isEmpty());
        for (var world : worlds) {
            var book = LoreBook.clean((Map<String, Object>) world.get("lorebook"), true);
            // 운영 세계는 항목을 나눠 둔 것(좀비 7개)도, 고정 설정 글 하나로 둔 것(하은이 만든 arcana · edelia)도 있다
            Object fixed = ((List<Map<String, Object>>) book.getOrDefault("books", List.of(book))).stream()
                    .map(b -> b.get("fixed_settings")).filter(v -> v instanceof String x && !x.isBlank()).findFirst().orElse(book.get("fixed_settings"));
            boolean hasEntries = !((List<?>) book.getOrDefault("entries", List.of())).isEmpty();
            boolean hasFixed = fixed instanceof String f && !f.isBlank();
            assertTrue(hasEntries || hasFixed, world.get("id") + " 설정집이 비었다");
            assertFalse(((List<?>) world.get("events")).isEmpty());
        }
    }
}
