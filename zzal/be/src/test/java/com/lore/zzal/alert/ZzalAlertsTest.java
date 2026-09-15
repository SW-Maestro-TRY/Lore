package com.lore.zzal.alert;

import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import com.lore.zzal.pet.ZzalRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 운영 경보 넷 — <b>언제 보내고, 언제 안 보내는가</b>.
 *
 * <h3>★★ 이 시험이 지키는 것</h3>
 * <ul>
 *   <li>임계를 넘으면 보낸다 — 그런데 <b>같은 임계로는 두 번 안 보낸다</b>(재시작 흉내 포함)</li>
 *   <li>스위치가 꺼져 있거나 받는 주소가 없으면 <b>아무 일도 안 한다</b>(원장도 안 건드린다)</li>
 *   <li>발송이 터져도 <b>예외가 부르는 쪽으로 안 나간다</b></li>
 * </ul>
 *
 * <h3>★ 메일은 실제로 안 나간다</h3>
 * {@link FakeAlertMailer} 를 끼운다 — 설정이 아니라 타입으로 막는다.
 */
@DisplayName("운영 경보 — 돈과 고장을 제때, 한 번만")
class ZzalAlertsTest {

    /** 2026-09-18(KST) 낮 12시. 날짜 경계를 만들 기준. */
    private static final Instant NOON = LocalDate.of(2026, 9, 18)
            .atTime(12, 0).atZone(ZzalRules.ZONE).toInstant();

    private FakeAlertMailer mailer;
    private MemoryAlertLedger ledger;
    private GenJobRepository jobs;
    private ZzalPetRepository pets;

    @BeforeEach
    void setUp() {
        mailer = new FakeAlertMailer();
        ledger = new MemoryAlertLedger();
        jobs = mock(GenJobRepository.class);
        pets = mock(ZzalPetRepository.class);
    }

    /** 기본값대로 켠 경보(받는 사람 하나 · $10 단위 · 3연속). */
    private ZzalAlerts alerts() {
        return alertsWith(new AlertSettings(true, "ops@example.invalid", 10, 3));
    }

    private ZzalAlerts alertsWith(AlertSettings settings) {
        return new ZzalAlerts(settings, mailer, ledger, jobs, pets);
    }

    private void spent(String total) {
        when(jobs.sumAllCost()).thenReturn(new BigDecimal(total));
    }

    // ══ 1. 돈 ═════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("누적 비용 $10 단위")
    class Cost {

        @Test
        @DisplayName("★ 임계를 넘으면 보낸다 — 제목에 금액이 있어 열지 않고도 안다")
        void sendsWhenTheThresholdIsCrossed() {
            spent("10.4210");
            alerts().generationFinished(NOON);

            assertThat(mailer.count()).isEqualTo(1);
            assertThat(mailer.lastSubject()).contains(ZzalAlerts.PREFIX).contains("$10");
            assertThat(ledger.rows).containsEntry(AlertKeys.COST, "10");
        }

        @Test
        @DisplayName("★ 아직 첫 임계 전이면 아무 일도 없다")
        void staysQuietBelowTheFirstThreshold() {
            spent("9.9999");
            alerts().generationFinished(NOON);

            assertThat(mailer.count()).isZero();
            assertThat(ledger.rows).isEmpty();
        }

