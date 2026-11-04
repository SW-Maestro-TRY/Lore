package com.lore.webtoon.runs;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.Admins;
import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.work.WorkLedger;
import com.lore.webtoon.work.WorkVisibility;
import io.swagger.v3.oas.annotations.Operation;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작품 카드의 썸네일(2026-11-04) — 보기와 고치기. 무엇을 어떻게 자르는지는 {@link RunThumbs}.
 *
 * <p>보기({@code GET /runs/{id}/thumb})는 장 그림과 같은 문지기를 지난다 — 공개면 누구나, 비공개면 주인 · 관리자,
 * 링크 공개는 주소의 열쇠가 맞을 때. 썸네일이 아직 없으면 기본 썸네일을 뒤에서 만들게 하고 이번에는 첫 장 그림을 준다.
 */
@RestController
public class ThumbController {

    private final RunThumbs thumbs;
    private final RunService runs;
    private final PageStore pages;
    private final WorkLedger ledger;
    private final Admins admins;

    public ThumbController(RunThumbs thumbs, RunService runs, PageStore pages, WorkLedger ledger, Admins admins) {
        this.thumbs = thumbs;
        this.runs = runs;
        this.pages = pages;
        this.ledger = ledger;
        this.admins = admins;
    }

    @Operation(summary = "작품 썸네일", description = "잘라 둔 한 장면으로 넘긴다(302). 아직 없으면 뒤에서 만들고 이번에는 첫 장 그림.")
    @GetMapping(WebtoonApi.V1 + "/runs/{runId}/thumb")
    public ResponseEntity<Void> thumb(@PathVariable String runId) {
        if (runs.isTrashed(runId) || !mayRead(runId)) {
            return ResponseEntity.notFound().build();
        }
        String where = thumbs.urlOf(runId);
        if (where == null) {
            List<Integer> numbers = pages.pageNumbersOf(runId);
            if (numbers.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            thumbs.ensure(runId, numbers.getFirst());
            where = pages.urlOf(runId, numbers.getFirst(), 1080);
        }
        return where == null ? ResponseEntity.notFound().build()
                : ResponseEntity.status(302).location(URI.create(where)).build();
    }

    @Operation(summary = "썸네일 자리", description = "지금 어느 장의 어디를 잘랐는지와 장 번호들. 주인 · 관리자만.")
    @GetMapping(WebtoonApi.V1 + "/my/runs/{runId}/thumb")
    public Map<String, Object> where(@PathVariable String runId) {
        mustOwn(runId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pages", pages.pageNumbersOf(runId));
        Map<String, Object> now = thumbs.describe(runId);
        if (now != null) {
            out.putAll(now);
        }
        return out;
    }

    /** 고른 자리 — 장 번호와 그 장 안의 자리(0~1 비율). */
    public record ThumbRequest(int page, double x, double y, double w, double h) {
    }

    @Operation(summary = "썸네일 바꾸기", description = "고른 장의 고른 자리를 잘라 썸네일로 쓴다. 주인 · 관리자만.")
    @PutMapping(WebtoonApi.V1 + "/my/runs/{runId}/thumb")
    public Map<String, Object> set(@PathVariable String runId, @RequestBody ThumbRequest req) {
        mustOwn(runId);
        if (!pages.pageNumbersOf(runId).contains(req.page())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "없는 장이에요.");
        }
        if (!thumbs.set(runId, new RunThumbs.Box(req.page(), req.x(), req.y(), req.w(), req.h()))) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "썸네일을 못 만들었어요. 잠시 뒤에 다시 해 주세요.");
        }
        return Map.of("ok", true);
    }

    @Operation(summary = "썸네일 기본으로", description = "고른 썸네일을 버린다. 다음에 볼 때 기본(가장 큰 컷)을 다시 만든다.")
    @DeleteMapping(WebtoonApi.V1 + "/my/runs/{runId}/thumb")
    public Map<String, Object> reset(@PathVariable String runId) {
        mustOwn(runId);
        thumbs.reset(runId);
        return Map.of("ok", true);
    }

    private boolean mayRead(String runId) {
        Long userId = CreditGate.currentUser();
        return ledger.mayRead(runId, userId, WorkVisibility.requestKey())
                || (userId != null && admins.isAdmin(userId));
    }

    private void mustOwn(String runId) {
        Long userId = CreditGate.currentUser();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "로그인해야 바꿀 수 있어요.");
        }
        if (!admins.isAdmin(userId) && !ledger.mayChange(runId, userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "내가 만든 작품만 바꿀 수 있어요.");
        }
    }
}
