package com.lore.webtoon.gift;

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
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 개별 사용자 사과·보상 크레딧 조회/수령 및 관리자 등록 API.
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(WebtoonApi.V1)
public class UserGiftController {

    private final UserGiftService service;
    private final Admins admins;

    public UserGiftController(UserGiftService service, Admins admins) {
        this.service = service;
        this.admins = admins;
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

    @Operation(summary = "미수령 개별 보상 목록",
            description = "지금 로그인한 계정이 아직 받지 않은 사과·보상 크레딧 알림 목록. 로그인 전이면 빈 목록.")
    @GetMapping("/gifts/pending")
    public Map<String, Object> pending() {
        Long me = CreditGate.currentUser();
        List<GiftView> rows = service.pendingFor(me).stream().map(GiftView::of).toList();
        return Map.of("gifts", rows);
    }

    @Operation(summary = "개별 보상 크레딧 수령",
            description = "대상 계정이 팝업에서 받기를 누르면 크레딧을 1회 지급하고 새 잔액을 돌려준다.")
    @PostMapping("/gifts/{id}/claim")
    public ClaimResponse claim(@PathVariable Long id) {
        Long me = CreditGate.currentUser();
        if (me == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "로그인이 필요합니다");
        }
        UserGiftService.ClaimResult res = service.claim(me, id);
        return new ClaimResponse(res.id(), res.refKey(), res.title(), res.granted(), res.balance());
    }

    @Operation(summary = "관리자 — 개별 보상 목록", description = "최근 등록한 개별 보상 50건.")
    @GetMapping("/admin/gifts")
    public Map<String, Object> adminList() {
        requireAdmin();
        List<AdminGiftView> rows = service.listRecent().stream().map(AdminGiftView::of).toList();
        return Map.of("gifts", rows);
    }

    @Operation(summary = "관리자 — 개별 보상 등록",
            description = "특정 이메일 계정에 1회성 팝업과 크레딧 보상을 등록한다. ref_key 가 같으면 멱등하게 기존 행을 반환한다.")
    @PostMapping("/admin/gifts")
    public AdminGiftView adminCreate(@RequestBody CreateRequest req) {
        Long adminId = requireAdmin();
        if (req == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "요청 본문이 필요합니다");
        }
        UserGift saved = service.create(
                req.ref_key(), req.email(), req.title(), req.message(),
                req.credits() == null ? 0 : req.credits(), adminId);
        return AdminGiftView.of(saved);
    }

    public record CreateRequest(String ref_key, String email, String title, String message, Integer credits) {
    }

    public record GiftView(Long id, String ref_key, String title, String message, int credits, Instant created_at) {
        static GiftView of(UserGift g) {
            return new GiftView(g.getId(), g.getRefKey(), g.getTitle(), g.getMessage(), g.getCredits(), g.getCreatedAt());
        }
    }

    public record ClaimResponse(Long id, String ref_key, String title, int granted, int balance) {
    }

    public record AdminGiftView(Long id, String ref_key, String email_key, String title, String message,
                                int credits, Instant created_at, Long claimed_by, Instant claimed_at) {
        static AdminGiftView of(UserGift g) {
            return new AdminGiftView(g.getId(), g.getRefKey(), g.getEmailKey(), g.getTitle(), g.getMessage(),
                    g.getCredits(), g.getCreatedAt(), g.getClaimedBy(), g.getClaimedAt());
        }
    }
}
