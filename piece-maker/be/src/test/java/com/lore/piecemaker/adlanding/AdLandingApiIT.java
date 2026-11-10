package com.lore.piecemaker.adlanding;

import com.lore.common.analytics.AnonIdResolver;
import com.lore.piecemaker.support.PieceMakerIntegrationTest;
import com.lore.piecemaker.support.PieceMakerItSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@PieceMakerIntegrationTest
@TestPropertySource(properties = "app.analytics.enabled=true")
class AdLandingApiIT extends PieceMakerItSupport {
    private static final long CLIENT_AT = Instant.now().minusSeconds(60).toEpochMilli();
    private static final String PATH = "/api/piece-maker/v1/ad-landings";

    @AfterEach
    void clearLandings() { jdbc.update("delete from piece_maker_ad_landing"); }

    @Test
    void expiredReceiptCannotBeClaimedOrReissuedByAnOldRetry() throws Exception {
        Cookie anon = cookie();
        long user = newUserId();
        var input = input(UUID.randomUUID(), null, "launch");
        var first = data(capture(anon, null, input));
        jdbc.update("update piece_maker_ad_landing set landed_at = now() - interval '365 days'");
        assertThat(status(claim(anon, user, first.path("landingId").asText(), user))).isEqualTo(404);
        assertThat(status(capture(anon, null, input))).isEqualTo(404);
        assertThat(jdbc.queryForObject("select owner_user_id from piece_maker_ad_landing", Long.class)).isNull();
    }

