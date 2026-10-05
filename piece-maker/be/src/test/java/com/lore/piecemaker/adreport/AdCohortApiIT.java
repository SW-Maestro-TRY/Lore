package com.lore.piecemaker.adreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.piecemaker.support.PieceMakerIntegrationTest;
import com.lore.piecemaker.support.PieceMakerItSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@PieceMakerIntegrationTest
@TestPropertySource(properties = {"app.analytics.enabled=true",
        "lore.piece-maker.ad-report.excluded-user-ids=900000000001",
        "lore.piece-maker.ad-report.test-exclusions-reviewed=false"})
class AdCohortApiIT extends PieceMakerItSupport {
    private static final String URL = "/api/piece-maker/v1/admin/ad-cohort";
    private static final String CAMPAIGN = "cohort-it-selected";
    private static final Instant BASE = Instant.now().minus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

    @DynamicPropertySource
    static void verifiedFixtureInterval(DynamicPropertyRegistry registry) {
        registry.add("lore.piece-maker.ad-report.history-verified-from", () -> BASE.minus(30, ChronoUnit.DAYS).toString());
        registry.add("lore.piece-maker.ad-report.history-verified-through", () -> BASE.plus(6, ChronoUnit.DAYS).toString());
    }

    @Override
    protected Long newUserId() {
        Long id = super.newUserId();
        jdbc.update("update users set created_at = ? where id = ?", Timestamp.from(BASE.minusSeconds(3600)), id);
        return id;
    }

    @AfterEach
    void removeAnonymousTestLandings() {
        jdbc.update("delete from piece_maker_ad_landing where utm_campaign like 'cohort-it-%'");
    }

    @Test
    void anonymousAndNonAdminCannotRead() throws Exception {
        assertThat(status(request(null, CAMPAIGN, BASE, BASE.plusSeconds(3600), BASE.plusSeconds(7200)))).isEqualTo(401);
        assertThat(status(request(newUserId(), CAMPAIGN, BASE, BASE.plusSeconds(3600), BASE.plusSeconds(7200)))).isEqualTo(403);
    }

    @Test
    void malformedCampaignAndTimeRangesAre400() throws Exception {
        long admin = admin();
        for (String campaign : List.of("", "bad campaign", "x".repeat(65), "test' OR 1=1")) {
            assertThat(status(request(admin, campaign, BASE, BASE.plusSeconds(1), BASE.plusSeconds(2)))).isEqualTo(400);
        }
        for (Instant[] times : List.of(new Instant[]{BASE, BASE, BASE},
                new Instant[]{BASE, BASE.plus(32, ChronoUnit.DAYS), BASE.plus(33, ChronoUnit.DAYS)},
                new Instant[]{BASE, BASE.plusSeconds(2), BASE.plusSeconds(1)},
                new Instant[]{BASE, BASE.plusSeconds(1), Instant.now().plusSeconds(3600)})) {
            assertThat(status(request(admin, CAMPAIGN, times[0], times[1], times[2]))).isEqualTo(400);
        }
        assertThat(status(mockMvc.perform(get(URL).with(asUser(admin)).param("campaign", CAMPAIGN)
                .param("from", "bad").param("to", BASE.toString())).andReturn())).isEqualTo(400);
    }

    @Test
    void choosesGlobalFirstLandingBeforeCampaignAndRangeThenDeduplicatesAccounts() throws Exception {
        long sameCampaign = newUserId();
        landing(sameCampaign, CAMPAIGN, "11", "feed", BASE);
        landing(sameCampaign, CAMPAIGN, "12", "story", BASE.plusSeconds(10));
        viewed(sameCampaign, BASE.plusSeconds(20)); // 다른 기기의 열람도 계정 장부 하나로 연결된다.
        long earlierCampaign = newUserId();
        landing(earlierCampaign, "cohort-it-earlier", "21", "marketplace", BASE.minusSeconds(10));
        landing(earlierCampaign, CAMPAIGN, "22", "feed", BASE);
        viewed(earlierCampaign, BASE.plusSeconds(30));
        long earlierWindow = newUserId();
        landing(earlierWindow, CAMPAIGN, "31", "feed", BASE.minusSeconds(1));
        landing(earlierWindow, CAMPAIGN, "32", "feed", BASE.plusSeconds(1));

        JsonNode report = report(BASE.plusSeconds(86400));
        assertThat(report.at("/accounts/firstAttributed").asLong()).isEqualTo(1);
        assertThat(report.at("/accounts/completed").asLong()).isEqualTo(1);
        assertThat(report.path("creatives")).hasSize(1);
        assertThat(report.at("/creatives/0/adId").asText()).isEqualTo("11");
        assertThat(report.at("/creatives/0/placement").asText()).isEqualTo("feed");
        assertThat(report.path("provisional").asBoolean()).isTrue();
        assertThat(report.toString()).doesNotContain("email", "userId", "anonId", "requestKey");
    }

