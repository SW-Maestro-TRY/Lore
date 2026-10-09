package com.lore.zzal.chat.prompt;

import com.lore.zzal.pet.AwakeClock;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 지금 — 요일·시각·만난 날·게이지. 지시문 [지금] 칸. 게이지는 <b>단어로</b> 넘긴다(숫자 금지).
 *
 * @param weekday      요일(한국 시각)
 * @param hour         시(0~23)
 * @param minute       분
 * @param daysTogether 만난 지 며칠째(부화일 = 1일째)
 * @param fullness     배부름 0~{@link ZzalRules#GAUGE_MAX}
 * @param happiness    기분 0~4
 * @param clean        청결 0~4
 * @param sick         아픈가
 */
public record PetState(DayOfWeek weekday, int hour, int minute, int daysTogether, int fullness, int happiness,
                       int clean, boolean sick) {

    public static PetState of(ZzalPet pet, Instant now) {
        ZonedDateTime t = now.atZone(ZzalRules.ZONE);
        int days = pet.getHatchedAt() == null ? 1
                : (int) ChronoUnit.DAYS.between(AwakeClock.dateOf(pet.getHatchedAt()), AwakeClock.dateOf(now)) + 1;
        return new PetState(t.getDayOfWeek(), t.getHour(), t.getMinute(), Math.max(1, days),
                pet.getFullness(), pet.getHappiness(), pet.getClean(), pet.isSick());
    }

    /** "목요일 밤 11시" */
    public String when() {
        String part = hour < 6 ? "새벽" : hour < 11 ? "아침" : hour < 17 ? "낮" : hour < 21 ? "저녁" : "밤";
        int h = hour % 12 == 0 ? 12 : hour % 12;
        String clock = minute == 0 ? h + "시" : h + "시 " + minute + "분";
        return day() + " " + part + " " + clock;
    }

    /** "보통 · 보통 · 보통" (+ " · 아픔") */
    public String words() {
        String s = word(fullness, "배부름", "보통", "배고픔") + " · " + word(happiness, "좋음", "보통", "시무룩")
                + " · " + word(clean, "깨끗함", "보통", "꼬질꼬질");
        return sick ? s + " · 아픔" : s;
    }

    private static String word(int v, String high, String mid, String low) {
        return v >= ZzalRules.GAUGE_MAX ? high : v >= 2 ? mid : low;
    }

    private String day() {
        return switch (weekday) {
            case MONDAY -> "월요일";
            case TUESDAY -> "화요일";
            case WEDNESDAY -> "수요일";
            case THURSDAY -> "목요일";
            case FRIDAY -> "금요일";
            case SATURDAY -> "토요일";
            case SUNDAY -> "일요일";
        };
    }
}
