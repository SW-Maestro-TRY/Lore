package com.lore.webtoon.job;

/**
 * 한 편이 지나는 네 걸음.
 *
 * 파이썬 서버의 {@code STAGES} 와 같은 순서·같은 이름이다 — 화면이 이 순서로
 * 진행률을 그린다.
 */
public enum JobStage {

    /** 이야기 후보 넷. 여기서 작품 번호(run_id)가 생긴다. */
    STORY("story"),

    /** 캐릭터 시트. */
    SHEET("sheet"),

    /** 고른 이야기를 장면으로. */
    BOARD("board"),

    /** 페이지 그림. 돈이 제일 많이 나가는 걸음이다. */
    PAGES("pages");

    private final String wire;

    JobStage(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** 몇 번째 걸음인가(0부터). 진행률을 낼 때 쓴다. */
    public int order() {
        return ordinal();
    }

    public static int count() {
        return values().length;
    }
}
