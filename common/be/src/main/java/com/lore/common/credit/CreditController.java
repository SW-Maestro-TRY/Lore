package com.lore.common.credit;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
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
