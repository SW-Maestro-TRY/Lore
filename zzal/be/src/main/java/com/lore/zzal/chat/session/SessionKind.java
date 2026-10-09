package com.lore.zzal.chat.session;

/**
 * 대화 한 판(세션)의 종류 — 첫 펫 턴이 무엇을 할지 정한다. 코드가 정한다.
 *
 * <ul>
 *   <li>{@link #BABY} — 튜토리얼 부름(부화 직후). 첫 턴 = 첫 만남</li>
 *   <li>{@link #FIRST_MEET} — 하루 부름인데 사용자가 아직 한 번도 답한 적이 없다. 첫 턴 = 첫 만남</li>
 *   <li>{@link #DAILY} — 보통의 하루 부름. 첫 턴 = 오늘 첫 인사</li>
 *   <li>{@link #LONG_ABSENCE} — 마지막 답에서 {@link TurnPlanner#LONG_ABSENCE_AFTER} 넘게 지났다. 첫 턴 = 오랜만</li>
 * </ul>
 */
public enum SessionKind {
    BABY, FIRST_MEET, DAILY, LONG_ABSENCE;

    public TurnType firstTurn() {
        return switch (this) {
            case BABY, FIRST_MEET -> TurnType.FIRST_MEET;
            case DAILY -> TurnType.GREETING;
            case LONG_ABSENCE -> TurnType.REUNION;
        };
    }
}