    @Test
    void anonymousCaptureIssuesCookieAndUsesServerTime() throws Exception {
        Instant before = dbNow();
        Map<String,Object> input = input(UUID.randomUUID(), null, "launch");
        var response = capture(null, null, input);
        var result = data(response);
        assertThat(response.getResponse().getHeader("Set-Cookie")).contains("lore_anon_id=").contains("HttpOnly");
        assertThat(Instant.parse(result.path("landedAt").asText())).isBetween(before, dbNow());
        assertThat(countLandings()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select owner_user_id from piece_maker_ad_landing", Long.class)).isNull();
    }

    @Test
    void captureRetryPreservesReceiptSourceAndTime() throws Exception {
        Cookie anon = cookie();
        UUID request = UUID.randomUUID();
        Map<String,Object> input = input(request, null, "launch");
        var first = data(capture(anon, null, input));
        assertThat(data(capture(anon, null, input))).isEqualTo(first);
        var changed = capture(anon, null, input(request, null, "other"));
        assertThat(status(changed)).isEqualTo(409);
        assertThat(countLandings()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select utm_campaign from piece_maker_ad_landing", String.class)).isEqualTo("launch");
    }

    @Test
    void authenticatedCaptureBindsImmediatelyAndClaimIsIdempotent() throws Exception {
        long owner = newUserId();
        Cookie anon = cookie();
        var first = data(capture(anon, owner, input(UUID.randomUUID(), owner, "launch")));
        String id = first.path("landingId").asText();
        var claimedAt = jdbc.queryForObject("select claimed_at from piece_maker_ad_landing", java.sql.Timestamp.class);
        for (int i = 0; i < 2; i++) {
            var claim = data(claim(anon, owner, id, owner));
            assertThat(claim.path("linked").asBoolean()).isTrue();
            assertThat(claim.path("landedAt")).isEqualTo(first.path("landedAt"));
        }
        assertThat(jdbc.queryForObject("select owner_user_id from piece_maker_ad_landing", Long.class)).isEqualTo(owner);
        assertThat(jdbc.queryForObject("select claimed_at from piece_maker_ad_landing", java.sql.Timestamp.class)).isEqualTo(claimedAt);
    }

    @Test
    void lostAnonymousResponseCanBeRecoveredAfterLoginWithoutChangingReceipt() throws Exception {
        Cookie anon = cookie();
        UUID key = UUID.randomUUID();
        var original = data(capture(anon, null, input(key, null, "launch")));
        long owner = newUserId();
        var recovered = data(capture(anon, owner, input(key, owner, "launch")));
        assertThat(recovered).isEqualTo(original);
        assertThat(jdbc.queryForObject("select owner_user_id from piece_maker_ad_landing", Long.class)).isNull();
        data(claim(anon, owner, recovered.path("landingId").asText(), owner));
        assertThat(jdbc.queryForObject("select owner_user_id from piece_maker_ad_landing", Long.class)).isEqualTo(owner);
        assertThat(countLandings()).isEqualTo(1);
    }

    @Test
    void anonymousReceiptCanBeClaimedExactlyOnceAndConflictPersists() throws Exception {
        long owner = newUserId();
        long stranger = newUserId();
        Cookie anon = cookie();
        String id = data(capture(anon, null, input(UUID.randomUUID(), null, "launch"))).path("landingId").asText();
        data(claim(anon, owner, id, owner));
        assertThat(status(claim(anon, stranger, id, stranger))).isEqualTo(409);
        assertThat(jdbc.queryForObject("select owner_user_id from piece_maker_ad_landing", Long.class)).isEqualTo(owner);
        assertThat(jdbc.queryForObject("select ambiguous from piece_maker_ad_landing", Boolean.class)).isTrue();
        var firstConflict = jdbc.queryForObject("select ambiguous_at from piece_maker_ad_landing", java.sql.Timestamp.class);
        assertThat(firstConflict).isNotNull();
        assertThat(status(claim(anon, stranger, id, stranger))).isEqualTo(409);
        assertThat(jdbc.queryForObject("select ambiguous_at from piece_maker_ad_landing", java.sql.Timestamp.class)).isEqualTo(firstConflict);
        assertThat(status(claim(anon, owner, id, owner))).isEqualTo(409);
    }

    @Test
    void wrongCookieMissingCookieAndAccountRaceDoNotClaimOrIssueCookie() throws Exception {
        long owner = newUserId();
        long stranger = newUserId();
        Cookie anon = cookie();
        String id = data(capture(anon, null, input(UUID.randomUUID(), null, "launch"))).path("landingId").asText();
        for (Cookie supplied : Arrays.asList(cookie(), null, new Cookie(AnonIdResolver.COOKIE, "invalid-shape"))) {
            var result = claim(supplied, owner, id, owner);
            assertThat(status(result)).isEqualTo(404);
            assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        }
        assertThat(status(claim(anon, stranger, id, owner))).isEqualTo(409);
        assertThat(jdbc.queryForObject("select owner_user_id from piece_maker_ad_landing", Long.class)).isNull();
        assertThat(jdbc.queryForObject("select ambiguous from piece_maker_ad_landing", Boolean.class)).isFalse();
        var racedCapture = capture(null, stranger, input(UUID.randomUUID(), owner, "launch"));
        assertThat(status(racedCapture)).isEqualTo(409);
        assertThat(racedCapture.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(countLandings()).isEqualTo(1);
    }

    @Test
    void concurrentCaptureAndClaimsKeepOneReceiptAndOwner() throws Exception {
        Cookie anon = cookie();
        var input = input(UUID.randomUUID(), null, "launch");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> requests = new ArrayList<>();
        try (var workers = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) requests.add(workers.submit(() -> { start.await(); return capture(anon, null, input); }));
            start.countDown();
            Set<String> ids = new HashSet<>();
            for (var request : requests) ids.add(data(request.get(20, TimeUnit.SECONDS)).path("landingId").asText());
            assertThat(ids).hasSize(1);
        }
        assertThat(countLandings()).isEqualTo(1);
        long owner = newUserId();
        String id = jdbc.queryForObject("select id::text from piece_maker_ad_landing", String.class);
        CountDownLatch claimStart = new CountDownLatch(1);
        requests.clear();
        try (var workers = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) requests.add(workers.submit(() -> { claimStart.await(); return claim(anon, owner, id, owner); }));
            claimStart.countDown();
            for (var request : requests) assertThat(data(request.get(20, TimeUnit.SECONDS)).path("linked").asBoolean()).isTrue();
        }
        assertThat(jdbc.queryForObject("select owner_user_id from piece_maker_ad_landing", Long.class)).isEqualTo(owner);
    }

    @Test
    void concurrentDifferentAccountsLeaveOneOwnerAndCommittedAmbiguity() throws Exception {
        Cookie anon = cookie();
        String id = data(capture(anon, null, input(UUID.randomUUID(), null, "launch"))).path("landingId").asText();
        long first = newUserId();
        long second = newUserId();
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { start.await(); return claim(anon, first, id, first); });
            var b = workers.submit(() -> { start.await(); return claim(anon, second, id, second); });
            start.countDown();
            assertThat(List.of(status(a.get(20, TimeUnit.SECONDS)), status(b.get(20, TimeUnit.SECONDS))))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("select owner_user_id from piece_maker_ad_landing", Long.class)).isIn(first, second);
        assertThat(jdbc.queryForObject("select ambiguous from piece_maker_ad_landing", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select ambiguous_at from piece_maker_ad_landing", java.sql.Timestamp.class)).isNotNull();
    }

    @Test
    void invalidPayloadAnonymousClaimAndOversizeDoNotSave() throws Exception {
        var malformed = input(UUID.randomUUID(), null, "launch");
        @SuppressWarnings("unchecked") var attr = (Map<String,Object>) malformed.get("attribution");
        attr.put("utmContent", "{{ad.id}}");
        assertThat(status(capture(null, null, malformed))).isEqualTo(400);
        assertThat(status(claim(cookie(), null, UUID.randomUUID().toString(), 1L))).isEqualTo(401);
        var large = mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("x".repeat(4097))).andReturn();
        assertThat(status(large)).isEqualTo(400);
        assertThat(countLandings()).isZero();
    }

