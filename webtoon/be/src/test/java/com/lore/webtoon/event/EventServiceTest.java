package com.lore.webtoon.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 누구나 부를 수 있는 주소라, 무엇을 버리는지가 이 클래스의 전부다.
 */
class EventServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-26T03:00:00Z");

    private WebtoonEventRepository repo;
    private EventService service;

    @BeforeEach
    void setUp() {
        repo = mock(WebtoonEventRepository.class);
        service = new EventService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private List<WebtoonEvent> saved() {
        ArgumentCaptor<List<WebtoonEvent>> cap = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(cap.capture());
        return cap.getValue();
    }

    private static EventService.Batch batch(EventService.Event... events) {
        return new EventService.Batch(null, null, null, List.of(events));
    }

    @Test
    @DisplayName("허용한 키의 기호·숫자·참거짓만 남긴다")
    void keepsOnlyAllowedShapes() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("where", "result_other");
        props.put("ep", 1);
        props.put("mine", false);
        props.put("run", "20260923T172824-20a71b");
        props.put("name", "몽이");                 // 허용 안 한 키
        props.put("reason", "사람이 쓴 한 줄");      // 허용한 키지만 한글·공백 → 버림
        props.put("quality", "a b");              // 공백 → 버림

        assertThat(EventService.cleanProps(props))
                .isEqualTo("{\"where\":\"result_other\",\"ep\":1,\"mine\":false,\"run\":\"20260923T172824-20a71b\"}");
    }

    @Test
    @DisplayName("남는 값이 없으면 props 는 비운다")
    void emptyPropsBecomeNull() {
        assertThat(EventService.cleanProps(Map.of("email", "a@b.c"))).isNull();
        assertThat(EventService.cleanProps(null)).isNull();
    }

    @Test
    @DisplayName("이름 모양이 틀린 줄은 버리고 나머지는 저장한다")
    void dropsBadNames() {
        int n = service.collect(batch(
                new EventService.Event("page_view", NOW.toEpochMilli(), "landing", null),
                new EventService.Event("Bad Name", NOW.toEpochMilli(), "landing", null),
                new EventService.Event("next_episode_click", NOW.toEpochMilli(), "result",
                        Map.of("where", "result_other"))),
                "u1abc2def", 7L, "1.2.3.4", "Mozilla/5.0 (iPhone)");

        assertThat(n).isEqualTo(2);
        List<WebtoonEvent> rows = saved();
        assertThat(rows).extracting(WebtoonEvent::getName).containsExactly("page_view", "next_episode_click");
        assertThat(rows.get(1).getUid()).isEqualTo("u1abc2def");
        assertThat(rows.get(1).getUserId()).isEqualTo(7L);
        assertThat(rows.get(1).getDevice()).isEqualTo("mobile");
    }

    @Test
    @DisplayName("머리가 없으면 본문의 uid 를 쓴다 — 페이지를 떠날 때 보내는 길")
    void bodyUidWhenNoHeader() {
        service.collect(new EventService.Batch("u9zz", null, null,
                        List.of(new EventService.Event("page_view", null, null, null))),
                null, null, "1.2.3.4", null);
        assertThat(saved().get(0).getUid()).isEqualTo("u9zz");
    }

    @Test
    @DisplayName("말이 안 되는 화면 시각은 받은 시각으로 바꾼다")
    void badTimestampFallsBackToNow() {
        long yearAgo = NOW.minusSeconds(365L * 24 * 3600).toEpochMilli();
        service.collect(batch(new EventService.Event("page_view", yearAgo, null, null)),
                "u1abc", null, "1.2.3.4", null);
        assertThat(saved().get(0).getOccurredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("유입 주소는 호스트만 남긴다")
    void refKeepsHostOnly() {
        assertThat(EventService.hostOf("https://www.instagram.com/p/abc?igsh=secret")).isEqualTo("www.instagram.com");
        assertThat(EventService.hostOf("not a url")).isNull();
        assertThat(EventService.cleanSource("insta gram<script>")).isEqualTo("instagramscript");
    }

    @Test
    @DisplayName("한 번에 50줄까지만 받는다")
    void capsBatchSize() {
        List<EventService.Event> many = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            many.add(new EventService.Event("page_view", null, null, null));
        }
        int n = service.collect(new EventService.Batch(null, null, null, many), "u1abc", null, "1.2.3.4", null);
        assertThat(n).isEqualTo(EventService.MAX_BATCH);
    }

    @Test
    @DisplayName("한 브라우저가 1분에 너무 많이 부르면 버린다")
    void rateLimited() {
        EventService.Batch one = batch(new EventService.Event("page_view", null, null, null));
        for (int i = 0; i < EventService.MAX_REQUESTS_PER_MINUTE; i++) {
            assertThat(service.collect(one, "u1abc", null, "1.2.3.4", null)).isEqualTo(1);
        }
        assertThat(service.collect(one, "u1abc", null, "1.2.3.4", null)).isZero();
        // 다른 브라우저는 따로 센다
        assertThat(service.collect(one, "u2xyz", null, "1.2.3.4", null)).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 묶음은 저장하지 않는다")
    void emptyBatch() {
        assertThat(service.collect(new EventService.Batch(null, null, null, List.of()), "u1abc", null, "1.2.3.4", null)).isZero();
        verify(repo, never()).saveAll(any());
    }
}
