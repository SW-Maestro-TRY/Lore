package com.lore.zzal.pet;

import com.lore.zzal.PetFixture;
import com.lore.zzal.chat.ChatSessionKeys;
import com.lore.zzal.chat.prompt.PetState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시간대 2단계 — 규칙이 펫 시간대의 벽시계로 돈다.
 *
 * <ol>
 *   <li>한국 사용자 불변 — 시간대 인자 없는 옛 판(KST)과 Asia/Seoul 판이 사흘치 5분 눈금 전부에서 같다</li>
 *   <li>로스앤젤레스 2026-11-01(서머타임 끝, 25시간 날) — 창 시각이 현지 벽시계로 정확하다</li>
 *   <li>부화일 열쇠(연속 대화 판)는 시간대를 처음 관측해도 안 바뀐다</li>
 * </ol>
 */
@DisplayName("펫 시간대 규칙 — 서울 불변 · LA 서머타임 · 판 열쇠")
class PetTimeZoneRulesTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");

    private static Instant at(String iso) {
        return OffsetDateTime.parse(iso).toInstant();
    }

    @Test
    @DisplayName("★★ 한국 사용자 불변 — 옛 KST 판과 Asia/Seoul 판이 3일 × 288칸 전부 같다(걷기 포함)")
    void seoulIsUnchanged() {
        Instant base = at("2026-10-25T00:00:00+09:00");
        for (int i = 0; i < 3 * 288; i++) {
            Instant t = base.plus(Duration.ofMinutes(5L * i)).plusSeconds(17);
            assertThat(AwakeClock.nextAutoSleep(t, null, SEOUL)).isEqualTo(AwakeClock.nextAutoSleep(t, null));
            assertThat(AwakeClock.autoWakeAt(SleepKind.NIGHT, t, SEOUL)).isEqualTo(AwakeClock.autoWakeAt(SleepKind.NIGHT, t));
            assertThat(AwakeClock.wakeWindowOpensAt(SleepKind.NIGHT, t, SEOUL)).isEqualTo(AwakeClock.wakeWindowOpensAt(SleepKind.NIGHT, t));
            assertThat(AwakeClock.sleepWindowOpensAt(t, SEOUL)).isEqualTo(AwakeClock.sleepWindowOpensAt(t));
            assertThat(AwakeClock.inSleepWindow(t, SEOUL)).isEqualTo(AwakeClock.inSleepWindow(t));
            assertThat(AwakeClock.inWakeWindow(t, SEOUL)).isEqualTo(AwakeClock.inWakeWindow(t));
            assertThat(AwakeClock.isNight(t, SEOUL)).isEqualTo(AwakeClock.isNight(t));
            assertThat(AwakeClock.dateOf(t, SEOUL)).isEqualTo(AwakeClock.dateOf(t));
        }
        AwakeClock.State awake = AwakeClock.State.awake(null);
        Instant from = base.plusSeconds(3600);
        Instant to = from.plus(Duration.ofDays(3));
        assertThat(AwakeClock.walk(awake, from, to, SEOUL)).isEqualTo(AwakeClock.walk(awake, from, to));
    }

    @Test
    @DisplayName("★★ 한국 사용자 불변 — 시간대 NULL 펫과 Asia/Seoul 펫이 하루를 똑같이 산다")
    void nullZonePetBehavesLikeSeoul() {
        Instant hatch = at("2026-10-24T09:00:00+09:00");
        ZzalPet unknown = alivePet(hatch);
        ZzalPet seoul = alivePet(hatch);
        seoul.observeTimeZone(SEOUL);
        assertThat(unknown.getTimeZone()).isNull();
        for (int h = 0; h < 72; h++) {
            Instant t = hatch.plus(Duration.ofMinutes(30L * h + 7));
            unknown.settle(t);
            seoul.settle(t);
            assertThat(unknown.isSleeping()).as("t=%s", t).isEqualTo(seoul.isSleeping());
            assertThat(unknown.canSleep(t)).isEqualTo(seoul.canSleep(t));
            assertThat(unknown.canWake(t)).isEqualTo(seoul.canWake(t));
            assertThat(unknown.getLastNightOf()).isEqualTo(seoul.getLastNightOf());
        }
    }

    @Test
    @DisplayName("★★ LA 2026-11-01(25시간 날) — 19·23·07·10시가 현지 벽시계로 정확하다")
    void losAngelesDstEndDay() {
        // 10/31 밤 23:00 PDT 에 잠들어 11/1 10:00 PST 에 깬다 — 사이에 02:00 전환이 있다.
        Instant slept = at("2026-10-31T23:00:00-07:00");
        assertThat(AwakeClock.nextAutoSleep(at("2026-10-31T12:00:00-07:00"), null, LA)).isEqualTo(slept);
        assertThat(AwakeClock.autoWakeAt(SleepKind.NIGHT, slept, LA)).isEqualTo(at("2026-11-01T10:00:00-08:00"));
        assertThat(AwakeClock.wakeWindowOpensAt(SleepKind.NIGHT, slept, LA)).isEqualTo(at("2026-11-01T07:00:00-08:00"));
        assertThat(Duration.between(slept, AwakeClock.autoWakeAt(SleepKind.NIGHT, slept, LA)))
                .as("벽시계로는 23시→10시(11시간)지만 그 밤은 한 시간 길다 — 실제 12시간").isEqualTo(Duration.ofHours(12));

        Instant noon = at("2026-11-01T12:00:00-08:00");
        assertThat(AwakeClock.sleepWindowOpensAt(noon, LA)).isEqualTo(at("2026-11-01T19:00:00-08:00"));
        assertThat(AwakeClock.nextAutoSleep(noon, null, LA)).isEqualTo(at("2026-11-01T23:00:00-08:00"));
        assertThat(AwakeClock.inSleepWindow(at("2026-11-01T18:59:59-08:00"), LA)).isFalse();
        assertThat(AwakeClock.inSleepWindow(at("2026-11-01T19:00:00-08:00"), LA)).isTrue();
        assertThat(AwakeClock.isNight(at("2026-11-01T06:59:00-08:00"), LA)).isTrue();
        assertThat(AwakeClock.inWakeWindow(at("2026-11-01T07:00:00-08:00"), LA)).isTrue();
        // 같은 순간이 서울로는 낮이다 — 시간대가 정말 바뀌었는가
        assertThat(AwakeClock.inSleepWindow(at("2026-11-01T19:00:00-08:00"), SEOUL)).isFalse();
    }

    @Test
    @DisplayName("★ LA 펫 — 재우기 버튼은 현지 19시에 켜지고, 서울 19시(현지 새벽)에는 꺼져 있다")
    void losAngelesPetWindows() {
        Instant hatch = at("2026-10-24T10:00:00-07:00");
        ZzalPet pet = alivePet(hatch);
        pet.observeTimeZone(LA);
        Instant laEvening = at("2026-10-26T19:30:00-07:00");
        Instant seoulEvening = at("2026-10-26T19:30:00+09:00");    // = LA 03:30
        pet.settle(seoulEvening);
        assertThat(pet.isSleeping()).as("LA 새벽 3시 반 — 자고 있다").isTrue();
        pet.settle(at("2026-10-26T12:00:00-07:00"));
        assertThat(pet.canSleep(at("2026-10-26T12:00:00-07:00"))).isFalse();
        pet.settle(laEvening);
        assertThat(pet.canSleep(laEvening)).isTrue();
    }

    @Test
    @DisplayName("★★ 부화일 열쇠 — 시간대를 처음 관측해도 연속 대화 판을 같은 열쇠로 찾는다(판이 고아가 되지 않는다)")
    void continuousSessionKeyIsStableAcrossFirstObservation() {
        // 한국 오전 9시 부화 = LA 전날 오후 5시. 열쇠를 '지금 시간대' 로 계산하면 10/24 → 10/23 으로 바뀌어
        // 그날부터 옛 판을 영영 못 찾는다(전환일 하루가 아니라 평생).
        Instant hatch = at("2026-10-24T09:00:00+09:00");
        ZzalPet pet = alivePet(hatch);
        LocalDate before = ChatSessionKeys.hatchDay(pet);
        pet.observeTimeZone(LA);
        assertThat(ChatSessionKeys.hatchDay(pet)).isEqualTo(before).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(AwakeClock.dateOf(hatch, pet.zone()))
                .as("이렇게 계산했다면 열쇠가 바뀌었다 — 그래서 열쇠는 KST 로 고정한다")
                .isEqualTo(LocalDate.of(2026, 10, 23));
    }

    @Test
    @DisplayName("★ 지시문의 '목요일 밤 11시' — LA 펫에게는 LA 시각으로 말한다")
    void promptTimeIsLocal() {
        Instant hatch = at("2026-10-24T10:00:00-07:00");
        ZzalPet pet = alivePet(hatch);
        pet.observeTimeZone(LA);
        Instant now = at("2026-10-29T08:15:00-07:00");          // 목요일 아침(LA) = 금요일 0시 15분(서울)
        PetState s = PetState.of(pet, now);
        assertThat(s.weekday()).isEqualTo(DayOfWeek.THURSDAY);
        assertThat(s.hour()).isEqualTo(8);
        assertThat(s.minute()).isEqualTo(15);
        assertThat(s.daysTogether()).isEqualTo(6);              // 10/24 → 10/29, LA 날짜
    }

    private static ZzalPet alivePet(Instant hatch) {
        ZzalPet p = PetFixture.hatching(1L, "여울", null, "k", hatch);
        p.markAlive("s", "i", hatch);
        p.skipTutorial(hatch);
        return p;
    }
}
