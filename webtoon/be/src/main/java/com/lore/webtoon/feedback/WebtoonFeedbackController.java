package com.lore.webtoon.feedback;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.Admins;
import com.lore.webtoon.WebtoonApi;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 검증 설문(#471). 규칙은 {@link WebtoonFeedbackService}, 무엇을 왜 묻는지는
 * {@code webtoon/docs/validation.md}.
 *
 * {@code /my/**} 는 로그인해야 부를 수 있다({@code WebSecurityConfig}). 짧은 설문은 로그인
 * 없이 만든 작품의 주인도 답해야 해서 그 바깥에 둔다.
 */
@Tag(name = "Webtoon", description = "설문 · 피드백")
@RestController
public class WebtoonFeedbackController {

    private final WebtoonFeedbackService service;
    private final Admins admins;

    public WebtoonFeedbackController(WebtoonFeedbackService service, Admins admins) {
        this.service = service;
        this.admins = admins;
    }

    @Operation(summary = "완성 직후 설문 — 무엇을 물을지", description = """
            작품 주인에게만, 한 작품에 한 번. 이미 답했거나 주인이 아니면 빈 목록.
            핵심 질문 하나(자기 것을 넣었으면 S1, 아니면 S6)에 나머지 중 1~2개를 무작위로 더한다.""")
    @GetMapping(WebtoonApi.V1 + "/feedback/questions")
    public WebtoonFeedbackService.ShortQuestions questions(
            @RequestParam String run,
            @RequestHeader(value = "X-Lore-Uid", required = false) String uid) {
        return service.shortQuestions(run, WebtoonFeedbackService.currentUser(), uid);
    }

    @Operation(summary = "완성 직후 설문 — 답 보내기", description = """
            {"run": "...", "answers": {"S0": 5, "S1": 4, "S7": "yes"}, "comment": "..."}. 이 작품에 물을 수 있는 질문과
            정해진 값만 저장한다. 두 번째로 보내면 저장하지 않고 saved=false.""")
    @PostMapping(WebtoonApi.V1 + "/feedback")
    public Map<String, Object> answer(
            @RequestBody ShortRequest req,
            @RequestHeader(value = "X-Lore-Uid", required = false) String uid) {
        boolean saved = service.saveShort(req.run(), WebtoonFeedbackService.currentUser(), uid, req.answers(), req.comment());
        return Map.of("saved", saved);
    }

    @Operation(summary = "전체 설문 — 냈는지 · 보상 · 안내를 띄울 차례인지", description = """
            prompt=true 이면 한 편 이상 완성하고 다른 날 다시 온 사람이다. 화면이 한 번 띄운다.""")
    @GetMapping(WebtoonApi.V1 + "/my/feedback")
    public WebtoonFeedbackService.Status status() {
        return service.status(mustLogin());
    }

    @Operation(summary = "전체 설문 — 답 보내기", description = """
            GET /my/feedback 의 questions 에 모두 답해야 한다(가장 최근에 완성한 작품에 맞춘 질문, S10 은 여러 개를 배열로). 자유 의견은 2,000자, 연락처는
            인터뷰를 원할 때만 200자까지. 처음 낸 계정에만 웹툰 한 편 값의 크레딧을 준다.""")
    @PostMapping(WebtoonApi.V1 + "/my/feedback")
    public WebtoonFeedbackService.Full full(
            @RequestBody FullRequest req,
            @RequestHeader(value = "X-Lore-Uid", required = false) String uid) {
        return service.saveFull(mustLogin(), uid, req.answers(), req.comment(),
                Boolean.TRUE.equals(req.wantsInterview()), req.contact());
    }

    @Operation(summary = "설문 답 모아 보기 (관리자)", description = "관리자 계정만. 새 것부터 limit 개(최대 500).")
    @GetMapping(WebtoonApi.V1 + "/admin/feedback")
    public List<Map<String, Object>> latest(@RequestParam(defaultValue = "200") int limit) {
        if (!admins.current()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "관리자만 볼 수 있어요");
        }
        return service.latest(limit);
    }

    private static Long mustLogin() {
        Long userId = WebtoonFeedbackService.currentUser();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "로그인해야 보낼 수 있어요");
        }
        return userId;
    }

    public record ShortRequest(String run, Map<String, Object> answers, String comment) {
    }

    public record FullRequest(Map<String, Object> answers, String comment, Boolean wantsInterview, String contact) {
    }
}