    @Test
    void firstLandingIntervalIsHalfOpenAndEqualTimeUsesUuidOrder() throws Exception {
        long user = newUserId();
        UUID first = landing(user, CAMPAIGN, "42", "story", BASE);
        UUID second = landing(user, CAMPAIGN, "41", "feed", BASE);
        UUID low = new UUID(0, 1);
        UUID high = new UUID(0, 2);
        jdbc.update("update piece_maker_ad_landing set id = ? where id = ?", low, first);
        jdbc.update("update piece_maker_ad_landing set id = ? where id = ?", high, second);
        landing(newUserId(), CAMPAIGN, "99", "feed", BASE.plusSeconds(3600));
        JsonNode report = report(BASE.plusSeconds(86400));
        assertThat(report.at("/accounts/firstAttributed").asLong()).isEqualTo(1);
        assertThat(report.at("/creatives/0/adId").asText()).isEqualTo("42");
    }

    @Test
    void twentyFourHourBoundaryIsInclusiveAndFutureViewsDoNotComplete() throws Exception {
        long atBoundary = newUserId();
        landing(atBoundary, CAMPAIGN, "1", "feed", BASE);
        viewed(atBoundary, BASE.plusSeconds(86400));
        long tooLate = newUserId();
        landing(tooLate, CAMPAIGN, "1", "feed", BASE);
        viewed(tooLate, BASE.plusSeconds(86401));
        long noView = newUserId();
        landing(noView, CAMPAIGN, "2", "story", BASE);
        long laterLanding = newUserId();
        landing(laterLanding, CAMPAIGN, "2", "story", BASE.plusSeconds(60));
        viewed(laterLanding, BASE.plusSeconds(86430));
        JsonNode early = report(BASE.plusSeconds(86399));
        assertThat(early.at("/accounts/completed").asLong()).isZero();
        assertThat(early.at("/accounts/pending").asLong()).isEqualTo(4);
        JsonNode boundary = report(BASE.plusSeconds(86400));
        assertThat(boundary.at("/accounts/completed").asLong()).isEqualTo(1);
        assertThat(boundary.at("/accounts/pending").asLong()).isEqualTo(1);
        assertThat(boundary.at("/accounts/noCompletion").asLong()).isEqualTo(2);
        JsonNode finalReport = report(BASE.plusSeconds(86500));
        assertThat(finalReport.at("/accounts/completed").asLong()).isEqualTo(2);
        assertThat(finalReport.at("/accounts/noCompletion").asLong()).isEqualTo(2);
        assertThat(finalReport.at("/accounts/pending").asLong()).isZero();
    }

    @Test
    void excludesPriorAcceptedSubmissionsEvenPendingOrFailedAndPriorViewWithoutHypothesis() throws Exception {
        for (String status : List.of("PENDING", "FAILED")) {
            long user = newUserId();
            landing(user, CAMPAIGN, "1", "feed", BASE);
            submitted(user, BASE.minusSeconds(1), status);
            viewed(user, BASE.plusSeconds(1));
        }
        long oldView = newUserId();
        landing(oldView, CAMPAIGN, "1", "feed", BASE);
        viewed(oldView, BASE.minusSeconds(1)); // 가설 FK가 NULL이어도 이전 사용자를 제외한다.
        long submittedAfter = newUserId();
        landing(submittedAfter, CAMPAIGN, "1", "feed", BASE);
        submitted(submittedAfter, BASE, "PENDING");
        viewed(submittedAfter, BASE.plusSeconds(1));
        JsonNode report = report(BASE.plusSeconds(86400));
        assertThat(excluded(report, "PRIOR_SUBMISSION")).isEqualTo(2);
        assertThat(excluded(report, "PRIOR_NORMAL_VIEW")).isEqualTo(1);
        assertThat(report.at("/accounts/eligible").asLong()).isEqualTo(1);
    }

