package com.lore.zzal.guard;

import com.lore.zzal.alert.ZzalAlerts;
import com.lore.common.exception.ErrorCode;
import com.lore.common.user.User;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPetRepository;
import com.lore.zzal.pet.ZzalRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 부화 막기 다섯 — <b>판정만</b> 본다(DB·HTTP 없이).
 *
 * ★ 진짜 DB·진짜 요청에서 도는지는 {@code HatchLimitsIT} · {@code HatchCapsIT} 가 본다.
 *   여기서는 숫자와 경계, 그리고 <b>어느 사유를 먼저 말하는가</b>를 못 박는다.
 */
@DisplayName("부화 막기 — 다섯 개의 문")
class HatchGuardTest {

    private static final Long USER = 7L;

    /** 오늘(KST) 낮 12시. 날짜 경계 시험이 이 자리를 기준으로 앞뒤를 만든다. */
    private static final Instant NOON = LocalDate.of(2026, 9, 14)
            .atTime(12, 0).atZone(ZzalRules.ZONE).toInstant();

    private ZzalPetRepository pets;
    private GenJobRepository jobs;
    private IpRateLimiter ipRateLimiter;
    private QuotaBreaker quotaBreaker;
    /** 경보는 여기서 "불렀는가" 만 본다 — 같은 날 두 번 안 보내는 것은 경보 쪽의 일이다. */
    private ZzalAlerts alerts;

    /** 명세의 기본값 그대로 — 동시 1 · 누적 3 · 하루 3 · 전체 40 · IP 10/60분 · 429 멈춤 30분. */
    private HatchLimits defaults() {
        return new HatchLimits(1, 3, 3, 40, 10, 60, 30);
    }

    private HatchGuard guardWith(HatchLimits limits) {
        HatchUserLockRepository locks = mock(HatchUserLockRepository.class);
        when(locks.lockForHatch(anyLong())).thenReturn(Optional.of(mock(User.class)));
        return new HatchGuard(locks, pets, jobs, limits, ipRateLimiter, quotaBreaker, alerts);
    }

    private HatchGuard guard() {
        return guardWith(defaults());
    }

    @BeforeEach
    void setUp() {
        pets = mock(ZzalPetRepository.class);
        jobs = mock(GenJobRepository.class);
        ipRateLimiter = new IpRateLimiter();
        quotaBreaker = new QuotaBreaker();
        alerts = mock(ZzalAlerts.class);
        alive(0);
        totalHatches(0);
        userHatchesToday(0);
        serviceHatchesToday(0);
    }

    private void alive(long n) {
        when(pets.countByUserIdAndPhaseIn(eq(USER), eq(PetPhase.OCCUPYING_SLOT))).thenReturn(n);
    }

    private void totalHatches(long n) {
        when(jobs.countHatchesOfUser(eq(USER), eq(GenKind.HATCH))).thenReturn(n);
    }

    private void userHatchesToday(long n) {
        when(jobs.countHatchesOfUserSince(eq(USER), eq(GenKind.HATCH), any())).thenReturn(n);
    }

    private void serviceHatchesToday(long n) {
        when(jobs.countHatchesSince(eq(GenKind.HATCH), any())).thenReturn(n);
    }

    private HatchBlockedException blockedBy(HatchGuard guard, String ip, Instant now) {
        return catchThrowableOfType(HatchBlockedException.class, () -> guard.check(USER, ip, now));
    }

    // ══ 1·2. 사람당 펫 — 동시 1 · 누적 3 ═══════════════════════════════

