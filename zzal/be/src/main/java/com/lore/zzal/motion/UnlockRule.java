package com.lore.zzal.motion;

/**
 * 기본 행동(2층)이 열리는 조건.
 *
 * <h3>★ 해금은 행동 조건으로, 날짜로 열지 않는다</h3>
 * 카운터는 전부 <b>부화 순간부터 누적</b>한다(아기 60분 포함). 튜토리얼이 모든 행동을 한 번씩 시켜 주므로
 * 여덟 종이 전부 1 부터 시작한다.
 *
 * <h3>★ 조건은 그 행동 자체다</h3>
 * 다른 행동으로 열리는 자세가 하나도 없다 — 밥 자세는 밥 주기로, 청소 자세는 청소로 열린다.
 * 그래야 "하던 행동이 좋아진다" 로 읽히고, 열리지 않은 행동을 억지로 하게 만들지 않는다.
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
        /** 목욕 횟수. */
        BATH,
        /** 미니게임 시작한 판 수(승패·종류 무관). */
        GAME_STARTS,
        /** 밥 준 횟수(누적). */
        FEEDS,
        /**
         * 간식 개수(누적) — <b>그날 4개까지만</b> 센다.
         *
         * ★ 5개째부터는 배탈이라 안 센다. 전부 세면 "빨리 열려면 배탈이 날 때까지 먹여라" 가 되어,
         *   아이를 아프게 하는 쪽이 이득인 구조가 된다.
         */
        SNACKS,
        /** 청소 횟수(누적). 청소 한 번이 흔적 하나를 없앤다. */
        CLEANS,
        /** 쓰다듬기 횟수(누적). 하루 친밀도 인정 한도(3회)와는 별개로 전부 센다. */
        PET_COUNT,
        /**
         * <b>손으로 깨운</b> 밤잠의 수.
         *
         * ★ 아침 자동 기상·튜토리얼 낮잠은 세지 않는다 — "깨우기" 라는 행동의 횟수이기 때문이다.
         *   {@code SLEEP_WAKE} 로는 못 대신한다(재우기까지 같이 세고 낮잠도 들어간다).
         */
        WAKES,
        /** 재우기 + 깨우기 횟수(낮잠 포함). <b>지금 카탈로그에는 쓰이지 않는다</b> — 옛 펫 설명용으로 남긴다. */
        SLEEP_WAKE,
        /** 케어 미스 0인 날 수(잠들 때 판정). <b>지금 카탈로그에는 쓰이지 않는다</b> — 옛 펫 설명용. */
        ZERO_MISS_DAYS,
        /** 열린 2층 동작 수(자기 자신 제외). <b>지금 카탈로그에는 쓰이지 않는다</b> — 옛 펫 설명용. */
        LAYER2_OPEN,
        /** 첫 심화 행동 — 튜토리얼을 끝낸 순간. 기본 행동 없음. */
        FIRST_GIFT,
        /** 두 번째 선물 — 첫 게임 패배. 기본 행동 없음. */
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
            case BATH -> "목욕 %d회".formatted(target);
            case GAME_STARTS -> "미니게임 %d판".formatted(target);
            case FEEDS -> "밥 주기 %d회".formatted(target);
            case SNACKS -> "간식 %d개".formatted(target);
            case CLEANS -> "청소 %d회".formatted(target);
            case PET_COUNT -> "쓰다듬기 %d회".formatted(target);
            case WAKES -> "깨우기 %d회".formatted(target);
            case SLEEP_WAKE -> "재우기·깨우기 합쳐 %d회".formatted(target);
            case ZERO_MISS_DAYS -> "잘 돌본 날 %d번".formatted(target);
            case LAYER2_OPEN -> "다른 동작 %d개 배우기".formatted(target);
            // ★ 선물의 문구는 조건을 말하지 않는다 — "게임에서 지면 준다" 는 선물이 아니게 된다.
            case FIRST_GIFT -> "함께한 첫 선물";
            case SECOND_GIFT -> "언젠가 깜짝 선물";
        };
    }

    /** 2층 잠긴 칸의 진행(`progress{current,target}`)을 보여줄 조건인가. */
    public boolean hasProgress() {
        return kind != Kind.ALWAYS && kind != Kind.FIRST_GIFT && kind != Kind.SECOND_GIFT;
    }
}
