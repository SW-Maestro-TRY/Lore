package com.lore.webtoon;

import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 생성 비용 — 올리는 자리와 보는 자리.
 *
 * <h2>올리는 쪽은 사람이 아니라 하네스다</h2>
 *
 * {@code /internal/**} 은 생성 하네스(serve.py)만 부른다. 그런데
 * {@code /api/webtoon/**} 은 게스트에게 열려 있어서, 그대로 두면 <b>아무나
 * 가짜 비용을 심을 수 있다.</b> 비용이 부풀면 상한이 엉뚱한 자리에서 걸려
 * 멀쩡한 사람이 못 만들게 되고, 반대로 낮춰 심으면 상한 자체가 무의미해진다.
 *
 * 그래서 <b>미리 나눠 가진 한 마디</b>를 헤더로 확인한다. 브라우저가 부를
 * 주소가 아니므로 로그인과는 다른 방식이 맞다 — 하네스에는 계정이 없다.
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(UsageController.PREFIX)
public class UsageController {

    static final String PREFIX = "/api/webtoon/internal";

    private final UsageService service;

    public UsageController(UsageService service) {
        this.service = service;
    }

    @Operation(summary = "모델 호출 비용 올리기", description = """
            하네스가 작품을 만들면서 부른다. 같은 것을 두 번 올려도 한 줄만 남는다
            (run_id + seq 가 유일). 그래서 어디까지 보냈는지 잃어도 처음부터 다시
            보내면 된다.""")
    @PostMapping("/usage")
    public ApiResponse<Ingested> ingest(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
                                        @Valid @RequestBody IngestRequest request) {
        service.checkToken(token);
        return ApiResponse.ok(new Ingested(service.ingest(request.runId(), request.calls())));
    }

    @Operation(summary = "오늘 몫이 얼마나 남았나", description = """
            오늘 만든 편수와 상한, 그리고 지금 만들 수 있는지.

            누구나 볼 수 있게 열어 둔다 — 만들려는 사람이 알아야 하는 값이고
            개인 정보가 아니다. **돈은 안 담는다.** 한 편에 얼마가 드는지는 우리
            원가라 화면에 실으면 그대로 밖으로 나간다 — 그건 /spend 쪽이다.""")
    @GetMapping("/today")
    public ApiResponse<UsageService.TodayView> today() {
        return service.today();
    }

    @Operation(summary = "얼마나 나갔나 (운영용)", description = """
            오늘 · 이번 달 나간 돈과, 무엇에 얼마나 썼는지(단계·모델별).

            **한 마디가 있어야 준다.** 단계별·모델별 원가는 우리 원가 구조가
            그대로 드러나는 값이다. 브라우저가 아니라 손에서 부른다 —
            haeun/landing/spend_report.py.""")
    @GetMapping("/spend")
    public ApiResponse<UsageService.SpendView> spend(
            @RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        return service.spend(token);
    }

    /** 헤더 이름. 값은 서버 환경변수로만 준다 — 코드에도 저장소에도 안 적는다. */
    static final String TOKEN_HEADER = "X-Lore-Internal";

    /** @param calls meta.json 의 calls 를 그대로. 순서가 곧 seq 다. */
    public record IngestRequest(@NotBlank String runId, @Valid List<UsageService.Call> calls) {
    }

    /** @param saved 이번에 새로 남은 줄 수. 이미 있던 것은 안 센다. */
    public record Ingested(int saved) {
    }
}
