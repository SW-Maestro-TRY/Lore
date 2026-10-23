package com.lore.common.analytics;

import com.lore.common.analytics.dto.EventRequests;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalyticsReceiptTest {
    final AnalyticsEventRepository events=mock(AnalyticsEventRepository.class);
    final AnalyticsService service=new AnalyticsService(events,mock(AnonIdentityRepository.class),true,50);
    EventRequests.Event event(String name,String id) {
        return new EventRequests.Event(name,System.currentTimeMillis(),"/zzal?secret=x",Map.of("action","feed","email","private"),id,2,UUID.randomUUID().toString(),"real","room","authenticated",42L);
    }
    EventRequests.Batch batch(EventRequests.Event... rows) { return new EventRequests.Batch(null,null,List.of(rows)); }
    @Test void acknowledgesPersistedAndDuplicateIdsButRejectsReservedNames() {
        String id=UUID.randomUUID().toString(), reserved=UUID.randomUUID().toString();
        when(events.insertOnce(any())).thenReturn(1,0);
        var first=service.collectAcknowledged(batch(event("zzal_care",id)),"anon",42L,null);
        var second=service.collectAcknowledged(batch(event("zzal_care",id),event("zzal_provider_usage",reserved)),"anon",42L,null);
        assertThat(first.accepted_event_ids()).containsExactly(id);
        assertThat(first.persisted_count()).isEqualTo(1);
        assertThat(second.accepted_event_ids()).containsExactly(id);
        assertThat(second.duplicate_count()).isEqualTo(1);
        assertThat(second.rejected_event_ids()).containsExactly(reserved);
        var captor=org.mockito.ArgumentCaptor.forClass(AnalyticsEvent.class);
        verify(events,times(2)).insertOnce(captor.capture());
        assertThat(captor.getValue().getPath()).isEqualTo("/zzal");
        assertThat(captor.getValue().getProps()).doesNotContain("private","email");
        assertThat(captor.getValue().getUserId()).isEqualTo(42L);
    }
    @Test void queuedUserAEventCannotBeAttributedToCurrentUserB() {
        when(events.insertOnce(any())).thenReturn(1);
        service.collectAcknowledged(batch(event("zzal_care",UUID.randomUUID().toString())),"anon",99L,null);
        var captor=org.mockito.ArgumentCaptor.forClass(AnalyticsEvent.class);verify(events).insertOnce(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
        assertThat(captor.getValue().getIdentityStatus()).isEqualTo("mismatch");
    }
    @Test void offlineThreeDayRetryPreservesReportedAndOccurrenceTime() {
        long original=java.time.Instant.now().minus(java.time.Duration.ofDays(3)).toEpochMilli();
        var e=new EventRequests.Event("zzal_care",original,"/zzal",null,UUID.randomUUID().toString(),2,null,"real","room");
        when(events.insertOnce(any())).thenReturn(1);
        service.collectAcknowledged(batch(e),"anon",null,null);
        var captor=org.mockito.ArgumentCaptor.forClass(AnalyticsEvent.class);verify(events).insertOnce(captor.capture());
        assertThat(captor.getValue().getOccurredAt().toEpochMilli()).isEqualTo(original);
        assertThat(captor.getValue().getReportedTs()).isEqualTo(original);
        assertThat(captor.getValue().getTimestampQuality()).isEqualTo("delayed");
    }
    @Test void storageFailureNeverAcknowledges() {
        when(events.insertOnce(any())).thenThrow(new IllegalStateException("unavailable"));
        var resolver=mock(AnonIdResolver.class);
        when(resolver.resolve(any(),any())).thenReturn("anon");
        var response=new AnalyticsController(service,resolver).collect(batch(event("zzal_care",UUID.randomUUID().toString())),null,new MockHttpServletRequest(),new MockHttpServletResponse());
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().getData().accepted_event_ids()).isEmpty();
    }
    @Test void disabledDoesNotIssueIdentityOrAcknowledge() {
        var disabled=new AnalyticsService(events,mock(AnonIdentityRepository.class),false,50);
        var resolver=mock(AnonIdResolver.class);
        var response=new AnalyticsController(disabled,resolver).collect(batch(event("zzal_care",UUID.randomUUID().toString())),null,new MockHttpServletRequest(),new MockHttpServletResponse());
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        verifyNoInteractions(events,resolver);
    }
    @Test void rateLimitIsExplicitAndLegacyEventsStillPersist() {
        var batch=batch(new EventRequests.Event("zzal_care",null,"/zzal",null));
        for(int i=0;i<120;i++) assertThat(service.collectAcknowledged(batch,"one",null,null).status()).isEqualTo("persisted");
        assertThat(service.collectAcknowledged(batch,"one",null,null).status()).isEqualTo("rate_limited");
        verify(events,times(120)).save(any());
    }
}
