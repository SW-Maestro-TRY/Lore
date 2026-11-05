package com.lore.webtoon.appwait;

import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.credit.CreditGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * LORE 앱 출시 알림 신청(#981). 로그인 없이도 쓴다 — 둘러보기 띠는 게스트도 본다.
 * 봉투({@code ApiResponse})를 안 씌운다. 웹툰 화면이 부르는 다른 주소와 같은 모양이다.
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(WebtoonApi.V1 + "/app-waitlist")
public class AppWaitlistController {

    private final AppWaitlists waitlists;

    public AppWaitlistController(AppWaitlists waitlists) {
        this.waitlists = waitlists;
    }

    @Operation(summary = "앱 출시 알림 신청",
            description = "이메일을 적는다. 출시날 이 목록으로 알리고 50크레딧을 준다. 같은 이메일은 한 번만 적힌다.")
    @PostMapping
    public Map<String, Object> join(@RequestBody JoinRequest req) {
        boolean already = waitlists.join(req.email(), CreditGate.currentUser(), req.uid(), req.device(), req.where(), req.lang());
        return Map.of("ok", true, "already", already);
    }

    public record JoinRequest(String email, String uid, String device, String where, String lang) {
    }
}
