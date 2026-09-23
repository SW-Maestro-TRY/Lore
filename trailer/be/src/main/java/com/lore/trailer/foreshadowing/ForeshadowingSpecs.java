package com.lore.trailer.foreshadowing;

import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * 목록과 검색의 조건. {@link Specification} 으로 짠다 — 검색 단어 수가 그때그때 달라서다.
 *
 * <p>★ 칸 이름을 글자로 적는다({@code "startChapter"}). lore 는 JPA 메타모델을 만들지 않는다.
 * 이름이 엔티티와 어긋나면 첫 호출에서 {@code IllegalArgumentException} 이 난다 — 통합 검사가 그 자리를 밟는다.
 */
final class ForeshadowingSpecs {

    private ForeshadowingSpecs() {
    }

    /** 독자가 읽은 회차 N 이하에 심은 카드만. 모든 목록 조회의 바탕이다. */
    static Specification<Foreshadowing> plantedBy(int chapter) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("startChapter"), chapter);
    }

    /** 유형의 한국어 이름이 똑같은 카드만. */
    static Specification<Foreshadowing> ofKind(String kind) {
        return (root, query, cb) -> cb.equal(root.get("threadKind"), kind);
    }

    /** 카드 번호가 똑같은 한 장. */
    static Specification<Foreshadowing> withThreadId(String threadId) {
        return (root, query, cb) -> cb.equal(root.get("threadId"), threadId);
    }

    /**
     * 단어가 검색용 글에 들어 있는 카드. 단어마다 {@code search_text LIKE '%단어%'} 하나.
     *
     * ★ {@code %} 와 {@code _} 는 글자 그대로 찾는다 — 이스케이프하지 않으면 "%" 한 글자가 모든 카드를 찾는다.
     */
    static Specification<Foreshadowing> containsWord(String word) {
        String pattern = "%" + escapeLike(word) + "%";
        return (root, query, cb) -> cb.like(root.get("searchText"), pattern, '\\');
    }

    /** 검색어 전체를 조건으로. T 번호면 그 한 장, 단어들이면 모두 들어 있는 카드, 비면 조건 없음. */
    static Specification<Foreshadowing> matching(SearchTerms terms) {
        if (terms.threadId().isPresent()) {
            return withThreadId(terms.threadId().get());
        }
        List<Specification<Foreshadowing>> each = new ArrayList<>();
        for (String word : terms.words()) {
            each.add(containsWord(word));
        }
        return Specification.allOf(each);
    }

    /** {@code LIKE} 의 특수 문자 셋을 백슬래시로 가린다. 이스케이프 문자도 자기 자신을 가려야 한다. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
