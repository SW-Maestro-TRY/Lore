package com.lore.common.credit;

import com.lore.common.auth.jwt.LoginUser;
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
 */
@Tag(name = "크레딧", description = "잔액·사용 내역 조회")
@RestController
@RequestMapping("/api/v1/credits")
public class CreditController {

    private final CreditService credits;

    public CreditController(CreditService credits) {
        this.credits = credits;
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
     *
     * <h2>왜 「쓰기」만 열고 「돌려주기」는 안 여나</h2>
     *
     * 쓰기는 <b>자기 크레딧만</b> 줄인다. 남의 것을 못 건드리고(누구인지는
     * 토큰에서 나온다), 같은 {@code refId} 로 두 번 불려도 한 번만 빠지고,
     * 모자라면 402 로 막힌다. 최악이 "자기 크레딧을 스스로 태우는 것" 이라
     * 열어도 된다.
     *
     * 돌려주기는 다르다 — 열어 두면 <b>잘 끝난 작업을 환불해 달라고 부를 수
     * 있고 그게 곧 공짜 크레딧이다.</b> 돌려줄지 말지는 "만들기가 실패했다"
     * 를 아는 서버만 판단할 수 있으므로 {@code CreditService.refund()} 를
     * 그 도메인 서버 코드에서 직접 부른다.
     *
     * <h2>돈이 나가는 일은 서버가 부른다</h2>
     *
     * 이 API 가 있다고 해서 화면이 크레딧을 깎고 서버는 믿고 넘어가면 안 된다.
     * 화면이 안 부르고 넘어갈 수 있기 때문이다. 실제로 값을 치르는 일을
     * 시작하는 <b>서버 코드가</b> 부르는 것이 원칙이고, 이 API 는 서버를
     * 거치지 않는 소비(예: 프론트에서 끝나는 기능)를 위한 자리다.
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
            @Schema(description = "무엇에 쓴 것인가 — 같은 값이면 한 번만 빠진다",
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
