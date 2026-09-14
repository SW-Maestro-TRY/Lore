package com.lore.zzal.chat;

/**
 * 하루 3회의 부름 + 튜토리얼 부름 하나.
 *
 * 시각 — BABY 는 시각이 아니라 <b>튜토리얼 순서</b>로 열린다(세 번째 칸). 시계가 멈춰 있어 분으로 셀 수 없다.
 * MORNING 기상+1h / NOON 기상+7h / EVENING 19:00 고정(재우기 창이 열리는 시각).
 * 부름은 다음 부름 시각에 만료되고, EVENING 은 잠들 때 만료.
 * <b>BABY 는 만료가 없다</b> — 답할 때까지 남는다.
 * BABY 는 하루 3회에 안 세지만 친밀도 +40 과 2층 조건 카운터에는 센다.
 */
public enum ChatSlot {
    BABY, MORNING, NOON, EVENING
}
