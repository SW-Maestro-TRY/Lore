package com.lore.webtoon.work;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 작가에게 보여 줄 관리자 처리 한 칸(#638) — 마이페이지 카드 · 휴지통 · 결과 화면이 같은 모양으로 붙인다.
 *
 * <b>주인과 관리자에게만</b> 붙인다. 둘러보기 카드에는 안 붙는다 — 경고 사유는 남이 볼 글이 아니다.
 */
@Service
public class ModerationNotes {

    private final WebtoonWorkRepository works;
    private final ModerationLogRepository logs;

    public ModerationNotes(WebtoonWorkRepository works, ModerationLogRepository logs) {
        this.works = works;
        this.logs = logs;
    }

    /**
     * -> {@code {state, reason, at, warning:{reason, at}}}. 처리도 경고도 없으면 {@code null}.
     *
     * state 는 HIDDEN(관리자 비공개) · REMOVED(관리자 삭제) · 비어 있음(경고만).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> of(String runId) {
        if (runId == null) {
            return null;
        }
        WebtoonWork work = works.findFirstByRunId(runId).orElse(null);
        ModerationLog warn = logs.findFirstByRunIdAndActionOrderByCreatedAtDescIdDesc(runId, WorkModeration.WARN).orElse(null);
        boolean moderated = work != null && work.getModeration() != null;
        if (!moderated && warn == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (moderated) {
            out.put("state", work.getModeration());
            out.put("reason", work.getModerationReason() == null ? "" : work.getModerationReason());
            out.put("at", work.getModeratedAt() == null ? null : work.getModeratedAt().toString());
        }
        if (warn != null) {
            out.put("warning", Map.of("reason", warn.getReason(), "at", warn.getCreatedAt().toString()));
        }
        return out;
    }

    /** 카드에 붙인다. 붙일 것이 없으면 그대로. */
    public void attach(Map<String, Object> card) {
        if (card == null) {
            return;
        }
        Object runId = card.get("run_id");
        Map<String, Object> note = runId == null ? null : of(runId.toString());
        if (note != null) {
            card.put("moderation", note);
        }
    }
}
