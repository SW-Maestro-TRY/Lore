package com.lore.zzal.chat;

import com.lore.zzal.pet.ZzalRules;

import java.time.LocalTime;

/**
 * 하루 3회의 부름 + 튜토리얼 부름 하나.
 *
 * <h3>부름 창(#709) — 벽시계, KST</h3>
 * MORNING 10:00~14:00 · NOON 14:00~19:00 · EVENING 19:00~23:00 ({@link ZzalRules#CHAT_MORNING_OPENS} 등).
 * 판은 창 안에서 조회가 올 때 열리고 <b>창 끝에 만료</b>된다. 창이 지나면 그 창의 판은 없다.
 *
 * <b>BABY 는 창이 없다</b> — 시각이 아니라 튜토리얼 순서(세 번째 칸)로 열리고 만료도 없다(답할 때까지 남는다).
 * BABY 는 하루 3회에 안 세지만 친밀도 +40 과 2층 조건 카운터에는 센다.
 */
public enum ChatSlot {
    BABY(null, null),
    MORNING(ZzalRules.CHAT_MORNING_OPENS, ZzalRules.CHAT_NOON_OPENS),
    NOON(ZzalRules.CHAT_NOON_OPENS, ZzalRules.CHAT_EVENING_OPENS),
    EVENING(ZzalRules.CHAT_EVENING_OPENS, ZzalRules.CHAT_EVENING_CLOSES);

    private final LocalTime opens;
    private final LocalTime closes;

    ChatSlot(LocalTime opens, LocalTime closes) {
        this.opens = opens;
        this.closes = closes;
    }

    /** 창이 열리는 시각(포함). BABY 는 null. */
    public LocalTime opens() {
        return opens;
    }

    /** 창이 닫히는 시각(제외) = 판의 만료. BABY 는 null. */
    public LocalTime closes() {
        return closes;
    }

    /** 하루 부름(창이 있는 슬롯)인가. */
    public boolean daily() {
        return opens != null;
    }

    /** 이 벽시계 시각이 속한 하루 부름 창. 창 밖(23:00~10:00)이면 null. */
    public static ChatSlot windowAt(LocalTime t) {
        for (ChatSlot s : values()) {
            if (s.daily() && !t.isBefore(s.opens) && t.isBefore(s.closes)) {
                return s;
            }
        }
        return null;
    }
}