    @Test
    @DisplayName("★ 아무것도 없으면 통과한다 — 막기가 늘 막기만 하면 서비스가 아니다")
    void anEmptyUserPasses() {
        assertThatCode(() -> guard().check(USER, "203.0.113.7", NOON)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★★ 살아 있는 아이가 있으면 못 만든다 — PET_LIMIT")
    void oneAliveBlocks() {
        alive(1);
        HatchBlockedException e = blockedBy(guard(), null, NOON);
        assertThat(e.getBlock()).isEqualTo(HatchBlock.PET_LIMIT);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ZZAL_HATCH_BLOCKED_PET_LIMIT);
        assertThat(e.getMessage()).as("무엇이 얼마나 걸렸는지가 보여야 한다").contains("1명");
    }

    @Test
    @DisplayName("★★ 누적 3번을 다 쓰면 아이가 없어도 못 만든다 — 실패로 끝난 판도 돈은 나갔다")
    void lifetimeCapBlocksEvenWithNoPet() {
        alive(0);
        totalHatches(3);
        HatchBlockedException e = blockedBy(guard(), null, NOON);
        assertThat(e.getBlock()).isEqualTo(HatchBlock.PET_LIMIT);
        assertThat(e.getMessage()).contains("3번");
    }

    @Test
    @DisplayName("★ 누적 2번까지는 통과한다 — 부등호가 밀리면 한 번을 덜 주거나 더 준다")
    void twoOfThreeStillPasses() {
        totalHatches(2);
        assertThatCode(() -> guard().check(USER, null, NOON)).doesNotThrowAnyException();
    }

    // ══ 3. 사람당 하루 ═════════════════════════════════════════════════

    @Nested
    @DisplayName("사람당 하루 상한 — 누적을 올려 준 뒤에 일하는 문")
    class PerUserDaily {

        /** 누적을 넉넉히 열어 둔 설정. 기본값(누적 3 = 하루 3)에서는 누적이 먼저 걸린다. */
        private HatchGuard guardWithRoom() {
            return guardWith(new HatchLimits(1, 99, 3, 40, 10, 60, 30));
        }

        @Test
        @DisplayName("★★ 오늘 3번을 쓰면 막힌다 — DAILY_CAP")
        void threeTodayBlocks() {
            userHatchesToday(3);
            HatchBlockedException e = blockedBy(guardWithRoom(), null, NOON);
            assertThat(e.getBlock()).isEqualTo(HatchBlock.DAILY_CAP);
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ZZAL_HATCH_BLOCKED_DAILY_CAP);
            assertThat(e.getMessage()).contains("3번").contains("자정");
        }

        @Test
        @DisplayName("★ 누적과 하루가 <b>둘 다</b> 걸리면 누적을 말한다 — '내일 다시' 는 거짓 약속이다")
        void lifetimeWinsOverDaily() {
            totalHatches(3);
            userHatchesToday(3);
            assertThat(blockedBy(guard(), null, NOON).getMessage())
                    .as("기다려도 안 풀리는 사유를 먼저 말해야 한다")
                    .contains("지금까지");
        }
    }

    // ══ 날짜 경계 — 한국 시각 자정 ══════════════════════════════════════

    @Nested
    @DisplayName("★★ 하루의 경계는 한국 시각 자정이다")
    class KoreanMidnight {

        @Test
        @DisplayName("낮 12시에 묻는 '오늘' 은 그날 0시부터다")
        void noonCountsFromMidnight() {
            assertThat(HatchGuard.startOfDay(NOON))
                    .isEqualTo(LocalDate.of(2026, 9, 14).atStartOfDay(ZzalRules.ZONE).toInstant());
        }

        @Test
        @DisplayName("★★ UTC 로는 이미 다음 날인 시각(KST 오전 8시)도 '오늘' 은 그날 0시다")
        void morningIsStillTheSameKoreanDay() {
            Instant kstMorning = LocalDate.of(2026, 9, 14)
                    .atTime(8, 0).atZone(ZzalRules.ZONE).toInstant();
            // 이 시각은 UTC 로 9/13 23:00 이다 — UTC 자정으로 세면 어제 것까지 오늘로 딸려 온다.
            assertThat(HatchGuard.startOfDay(kstMorning))
                    .isEqualTo(LocalDate.of(2026, 9, 14).atStartOfDay(ZzalRules.ZONE).toInstant());
        }

        @Test
        @DisplayName("★ 자정 직전과 직후는 다른 날이다 — 여기가 밀리면 하루 상한이 하루 늦게 풀린다")
        void justBeforeAndAfterMidnight() {
            Instant midnight = LocalDate.of(2026, 9, 15).atStartOfDay(ZzalRules.ZONE).toInstant();
            assertThat(HatchGuard.startOfDay(midnight.minusSeconds(1)))
                    .isEqualTo(LocalDate.of(2026, 9, 14).atStartOfDay(ZzalRules.ZONE).toInstant());
            assertThat(HatchGuard.startOfDay(midnight)).isEqualTo(midnight);
        }
    }

    // ══ 4. 서비스 전체 하루 ════════════════════════════════════════════

