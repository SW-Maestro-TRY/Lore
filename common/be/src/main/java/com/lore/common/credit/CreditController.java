package com.lore.common.credit;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.user.UserRepository;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 크레딧 API. 전부 로그인이 필요하다 — 계정에 붙는 값이다.
 *
 * <b>지급·차감 주소는 밖에 안 낸다.</b> 크레딧을 늘리는 길이 바깥에 하나라도
 * 있으면 그 길로 무한히 늘릴 수 있다. 지급은 안에서(가입·날마다·결제) 일어나고,
 * 차감은 만들기가 부른다. 밖에서 부를 수 있는 것은 <b>보는 것</b>뿐이다.
 *
 * 충전(결제)은 아직 없다 — #155.
 *
 * 지급 주소({@code POST /grant})는 <b>관리자만</b> 부른다(#408). 전에는 로그인한
 * 누구나 금액과 refId 를 정해 불러서, refId 만 바꾸면 크레딧을 무한히 넣을 수 있었다.
 */
@Tag(name = "크레딧", description = "잔액·사용 내역 조회")
@RestController
@RequestMapping("/api/v1/credits")
/*
 * ─────────────────────────────────────────────────────────────────────────
 *  refId — 팀 공용 크레딧 API 를 쓸 때 **가장 먼저 이해해야 하는 값**
 * ─────────────────────────────────────────────────────────────────────────
 *
 *  refId 는 "이 크레딧이 **어떤 일** 때문에 움직였나" 를 가리키는 문자열이다.
 *  장부에 (계정, 이유, refId) 로 유일키가 걸려 있어서, **같은 셋으로 두 번
 *  불러도 한 번만 적힌다.** 그래서 refId 는 설명이 아니라 **열쇠**다.
 *
 *  이 값이 왜 중요한가 — 크레딧은 돈에 준하는 기록이라 두 사고가 늘 난다.
 *
 *    · 사용자가 단추를 두 번 눌렀다        → 두 번 빠지면 안 된다
 *    · 통신이 끊겨 프론트가 다시 보냈다     → 두 번 주면 안 된다
 *
 *  refId 를 제대로 넣으면 둘 다 서버가 알아서 막는다. 재시도를 마음 놓고
 *  해도 된다는 뜻이라, 부르는 쪽 코드가 훨씬 단순해진다.
 *
 *  ── 무엇을 넣나 ────────────────────────────────────────────────────────
 *
 *  **그 일을 하나로 가리키는 값**을 넣는다. 보통은 이미 갖고 있다.
 *
 *    쓸 때(spend)   그 일의 id            "job-4821" · "pet-1042" · 작품 id
 *    줄 때(grant)   그 보상의 이름         "onboarding" · "first-share"
 *
 *  ── 한 번만 줄 것 vs 반복해서 줄 것 ───────────────────────────────────
 *
 *  이 구분을 refId 로 표현한다. 따로 설정이 없다.
 *
 *    한 번만    refId = "onboarding"
 *               → 몇 번을 불러도 처음 한 번만 들어간다
 *
 *    날마다     refId = "daily-2026-09-08"     (날짜를 붙인다)
 *               → 날이 바뀌면 새 값이라 또 들어간다
 *
 *    달마다     refId = "share-2026-09"
 *    회차마다   refId = "event-launch-3"
 *
 *  ── 하면 안 되는 것 ───────────────────────────────────────────────────
 *
 *    ✗ 매번 새로 지어낸 값 (UUID·타임스탬프)
 *         → 중복 막기가 통째로 꺼진다. 두 번 누르면 두 번 빠진다.
 *    ✗ 계정 id
 *         → 그 사람에게 평생 한 번만 되는 일이 되어 버린다.
 *    ✗ 사람이 읽을 설명
 *         → 그건 memo 칸이다. refId 는 기계가 견주는 열쇠다.
 *
 *  ── 돌려줄 때 ─────────────────────────────────────────────────────────
 *
 *  환불은 **쓸 때 넣었던 그 refId** 로 부른다. 얼마를 돌려줄지는 장부가
 *  그 refId 로 빠진 줄을 찾아 정한다 — 부르는 쪽이 금액을 안 정하므로
 *  "안 낸 것을 돌려받기" 와 "두 번 돌려받기" 가 구조적으로 막힌다.
 *  (환불은 HTTP 로 안 연다. 아래 spend 의 설명 참고.)
 * ─────────────────────────────────────────────────────────────────────────
 */
public class CreditController {

    private final CreditService credits;
    private final UserRepository users;

    public CreditController(CreditService credits, UserRepository users) {
        this.credits = credits;
        this.users = users;
    }

    @Operation(summary = "내 크레딧 잔액", description = """
            오늘 몫까지 챙겨 준 다음의 잔액이다 — 따로 「받기」를 누르지 않아도 된다.
            받으러 눌러야 하는 무료 크레딧은 안 누른 사람에게는 없는 것과 같다.""")
    @GetMapping("/me")
    public ApiResponse<Balance> balance(@LoginUser Long userId) {
        return ApiResponse.ok(new Balance(credits.balanceWithDaily(userId)));
    }

    @Operation(summary = "내 크레딧 내역", description = """
            최근 것부터. 잔액과 같은 자료에서 나오므로 둘이 어긋날 수 없다.""")
    @GetMapping("/me/events")
    public ApiResponse<List<Line>> events(@LoginUser Long userId,
                                          @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(credits.history(userId, limit).stream().map(Line::from).toList());
    }

    /**
     * 크레딧을 쓴다 — <b>팀 공용.</b> 웹툰·짤·예고편이 같은 자리를 부른다.
     */
    @Operation(summary = "크레딧 사용 (팀 공용)", description = """
            내 크레딧을 쓴다. 어느 서비스에서 썼는지(domain)가 내역에 남는다.

            · 같은 refId 로 두 번 불러도 한 번만 빠진다 — 재시도·중복 클릭이 여기서 걸린다
            · 모자라면 402 로 막고 아예 안 뺀다
            · 돌려주기는 여기 없다. 잘 끝난 것을 환불받는 길이 되기 때문이다
              (실패 판정을 아는 서버가 직접 CreditService.refund 를 부른다)""")
    @PostMapping("/spend")
    public ApiResponse<Spent> spend(@Parameter(hidden = true) @LoginUser Long userId,
                                    @Valid @RequestBody SpendRequest req) {
        int spent = credits.spend(userId, req.domain(), req.amount(), req.refId(), req.memo());
        return ApiResponse.ok(new Spent(spent, credits.balance(userId)));
    }

    /**
     * @param domain 어느 서비스에서 쓰나 (WEBTOON · ZZAL · TRAILER)
     * @param amount 쓸 양. 0 이하면 아무 일도 안 한다
     * @param refId  무엇에 쓴 것인가 — <b>같은 값으로 두 번 불러도 한 번만 빠진다.</b>
     *               작품 id 처럼 그 일을 가리키는 값을 넣는다
     * @param memo   내역에 보일 한 줄 (작품 이름 등). 비워도 된다
     */
    public record SpendRequest(
            @Schema(description = "어느 서비스에서 쓰나", example = "ZZAL")
            @NotNull CreditDomain domain,
            @Schema(description = "쓸 양", example = "12")
            @Positive int amount,
            @Schema(description = "그 일을 가리키는 열쇠. 같은 값으로 두 번 불러도 한 번만 "
                    + "빠진다 — 중복 클릭·재시도가 여기서 걸린다. 보통 이미 갖고 있는 id 를 "
                    + "그대로 넣는다(작업 id·펫 id·작품 id). 매번 새로 지어낸 값(UUID 등)을 "
                    + "넣으면 중복 막기가 꺼진다.",
                    example = "pet-1042")
            @NotBlank String refId,
            @Schema(description = "내역에 보일 한 줄", example = "펫 옷 갈아입히기")
            String memo) {
    }

    /**
     * @param spent   이번에 실제로 빠진 양 (이미 뺀 것이면 0)
     * @param balance 뺀 뒤 남은 크레딧
     */
    public record Spent(int spent, int balance) {
    }

    /**
     * 크레딧을 준다 — <b>팀 공용.</b> 무엇에 얼마를 줄지는 서비스가 정한다.
     *
     * <h2>무엇을 여기서 하고 무엇을 서비스가 하나</h2>
     *
     * 공통은 <b>장부와 규칙</b>만 갖는다 — 적고, 두 번 안 주고, 어디서 준
     * 것인지 남긴다. 회원가입 축하인지 공유 보상인지, 얼마를 줄지, 하루에
     * 몇 번까지인지는 <b>서비스가 정해서 부른다.</b>
     *
     * <h2>같은 활동으로 두 번 안 준다</h2>
     *
     * {@code refId} 가 그 일을 가리킨다 — {@code "onboarding"},
     * {@code "share-2026-09"} 처럼. 유일키가 (계정, 이유, refId) 라
     * 같은 값으로 몇 번을 불러도 한 번만 들어간다. 반복 보상을 주려면
     * refId 에 회차를 넣는다({@code "daily-2026-09-08"}).
     *
     * <h2>⚠ 관리자만 부른다</h2>
     *
     * 금액과 refId 를 부르는 사람이 정하므로, 누구나 부를 수 있으면 refId 를
     * 바꿔 가며 무한히 넣을 수 있다. 그래서 관리자 계정({@code User.isAdmin()})이
     * 아니면 403 으로 막는다. 사용자에게 주는 보상은 조건을 아는 서버 코드가
     * {@code CreditService.grantOnce} 를 바로 부른다.
     */
    @Operation(summary = "크레딧 지급 (팀 공용)", description = """
            내 계정에 크레딧을 넣는다. **관리자 계정만** 부를 수 있고, 아니면 403 이다.

            · 같은 refId 로 두 번 불러도 한 번만 들어간다 — 중복 보상이 여기서 걸린다
            · 반복 보상은 refId 에 회차를 넣는다 (daily-2026-09-08)
            · 어느 서비스에서 준 것인지(domain)가 내역에 남는다

            조건 판정이 서버에서 끝나는 보상은 이 주소를 거치지 말고
            CreditService.grantOnce 를 서버 코드에서 바로 부른다.""")
    @PostMapping("/grant")
    public ApiResponse<Granted> grant(@Parameter(hidden = true) @LoginUser Long userId,
                                      @Valid @RequestBody GrantRequest req) {
        boolean admin = users.findById(userId).map(u -> u.isAdmin()).orElse(false);
        if (!admin) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        int given = credits.grantOnce(userId, req.amount(), CreditReason.REWARD,
                                      req.domain(), req.refId());
        return ApiResponse.ok(new Granted(given, credits.balance(userId)));
    }

    /**
     * @param domain 어느 서비스가 주나 (WEBTOON · ZZAL · TRAILER)
     * @param amount 줄 양
     * @param refId  무슨 활동인가 — <b>같은 값이면 한 번만 들어간다.</b>
     *               반복 보상은 회차를 붙인다 (daily-2026-09-08)
     */
    public record GrantRequest(
            @Schema(description = "어느 서비스가 주나", example = "ZZAL")
            @NotNull CreditDomain domain,
            @Schema(description = "줄 양", example = "5")
            @Positive int amount,
            @Schema(description = "그 보상을 가리키는 열쇠. 같은 값으로 두 번 불러도 한 번만 "
                    + "들어간다. **한 번만 줄 보상**은 이름만 넣고(onboarding), **반복해서 줄 "
                    + "보상**은 회차를 붙인다(daily-2026-09-08 · share-2026-09). 계정 id 나 "
                    + "매번 새로 지어낸 값은 넣지 않는다.",
                    example = "onboarding")
            @NotBlank String refId) {
    }

    /**
     * @param granted 이번에 실제로 들어간 양 (이미 준 것이면 0)
     * @param balance 넣은 뒤 잔액
     */
    public record Granted(int granted, int balance) {
    }

    /** @param balance 지금 쓸 수 있는 크레딧 */
    public record Balance(int balance) {
    }

    /**
     * 내역 한 줄.
     *
     * @param delta  움직인 양. 받으면 양수, 쓰면 음수
     * @param reason 코드 이름 그대로 — 화면이 문구가 아니라 이것으로 분기한다
     * @param label  사람이 읽을 말
     * @param domain 어느 서비스에서 일어난 일인가 (WEBTOON · ZZAL · TRAILER · COMMON).
     *               이 칸이 생기기 전 줄은 COMMON 으로 온다
     * @param domainLabel 사람이 읽을 서비스 이름
     */
    @Schema(name = "CreditEventLine", description = "크레딧 내역 한 줄")
    public record Line(Long id, int delta, String reason, String label,
                       String domain, String domainLabel, String memo, Instant at) {

        static Line from(CreditEvent e) {
            return new Line(e.getId(), e.getDelta(), e.getReason().name(),
                    e.getReason().label(), e.getDomain().name(), e.getDomain().label(),
                    e.getMemo(), e.getCreatedAt());
        }
    }
}
