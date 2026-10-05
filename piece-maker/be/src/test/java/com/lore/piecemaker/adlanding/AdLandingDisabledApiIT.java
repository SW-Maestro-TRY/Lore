package com.lore.piecemaker.adlanding;

import com.lore.piecemaker.support.PieceMakerIntegrationTest;
import com.lore.piecemaker.support.PieceMakerItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@PieceMakerIntegrationTest
@TestPropertySource(properties = "app.analytics.enabled=false")
class AdLandingDisabledApiIT extends PieceMakerItSupport {
    @Test
    void disabledCaptureAndClaimDoNotIssueCookieOrPersist() throws Exception {
        long owner = newUserId();
        var input = Map.of("requestKey", UUID.randomUUID().toString(), "attribution", Map.of(
                "utmSource", "facebook", "utmMedium", "paid_social", "utmCampaign", "launch", "utmContent", "123",
                "placement", "facebook_feed", "firstAdLandedAt", 1_700_000_000_000L));
        var capture = mockMvc.perform(post("/api/piece-maker/v1/ad-landings").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input))).andReturn();
        var claim = postJson(owner, "/api/piece-maker/v1/ad-landings/" + UUID.randomUUID() + "/claim", Map.of("expectedUserId", owner));
        for (var result : new org.springframework.test.web.servlet.MvcResult[]{capture, claim}) {
            assertThat(status(result)).isEqualTo(409);
            assertThat(errorCode(result)).isEqualTo("PIECE_MAKER_AD_MEASUREMENT_DISABLED");
            assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        }
        assertThat(jdbc.queryForObject("select count(*) from piece_maker_ad_landing", Long.class)).isZero();
    }
}
