package com.lore.zzal.chat.prompt;

import com.lore.zzal.pet.AwakeClock;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 지금 상태 — 시각·함께한 날·게이지. 지시문의 [지금 상태] 칸.
 *
 * @param hour        펫 시계의 시(0~23, 한국 시각)
 * @param minute      분
 * @param daysTogether 함께한 날(부화일 = 1일째)
 * @param fullness    배부름 0~{@link ZzalRules#GAUGE_MAX}
 * @param happiness   행복 0~4
 * @param clean       청결 0~4
 * @param sick        아픈가
 */
public record PetState(int hour, int minute, int daysTogether, int fullness, int happiness, int clean, boolean sick) {

    public static PetState of(ZzalPet pet, Instant now) {
        ZonedDateTime t = now.atZone(ZzalRules.ZONE);
        int days = pet.getHatchedAt() == null ? 1
                : (int) ChronoUnit.DAYS.between(AwakeClock.dateOf(pet.getHatchedAt()), AwakeClock.dateOf(now)) + 1;
        return new PetState(t.getHour(), t.getMinute(), Math.max(1, days),
                pet.getFullness(), pet.getHappiness(), pet.getClean(), pet.isSick());
    }

    /** "오후 3시 10분" */
    public String clock() {
        String half = hour < 12 ? "오전" : "오후";
        int h = hour % 12 == 0 ? 12 : hour % 12;
        return minute == 0 ? "%s %d시".formatted(half, h) : "%s %d시 %d분".formatted(half, h, minute);
    }
}
