package com.lore.webtoon.invite;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import com.lore.webtoon.WebtoonApi;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 친구 초대(2026-11-03). {@code /my/**} 라 로그인한 사람만 부른다(common 의 WebSecurityConfig).
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(WebtoonApi.V1 + "/my/invite")
public class InviteController {

    private final InviteService invites;

    public InviteController(InviteService invites) {
        this.invites = invites;
    }

    @Operation(summary = "내 초대 코드와 지금까지의 성과",
            description = "코드가 없으면 지금 만든다. 링크는 화면이 /webtoon?ref=<code> 로 조립한다.")
    @GetMapping
    public ApiResponse<InviteService.Summary> summary(@LoginUser Long userId) {
        return ApiResponse.ok(invites.summary(userId));
    }

    @Operation(summary = "초대 링크로 들어와 가입했다", description = """
            초대 링크로 들어온 브라우저가 들고 있던 코드를, 로그인 확인 직후 한 번 보낸다.

            · 새 계정(가입한 지 얼마 안 됨)일 때만 적힌다 — 옛 계정 로그인은 not_new
            · 내 코드·초대자와 같은 기기·이미 적힌 사람은 안 적힌다
            · 적히면 초대한 사람에게 가입 보상이 들어간다(이번 달 상한 안에서)""")
    @PostMapping("/claim")
    public ApiResponse<ClaimResult> claim(@LoginUser Long userId, @RequestBody ClaimRequest req) {
        InviteService.Claim result = invites.claim(userId, req.code(), req.uid());
        return ApiResponse.ok(new ClaimResult(result.name().toLowerCase()));
    }

    public record ClaimRequest(
            @Schema(description = "초대 링크의 ref", example = "K7PQ2XHM") String code,
            @Schema(description = "브라우저가 들고 다니는 값. 프론트 localStorage 의 lore_uid") String uid) {
    }

    public record ClaimResult(String result) {
    }
}
