package com.lore.webtoon.job;

/**
 * 한 편이 지나는 다섯 걸음.
 *
 * 파이썬 서버의 {@code STAGES} 와 같은 순서·같은 이름이다 — 화면이 이 순서로
 * 진행률을 그린다. {@code BIND} 는 2026-09-23에 더한 걸음이다 — 전에는 페이지를
 * 다 그린 순간 {@code pages} 단계의 frac 이 1.0 이 되어 진행률이 이미 100% 를
 * 찍어 버리는데, 실제로는 그 뒤에 검수·합본(이어 붙이기)·업로드가 아직 남아 있어서
 * "100%인데 안 넘어간다" 는 체감으로 이어졌다({@link JobView#of}).
 */
public enum JobStage {

    /** 이야기 후보 넷. 여기서 작품 번호(run_id)가 생긴다. */
    STORY("story"),

    /** 캐릭터 시트. */
    SHEET("sheet"),

    /** 고른 이야기를 장면으로. */
    BOARD("board"),

    /** 페이지 그림. 돈이 제일 많이 나가는 걸음이다. */
    PAGES("pages"),

    /** 페이지를 다 그린 뒤 검수하고 한 화로 잇는 걸음. */
    BIND("bind");

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
