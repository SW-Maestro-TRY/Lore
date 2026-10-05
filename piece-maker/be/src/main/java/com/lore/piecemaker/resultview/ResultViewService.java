package com.lore.piecemaker.resultview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.piecemaker.metapixel.MetaPixelSignalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** 정상 결과 열람만 기록한다. DB 오류는 호출자에게 전파해 재시도를 가능하게 한다. */
@Service
public class ResultViewService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> GRADES = Set.of("likely", "unlikely", "insufficient");
    private final ResultViewRepository repository;
    private final boolean analyticsEnabled;
    private final MetaPixelSignalService metaPixel;

    public ResultViewService(ResultViewRepository repository,
                             @Value("${app.analytics.enabled:true}") boolean analyticsEnabled,
                             MetaPixelSignalService metaPixel) {
        this.repository = repository;
        this.analyticsEnabled = analyticsEnabled;
        this.metaPixel = metaPixel;
    }

    @Transactional
    public ResultViewResponse record(Long userId, String id) {
        if (userId == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (!analyticsEnabled) throw new BusinessException(ErrorCode.PIECE_MAKER_RESULT_VIEW_DISABLED);
        long hypothesisId = requireId(id);
        var result = repository.lockOwnedResult(hypothesisId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PIECE_MAKER_HYPOTHESIS_NOT_FOUND));
        if (!"COMPLETE".equals(result.status()) || !readable(result.judgement())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "정상 판정 결과를 확인할 수 없습니다");
        }
        var insertedAt = repository.insertFirst(userId, hypothesisId);
        return insertedAt.map(at -> new ResultViewResponse(true, at, metaPixel.forFirstView(userId)))
                .orElseGet(() -> new ResultViewResponse(false, repository.firstViewedAt(userId), null));
    }

    private static long requireId(String id) {
        try {
            long parsed = Long.parseLong(id);
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) { }
        throw new BusinessException(ErrorCode.PIECE_MAKER_HYPOTHESIS_NOT_FOUND);
    }

    private static boolean readable(String judgement) {
        if (judgement == null) return false;
        try {
            JsonNode value = JSON.readTree(judgement);
            return value != null && value.isObject()
                    && GRADES.contains(value.path("grade").asText(""))
                    && value.path("reason").isTextual() && !value.path("reason").asText().isBlank()
                    && cardIds(value.path("support")) && cardIds(value.path("against"));
        } catch (JsonProcessingException ignored) {
            return false;
        }
    }

    private static boolean cardIds(JsonNode ids) {
        if (!ids.isArray()) return false;
        for (JsonNode id : ids) {
            if (!id.isTextual() || !id.asText().matches("T[0-9]{1,6}")) return false;
        }
        return true;
    }
}