    @Test
    void encodedPathReachesSameEndpointButCannotBypassBodyLimit() throws Exception {
        URI encoded = URI.create(PATH.replace("landings", "landing%73"));
        String small = json.writeValueAsString(input(UUID.randomUUID(), null, "launch"));
        var reached = mockMvc.perform(post(encoded).contentType(MediaType.APPLICATION_JSON).content(small)).andReturn();
        assertThat(status(reached)).isEqualTo(200);
        var rejected = mockMvc.perform(post(encoded).contentType(MediaType.APPLICATION_JSON)
                .content(small + " ".repeat(4097))).andReturn();
        assertThat(status(rejected)).isEqualTo(400);
        assertThat(errorMessage(rejected)).contains("너무 큽니다");
        assertThat(countLandings()).isEqualTo(1);
    }

    @Test
    void databaseFailureIsNotAcknowledgedAndRetrySucceeds() throws Exception {
        Cookie anon = cookie();
        var input = input(UUID.randomUUID(), null, "launch");
        jdbc.execute("alter table piece_maker_ad_landing add constraint ad_landing_it_reject check (false) not valid");
        try {
            assertThat(status(capture(anon, null, input))).isEqualTo(500);
            assertThat(countLandings()).isZero();
        } finally { jdbc.execute("alter table piece_maker_ad_landing drop constraint ad_landing_it_reject"); }
        data(capture(anon, null, input));
        assertThat(countLandings()).isEqualTo(1);
    }

    @Test
    void accountDeletionRemovesBoundReceipt() throws Exception {
        long owner = newUserId();
        data(capture(cookie(), owner, input(UUID.randomUUID(), owner, "launch")));
        jdbc.update("delete from users where id = ?", owner);
        assertThat(countLandings()).isZero();
    }

    private Map<String,Object> input(UUID key, Long expected, String campaign) {
        Map<String,Object> body = new HashMap<>();
        body.put("requestKey", key.toString()); body.put("expectedUserId", expected);
        body.put("attribution", new HashMap<>(Map.of("utmSource", "facebook", "utmMedium", "paid_social",
                "utmCampaign", campaign, "utmContent", "123", "placement", "facebook_feed", "firstAdLandedAt", CLIENT_AT)));
        return body;
    }
    private Cookie cookie() { return new Cookie(AnonIdResolver.COOKIE, UUID.randomUUID().toString().replace("-", "")); }
    private long countLandings() { return jdbc.queryForObject("select count(*) from piece_maker_ad_landing", Long.class); }
    private MvcResult capture(Cookie cookie, Long user, Map<String,Object> input) throws Exception {
        var request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input));
        if (cookie != null) request.cookie(cookie);
        if (user != null) request.with(asUser(user));
        return mockMvc.perform(request).andReturn();
    }
    private MvcResult claim(Cookie cookie, Long user, String id, long expected) throws Exception {
        var request = post(PATH + "/" + id + "/claim").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedUserId", expected)));
        if (cookie != null) request.cookie(cookie);
        if (user != null) request.with(asUser(user));
        return mockMvc.perform(request).andReturn();
    }
    private Instant dbNow() {
        return jdbc.queryForObject("select clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
    }

}
