package com.lore.zzal.chat.line;

import com.lore.zzal.chat.BanFilter;
import com.lore.zzal.chat.prompt.ChatContext;
import com.lore.zzal.chat.prompt.PromptAssembler;

import java.util.regex.Pattern;

/**
 * 생성된 대사의 출력 검사 — 빈 줄·길이·괄호(지문)·이모지·질문 수·금칙(원망·위험 소재). 걸리면 그 사유를 돌려준다.
 *
 * <h3>★ 독립 모듈인 이유</h3>
 * 생성기가 무엇이든 나가는 문은 하나여야 한다. v2 의 "걸리면 재생성 1회" 는 이 사유를 보고 {@link LineChain} 이
 * 생성기를 한 번 더 부르는 자리에 붙는다. 템플릿 대사는 여기를 거치지 않는다(원망 필터만 {@link BanFilter#clean}).
 */
public final class LineFilter {

    private LineFilter() {
    }

    /** 지문·동작 묘사의 흔적 — 괄호류와 별표. */
    private static final Pattern BRACKETS = Pattern.compile("[()\\[\\]{}（）［］【】<>〈〉《》*]");

    /** 통과면 null, 아니면 사유(blank · length · bracket · emoji · questions · asked · resent · unsafe). */
    public static String check(String line, ChatContext ctx) {
        if (line == null || line.isBlank()) {
            return "blank";
        }
        if (line.codePointCount(0, line.length()) > PromptAssembler.LINE_MAX) {
            return "length";
        }
        if (BRACKETS.matcher(line).find()) {
            return "bracket";
        }
        if (hasEmoji(line)) {
            return "emoji";
        }
        long questions = line.chars().filter(c -> c == '?' || c == '？').count();
        if (questions > 1) {
            return "questions";
        }
        // 질문 금지 턴(짝수 턴·되묻기 답·오랜만·닫기)에 물음표가 있으면 지시문만 믿지 않고 폴백한다.
        if (questions > 0 && ctx != null && ctx.plan() != null && !ctx.plan().allowQuestion()) {
            return "asked";
        }
        return BanFilter.llmViolation(line);
    }

    /** 그림 글자(이모지·딩뱃·하트 기호 등)가 있나. 한글·문장부호·말줄임표(…)·물결(~)은 아니다. */
    static boolean hasEmoji(String s) {
        return s.codePoints().anyMatch(cp ->
                (cp >= 0x1F000 && cp <= 0x1FAFF)        // 이모지·그림문자
                        || (cp >= 0x2600 && cp <= 0x27BF)    // 기타 기호·딩뱃(☀ ✨ ❤)
                        || (cp >= 0x2B00 && cp <= 0x2BFF)    // 화살표·별(⭐)
                        || cp == 0xFE0F || cp == 0x200D      // 이모지 변형·결합자
                        || cp == 0x2665 || cp == 0x2661);    // ♥ ♡
    }
}
