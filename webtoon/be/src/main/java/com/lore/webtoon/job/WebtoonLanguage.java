package com.lore.webtoon.job;

import java.util.Set;

/**
 * 어느 언어로 웹툰을 만들 것인가.
 *
 * 화면(webtoon/fe {@code lib/i18n.tsx})이 쓰는 언어 코드와 하네스({@code webtoon/ai
 * /new_harness/lang.py})가 아는 언어 코드가 같아서, {@link WebtoonQuality} 처럼
 * 이름을 잇는 표가 따로 필요 없다.
 */
public final class WebtoonLanguage {

    private static final Set<String> SUPPORTED = Set.of("ko", "en", "ja");

    /** 아무도 안 고르면, 또는 모르는 값이면 이것. */
    public static final String DEFAULT_LANGUAGE = "ko";

    private WebtoonLanguage() {
    }

    /** 모르는 값이면 기본으로 돌린다 — 화면이 옛 값이나 지원 안 하는 코드를 보내도 만들기가 막히지 않는다. */
    public static String normalize(String key) {
        String k = key == null ? "" : key.trim().toLowerCase();
        return SUPPORTED.contains(k) ? k : DEFAULT_LANGUAGE;
    }
}