        @Test
        @DisplayName("★★ 같은 임계로 두 번 안 보낸다 — 굽기가 계속 끝나도 한 통뿐")
        void neverSendsTheSameThresholdTwice() {
            spent("12.00");
            ZzalAlerts alerts = alerts();
            alerts.generationFinished(NOON);
            alerts.generationFinished(NOON);
            alerts.generationFinished(NOON);

            assertThat(mailer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("★★ 재시작해도 다시 안 보낸다 — 기억이 표에 있지 메모리에 없다")
        void survivesARestart() {
            spent("12.00");
            alerts().generationFinished(NOON);
            assertThat(mailer.count()).isEqualTo(1);

            // 재시작 흉내 — 원장(표)은 그대로 두고 경보만 새로 만든다.
            alerts().generationFinished(NOON);

            assertThat(mailer.count()).as("배포할 때마다 같은 메일이 오면 아무도 안 읽는다").isEqualTo(1);
        }

        @Test
        @DisplayName("★ 다음 단위를 넘으면 다시 보낸다")
        void sendsAgainAtTheNextStep() {
            spent("12.00");
            alerts().generationFinished(NOON);
            spent("21.50");
            alerts().generationFinished(NOON);

            assertThat(mailer.count()).isEqualTo(2);
            assertThat(mailer.lastSubject()).contains("$20");
        }

        @Test
        @DisplayName("★ 한 번에 두 단위를 건너뛰어도 한 통 — 가장 높은 단위로")
        void oneMailEvenWhenTwoStepsAreCrossedAtOnce() {
            spent("25.00");
            alerts().generationFinished(NOON);

            assertThat(mailer.count()).isEqualTo(1);
            assertThat(mailer.lastSubject()).contains("$20");
        }

        @Test
        @DisplayName("★ 합계가 뒤로 가도 이미 알린 금액을 다시 안 보낸다")
        void neverGoesBackwards() {
            spent("31.00");
            alerts().generationFinished(NOON);
            mailer.clear();

            spent("22.00");                       // 단계 기록이 지워져 합계가 내려간 경우
            alerts().generationFinished(NOON);

            assertThat(mailer.count()).isZero();
        }

        @Test
        @DisplayName("★ 본문에 부화·심화·오늘이 갈려 있다 — 어디로 새는지 바로 보이게")
        void theBodyBreaksTheNumbersDown() {
            spent("30.00");
            when(jobs.sumCostByKind(GenKind.HATCH)).thenReturn(new BigDecimal("20.0000"));
            when(jobs.sumCostByKind(GenKind.MOTION)).thenReturn(new BigDecimal("10.0000"));
            when(jobs.sumCostSince(any())).thenReturn(new BigDecimal("7.5000"));

            alerts().generationFinished(NOON);

            assertThat(mailer.lastBody())
                    .contains("$30.00").contains("부화 $20.00").contains("심화 $10.00").contains("$7.50");
        }

        @Test
        @DisplayName("★ cost-step-usd 가 0 이하면 비용 경보만 꺼진다")
        void zeroStepTurnsOnlyThisAlertOff() {
            spent("99.00");
            alertsWith(new AlertSettings(true, "ops@example.invalid", 0, 3)).generationFinished(NOON);

            assertThat(mailer.count()).isZero();
        }
    }

    // ══ 2. 부화 연속 실패 ══════════════════════════════════════════════════

    @Nested
    @DisplayName("부화 연속 실패")
    class HatchStreak {

        @Test
        @DisplayName("★★ 3연속이면 보낸다 — 그림 탓이 아니라 굽기가 막힌 모양")
        void sendsOnTheThirdInARow() {
            outcomes(PetPhase.FAILED, PetPhase.FAILED, PetPhase.FAILED, PetPhase.ALIVE);
            alerts().hatchFinallyFailed(30L, NOON);

            assertThat(mailer.count()).isEqualTo(1);
            assertThat(mailer.lastSubject()).contains("3연속");
        }

        @Test
        @DisplayName("★ 2연속이면 안 보낸다 — 실패 하나는 평상시에도 난다")
        void quietOnTwo() {
            outcomes(PetPhase.FAILED, PetPhase.FAILED, PetPhase.ALIVE);
            alerts().hatchFinallyFailed(30L, NOON);

            assertThat(mailer.count()).isZero();
        }

        @Test
        @DisplayName("★★ 한 구간에 한 통 — 4번째·5번째가 이어져도 더 안 온다")
        void oneMailPerStreak() {
            outcomes(PetPhase.FAILED, PetPhase.FAILED, PetPhase.FAILED, PetPhase.ALIVE);
            alerts().hatchFinallyFailed(30L, NOON);
            outcomes(PetPhase.FAILED, PetPhase.FAILED, PetPhase.FAILED, PetPhase.FAILED, PetPhase.ALIVE);
            alerts().hatchFinallyFailed(31L, NOON);

            assertThat(mailer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 성공이 한 번 끼면 다시 열린다 — 새 사고는 새 통으로")
        void aSuccessReopensIt() {
            outcomes(PetPhase.FAILED, PetPhase.FAILED, PetPhase.FAILED, PetPhase.ALIVE);
            alerts().hatchFinallyFailed(30L, NOON);
            // 성공 하나가 끼고 그 위에 다시 3연속
            outcomes(PetPhase.FAILED, PetPhase.FAILED, PetPhase.FAILED,
                    PetPhase.ALIVE, PetPhase.FAILED, PetPhase.FAILED, PetPhase.FAILED);
            alerts().hatchFinallyFailed(40L, NOON);

            assertThat(mailer.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("★ hatch-fail-streak 이 0 이하면 이 경보만 꺼진다")
        void zeroStreakTurnsOnlyThisAlertOff() {
            outcomes(PetPhase.FAILED, PetPhase.FAILED, PetPhase.FAILED);
            alertsWith(new AlertSettings(true, "ops@example.invalid", 10, 0)).hatchFinallyFailed(30L, NOON);

            assertThat(mailer.count()).isZero();
        }

        /**
         * 최근 순으로 끝난 알들을 흉내 낸다(맨 앞이 가장 최근).
         *
         * ★ id 는 <b>뒤에서부터</b> 1 씩 올려 붙인다 — 실제로는 새 알이 더 큰 번호를 받고
         *   <b>앞서 끝난 알의 번호는 안 바뀐다.</b> 앞에서부터 매기면 실패가 하나 늘 때마다
         *   옛 알의 번호가 통째로 밀려, 시험이 현실에 없는 상황을 보게 된다.
         */
        private void outcomes(PetPhase... phases) {
            List<ZzalPet> rows = new ArrayList<>();
            for (int i = 0; i < phases.length; i++) {
                ZzalPet pet = mock(ZzalPet.class);
                when(pet.getPhase()).thenReturn(phases[i]);
                when(pet.getId()).thenReturn((long) (phases.length - i));
                rows.add(pet);
            }
            when(pets.findByPhaseInOrderByIdDesc(any(), any())).thenReturn(rows);
            when(jobs.findFirstByPetIdOrderByIdDesc(anyLong())).thenReturn(Optional.<GenJob>empty());
        }
    }

    // ══ 3. 하루 상한 ══════════════════════════════════════════════════════

    @Nested
    @DisplayName("서비스 하루 상한")
    class DailyCap {

        @Test
        @DisplayName("★★ 그날 한 번만 — 닿은 뒤로는 요청마다 이 자리를 지난다")
        void onlyOncePerDay() {
            ZzalAlerts alerts = alerts();
            alerts.serviceDailyCapReached(40, 40, NOON);
            alerts.serviceDailyCapReached(41, 40, NOON.plusSeconds(60));
            alerts.serviceDailyCapReached(42, 40, NOON.plusSeconds(120));

            assertThat(mailer.count()).isEqualTo(1);
            assertThat(mailer.lastSubject()).contains("40회");
        }

        @Test
        @DisplayName("★ 날이 바뀌면 다시 알린다 — 자정에 상한이 풀리므로")
        void opensAgainTheNextDay() {
            alerts().serviceDailyCapReached(40, 40, NOON);
            alerts().serviceDailyCapReached(40, 40, NOON.plus(java.time.Duration.ofDays(1)));

            assertThat(mailer.count()).isEqualTo(2);
        }
    }

    // ══ 4. 밤 굽기 ════════════════════════════════════════════════════════

    @Nested
    @DisplayName("밤 굽기 실패")
    class NightBake {

        @Test
        @DisplayName("★★ 그 밤 한 번만 — 한 밤에 200장을 굽는다")
        void onlyOncePerNight() {
            Instant at23 = LocalDate.of(2026, 9, 18).atTime(23, 10).atZone(ZzalRules.ZONE).toInstant();
            ZzalAlerts alerts = alerts();
            alerts.nightBakeFailed(1L, "지시문 없음", at23);
            alerts.nightBakeFailed(2L, "지시문 없음", at23.plusSeconds(30));

            assertThat(mailer.count()).isEqualTo(1);
            assertThat(mailer.lastSubject()).contains("2026-09-18");
        }

        @Test
        @DisplayName("★★ 새벽 1시의 실패는 <b>어제 밤</b>이다 — 스위프와 같은 자를 쓴다")
        void afterMidnightBelongsToThePreviousNight() {
            Instant at23 = LocalDate.of(2026, 9, 18).atTime(23, 10).atZone(ZzalRules.ZONE).toInstant();
            Instant at1am = LocalDate.of(2026, 9, 19).atTime(1, 0).atZone(ZzalRules.ZONE).toInstant();
            ZzalAlerts alerts = alerts();
            alerts.nightBakeFailed(1L, "지시문 없음", at23);
            alerts.nightBakeFailed(2L, "지시문 없음", at1am);

            assertThat(mailer.count()).as("같은 밤이라 한 통이어야 한다").isEqualTo(1);
        }
    }

    // ══ 스위치와 사고 ══════════════════════════════════════════════════════

    @Nested
    @DisplayName("꺼짐 · 주소 없음 · 발송 실패")
    class OffAndBroken {

        @Test
        @DisplayName("★★ 스위치가 꺼져 있으면 아무 일도 안 한다 — 원장도 안 건드린다")
        void offMeansNothingHappens() {
            spent("99.00");
            ZzalAlerts off = alertsWith(new AlertSettings(false, "ops@example.invalid", 10, 3));

            off.generationFinished(NOON);
            off.serviceDailyCapReached(40, 40, NOON);
            off.nightBakeFailed(1L, "x", NOON);
            off.hatchFinallyFailed(1L, NOON);

            assertThat(mailer.count()).isZero();
            assertThat(ledger.rows).as("껐다 켜면 그때부터 알려야 한다 — 꺼진 동안의 기록을 남기지 않는다").isEmpty();
        }

        @Test
        @DisplayName("★★ 받는 주소가 없으면 안 보낸다 — 켜져 있어도")
        void noRecipientsMeansNoMail() {
            spent("99.00");
            AlertSettings settings = new AlertSettings(true, "   ", 10, 3);

            assertThat(settings.blockedReason()).contains("app.zzal.alert.to");
            alertsWith(settings).generationFinished(NOON);
            assertThat(mailer.count()).isZero();
        }

        @Test
        @DisplayName("★ 받는 사람이 여럿이면 사람마다 한 통")
        void oneMailPerRecipient() {
            spent("10.00");
            alertsWith(new AlertSettings(true, "a@example.invalid, b@example.invalid", 10, 3))
                    .generationFinished(NOON);

            assertThat(mailer.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("★★ 한 사람에게 실패해도 나머지는 받는다")
        void oneBadAddressDoesNotStopTheRest() {
            spent("10.00");
            mailer.explodeFor = "a@example.invalid";

            alertsWith(new AlertSettings(true, "a@example.invalid,b@example.invalid", 10, 3))
                    .generationFinished(NOON);

            assertThat(mailer.count()).isEqualTo(1);
            assertThat(mailer.sent.get(0)[0]).isEqualTo("b@example.invalid");
        }

        @Test
        @DisplayName("★★ 발송이 터져도 예외가 밖으로 안 나간다")
        void aBrokenMailerNeverEscapes() {
            spent("10.00");
            mailer.explode = true;

            assertThatCode(() -> alerts().generationFinished(NOON)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("★★ 표가 터져도 예외가 밖으로 안 나간다 — 경보가 서비스를 세우지 않는다")
        void aBrokenLedgerNeverEscapes() {
            spent("10.00");
            AlertLedger broken = new AlertLedger() {
                @Override
                public boolean claimOnce(String key, String value, Instant now) {
                    throw new IllegalStateException("DB 가 죽었다(시험이 일부러 일으킨 실패)");
                }

                @Override
                public boolean claimCostThreshold(int dollars, Instant now) {
                    throw new IllegalStateException("DB 가 죽었다(시험이 일부러 일으킨 실패)");
                }
            };
            ZzalAlerts alerts = new ZzalAlerts(
                    new AlertSettings(true, "ops@example.invalid", 10, 3), mailer, broken, jobs, pets);

            assertThatCode(() -> {
                alerts.generationFinished(NOON);
                alerts.serviceDailyCapReached(40, 40, NOON);
                alerts.nightBakeFailed(1L, "x", NOON);
            }).doesNotThrowAnyException();
            assertThat(mailer.count()).isZero();
        }
    }
}
