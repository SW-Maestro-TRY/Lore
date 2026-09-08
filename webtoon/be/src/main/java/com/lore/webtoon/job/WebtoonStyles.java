package com.lore.webtoon.job;

import java.util.Map;

/**
 * 그림체 이름과, 사람에게 보일 딱지.
 *
 * <b>한 곳에만 둔다.</b> 만들 때 고르는 자리(작업)와 다 만든 뒤 보여 주는
 * 자리(둘러보기 · 완성본)가 같은 값을 읽어야 한다 — 두 벌이 되면 같은 작품이
 * 화면마다 다른 그림체로 적힌다.
 */
public final class WebtoonStyles {

    /** 화면이 보내는 짧은 이름 -> 하네스가 아는 이름. */
    static final Map<String, String> STYLE = Map.of(
            "romance", "romance_fantasy",
            "webtoon", "webtoon_lock_bg",
            "frost", "frost",
            "cinematic", "cinematic",
            "pastel", "pastel",
            "noir", "noir",
            "shoujo", "shoujo",
            "game", "game");

    /** 하네스가 아는 이름 -> 사람에게 보일 딱지. */
    private static final Map<String, String> LABEL = Map.of(
            "romance_fantasy", "로맨스 판타지",
            "webtoon_lock_bg", "일반 웹툰",
            "frost", "세미리얼 · 성인향",
            "cinematic", "시네마틱 반실사",
            "pastel", "일상툰 감성",
            "noir", "다크 느와르",
            "shoujo", "순정 · BL",
            "game", "게임 원화");

    static final String DEFAULT_STYLE = "webtoon_lock_bg";

    private WebtoonStyles() {
    }

    /**
     * 이 그림체의 딱지. 모르는 값이면 <b>빈 문자열</b>이다.
     *
     * 그림체를 남기기 전에 만든 작품이 있어서 빈 값이 실제로 나온다 — 화면은
     * 비면 딱지를 안 그린다. 여기서 "알 수 없음" 같은 것을 지어내면 그 글자가
     * 카드마다 붙는다.
     */
    public static String labelOf(String style) {
        return style == null ? "" : LABEL.getOrDefault(style, "");
    }
}
