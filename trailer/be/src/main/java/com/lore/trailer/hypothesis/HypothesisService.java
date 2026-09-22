package com.lore.trailer.hypothesis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.trailer.foreshadowing.Foreshadowing;
import com.lore.trailer.foreshadowing.ForeshadowingRepository;
import com.lore.trailer.foreshadowing.ForeshadowingService;
import com.lore.trailer.foreshadowing.dto.ForeshadowingResponses;
import com.lore.trailer.hypothesis.dto.HypothesisRequests;
import com.lore.trailer.hypothesis.dto.HypothesisResponses;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 가설 — 맡기기(2-5), 하나 보기(2-6), 보관함(2-7), 운영자의 가져가기(2-8)와 판정 넣기(2-9).
 *
 * <p>검사는 모두 여기서 한다. 틀린 칸마다 한국어 문구를 붙여 400 {@code INVALID_INPUT} 으로 답한다.
 * 회차는 카드 API 와 같은 코드({@code TRAILER_INVALID_CHAPTER})다. 해시 둘이 카드 표의 값과 다르면
 * {@code TRAILER_DIGEST_MISMATCH} 다 — 표를 갈아 넣은 뒤 옛 화면이 맡기는 가설은 운영자 PC 의 judge.py 가
 * 어차피 거절하므로, 독자가 기다리다 실패를 보는 대신 맡기는 순간에 막는다(NA decisions.md 1-30 의 제안).
 */
@Service
public class HypothesisService {

    static final int TITLE_MAX = 180;
    static final int CLAIM_MAX = 6_000;
    static final int NOTE_MAX = 4_000;
    /** 한 가설에 담을 수 있는 카드 수. 명세에는 위 끝이 없지만 몸통 크기와 복사 횟수에 끝이 있어야 한다. */
    static final int CARDS_MAX = 100;

