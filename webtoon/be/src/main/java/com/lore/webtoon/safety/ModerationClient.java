package com.lore.webtoon.safety;

import java.util.Map;

/**
 * 글이 정책에 걸리는지 묻는 자리. 실제 구현은 {@link OpenAiModerationClient}, 테스트는 가짜를 끼운다.
 */
public interface ModerationClient {

    /** 한 번 물은 결과. {@code scores} 는 분류 이름 → 0~1. */
    record Verdict(boolean flagged, Map<String, Boolean> categories, Map<String, Double> scores) {
        public static Verdict clean() {
            return new Verdict(false, Map.of(), Map.of());
        }
    }

    /**
     * @return 판정. <b>못 물어봤으면(키 없음·통신 실패) 예외를 던진다</b> — 통과로 속이지 않는다.
     *         통과·차단을 정하는 것은 {@link SafetyGuard} 다.
     */
    Verdict moderate(String text) throws ModerationUnavailable;

    /**
     * 여러 글을 묻는다 — 걸린 자리를 찾을 때만 쓴다({@link SafetyGuard#locate}). 결과는 같은 순서다.
     * 기본은 하나씩 묻고, 실제 구현은 한 번의 요청으로 묻는다.
     */
    default java.util.List<Verdict> moderateAll(java.util.List<String> texts) throws ModerationUnavailable {
        java.util.List<Verdict> out = new java.util.ArrayList<>();
        for (String t : texts) {
            out.add(moderate(t));
        }
        return out;
    }

    /** 물어볼 수 없었다. 원인은 로그에, 사용자에게는 안 보인다. */
    class ModerationUnavailable extends Exception {
        public ModerationUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
