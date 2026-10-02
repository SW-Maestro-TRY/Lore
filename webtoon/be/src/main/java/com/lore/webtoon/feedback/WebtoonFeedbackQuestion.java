package com.lore.webtoon.feedback;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 사용자 검증 설문의 질문 S0(만족도) · S1~S8 · S10 과, 질문마다 받을 수 있는 답(#471).
 *
 * 질문의 뜻과 어느 가설을 재는지는 {@code webtoon/docs/validation.md} 「설문」 표에 있다.
 * 화면은 문구를 들고 있고, 서버는 <b>기호와 허용 값</b>만 안다 — 화면이 보낸 값이 여기
 * 없는 모양이면 받지 않는다. 그래야 집계할 때 값이 몇 가지로만 나온다.
 */
public enum WebtoonFeedbackQuestion {

    /** LORE 서비스에 전체적으로 만족했나(1~5). 모든 설문에서 맨 앞에 고정. */
    S0(Answer.SCALE),

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
    /** 이 캐릭터의 다음 이야기도 보고 싶나 — 연속성 신호. 2026-10-02 부터 묻지 않는다(예전 답을 읽으려고 남김). */
    S7(Answer.YES_NO),
    /** 다시 만들어 볼 의향이 있나 — 재사용 신호. 2026-10-02 부터 묻지 않는다(예전 답을 읽으려고 남김). */
    S8(Answer.YES_MAYBE_NO),
    /** 있으면 좋겠는 기능 — 있으면 모두 고르기(답 안 해도 됨). 다음 기능 우선순위를 정하는 데 쓴다. 전체 설문만. */
    S10(Answer.WANTS);

    private final Answer answer;

    WebtoonFeedbackQuestion(Answer answer) {
        this.answer = answer;
    }

    /**
     * 「아니오」를 골랐을 때만 더 묻는 것(선택). 본 답이 그 값일 때만 받는다.
     * <ul>
     *   <li>{@code S3_note} — 넣은 설정이 안 들어갔다면 무엇이 달라졌나 (글)</li>
     *   <li>{@code S7_why} — 다음 이야기가 안 궁금한 이유 (not_fun · not_curious · enough)</li>
     *   <li>{@code S7_note} — 그 밖의 이유 (글)</li>
     * </ul>
     */
    static final int MAX_NOTE = 500;

    static final Set<String> S7_WHY = Set.of("not_fun", "not_curious", "enough");

    /** 받을 수 있는 답이면 저장할 값으로, 아니면 null. */
    Object accept(Object raw) {
        return raw == null ? null : answer.accept(raw);
    }

    enum Answer {
        SCALE, YES_PARTLY_NO, YES_NO, YES_MAYBE_NO, WANTS;

        private static final Set<String> YPN = Set.of("yes", "partly", "no");
        private static final Set<String> YN = Set.of("yes", "no");
        private static final Set<String> YMN = Set.of("yes", "maybe", "no");
        /** 여러 캐릭터 · 다음 화 · 컷마다 그림 따로 · 숏츠로 만들기 · 내 캐릭터 공개 · 커뮤니티 ·
         *  그림체 생성·추가 · 로어북. 뺀 값(장면 하나로 바로 만화 · 대사·컷 직접 설계 · 지금으로
         *  충분)은 더 받지 않는다 — 예전에 받은 답은 그대로 남아 있다. */
        private static final Set<String> FEATURES = Set.of(
                "multi_char", "next_episode", "cut_image", "trailer_share", "character_lend",
                "community", "style_add", "lorebook");

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
                default -> YMN;
            };
            return ok.contains(s) ? s : null;
        }
    }
}