    private static final Pattern THREAD_ID = Pattern.compile("^T[0-9]{1,6}$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final HypothesisRepository hypotheses;
    private final ForeshadowingRepository foreshadowings;
    private final ForeshadowingService foreshadowingService;
    private final TrailerAdminGuard adminGuard;

    public HypothesisService(HypothesisRepository hypotheses, ForeshadowingRepository foreshadowings,
                             ForeshadowingService foreshadowingService, TrailerAdminGuard adminGuard) {
        this.hypotheses = hypotheses;
        this.foreshadowings = foreshadowings;
        this.foreshadowingService = foreshadowingService;
        this.adminGuard = adminGuard;
    }

    /** 가설을 맡긴다(2-5). 담은 카드를 그 회차로 가린 값으로 복사해 두고 PENDING 으로 저장한다. */
    @Transactional
    public HypothesisResponses.Hypothesis submit(Long userId, HypothesisRequests.Submit body) {
        int chapter = requireChapter(body.chapter());
        Foreshadowing ledger = foreshadowings.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new BusinessException(ErrorCode.TRAILER_LEDGER_NOT_LOADED));
        requireDigest(body.stateDigest(), ledger.getStateDigest(), "stateDigest");
        requireDigest(body.cardsDigest(), ledger.getCardsDigest(), "cardsDigest");
        String title = bounded(body.title(), "제목", TITLE_MAX);
        String claim = required(body.claim(), "주장", CLAIM_MAX);
        List<String> ids = requireCards(body.cards());
        Map<String, String> notes = requireNotes(body.notes(), ids);

        Hypothesis hypothesis = Hypothesis.submit(userId, chapter, title, claim,
                ledger.getStateDigest(), ledger.getCardsDigest(), Instant.now());
        for (int position = 0; position < ids.size(); position++) {
            String id = ids.get(position);
            Foreshadowing card = foreshadowings.findByThreadId(id)
                    .filter(f -> f.isPlantedBy(chapter))
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT,
                            "%d화 기록에 없는 카드입니다: %s".formatted(chapter, id)));
            hypothesis.addCard(HypothesisForeshadowing.copyOf(hypothesis, card, chapter, position, notes.get(id)));
        }
        return toResponse(hypotheses.save(hypothesis));
    }

    /** 내 가설 하나(2-6). 없는 번호와 남의 가설은 같은 404 다 — 번호를 바꿔 가며 남의 가설이 있는지 알아낼 수 없게. */
    @Transactional(readOnly = true)
    public HypothesisResponses.Hypothesis get(Long userId, String rawId) {
        long id = parseId(rawId);
        Hypothesis hypothesis = hypotheses.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRAILER_HYPOTHESIS_NOT_FOUND));
        return toResponse(hypothesis);
    }

    /** 내 가설 보관함(2-7). 최신이 앞. 카드는 싸지 않으니 목록에는 실지 않는다 — 하나를 누르면 2-6 으로 받는다. */
    @Transactional(readOnly = true)
    public HypothesisResponses.MyList my(Long userId) {
        List<HypothesisResponses.Summary> items = hypotheses.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(HypothesisResponses.Summary::of)
                .toList();
        return new HypothesisResponses.MyList(items);
    }

    /* ---- 운영자 ------------------------------------------------------------------ */

    /** 운영자가 가져갈 것(2-8). PENDING 만, 오래된 것이 앞. 운영자가 아니면 403. */
    @Transactional(readOnly = true)
    public HypothesisResponses.PendingList pendingForOperator(Long operatorId) {
        adminGuard.require(operatorId);
        List<HypothesisResponses.Pending> items = new ArrayList<>();
        for (Hypothesis h : hypotheses.pendingOldestFirst()) {
            List<ForeshadowingResponses.Card> cards = new ArrayList<>();
            Map<String, String> notes = new LinkedHashMap<>();
            for (HypothesisForeshadowing copy : h.getCards()) {
                cards.add(copy.toCard());
                notes.put(copy.getThreadId(), copy.getNote());
            }
            items.add(new HypothesisResponses.Pending(h.getId(), h.getChapter(), h.getTitle(), h.getClaim(), cards, notes,
                    h.getStateDigest(), h.getCardsDigest(), h.getCreatedAt()));
        }
        return new HypothesisResponses.PendingList(items);
    }

    static final int FAILURE_MESSAGE_MAX = 1_000;
    private static final Set<String> GRADES = Set.of("likely", "unlikely", "insufficient");

    /**
     * 운영자가 판정을 넣는다(2-9). COMPLETE 면 판정(과 편집본)을, FAILED 면 실패 문구를 넣고 judgedAt 을 찍는다.
     * 이미 판정한 가설도 덮어쓴다 — 다시 돌린 결과를 넣을 수 있게(decisions.md 2-24). 서버는 판정의 안을 읽지 않는다.
     * 모양만 본다: grade 셋 가운데 하나, reason 은 글, support · against 는 글의 배열.
     */
    @Transactional
    public HypothesisResponses.Hypothesis judgeForOperator(Long operatorId, HypothesisRequests.Judge body) {
        adminGuard.require(operatorId);
        if (body.id() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "id가 필요합니다");
        }
        Hypothesis hypothesis = hypotheses.findById(body.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.TRAILER_HYPOTHESIS_NOT_FOUND));
        String status = body.judgementStatus() == null ? "" : body.judgementStatus();
        switch (status) {
            case Hypothesis.COMPLETE -> hypothesis.complete(writeJudgement(body.judgement()),
                    body.presentation() == null ? null : write(body.presentation()), Instant.now());
            case Hypothesis.FAILED -> hypothesis.fail(required(body.failureMessage(), "실패 문구", FAILURE_MESSAGE_MAX), Instant.now());
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT, "judgementStatus는 COMPLETE 또는 FAILED 입니다");
        }
        return toResponse(hypothesis);
    }

    /** 판정의 모양을 보고 JSON 글로 만든다. 안(reason 의 내용, cited_cards)은 읽지 않는다. */
    private static String writeJudgement(Map<String, Object> judgement) {
        if (judgement == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "COMPLETE 에는 judgement가 필요합니다");
        }
        Object grade = judgement.get("grade");
        if (!(grade instanceof String g) || !GRADES.contains(g)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "judgement.grade는 likely · unlikely · insufficient 가운데 하나입니다");
        }
        if (!(judgement.get("reason") instanceof String reason) || reason.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "judgement.reason이 비어 있습니다");
        }
        for (String field : List.of("support", "against")) {
            if (!(judgement.get(field) instanceof List<?> ids) || !ids.stream().allMatch(String.class::isInstance)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "judgement." + field + "는 카드 번호의 배열입니다");
            }
        }
        return write(judgement);
    }

    private static String write(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "JSON으로 쓸 수 없는 값입니다");
        }
    }

    /** 경로의 id. 숫자가 아니면 그런 가설이 없는 것이다(404) — 500 이 되지 않게 직접 읽는다(found.md 5-8). */
    static long parseId(String raw) {
        try {
            long id = Long.parseLong(raw == null ? "" : raw.trim());
            if (id < 1) {
                throw new BusinessException(ErrorCode.TRAILER_HYPOTHESIS_NOT_FOUND);
            }
            return id;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.TRAILER_HYPOTHESIS_NOT_FOUND);
        }
    }

    /* ---- 검사 -------------------------------------------------------------------- */

    /** 회차. 없거나 1보다 작거나 가장 뒤 회차를 넘으면 400 {@code TRAILER_INVALID_CHAPTER}. 표가 비었으면 503. */
    private int requireChapter(Integer raw) {
        if (raw == null) {
            throw new BusinessException(ErrorCode.TRAILER_INVALID_CHAPTER, "회차가 필요합니다");
        }
        if (raw < 1) {
            throw new BusinessException(ErrorCode.TRAILER_INVALID_CHAPTER, "회차는 1 이상의 숫자여야 합니다");
        }
        foreshadowingService.requireChapterInRange(raw);
        return raw;
    }

    private static void requireDigest(String given, String expected, String name) {
        if (given == null || given.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, name + "가 필요합니다");
        }
        if (!given.equals(expected)) {
            throw new BusinessException(ErrorCode.TRAILER_DIGEST_MISMATCH);
        }
    }

    /** 비어도 되는 글. 없으면 빈 글. 위 끝을 넘으면 400. */
    private static String bounded(String value, String name, int max) {
        String text = value == null ? "" : value;
        if (text.length() > max) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "%s은 %,d자까지입니다".formatted(name, max));
        }
        return text;
    }

    /** 비면 안 되는 글. 빈칸만 있어도 400. */
    private static String required(String value, String name, int max) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, name + "이 비어 있습니다");
        }
        return bounded(value, name, max);
    }

    private static List<String> requireCards(List<String> cards) {
        if (cards == null || cards.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "카드를 하나 이상 담아야 합니다");
        }
        if (cards.size() > CARDS_MAX) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "카드는 %d장까지 담을 수 있습니다".formatted(CARDS_MAX));
        }
        Set<String> seen = new HashSet<>();
        List<String> ids = new ArrayList<>(cards.size());
        for (String id : cards) {
            if (id == null || !THREAD_ID.matcher(id).matches()) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "카드 번호가 올바르지 않습니다: " + id);
            }
            if (!seen.add(id)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "카드가 겹칩니다: " + id);
            }
            ids.add(id);
        }
        return ids;
    }

    /** 해석. 담은 카드마다 한 칸으로 채운다(없으면 빈 글). 담지 않은 카드의 해석과 위 끝을 넘는 해석은 400. */
    private static Map<String, String> requireNotes(Map<String, String> notes, List<String> ids) {
        Map<String, String> filled = new LinkedHashMap<>();
        for (String id : ids) {
            filled.put(id, "");
        }
        if (notes == null) {
            return filled;
        }
        for (Map.Entry<String, String> entry : notes.entrySet()) {
            if (!filled.containsKey(entry.getKey())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "담지 않은 카드의 해석입니다: " + entry.getKey());
            }
            String note = entry.getValue() == null ? "" : entry.getValue();
            if (note.length() > NOTE_MAX) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        "해석은 %,d자까지입니다: %s".formatted(NOTE_MAX, entry.getKey()));
            }
            filled.put(entry.getKey(), note);
        }
        return filled;
    }

    /* ---- 응답 -------------------------------------------------------------------- */

    static HypothesisResponses.Hypothesis toResponse(Hypothesis h) {
        List<ForeshadowingResponses.Card> cards = new ArrayList<>();
        Map<String, String> notes = new LinkedHashMap<>();
        for (HypothesisForeshadowing copy : h.getCards()) {
            cards.add(copy.toCard());
            notes.put(copy.getThreadId(), copy.getNote());
        }
        return new HypothesisResponses.Hypothesis(h.getId(), h.getChapter(), h.getTitle(), h.getClaim(), cards, notes,
                h.getJudgementStatus(), readObject(h.getJudgement()), readObject(h.getPresentation()),
                h.getFailureMessage(), h.getCreatedAt(), h.getJudgedAt());
    }

    /** 표의 JSON 글을 응답의 객체로. 표에 넣을 때 객체였음을 확인했으므로 여기서 틀리면 서버 오류다. */
    static Map<String, Object> readObject(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, OBJECT);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("표의 JSON 칸을 읽지 못했습니다", e);
        }
    }
}