    @Test
    void excludesAdminsInactiveAndConfiguredTestAccountsWithDisjointReasons() throws Exception {
        long operator = newUserId();
        makeAdmin(operator);
        landing(operator, CAMPAIGN, "1", "feed", BASE);
        submitted(operator, BASE.minusSeconds(1), "PENDING"); // ADMIN 사유만 센다.
        long withdrawn = newUserId();
        landing(withdrawn, CAMPAIGN, "1", "feed", BASE);
        jdbc.update("update users set status = 'DELETED', deleted_at = now() where id = ?", withdrawn);
        jdbc.update("""
                insert into users (id,email,status,role,pet_slots,created_at,updated_at)
                values (900000000001,'piece-maker-it-cohort-fixed@example.invalid','ACTIVE','USER',1,now(),now())
                """);
        landing(900000000001L, CAMPAIGN, "1", "feed", BASE);
        JsonNode report = report(BASE.plusSeconds(86400));
        assertThat(excluded(report, "ADMIN")).isEqualTo(1);
        assertThat(excluded(report, "INACTIVE")).isEqualTo(1);
        assertThat(excluded(report, "TEST_ACCOUNT")).isEqualTo(1);
        assertThat(excluded(report, "PRIOR_SUBMISSION")).isZero();
        assertThat(report.at("/accounts/excluded").asLong()).isEqualTo(3);
        assertThat(report.at("/accounts/eligible").asLong()).isZero();
    }

    @Test
    void asOfRespectsClaimAndAmbiguityKnowledgeAndReportsLandingCountsSeparately() throws Exception {
        long lateClaim = newUserId();
        UUID claimed = landing(lateClaim, CAMPAIGN, "1", "feed", BASE);
        jdbc.update("update piece_maker_ad_landing set claimed_at = ? where id = ?", Timestamp.from(BASE.plusSeconds(7200)), claimed);
        long conflict = newUserId();
        landing(conflict, CAMPAIGN, "1", "feed", BASE);
        UUID conflicting = landing(conflict, "cohort-it-another", "2", "story", BASE.plusSeconds(1));
        jdbc.update("update piece_maker_ad_landing set ambiguous = true, ambiguous_at = ? where id = ?",
                Timestamp.from(BASE.plusSeconds(7200)), conflicting);
        landing(null, CAMPAIGN, "3", "marketplace", BASE);
        UUID unclaimedAmbiguous = landing(null, CAMPAIGN, "3", "marketplace", BASE);
        jdbc.update("update piece_maker_ad_landing set ambiguous = true, ambiguous_at = ? where id = ?",
                Timestamp.from(BASE.plusSeconds(1)), unclaimedAmbiguous);

        JsonNode early = report(BASE.plusSeconds(3600));
        assertThat(early.at("/accounts/firstAttributed").asLong()).isEqualTo(1);
        assertThat(early.at("/accounts/eligible").asLong()).isEqualTo(1);
        assertThat(early.at("/receipts/unclaimedLandings").asLong()).isEqualTo(3);
        assertThat(early.at("/receipts/ambiguousLandings").asLong()).isEqualTo(1);
        JsonNode later = report(BASE.plusSeconds(7200));
        assertThat(later.at("/accounts/firstAttributed").asLong()).isEqualTo(2);
        assertThat(excluded(later, "AMBIGUOUS_IDENTITY")).isEqualTo(1);
        assertThat(later.at("/receipts/unclaimedLandings").asLong()).isEqualTo(2);
    }

    @Test
    void reportDoesNotWriteAnyRowsAndDefaultAsOfIsCurrent() throws Exception {
        long owner = newUserId();
        landing(owner, CAMPAIGN, "1", "feed", BASE);
        long countBefore = jdbc.queryForObject("select count(*) from piece_maker_ad_landing", Long.class);
        JsonNode report = data(mockMvc.perform(get(URL).with(asUser(admin())).param("campaign", CAMPAIGN)
                .param("from", BASE.toString()).param("to", BASE.plusSeconds(3600).toString())).andReturn());
        assertThat(Instant.parse(report.path("asOf").asText())).isAfter(BASE.plus(1, ChronoUnit.DAYS));
        assertThat(jdbc.queryForObject("select count(*) from piece_maker_ad_landing", Long.class)).isEqualTo(countBefore);
        assertThat(report.path("collectionEnabled").asBoolean()).isTrue();
    }

    @Test
    void oldAccountsAndAccountsBeforeVerifiedCollectionAreNotNewAfterHistoryExpires() throws Exception {
        for (long days : List.of(400L, 40L)) {
            long user = newUserId();
            jdbc.update("update users set created_at = ? where id = ?", Timestamp.from(BASE.minus(days, ChronoUnit.DAYS)), user);
            landing(user, CAMPAIGN, "1", "feed", BASE);
            viewed(user, BASE.plusSeconds(1));
        }
        var report = report(BASE.plusSeconds(90000));
        assertThat(excluded(report, "HISTORY_UNVERIFIABLE")).isEqualTo(2);
        assertThat(report.at("/accounts/completed").asLong()).isZero();
    }

