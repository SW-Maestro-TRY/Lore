package com.lore.zzal.chat.session;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 턴 결정 규칙 — 다음 펫 턴의 종류·질문 허용·질문 항목을 <b>코드가</b> 정한다(LLM 이 고르면 흐름이 흔들린다).
 *
 * <h3>규칙</h3>
 * <ul>
 *   <li>첫 턴 = 세션 종류({@link SessionKind#firstTurn()}): 첫 만남 · 오늘 첫 인사 · 오랜만</li>
 *   <li>사용자 답 뒤: 왕복이 상한({@code maxRounds})에 닿았으면 <b>닫기</b>, 아니면 <b>이어 말하기</b></li>
 *   <li>질문 허용 — 한 턴 걸러(펫 턴 1·3·5번째). 오랜만·닫기는 질문 금지</li>
 *   <li>사용자가 되물었으면(물음표·"뭐야" 류) 그 턴은 <b>답 우선</b> — 질문 금지</li>
 *   <li>질문 항목 — 순서표({@link QuestionItem})에서 아직 답이 없는 첫 항목. <b>한 판에 한 항목</b>,
 *       질문이 허용된 첫 펫 턴에 싣는다. 다 끝났으면 없음</li>
 * </ul>
 */
public final class TurnPlanner {

    /** 마지막 답에서 이만큼 지나 돌아오면 "오랜만". 하룻밤(저녁 답 → 다음 아침)은 오랜만이 아니다. */
    public static final Duration LONG_ABSENCE_AFTER = Duration.ofHours(24);

    /** 마지막 펫 턴 뒤 이만큼 답이 없으면 그 판은 이탈(ABANDONED). 첫 답 전에는 적용하지 않는다(만료 규칙이 따로 있다). */
    public static final Duration ABANDON_AFTER = Duration.ofMinutes(10);

    /** 되묻기 표지 — 물음표 말고도 물음으로 읽히는 끝말. */
    private static final Pattern ASKS = Pattern.compile(
            "[?？]|뭐야|뭐해|뭐하|뭐 해|너는|너도|넌 |어때|어땠|어디야|누구야|왜\\s*$|알아\\s*$");

    private TurnPlanner() {
    }

    /** 판의 첫 펫 턴. */
    public static TurnPlan first(SessionKind kind, Set<QuestionItem> answered) {
        TurnType type = kind.firstTurn();
        boolean allow = type != TurnType.REUNION;
        return new TurnPlan(type, 1, allow, allow ? nextItem(answered) : null, false);
    }

    /**
     * 사용자 답 뒤의 펫 턴.
     *
     * @param rounds        이번 답까지 센 왕복 수
     * @param maxRounds     상한
     * @param petTurnNo     이번에 만들 펫 턴의 번호(1부터)
     * @param userLine      방금 사용자가 한 말
     * @param itemAsked     이번 판에서 이미 항목을 물었나
     * @param answered      답이 있는 항목
     */
    public static TurnPlan next(int rounds, int maxRounds, int petTurnNo, String userLine, boolean itemAsked,
                                Set<QuestionItem> answered) {
        if (rounds >= maxRounds) {
            return new TurnPlan(TurnType.CLOSE, petTurnNo, false, null, false);
        }
        boolean asked = userAsked(userLine);
        boolean allow = petTurnNo % 2 == 1 && !asked;
        QuestionItem item = allow && !itemAsked ? nextItem(answered) : null;
        return new TurnPlan(TurnType.CONTINUE, petTurnNo, allow, item, asked);
    }

    /** 순서표에서 아직 답이 없는 첫 항목. */
    public static QuestionItem nextItem(Set<QuestionItem> answered) {
        for (QuestionItem q : List.of(QuestionItem.values())) {
            if (!answered.contains(q)) {
                return q;
            }
        }
        return null;
    }

    /** 사용자가 되물었나. */
    public static boolean userAsked(String line) {
        return line != null && ASKS.matcher(line.strip()).find();
    }
}