    @Test
    @DisplayName("★★ 서비스 전체가 오늘 40번을 쓰면 아무도 못 만든다 — 잔액 방벽")
    void serviceCapBlocksEveryone() {
        serviceHatchesToday(40);
        HatchBlockedException e = blockedBy(guard(), null, NOON);
        assertThat(e.getBlock()).isEqualTo(HatchBlock.SERVICE_CAP);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ZZAL_HATCH_BLOCKED_SERVICE_CAP);
        assertThat(e.getMessage()).contains("40번");
    }

    @Test
    @DisplayName("★★ 상한에 닿으면 운영자에게 알린다 — 오늘 몇 번 썼는지와 상한을 함께")
    void serviceCapNotifiesTheOperator() {
        serviceHatchesToday(40);
        blockedBy(guard(), null, NOON);
        org.mockito.Mockito.verify(alerts).serviceDailyCapReached(40L, 40, NOON);
    }

    @Test
    @DisplayName("★ 39번이면 알리지 않는다 — 닿은 순간에만")
    void noAlertBeforeTheCap() {
        serviceHatchesToday(39);
        guard().check(USER, null, NOON);
        org.mockito.Mockito.verify(alerts, org.mockito.Mockito.never())
                .serviceDailyCapReached(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    @DisplayName("★ 39번이면 한 번 더 된다")
    void thirtyNineStillPasses() {
        serviceHatchesToday(39);
        assertThatCode(() -> guard().check(USER, null, NOON)).doesNotThrowAnyException();
    }

    // ══ 5. IP ══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("한 집에서 몰아치는 것 — 완화 수준")
    class IpRate {

        @Test
        @DisplayName("★★ 창 안에 상한만큼 시작했으면 막는다 — IP_RATE")
        void tooManyFromOneHouse() {
            for (int i = 0; i < 10; i++) {
                ipRateLimiter.mark("203.0.113.7", Duration.ofMinutes(60), NOON);
            }
            HatchBlockedException e = blockedBy(guard(), "203.0.113.7", NOON);
            assertThat(e.getBlock()).isEqualTo(HatchBlock.IP_RATE);
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ZZAL_HATCH_BLOCKED_IP_RATE);
        }

        @Test
        @DisplayName("★ 창이 지나면 풀린다 — 영구 차단이 아니다")
        void theWindowSlides() {
            for (int i = 0; i < 10; i++) {
                ipRateLimiter.mark("203.0.113.7", Duration.ofMinutes(60), NOON);
            }
            Instant later = NOON.plus(Duration.ofMinutes(61));
            assertThatCode(() -> guard().check(USER, "203.0.113.7", later)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("★ 주소를 모르면 이 문만 넘어간다 — 헤더가 없는 정상 호출이 409 가 되면 안 된다")
        void unknownAddressPasses() {
            for (int i = 0; i < 50; i++) {
                ipRateLimiter.mark("203.0.113.7", Duration.ofMinutes(60), NOON);
            }
            assertThatCode(() -> guard().check(USER, null, NOON)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("★★ 자기 사정으로 설명되는 것이 있으면 그쪽을 먼저 말한다 — IP 는 가장 억울한 사유다")
        void personalReasonsComeFirst() {
            alive(1);
            for (int i = 0; i < 10; i++) {
                ipRateLimiter.mark("203.0.113.7", Duration.ofMinutes(60), NOON);
            }
            assertThat(blockedBy(guard(), "203.0.113.7", NOON).getBlock()).isEqualTo(HatchBlock.PET_LIMIT);
        }
    }

    // ══ 6. 바깥 한도(429) ══════════════════════════════════════════════

    @Nested
    @DisplayName("바깥이 한도로 막는 동안")
    class Quota {

        @Test
        @DisplayName("★★ 차단기가 내려가 있으면 굽기를 시작하지 않는다 — QUOTA")
        void trippedBreakerBlocks() {
            quotaBreaker.trip(NOON);
            HatchBlockedException e = blockedBy(guard(), null, NOON.plus(Duration.ofMinutes(5)));
            assertThat(e.getBlock()).isEqualTo(HatchBlock.QUOTA);
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ZZAL_HATCH_BLOCKED_QUOTA);
            assertThat(e.getMessage()).as("언제 열리는지가 보여야 한다").contains("분 뒤");
        }

        @Test
        @DisplayName("★ 멈춤 시간이 지나면 다시 열린다 — 사람이 손대지 않아도 회복된다")
        void itOpensAgain() {
            quotaBreaker.trip(NOON);
            assertThatCode(() -> guard().check(USER, null, NOON.plus(Duration.ofMinutes(31))))
                    .doesNotThrowAnyException();
        }
    }

    // ══ 상한을 끄는 길 ═════════════════════════════════════════════════

    @Test
    @DisplayName("★★ 0 이하는 '전면 차단' 이 아니라 '그 상한 꺼짐' 이다 — 설정을 잘못 비운 날 서비스가 멈추면 안 된다")
    void zeroTurnsALimitOff() {
        alive(9);
        totalHatches(99);
        userHatchesToday(99);
        serviceHatchesToday(999);
        HatchGuard everythingOff = guardWith(new HatchLimits(0, 0, 0, 0, 0, 60, 30));
        assertThatCode(() -> everythingOff.check(USER, "203.0.113.7", NOON)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 없는 사람이면 잠그는 자리에서 멈춘다 — 넘어가면 셈이 전부 0 이라 무한히 통과한다")
    void unknownUserStopsAtTheLock() {
        HatchUserLockRepository locks = mock(HatchUserLockRepository.class);
        when(locks.lockForHatch(anyLong())).thenReturn(Optional.empty());
        HatchGuard guard = new HatchGuard(locks, pets, jobs, defaults(), ipRateLimiter, quotaBreaker, alerts);
        assertThat(catchThrowableOfType(com.lore.common.exception.BusinessException.class,
                () -> guard.lockUser(USER)).getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
    }
}
