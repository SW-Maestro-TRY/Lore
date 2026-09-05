package com.lore.zzal.chat;

/**
 * 하루 3회의 부름 + 아기 8분(정본 10·12·16장).
 *
 * 시각 — BABY 부화+8분 / MORNING 기상+1h / NOON 기상+7h / EVENING 19:00 고정(재우기 창이 열리는 시각).
 * 부름은 다음 부름 시각에 만료되고, EVENING 은 잠들 때 만료. BABY 는 답하거나 첫 밤잠까지(해석 7).
 * BABY 는 하루 3회에 안 세지만 친밀도 +40 과 2층 조건 카운터에는 센다.
 */
public enum ChatSlot {
    BABY, MORNING, NOON, EVENING
}
