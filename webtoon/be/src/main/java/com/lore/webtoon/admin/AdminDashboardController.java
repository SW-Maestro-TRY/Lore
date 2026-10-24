package com.lore.webtoon.admin;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.Admins;
import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.credit.CreditGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 관리자 대시보드(#779) — <b>관리자만</b>. 개요 숫자와, 비공개까지 포함한 작품 · 캐릭터 목록.
 * 옛 관리자 홈의 실시간 패널(/admin/live)을 대신한다.
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(WebtoonApi.V1 + "/admin/dash")
public class AdminDashboardController {

    private static final int MAX_DAYS = 120;

    private final Admins admins;
    private final AdminOverview overview;
    private final AdminCatalog catalog;

    public AdminDashboardController(Admins admins, AdminOverview overview, AdminCatalog catalog) {
        this.admins = admins;
        this.overview = overview;
        this.catalog = catalog;
    }

    private void requireAdmin() {
        Long me = CreditGate.currentUser();
        if (me == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "로그인이 필요합니다");
        }
        if (!admins.isAdmin(me)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "관리자만 쓸 수 있습니다");
        }
    }

    @Operation(summary = "개요",
            description = "날짜 범위(KST, 포함) 안의 방문 · 가입 · 만들기 · 완성과 최소 KPI 세 개. "
                    + "who = external(기본, 관리자·멘토·지인 뺌) · internal · all. 범위를 안 넘기면 최근 7일.")
    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String who) {
        requireAdmin();
        LocalDate today = LocalDate.now(AdminOverview.KST);
        LocalDate toDate = parse(to, today);
        LocalDate fromDate = parse(from, toDate.minusDays(6));
        if (toDate.isBefore(fromDate)) {
            LocalDate t = fromDate;
            fromDate = toDate;
            toDate = t;
        }
        if (fromDate.plusDays(MAX_DAYS).isBefore(toDate)) {
            fromDate = toDate.minusDays(MAX_DAYS);
        }
        return overview.overview(fromDate, toDate, whoOf(who));
    }

    @Operation(summary = "작품 목록", description = "비공개 · 관리자 처리 · 휴지통까지. filter = all · public · private · hidden · removed · trash · guest")
    @GetMapping("/works")
    public Map<String, Object> works(@RequestParam(required = false) String filter,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "0") int page) {
        requireAdmin();
        return catalog.works(filter, q, page);
    }

    @Operation(summary = "캐릭터 목록", description = "남의 비공개 캐릭터까지. filter = all · private · shared · blocked · failed · guest")
    @GetMapping("/characters")
    public Map<String, Object> characters(@RequestParam(required = false) String filter,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "0") int page) {
        requireAdmin();
        return catalog.characters(filter, q, page);
    }

    @Operation(summary = "캐릭터 하나", description = "사람이 넣은 글과 카드 글 전부")
    @GetMapping("/characters/{publicId}")
    public Map<String, Object> character(@PathVariable String publicId) {
        requireAdmin();
        return catalog.character(publicId);
    }

    private static AdminOverview.Who whoOf(String who) {
        if ("internal".equals(who)) {
            return AdminOverview.Who.INTERNAL;
        }
        return "all".equals(who) ? AdminOverview.Who.ALL : AdminOverview.Who.EXTERNAL;
    }

    private static LocalDate parse(String s, LocalDate fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