    @Test
    void deletingPriorSubmissionViewOrLandingNeverTurnsExistingUseIntoNewUse() throws Exception {
        for (String kind : List.of("submission", "view", "landing")) {
            long user = newUserId();
            switch (kind) {
                case "submission" -> {
                    submitted(user, BASE.minusSeconds(60), "PENDING");
                    jdbc.update("delete from hypotheses where user_id = ?", user);
                }
                case "view" -> {
                    viewed(user, BASE.minusSeconds(60));
                    jdbc.update("delete from piece_maker_first_result_view where user_id = ?", user);
                }
                case "landing" -> {
                    UUID first = landing(user, "cohort-it-old", "1", "feed", BASE.minusSeconds(60));
                    jdbc.update("delete from piece_maker_ad_landing where id = ?", first);
                }
            }
            landing(user, CAMPAIGN, "2", "story", BASE);
            viewed(user, BASE.plusSeconds(1));
        }
        var report = report(BASE.plusSeconds(90000));
        assertThat(excluded(report, "HISTORY_UNVERIFIABLE")).isEqualTo(3);
        assertThat(report.at("/accounts/completed").asLong()).isZero();
    }

    @Test
    void collectionGapCannotBeCountedAndHistoricalAsOfCannotRestoreExpiredData() throws Exception {
        long user = newUserId();
        landing(user, CAMPAIGN, "1", "feed", BASE);
        viewed(user, BASE.plusSeconds(1));
        var unverified = report(BASE.plus(6, ChronoUnit.DAYS).plusSeconds(1));
        assertThat(unverified.at("/history/requestedWindowCovered").asBoolean()).isFalse();
        assertThat(excluded(unverified, "HISTORY_UNVERIFIABLE")).isEqualTo(1);
        assertThat(unverified.path("provisional").asBoolean()).isTrue();
        var old = Instant.now().minus(365, ChronoUnit.DAYS);
        assertThat(status(request(admin(), CAMPAIGN, old, old.plusSeconds(3600), old.plusSeconds(90000)))).isEqualTo(400);
    }

    private long admin() {
        long id = newUserId();
        makeAdmin(id);
        return id;
    }

    private JsonNode report(Instant asOf) throws Exception {
        return data(request(admin(), CAMPAIGN, BASE, BASE.plusSeconds(3600), asOf));
    }

    private MvcResult request(Long user, String campaign, Instant from, Instant to, Instant asOf) throws Exception {
        var request = get(URL).param("campaign", campaign).param("from", from.toString())
                .param("to", to.toString()).param("asOf", asOf.toString());
        if (user != null) request.with(asUser(user));
        return mockMvc.perform(request).andReturn();
    }

    private UUID landing(Long user, String campaign, String ad, String placement, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into piece_maker_ad_landing (id,anon_id,request_key,owner_user_id,landed_at,client_landed_at,
                    utm_source,utm_medium,utm_campaign,utm_content,placement,claimed_at)
                values (?,?,?,?,?,?,'facebook','paid_social',?,?,?,?)
                """, id, UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID(), user,
                Timestamp.from(at), Timestamp.from(at.minusSeconds(2)), campaign, ad, placement,
                user == null ? null : Timestamp.from(at));
        return id;
    }

    private void viewed(long user, Instant at) {
        jdbc.update("insert into piece_maker_first_result_view (user_id,hypothesis_id,viewed_at) values (?,null,?)",
                user, Timestamp.from(at));
    }

    private void submitted(long user, Instant at, String status) {
        jdbc.update("""
                insert into hypotheses (user_id,chapter,title,claim,state_digest,cards_digest,judgement_status,
                    failure_message,judged_at,created_at)
                values (?,400,'시험 가설','시험 주장',?,?,?,?,?,?)
                """, user, "a".repeat(64), "b".repeat(64), status, "FAILED".equals(status) ? "실패" : null,
                "FAILED".equals(status) ? Timestamp.from(at) : null, Timestamp.from(at));
    }

    private long excluded(JsonNode report, String reason) {
        for (JsonNode exclusion : report.path("exclusions")) {
            if (reason.equals(exclusion.path("reason").asText())) return exclusion.path("accounts").asLong();
        }
        throw new AssertionError("missing exclusion reason: " + reason);
    }
}
