package com.lore.webtoon.runs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 편집실이 보낸 것을 <b>믿을 수 있는 값으로 깎는가</b>.
 *
 * 브라우저에서 온 값이라 무엇이든 올 수 있다. 여기서 세우면 항목 하나가
 * 이상할 때 화 전체를 못 굽는다 — 그래서 세우지 않고 깎는다. 규칙은 파이썬
 * ({@code landing/overlay.py} 의 {@code clean_item})과 같아야 한다.
 */
class OverlayStoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OverlayStore store;

    @BeforeEach
    void 세운다() {
        OverlayRepository repo = mock(OverlayRepository.class);
        when(repo.findByRunIdAndEpisode(anyString(), anyInt())).thenReturn(Optional.empty());
        store = new OverlayStore(repo);
    }

    private JsonNode clean(String json) throws Exception {
        return store.clean(JSON.readTree(json));
    }

    @Test
    @DisplayName("모르는 종류와 빈 글은 버린다 — 어차피 못 그린다")
    void 못_그릴_것은_버린다() throws Exception {
        JsonNode out = clean("""
                {"scenes":{"1":{"items":[
                  {"type":"bubble","text":"살아남는다"},
                  {"type":"낙서","text":"모르는 종류"},
                  {"type":"bubble","text":"   "}]}}}""");

        assertThat(out.path("scenes").path("1").path("items")).hasSize(1);
        assertThat(out.path("scenes").path("1").path("items").get(0).path("text").asText())
                .isEqualTo("살아남는다");
    }

    @Test
    @DisplayName("터무니없는 값은 범위 안으로 깎는다")
    void 범위_밖은_깎는다() throws Exception {
        JsonNode item = clean("""
                {"scenes":{"1":{"items":[
                  {"type":"bubble","text":"하이","x":9999,"y":-9999,
                   "w":0,"size":99999,"rot":720}]}}}""")
                .path("scenes").path("1").path("items").get(0);

        assertThat(item.path("x").asDouble()).isEqualTo(110);
        assertThat(item.path("y").asDouble()).isEqualTo(-20);
        assertThat(item.path("w").asDouble()).isEqualTo(3);
        assertThat(item.path("size").asDouble()).isEqualTo(200);
        assertThat(item.path("rot").asDouble()).isEqualTo(180);
    }

    @Test
    @DisplayName("옛 값(왼쪽/오른쪽)으로 저장한 것도 그대로 열린다")
    void 옛_꼬리도_읽는다() throws Exception {
        JsonNode items = clean("""
                {"scenes":{"1":{"items":[
                  {"type":"bubble","text":"왼쪽","tail":"left"},
                  {"type":"bubble","text":"오른쪽","tail":"right"}]}}}""")
                .path("scenes").path("1").path("items");

        // 끝점을 안 보냈으면 그 자리의 흔한 값으로 만들어 준다.
        assertThat(items.get(0).path("tx").asDouble()).isEqualTo(22);
        assertThat(items.get(1).path("tx").asDouble()).isEqualTo(78);
        assertThat(items.get(0).path("ty").asDouble()).isEqualTo(152);
    }

    @Test
    @DisplayName("사람이 끌어다 놓은 꼬리 끝은 그대로 지킨다")
    void 끌어다_놓은_꼬리() throws Exception {
        JsonNode item = clean("""
                {"scenes":{"1":{"items":[
                  {"type":"bubble","text":"저기","tx":-45,"ty":55}]}}}""")
                .path("scenes").path("1").path("items").get(0);

        assertThat(item.path("tx").asDouble()).isEqualTo(-45);
        assertThat(item.path("ty").asDouble()).isEqualTo(55);
    }

    @Test
    @DisplayName("빈 장도 남긴다 — 지운 것과 안 열어 본 것은 다르다")
    void 빈_장을_남긴다() throws Exception {
        JsonNode out = clean("""
                {"scenes":{"2":{"items":[]}}}""");

        // 이 줄이 없으면 다시 구울 때 옛 말풍선이 되살아난다.
        assertThat(out.path("scenes").has("2")).isTrue();
        assertThat(out.path("scenes").path("2").path("items")).isEmpty();
    }

    @Test
    @DisplayName("여백 고침은 0~3 만 받는다")
    void 여백() throws Exception {
        JsonNode gaps = clean("""
                {"scenes":{},"gaps":{"1":2,"2":9,"삼":1}}""").path("gaps");

        assertThat(gaps.path("1").asInt()).isEqualTo(2);
        assertThat(gaps.has("2")).isFalse();
        assertThat(gaps.has("삼")).isFalse();
    }

    @Test
    @DisplayName("모르는 모양이 와도 안 죽는다 — 빈 것으로 둔다")
    void 이상한_본문() throws Exception {
        assertThat(store.clean(null).path("scenes")).isEmpty();
        assertThat(store.clean(JSON.readTree("[1,2,3]")).path("scenes")).isEmpty();
        assertThat(store.clean(JSON.readTree("{}")).path("gaps")).isEmpty();
    }

    @Test
    @DisplayName("몇 개 얹었는지 센다 — 화면이 그 숫자를 적는다")
    void 개수를_센다() throws Exception {
        JsonNode data = clean("""
                {"scenes":{"1":{"items":[{"type":"bubble","text":"하나"},
                                          {"type":"sfx","text":"쿵"}]},
                            "2":{"items":[{"type":"sticker","text":"💢"}]}}}""");

        assertThat(store.count(data)).isEqualTo(3);
    }
}
