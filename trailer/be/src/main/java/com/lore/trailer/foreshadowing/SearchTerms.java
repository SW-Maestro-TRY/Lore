package com.lore.trailer.foreshadowing;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 검색어 — 화면의 규칙({@code trailer/fe/lib/search.ts} 의 {@code matchesCard})을 서버에 옮긴 것.
 *
 * <ul>
 *   <li>{@code T12} 꼴이면 그 번호의 카드 한 장만 찾는다. 대소문자를 가리지 않는다</li>
 *   <li>그 밖에는 빈칸으로 나눈 단어가 <b>모두</b> 카드의 검색용 글에 들어 있어야 한다.
 *       단어는 소문자로 바꾸고 빈칸을 없앤다 — 검색용 글도 그렇게 만들어져 있다({@code search_text})</li>
 *   <li>비면 아무것도 거르지 않는다</li>
 * </ul>
 *
 * <h3>★ 화면과 다른 곳은 위 끝 하나다</h3>
 * 200자 · 단어 10개까지 받는다. 넘으면 400 {@code INVALID_INPUT}. 브라우저 안에서 찾을 때는 상한이 없었지만
 * 서버가 찾으면서 단어마다 {@code LIKE} 가 하나씩 붙는다(decisions.md 2-16).
 */
public final class SearchTerms {

    public static final int MAX_LENGTH = 200;
    public static final int MAX_WORDS = 10;

    private static final SearchTerms EMPTY = new SearchTerms(null, List.of());

    /** {@code /^T\d+$/i}. 앞뒤 빈칸을 뗀 검색어 전체가 이 꼴이어야 한다. */
    private static final Pattern THREAD_ID = Pattern.compile("^T\\d+$", Pattern.CASE_INSENSITIVE);

    /**
     * 빈칸. ★ 유니코드 빈칸까지 본다 — 검색용 글을 만든 파이썬의 {@code \s} 와 화면의 {@code /\s+/} 가 그렇다.
     * 자바의 기본 {@code \s} 는 아스키 빈칸만 봐서 전각 빈칸(U+3000)이 남는다.
     */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private final String threadId;
    private final List<String> words;

    private SearchTerms(String threadId, List<String> words) {
        this.threadId = threadId;
        this.words = words;
    }

    /** 요청의 {@code search} 를 읽는다. null 과 빈칸뿐인 글은 빈 검색어다. */
    public static SearchTerms parse(String raw) {
        if (raw == null) {
            return EMPTY;
        }
        if (raw.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "검색어는 %d자까지입니다".formatted(MAX_LENGTH));
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return EMPTY;
        }
        if (THREAD_ID.matcher(trimmed).matches()) {
            // "t12" 도 T12 다. 번호 부분은 그대로 둔다 — "T012" 는 화면에서도 T12 를 찾지 못했다.
            return new SearchTerms("T" + trimmed.substring(1), List.of());
        }
        List<String> words = WHITESPACE.splitAsStream(trimmed).map(SearchTerms::normalize).toList();
        if (words.size() > MAX_WORDS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "검색어는 단어 %d개까지입니다".formatted(MAX_WORDS));
        }
        return new SearchTerms(null, words);
    }

    /** 소문자로 바꾸고 빈칸을 모두 없앤다. 검색용 글({@code search_text})을 만든 규칙과 같다. */
    public static String normalize(String text) {
        return WHITESPACE.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /** 거를 것이 없는 검색어인가. */
    public boolean isEmpty() {
        return threadId == null && words.isEmpty();
    }

    /** {@code T12} 꼴이었으면 그 번호(대문자 T). */
    public Optional<String> threadId() {
        return Optional.ofNullable(threadId);
    }

    /** 모두 들어 있어야 하는 단어. 소문자·빈칸 없음. */
    public List<String> words() {
        return words;
    }
}
