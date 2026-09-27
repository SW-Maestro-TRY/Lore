package com.lore.webtoon.feedback;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 사용자 검증 설문의 질문 S1~S9 와, 질문마다 받을 수 있는 답(#471).
 *
 * 질문의 뜻과 어느 가설을 재는지는 {@code webtoon/docs/validation.md} 「설문」 표에 있다.
 * 화면은 문구를 들고 있고, 서버는 <b>기호와 허용 값</b>만 안다 — 화면이 보낸 값이 여기
 * 없는 모양이면 받지 않는다. 그래야 집계할 때 값이 몇 가지로만 나온다.
 */
public enum WebtoonFeedbackQuestion {

    /** 다 읽고 "이거 내 캐릭터 얘기 맞네" 싶었나 — H2. 자기 것을 넣은 사람만. */
    S1(Answer.SCALE),
    /** 첫 장면부터 마지막까지 같은 캐릭터로 보였나 — H2a. */
    S2(Answer.SCALE),
    /** 넣은 설정이 그대로 들어갔나 — H2b. 자기 것을 넣은 사람만. */
    S3(Answer.YES_PARTLY_NO),
    /** 생각한 성격대로 말하고 행동했나 — H2c. 설명을 적은 사람만. */
    S4(Answer.SCALE),
    /** 적은 이야기가 들어갔나 — H2d. 줄거리를 적은 사람만. */
    S5(Answer.YES_PARTLY_NO),
    /** 캐릭터와 상관없이 1화 자체가 재미있었나 — H3. */
    S6(Answer.SCALE),
    /** 이 캐릭터의 다음 이야기도 보고 싶나 — 연속성 신호. */
    S7(Answer.YES_NO),
    /** 다시 만들어 볼 건가 — 재사용 신호. */
    S8(Answer.YES_MAYBE_NO),
    /** 기대와 가장 달랐던 곳. */
    S9(Answer.MISMATCH),
    /** 있으면 좋겠는 기능 — 모두 고르기. 다음 기능 우선순위를 정하는 데 쓴다. 전체 설문만. */
    S10(Answer.WANTS);

    /** 전체 설문에서 "해당 없음" 을 고를 수 있는 질문 — 넣은 것이 없으면 답할 수 없는 것들. */
    static final Set<WebtoonFeedbackQuestion> MAY_SKIP = Set.of(S1, S3, S4, S5);

    static final String NOT_APPLICABLE = "na";

    private final Answer answer;

    WebtoonFeedbackQuestion(Answer answer) {
        this.answer = answer;
    }

    /**
     * 받을 수 있는 답이면 저장할 값으로, 아니면 null.
     *
     * @param allowNa 「해당 없음」을 받아도 되는가 (전체 설문의 {@link #MAY_SKIP} 만)
     */
    Object accept(Object raw, boolean allowNa) {
        if (raw == null) {
            return null;
        }
        if (allowNa && MAY_SKIP.contains(this) && NOT_APPLICABLE.equals(raw)) {
            return NOT_APPLICABLE;
        }
        return answer.accept(raw);
    }

    enum Answer {
        SCALE, YES_PARTLY_NO, YES_NO, YES_MAYBE_NO, MISMATCH, WANTS;

        private static final Set<String> YPN = Set.of("yes", "partly", "no");
        private static final Set<String> YN = Set.of("yes", "no");
        private static final Set<String> YMN = Set.of("yes", "maybe", "no");
        private static final Set<String> WHERE = Set.of("look", "persona", "story", "art", "wait", "none");
        /** 여러 캐릭터 · 다음 화 · 장면 하나로 바로 만화 · 지금으로 충분 */
        private static final Set<String> FEATURES = Set.of("multi_char", "next_episode", "scene_comic", "enough");

        Object accept(Object raw) {
            if (this == WANTS) {
                if (!(raw instanceof List<?> list)) {
                    return null;
                }
                Set<String> picked = new LinkedHashSet<>();
                for (Object o : list) {
                    if (o instanceof String s && FEATURES.contains(s)) {
                        picked.add(s);
                    }
                }
                return picked.isEmpty() ? null : new ArrayList<>(picked);
            }
            if (this == SCALE) {
                if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
                    int v = n.intValue();
                    return v >= 1 && v <= 5 ? v : null;
                }
                return null;
            }
            if (!(raw instanceof String s)) {
                return null;
            }
            Set<String> ok = switch (this) {
                case YES_PARTLY_NO -> YPN;
                case YES_NO -> YN;
                case YES_MAYBE_NO -> YMN;
                default -> WHERE;
            };
            return ok.contains(s) ? s : null;
        }
    }
}
