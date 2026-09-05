package com.lore.webtoon;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 로그인한 사람의 웹툰.
 *
 * <h2>여기는 응답을 감싼다</h2>
 *
 * 프록시({@link WebtoonController})는 하네스가 준 것을 그대로 흘려보내지만,
 * 이 주소들은 <b>자바가 뜻을 갖고 판단하는 것</b>이라 저장소 규약대로
 * {@code ApiResponse} 로 감싼다 — 로그인이 필요하고, 계정과 브라우저를 잇는
 * 판단이 여기서 일어난다.
 *
 * <h2>로그인이 필요한 유일한 웹툰 주소다</h2>
 *
 * 나머지 {@code /api/webtoon/**} 는 게스트도 부를 수 있게 열려 있다
 * (common 의 WebSecurityConfig). {@code /api/webtoon/my/**} 만 그 앞에서
 * 잠근다 — 로그인 안 한 사람에게는 "내" 라는 말이 성립하지 않는다.
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(MyWebtoonController.PREFIX)
public class MyWebtoonController {

    static final String PREFIX = "/api/webtoon/my";

    private final MyWebtoonService service;

    public MyWebtoonController(MyWebtoonService service) {
        this.service = service;
    }

    @Operation(summary = "이 브라우저를 내 계정에 잇기", description = """
            로그인할 때마다 부른다. 기기를 바꾸면 브라우저 uid 가 새로 생기므로
            한 번만 잇는 것으로는 두 번째 기기가 안 붙는다.

            이미 이어져 있으면 아무 일도 안 하고 linked=false 를 준다.""")
    @PostMapping("/link")
    public ApiResponse<LinkResult> link(@LoginUser Long userId,
                                        @jakarta.validation.Valid @RequestBody LinkRequest request) {
        return ApiResponse.ok(new LinkResult(service.link(userId, request.uid())));
    }

    @Operation(summary = "내가 만든 웹툰", description = """
            내 계정에 이어진 브라우저들이 만든 작품 전부. 나만 보기로 내려 둔
            것도 포함한다 — 내 목록이라서다.

            모양은 둘러보기 목록(GET /api/webtoon/runs)과 같다.""")
    @GetMapping("/runs")
    public ApiResponse<List<Map<String, Object>>> runs(@LoginUser Long userId) {
        return ApiResponse.ok(service.myRuns(userId));
    }

    public record LinkRequest(
            @Schema(description = "브라우저가 들고 다니는 값. 프론트 localStorage 의 lore_uid",
                    example = "umt747mfwy4k8hbj8")
            @NotBlank String uid) {
    }

    /** @param linked 이번에 새로 이었는가. 이미 이어져 있었으면 false. */
    public record LinkResult(boolean linked) {
    }
}
