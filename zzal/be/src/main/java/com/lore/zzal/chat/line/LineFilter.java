package com.lore.zzal.chat.line;

import com.lore.zzal.chat.BanFilter;
import com.lore.zzal.chat.prompt.ChatContext;
import com.lore.zzal.chat.prompt.PromptAssembler;

/**
 * 생성된 대사의 출력 검사 — 금칙(원망·위험 소재)·길이·질문 수. 걸리면 그 사유를 돌려준다.
 *
 * <h3>★ 독립 모듈인 이유</h3>
 * 생성기가 무엇이든(LLM 모델이 바뀌어도) 나가는 문은 하나여야 한다. v2 의 "걸리면 재생성 1회" 는
 * 이 검사의 사유를 보고 생성기를 한 번 더 부르는 자리({@code LineChain})에 붙는다.
 *
 * ★ 템플릿 대사는 여기를 거치지 않는다 — 우리가 쓴 문장이라 길이·질문 규칙이 다르고, 원망 필터는
 *   예전처럼 {@link BanFilter#clean} 이 건다.
 */
public final class LineFilter {

    private LineFilter() {
    }

    /** 통과면 null, 아니면 사유(blank · length · questions · asked · resent · unsafe). */
    public static String check(String line, ChatContext ctx) {
        if (line == null || line.isBlank()) {
            return "blank";
        }
        if (line.codePointCount(0, line.length()) > PromptAssembler.LINE_MAX) {
            return "length";
        }
        long questions = line.chars().filter(c -> c == '?' || c == '？').count();
        if (questions > 1) {
            return "questions";
        }
        String banned = BanFilter.llmViolation(line);
        if (banned != null) {
            return banned;
        }
        return null;
    }
}
