package com.lore.webtoon.admin;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.Admins;
import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.job.JobQueue;
import com.lore.webtoon.usage.SpendGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 오늘 생성 상한(#617) — <b>관리자만</b>. 관리자 화면 개요 맨 위 칸이 부른다.
 *
 * 사람이 몰려 하루 상한에 걸린 날, 서버를 다시 띄우지 않고 그날(한국 자정까지)만 올린다. 자정이 지나면
 * 저절로 설정값(Parameter Store)으로 돌아간다. 숫자 고르는 기준은 webtoon/docs/capacity.md 「사용자가 몰렸을 때」.
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(WebtoonApi.V1 + "/admin/spend")
public class AdminSpendController {

    /** 하루 200만 원 — 0 을 하나 더 친 실수를 막는 선이다. 정말 더 써야 하면 설정값을 올린다. */
    static final long MAX_KRW = 2_000_000;
    static final long MAX_RUNS = 1_000;

    private final Admins admins;
    private final SpendGuard guard;
    private final JobQueue queue;

    public AdminSpendController(Admins admins, SpendGuard guard, JobQueue queue) {
        this.admins = admins;
        this.guard = guard;
        this.queue = queue;
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

    @Operation(summary = "오늘 생성 상한",
            description = "오늘(한국 날짜) 쓴 돈 · 편수, 아직 안 끝난 작업 몫, 지금 상한과 설정값, 오늘만 바꾼 값. 알림은 안 보낸다.")
    @GetMapping
    public SpendView get() {
        requireAdmin();
        return view();
    }

    @Operation(summary = "오늘만 상한 바꾸기",
            description = "금액(원) · 편수 상한을 오늘 한국 자정까지 바꾼다. 서버를 다시 띄우지 않고 바로 적용된다. "
                    + "1 이상, 금액 " + MAX_KRW + "원 · " + MAX_RUNS + "편 이하.")
    @PutMapping
    public SpendView set(@RequestBody SetRequest req) {
        Long me = requireAdmin();
        long krw = check(req == null ? null : req.krw(), MAX_KRW, "금액");
        long runs = check(req == null ? null : req.runs(), MAX_RUNS, "편수");
        guard.setToday(krw, runs, me);
        return view();
    }

    @Operation(summary = "오늘 바꾼 상한 무르기", description = "설정값(Parameter Store)으로 돌아간다.")
    @DeleteMapping
    public SpendView clear() {
        Long me = requireAdmin();
        guard.clearToday(me);
        return view();
    }

    /* 0 은 받지 않는다 — 설정에서 0 은 「상한을 끈다」라서, 화면에서 잘못 지운 칸이 무제한이 되면 안 된다. */
    private static long check(Long value, long max, String what) {
        if (value == null || value < 1 || value > max) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, what + " 상한은 1 이상 " + max + " 이하로 적어 주세요");
        }
        return value;
    }

    private SpendView view() {
        SpendGuard.Status s = guard.status(queue.reserved());
        SpendGuard.Caps c = s.caps();
        Today today = c.today() == null ? null
                : new Today(c.today().krw(), c.today().runs(), c.today().setBy(), c.today().setAt());
        return new SpendView(s.day().toString(),
                new Pair(s.krw(), s.runs()),
                new Pair(s.reserved().krw(), s.reserved().runs()),
                new Pair(c.krw(), c.runs()),
                new Pair(c.defaultKrw(), c.defaultRuns()),
                today, s.blocked());
    }

    /** 바꿀 값. */
    public record SetRequest(Long krw, Long runs) {
    }

    public record Pair(long krw, long runs) {
    }

    /** 오늘만 바꾼 값. 빈 칸은 설정값을 쓴다. */
    public record Today(Long krw, Long runs, Long setBy, Instant setAt) {
    }

    /**
     * @param day      한국 날짜
     * @param used     오늘 적힌 돈 · 편수
     * @param reserved 아직 안 끝난 작업이 쓸 몫 — 상한은 이것까지 더해서 본다
     * @param cap      지금 쓰는 상한
     * @param defaults 설정값 — 자정이 지나면 돌아갈 값
     * @param today    오늘만 바꾼 값. 없으면 null
     * @param blocked  지금 새로 만들면 막히는가
     */
    public record SpendView(String day, Pair used, Pair reserved, Pair cap, Pair defaults, Today today, boolean blocked) {
    }
}
