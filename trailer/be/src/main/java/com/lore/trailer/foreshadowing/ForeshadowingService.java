package com.lore.trailer.foreshadowing;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.trailer.credit.TrailerCreditPolicy;
import com.lore.trailer.foreshadowing.dto.ForeshadowingResponses;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 카드 API 셋의 일 — 장부 정보 · 목록과 검색 · 상세. 모두 읽기다.
 *
 * <h3>회차 N 이 하는 일 둘</h3>
 * N화 이하에 심은 카드만 고르고({@link ForeshadowingSpecs#plantedBy}), N화 뒤에 회수된 복선을 미회수로 가린다
 * ({@link Foreshadowing#statusAt}). 카드를 내보내는 두 API(목록 · 상세)가 똑같이 한다.
 *
 * <h3>★ 표가 비었으면 503</h3>
 * 운영 DB 에 카드 SQL 을 넣기 전이다. 서버가 아니라 자료가 준비되지 않은 상태라 500 과 갈라 둔다.
 */
@Service
public class ForeshadowingService {

    /** 검색창 아래에 권하는 인물의 수. 화면의 {@code suggestedPeople()} 과 같다. */
    static final int SUGGESTED_PEOPLE = 5;

    /** 권하는 인물을 고를 때 인물 칸을 한 번에 읽는 줄 수. 실제 자료에서는 첫 아홉 줄 안에 다섯이 다 나온다. */
    private static final int PEOPLE_CHUNK = 100;

    private final ForeshadowingRepository repository;
    private final TrailerCreditPolicy creditPolicy;

    public ForeshadowingService(ForeshadowingRepository repository, TrailerCreditPolicy creditPolicy) {
        this.repository = repository;
        this.creditPolicy = creditPolicy;
    }

    /** 장부 정보(2-1). 해시 둘은 아무 줄에서 읽는다 — 줄마다 같은 값이다. */
    @Transactional(readOnly = true)
    public ForeshadowingResponses.Meta meta() {
        Foreshadowing any = repository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new BusinessException(ErrorCode.TRAILER_LEDGER_NOT_LOADED));
        return new ForeshadowingResponses.Meta(
                maxChapterOrThrow(),
                any.getStateDigest(),
                any.getCardsDigest(),
                repository.kindsInFirstSeenOrder(),
                suggestedPeople(),
                creditPolicy.judgeCredits());
    }

    /**
     * 목록과 검색(2-2). 순서는 표의 {@code id} 순 = T 번호 순. 검색 결과도 같은 순서다.
     *
     * @param kind 유형의 한국어 이름. 비면 거르지 않는다
     */
    @Transactional(readOnly = true)
    public ForeshadowingResponses.CardPage list(int chapter, SearchTerms terms, String kind, int page, int size) {
        requireChapterInRange(chapter);

        Specification<Foreshadowing> spec = ForeshadowingSpecs.plantedBy(chapter)
                .and(ForeshadowingSpecs.matching(terms));
        if (kind != null && !kind.isEmpty()) {
            spec = spec.and(ForeshadowingSpecs.ofKind(kind));
        }
        Page<Foreshadowing> found = repository.findAll(spec, PageRequest.of(page, size, Sort.by("id")));
        long chapterTotal = repository.countByStartChapterLessThanEqual(chapter);

        List<ForeshadowingResponses.Card> items = found.getContent().stream()
                .map(f -> ForeshadowingResponses.Card.of(f, chapter))
                .toList();
        return new ForeshadowingResponses.CardPage(chapter, page, size,
                found.getTotalElements(), chapterTotal, found.hasNext(), items);
    }

    /**
     * 상세(2-3). 모르는 번호와 N화 뒤에 심은 카드는 <b>같은</b> 404 다 — 갈라 주면 번호를 바꿔 가며
     * 뒤 회차에 카드가 있는지 알아낼 수 있다(decisions.md 1-25).
     */
    @Transactional(readOnly = true)
    public ForeshadowingResponses.Card detail(String threadId, int chapter) {
        requireChapterInRange(chapter);
        Foreshadowing card = repository.findByThreadId(threadId)
                .filter(f -> f.isPlantedBy(chapter))
                .orElseThrow(() -> new BusinessException(ErrorCode.TRAILER_CARD_NOT_FOUND));
        return ForeshadowingResponses.Card.of(card, chapter);
    }

    /** 1 이상은 부르는 쪽이 이미 봤다. 여기서는 가장 뒤 회차를 넘는지 본다 — 표를 봐야 아는 값이다. 가설 맡기기도 쓴다. */
    public void requireChapterInRange(int chapter) {
        int max = maxChapterOrThrow();
        if (chapter > max) {
            throw new BusinessException(ErrorCode.TRAILER_INVALID_CHAPTER,
                    "회차는 1부터 %d까지입니다".formatted(max));
        }
    }

    private int maxChapterOrThrow() {
        Integer max = repository.maxStartChapter();
        if (max == null) {
            throw new BusinessException(ErrorCode.TRAILER_LEDGER_NOT_LOADED);
        }
        return max;
    }

    /** 카드에 먼저 나온 인물 다섯. 인물 칸을 id 순으로 읽어 처음 보는 이름을 모은다. 화면의 {@code suggestedPeople()} 과 같다. */
    private List<String> suggestedPeople() {
        List<String> people = new ArrayList<>(SUGGESTED_PEOPLE);
        Pageable pageable = PageRequest.of(0, PEOPLE_CHUNK);
        while (true) {
            Slice<String> columns = repository.peopleColumns(pageable);
            for (String column : columns) {
                for (String name : Foreshadowing.splitPeople(column)) {
                    if (!people.contains(name)) {
                        people.add(name);
                        if (people.size() == SUGGESTED_PEOPLE) {
                            return people;
                        }
                    }
                }
            }
            if (!columns.hasNext()) {
                return people;
            }
            pageable = columns.nextPageable();
        }
    }
}
