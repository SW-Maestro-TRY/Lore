package com.lore.webtoon.push;

import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.credit.CreditGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 웹푸시 구독(#599). 로그인 없이도 쓴다 — 게스트도 자기 작업 알림을 받아야 한다.
 *
 * 봉투({@code ApiResponse})를 안 씌운다. 웹툰 화면이 부르는 {@code /nh/*} 와 같은 모양이다.
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(WebtoonApi.V1 + "/push")
public class PushController {

    private final PushSender sender;
    private final PushSubscriptions subscriptions;

    public PushController(PushSender sender, PushSubscriptions subscriptions) {
        this.sender = sender;
        this.subscriptions = subscriptions;
    }

    @Operation(summary = "푸시 공개키",
            description = "브라우저가 구독할 때 쓴다. 서버에 키가 없으면 빈 글자 — 화면은 「알림 받기」를 안 띄운다.")
    @GetMapping("/key")
    public Map<String, Object> key() {
        return Map.of("key", sender.publicKey());
    }

    @Operation(summary = "이 기기로 알림 받기",
            description = "브라우저가 준 구독(endpoint · keys)을 적는다. 로그인했으면 계정에, 아니면 uid 에 묶는다.")
    @PostMapping("/subscribe")
    public Map<String, Object> subscribe(@RequestBody SubscribeRequest req) {
        SubscribeRequest.Keys keys = req.keys() == null ? new SubscribeRequest.Keys(null, null) : req.keys();
        subscriptions.subscribe(req.endpoint(), keys.p256dh(), keys.auth(),
                CreditGate.currentUser(), req.uid(), req.lang());
        return Map.of("ok", true);
    }

    @Operation(summary = "이 기기 알림 끄기")
    @PostMapping("/unsubscribe")
    public Map<String, Object> unsubscribe(@RequestBody UnsubscribeRequest req) {
        subscriptions.unsubscribe(req.endpoint());
        return Map.of("ok", true);
    }

    /** 브라우저 {@code PushSubscription.toJSON()} 모양에 uid · lang 을 더한 것. */
    public record SubscribeRequest(String endpoint, Keys keys, String uid, String lang) {
        public record Keys(String p256dh, String auth) {
        }
    }

    public record UnsubscribeRequest(String endpoint) {
    }
}
