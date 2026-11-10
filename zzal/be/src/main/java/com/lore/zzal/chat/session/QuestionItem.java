package com.lore.zzal.chat.session;

/**
 * 아이가 묻는 질문 항목 — 순서표. 이미 답이 있는 항목은 건너뛰고, 다 끝나면 없음(스몰톡).
 *
 * ★ 한 세션(대화 한 판)에 항목은 하나만 묻는다 — 매번 묻으면 취조가 된다. 답을 못 얻은 항목은 다음 세션에 다시.
 * ★ 1번(호칭)의 "답이 있다" 는 호칭이 실제로 저장됐는지로 본다(펫 칸 또는 프로필). 나머지는 그 항목을 물은
 *   펫 턴 바로 뒤에 사용자가 답했는지로 본다.
 */
public enum QuestionItem {
    CALL_ME("뭐라고 부를까"),
    WHO("뭐 하는 사람인지"),
    FUN("요즘 재밌는 것"),
    LIKES("좋아하는 것(음식·놀이)"),
    MOOD("지금 기분");

    private final String text;

    QuestionItem(String text) {
        this.text = text;
    }

    /** 지시문에 따옴표째 들어가는 말. */
    public String text() {
        return text;
    }
}
