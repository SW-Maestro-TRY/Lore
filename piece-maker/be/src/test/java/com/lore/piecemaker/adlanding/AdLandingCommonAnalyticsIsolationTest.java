package com.lore.piecemaker.adlanding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.analytics.AnalyticsEvent;
import com.lore.common.analytics.AnalyticsEventRepository;
import com.lore.common.analytics.AnalyticsService;
import com.lore.common.analytics.AnonIdentityRepository;
import com.lore.common.analytics.dto.EventRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 광고 기능을 추가해도 기존 서비스의 이벤트 저장 형식과 속성 제한은 바꾸지 않는다. */
class AdLandingCommonAnalyticsIsolationTest {
    private final List<AnalyticsEvent> saved = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();
    private AnalyticsService analytics;

    @BeforeEach
    void setUp() {
        var repository = mock(AnalyticsEventRepository.class);
        when(repository.saveAll(any())).thenAnswer(call -> {
            Iterable<AnalyticsEvent> rows = call.getArgument(0);
            rows.forEach(saved::add);
            return saved;
        });
        analytics = new AnalyticsService(repository, mock(AnonIdentityRepository.class), true, 50);
    }

    @Test
    void existingServiceEventsKeepTheirPropertiesAndIdentityWithoutAdMetadata() throws Exception {
        var batch = new EventRequests.Batch(null, null, List.of(
                new EventRequests.Event("auth_login_succeeded", System.currentTimeMillis(), "/zzal",
                        Map.of("tab", "login", "email", "reader@example.com", "ad_attribution", "forged")),
                new EventRequests.Event("zzal_care", System.currentTimeMillis(), "/zzal", Map.of("action", "feed")),
                new EventRequests.Event("webtoon_opened", System.currentTimeMillis(), "/webtoon", null)));
        assertThat(analytics.collect(batch, "a".repeat(32), 42L, null)).isEqualTo(3);
        assertThat(saved).allSatisfy(row -> {
            assertThat(row.getAnonId()).isEqualTo("a".repeat(32));
            assertThat(row.getUserId()).isEqualTo(42L);
        });
        assertThat(json.readTree(props(saved.get(0)))).isEqualTo(json.valueToTree(Map.of("tab", "login")));
        assertThat(json.readTree(props(saved.get(1)))).isEqualTo(json.valueToTree(Map.of("action", "feed")));
        assertThat(props(saved.get(2))).isNull();
    }

    @Test
    void ordinaryTenPropertyLimitAndUnknownPropertyFilteringRemainInPlace() throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("ad_attribution", Map.of("utm_source", "facebook"));
        input.put("utm_source", "facebook");
        input.put("email", "reader@example.com");
        for (String key : List.of("action", "tab", "from", "to", "code", "reason", "type", "step")) input.put(key, "ok");
        input.put("stars", 5);
        input.put("count", 1);
        input.put("seq", 2);
        analytics.collect(new EventRequests.Batch(null, null, List.of(
                new EventRequests.Event("zzal_care", System.currentTimeMillis(), "/zzal", input))), "b".repeat(32), null, null);
        var stored = json.readTree(props(saved.getFirst()));
        assertThat(stored.size()).isEqualTo(10);
        assertThat(stored.path("count").asInt()).isEqualTo(1);
        for (String absent : List.of("seq", "ad_attribution", "utm_source", "email")) assertThat(stored.has(absent)).isFalse();
        assertThat(saved.getFirst().getUserId()).isNull();
    }

    private String props(AnalyticsEvent row) throws Exception {
        Field field = AnalyticsEvent.class.getDeclaredField("props");
        field.setAccessible(true);
        return (String) field.get(row);
    }
}
