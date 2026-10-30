package com.lore.webtoon.runs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 장 위에 직접 그린 것을 <b>믿을 수 있는 값으로 깎는가</b>, 그리고 말풍선과 같이 읽히는가.
 */
class PaintStoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PaintRepository repo;
    private PaintStore store;

    @BeforeEach
    void 세운다() {
        repo = mock(PaintRepository.class);
        when(repo.findByRunIdAndEpisodeAndPageNo(anyString(), anyInt(), anyInt())).thenReturn(Optional.empty());
        store = new PaintStore(repo);
    }

    @Test
    @DisplayName("모르는 종류 · 점 없는 선은 버리고, 색 · 굵기 · 자리는 범위 안으로 깎는다")
    void 깎는다() throws Exception {
        JsonNode out = store.clean(JSON.readTree("""
                {"strokes":[
                  {"k":"b","c":"#FF0000","w":99999,"p":[-5,20000,10,10]},
                  {"k":"x","p":[1,2]},
                  {"k":"b","p":[]},
                  {"k":"b","c":"red","w":1,"p":[5,5]},
                  {"k":"e","c":"#123456","w":100,"p":[1.4,2.6,"x",3]}]}"""));

        JsonNode s = out.path("strokes");
        assertThat(out.path("v").asInt()).isEqualTo(1);
        assertThat(s).hasSize(3);
        assertThat(s.get(0).path("c").asText()).isEqualTo("#ff0000");
        assertThat(s.get(0).path("w").asInt()).isEqualTo(PaintStore.W_MAX);
        assertThat(s.get(0).path("p").toString()).isEqualTo("[0,10000,10,10]");
        assertThat(s.get(1).path("c").asText()).isEqualTo("#111111");   // 모르는 색은 먹색
        assertThat(s.get(1).path("w").asInt()).isEqualTo(PaintStore.W_MIN);
        assertThat(s.get(2).has("c")).isFalse();                         // 지우개는 색이 없다
        assertThat(s.get(2).path("p").toString()).isEqualTo("[1,3]");    // 숫자가 아닌 점만 빠진다
    }

    @Test
    @DisplayName("한 장의 점 수 한도를 넘는 것은 잘라 낸다")
    void 한도() {
        ObjectNode raw = JSON.createObjectNode();
        ArrayNode strokes = raw.putArray("strokes");
        for (int i = 0; i < 5; i++) {
            ObjectNode one = strokes.addObject();
            one.put("k", "b");
            ArrayNode p = one.putArray("p");
            for (int j = 0; j < 3000; j++) {
                p.add(j % 10000).add(i * 100);
            }
        }
        JsonNode out = store.clean(raw);
        int points = 0;
        for (JsonNode s : out.path("strokes")) {
            points += s.path("p").size() / 2;
        }
        assertThat(points).isEqualTo(PaintStore.MAX_POINTS);
    }

    @Test
    @DisplayName("다 지우고 보내면 줄을 지운다 — 다시 구울 때 옛 선이 되살아나지 않게")
    void 다_지우면_지운다() throws Exception {
        WebtoonPaint had = WebtoonPaint.of("r1", 1, 2, "{\"v\":1,\"strokes\":[]}", java.time.Instant.now());
        when(repo.findByRunIdAndEpisodeAndPageNo("r1", 1, 2)).thenReturn(Optional.of(had));

        int n = store.save("r1", 1, 2, JSON.readTree("{\"strokes\":[]}"));

        assertThat(n).isZero();
        verify(repo).delete(had);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("얹은 것을 읽으면 그린 장에 paint 가 붙고, 말풍선 없는 장에는 items 를 만들지 않는다")
    void 얹은_것과_같이_읽힌다() throws Exception {
        OverlayRepository overlays = mock(OverlayRepository.class);
        when(overlays.findByRunIdAndEpisode("r1", 1)).thenReturn(Optional.of(WebtoonOverlay.of("r1", 1,
                "{\"scenes\":{\"1\":{\"ref_w\":720,\"items\":[{\"type\":\"bubble\",\"text\":\"안녕\"}]}},\"gaps\":{}}",
                java.time.Instant.now())));
        when(repo.findByRunIdAndEpisode("r1", 1)).thenReturn(List.of(
                WebtoonPaint.of("r1", 1, 1, "{\"v\":1,\"strokes\":[{\"k\":\"b\",\"c\":\"#000000\",\"w\":60,\"p\":[1,1]}]}", java.time.Instant.now()),
                WebtoonPaint.of("r1", 1, 3, "{\"v\":1,\"strokes\":[{\"k\":\"b\",\"c\":\"#000000\",\"w\":60,\"p\":[2,2]}]}", java.time.Instant.now())));
        OverlayStore both = new OverlayStore(overlays);
        both.setPaints(store);

        JsonNode got = both.read("r1", 1);

        assertThat(got.path("scenes").path("1").path("items")).hasSize(1);
        assertThat(got.path("scenes").path("1").path("paint").path("strokes")).hasSize(1);
        assertThat(got.path("scenes").path("3").has("items")).isFalse();
        assertThat(got.path("scenes").path("3").path("paint").path("strokes")).hasSize(1);
        assertThat(both.count(got)).isEqualTo(1);    // 「n개 얹음」은 말풍선만 센다
    }
}
