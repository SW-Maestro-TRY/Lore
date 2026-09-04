package com.lore.zzal.motion;

/**
 * 기본 행동이 열리는 조건(정본 6장 2층 조건표 + 16장).
 *
 * <h3>★ 해금은 행동 조건으로, 날짜로 열지 않는다(정본 0장 8)</h3>
 * 카운터는 전부 <b>부화 순간부터 누적</b>한다(아기 60분 포함). 튜토리얼에서 답한 채팅 1회가 곧 갸웃 해금이다.
 *
 * @param kind   무엇을 세는가
 * @param target 몇이면 열리는가. ALWAYS·선물은 0
 */
public record UnlockRule(Kind kind, int target) {

    public enum Kind {
        /** 처음부터(1층). */
        ALWAYS,
        /** 채팅 응답 횟수(BABY 부름 포함). */
        CHAT_ANSWERS,
        /** 재우기 + 깨우기 횟수(낮잠 포함). */
        SLEEP_WAKE,
        /** 목욕 횟수. */
        BATH,
        /** 미니게임 시작한 판 수(승패·종류 무관). */
        GAME_STARTS,
        /** 케어 미스 0인 날 수(잠들 때 판정, 아기 첫날 포함). */
        ZERO_MISS_DAYS,
        /** 열린 2층 동작 수(자기 자신 제외). */
        LAYER2_OPEN,
        /** 첫 심화 행동 — 함께한 날 3 + 그날 케어 미스 0. 기본 행동 없음. */
        FIRST_GIFT,
        /** 두 번째 선물 — 3층 8번째 뒤. 기본 행동 없음. */
        SECOND_GIFT
    }

    public static UnlockRule always() {
        return new UnlockRule(Kind.ALWAYS, 0);
    }

    public static UnlockRule of(Kind kind, int target) {
        return new UnlockRule(kind, target);
    }

    /** 잠긴 칸 옆에 보일 조건 문구(api-v2.md `motions[].hint`). 화면이 문구를 따로 갖지 않는다. */
    public String hint() {
        return switch (kind) {
            case ALWAYS -> null;
            case CHAT_ANSWERS -> "채팅 응답 %d회".formatted(target);
            case SLEEP_WAKE -> "재우기·깨우기 합쳐 %d회".formatted(target);
            case BATH -> "목욕 %d회".formatted(target);
            case GAME_STARTS -> "미니게임 %d판".formatted(target);
            case ZERO_MISS_DAYS -> "잘 돌본 날 %d번".formatted(target);
            case LAYER2_OPEN -> "다른 동작 %d개 배우기".formatted(target);
            case FIRST_GIFT -> "3일이나 함께해서…";
            case SECOND_GIFT -> "언젠가 깜짝 선물";
        };
    }

    /** 2층 잠긴 칸의 진행(`progress{current,target}`)을 보여줄 조건인가. */
    public boolean hasProgress() {
        return kind != Kind.ALWAYS && kind != Kind.FIRST_GIFT && kind != Kind.SECOND_GIFT;
    }
}
