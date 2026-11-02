package com.lore.webtoon.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.webtoon.lorebook.LoreBook;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlayCatalogTest {

    @Test
    @SuppressWarnings("unchecked")
    void 모든_세계의_설정집이_만들기_검사를_통과하고_사건이_있다() {
        var worlds = new PlayCatalog(new ObjectMapper()).worlds();
        assertFalse(worlds.isEmpty());
        for (var world : worlds) {
            var book = LoreBook.clean((Map<String, Object>) world.get("lorebook"), true);
            assertEquals(7, ((List<?>) book.get("entries")).size());
            assertFalse(((List<?>) world.get("events")).isEmpty());
        }
    }
}
