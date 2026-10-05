package com.lore.webtoon.work;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.Admins;
import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.credit.CreditGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 관리자 작품 처리(#638) — 관리자(`role = ADMIN`)만. 로그인 안 했으면 401, 관리자가 아니면 403 이고 그때는 아무것도 안 한다.
 *
 * 처리 규칙은 {@link WorkModeration}. 비공개 · 삭제 · 경고는 사유가 꼭 있어야 하고(작가에게 그대로 보인다),
 * 작가에게 메일이 간다.
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(WebtoonApi.V1 + "/admin/works")
public class ModerationController {

    private final Admins admins;
    private final WorkModeration moderation;

    public ModerationController(Admins admins, WorkModeration moderation) {
        this.admins = admins;
        this.moderation = moderation;
    }

    /** 사유 한 줄. 다시 공개 · 되살리기는 비워도 된다. */
    public record ReasonRequest(String reason) {
    }

    private Long requireAdmin() {
        Long me = CreditGate.currentUser();
        if (me == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "로그인이 필요합니다");
        }
        if (!admins.isAdmin(me)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "관리자만 쓸 수 있습니다");
        }
        return me;
    }

    private static String reasonOf(ReasonRequest body) {
        return body == null ? null : body.reason();
    }

    @Operation(summary = "작품 처리 상태", description = "지금 상태 · 작가(이메일 · 처리 받은 수) · 이 작품의 지난 처리.")
    @GetMapping("/{runId}")
    public Map<String, Object> state(@PathVariable String runId) {
        requireAdmin();
        return moderation.state(runId);
    }

    @Operation(summary = "비공개 처리", description = "둘러보기에서 빠지고 그림도 안 열리는 자리로. 작가는 다시 공개로 못 바꾼다. 작가에게 메일.")
    @PostMapping("/{runId}/hide")
    public Map<String, Object> hide(@PathVariable String runId, @RequestBody(required = false) ReasonRequest body) {
        return moderation.hide(requireAdmin(), runId, reasonOf(body));
    }

    @Operation(summary = "다시 공개", description = "관리자 비공개를 거둔다. 처리 전 공개 여부로 돌아간다.")
    @PostMapping("/{runId}/unhide")
    public Map<String, Object> unhide(@PathVariable String runId, @RequestBody(required = false) ReasonRequest body) {
        return moderation.unhide(requireAdmin(), runId, reasonOf(body));
    }

    @Operation(summary = "삭제 처리", description = "관리자 휴지통. 작가는 못 되살리고, 기간이 지나면 영구 삭제된다. 작가에게 메일.")
    @PostMapping("/{runId}/remove")
    public Map<String, Object> remove(@PathVariable String runId, @RequestBody(required = false) ReasonRequest body) {
        return moderation.remove(requireAdmin(), runId, reasonOf(body));
    }

    @Operation(summary = "되살리기", description = "관리자 휴지통에서 꺼낸다. 처리 전 공개 여부로 돌아간다.")
    @PostMapping("/{runId}/restore")
    public Map<String, Object> restore(@PathVariable String runId, @RequestBody(required = false) ReasonRequest body) {
        return moderation.restore(requireAdmin(), runId, reasonOf(body));
    }

    @Operation(summary = "경고", description = "작품은 그대로 두고 작가에게 알린다. 기록에 남아 작가별로 센다.")
    @PostMapping("/{runId}/warn")
    public Map<String, Object> warn(@PathVariable String runId, @RequestBody(required = false) ReasonRequest body) {
        return moderation.warn(requireAdmin(), runId, reasonOf(body));
    }

    @Operation(summary = "처리 기록", description = "최근부터.")
    @GetMapping("/log")
    public Map<String, Object> log(@RequestParam(defaultValue = "100") int limit) {
        requireAdmin();
        return Map.of("rows", moderation.recent(limit));
    }

    @Operation(summary = "관리자 휴지통", description = "관리자가 삭제 처리한 작품들.")
    @GetMapping("/removed")
    public Map<String, Object> removed() {
        requireAdmin();
        return Map.of("rows", moderation.removed());
    }

    @Operation(summary = "작가별 처리 수", description = "경고 · 비공개 · 삭제를 센다. 많은 사람부터.")
    @GetMapping("/owners")
    public Map<String, Object> owners() {
        requireAdmin();
        return Map.of("rows", moderation.owners());
    }
}
