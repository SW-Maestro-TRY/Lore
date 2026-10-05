package com.lore.piecemaker.adlanding;

import com.lore.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AdLandingServiceTest {
    private final AdLandingRepository repository = mock(AdLandingRepository.class);

    @Test
    void rejectsIncompleteOrUnboundedSourceBeforeStorage() {
        long now = Instant.now().toEpochMilli();
        for (var input : List.of(
                attr("instagram", "paid_social", "launch", "123", "feed", now),
                attr("facebook", "organic", "launch", "123", "feed", now),
                attr("facebook", "paid_social", null, "123", "feed", now),
                attr("facebook", "paid_social", "", "123", "feed", now),
                attr("facebook", "paid_social", "x".repeat(65), "123", "feed", now),
                attr("facebook", "paid_social", "reader@example.com", "123", "feed", now),
                attr("facebook", "paid_social", "{{campaign.name}}", "123", "feed", now),
                attr("facebook", "paid_social", "launch", "{{ad.id}}", "feed", now),
                attr("facebook", "paid_social", "launch", null, "feed", now),
                attr("facebook", "paid_social", "launch", "123x", "feed", now),
                attr("facebook", "paid_social", "launch", "1".repeat(65), "feed", now),
                attr("facebook", "paid_social", "launch", "123", " ", now),
                attr("facebook", "paid_social", "launch", "123", null, now),
                attr("facebook", "paid_social", "launch", "123", "{{placement}}", now),
                attr("facebook", "paid_social", "launch", "123", "https://example.com", now),
                attr("facebook", "paid_social", "launch", "123", "feed", null),
                attr("facebook", "paid_social", "launch", "123", "feed", 0L),
                attr("facebook", "paid_social", "launch", "123", "feed", -1L),
                attr("facebook", "paid_social", "launch", "123", "feed", now + 600_000),
                attr("facebook", "paid_social", "launch", "123", "feed", now - 365L * 86_400_000),
                attr("facebook", "paid_social", "launch", "123", "feed", Long.MAX_VALUE))) {
            assertThatThrownBy(() -> new AdLandingService(repository, true).capture("a", null,
                    new AdLandingRequests.Capture(UUID.randomUUID(), null, input)))
                    .isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void disabledCaptureAndClaimNeverReadOrWrite() {
        var service = new AdLandingService(repository, false);
        assertThatThrownBy(() -> service.capture("a", null, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.claim("a", 1L, UUID.randomUUID().toString(), 1L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void expectedAccountMismatchNeverReadsOrChangesLanding() {
        var service = new AdLandingService(repository, true);
        var input = new AdLandingRequests.Capture(UUID.randomUUID(), 1L,
                attr("facebook", "paid_social", "launch", "123", "feed", Instant.now().toEpochMilli()));
        assertThatThrownBy(() -> service.capture("a", 2L, input)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.claim("a", 2L, UUID.randomUUID().toString(), 1L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void cookieRateLimitBoundsRepeatedCapture() {
        var a = attr("facebook", "paid_social", "launch", "123", "feed", Instant.now().toEpochMilli());
        UUID key = UUID.randomUUID();
        var input = new AdLandingRequests.Capture(key, null, a);
        when(repository.lockRequest("a", key)).thenReturn(new AdLandingRepository.Landing(UUID.randomUUID(), null, Instant.now(), a, false));
        var service = new AdLandingService(repository, true);
        for (int n = 0; n < 60; n++) service.capture("a", null, input);
        assertThatThrownBy(() -> service.capture("a", null, input)).isInstanceOf(BusinessException.class);
        verify(repository, times(60)).insert("a", null, input);
    }

    private AdLandingRequests.Attribution attr(String source, String medium, String campaign, String content, String placement, Long at) {
        return new AdLandingRequests.Attribution(source, medium, campaign, content, placement, at);
    }
}
