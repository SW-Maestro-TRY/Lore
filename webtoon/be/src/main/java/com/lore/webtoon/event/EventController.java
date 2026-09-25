package com.lore.webtoon.event;

import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.credit.CreditGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 웹툰 화면의 행동 기록을 받는 자리(#413).
 *
 * 공용 행동 기록({@code /api/v1/events})과 따로 둔다 — 이유는 표를 만드는 SQL
 * ({@code V20260926_0001__webtoon_event.sql}) 머리에 있다. 무엇을 막는지는
 * {@link EventService} 에 있다.
 */
@Tag(name = "Webtoon", description = "행동 기록")
@RestController
@RequestMapping(WebtoonApi.V1 + "/events")
public class EventController {

    /** 이보다 큰 본문은 읽지 않는다. 50줄을 꽉 채워도 이만큼이 안 된다. */
    static final long MAX_BODY_BYTES = 64 * 1024;

    private final EventService events;

    public EventController(EventService events) {
        this.events = events;
    }

    @Operation(summary = "행동 기록 보내기", description = """
            화면이 모아 둔 행동을 한 번에 보낸다. 로그인 없이 부른다.

            · 한 번에 50줄까지, 한 브라우저가 1분에 120번까지. 넘치면 조용히 버린다
            · props 는 서버가 허용한 키만, 값은 짧은 기호·숫자·참거짓만 남는다
            · 사람이 쓴 글(이름·설명·메모·본문·이메일)은 보내지 않는다
            · 페이지를 떠날 때(sendBeacon)는 머리를 못 실으므로 uid 를 본문에 넣는다""")
    @PostMapping
    public Map<String, Object> collect(
            @RequestBody(required = false) EventService.Batch batch,
            @RequestHeader(value = "X-Lore-Uid", required = false) String uid,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return Map.of("saved", 0);
        }
        int saved = events.collect(batch, uid, CreditGate.currentUser(), clientIp(request), userAgent);
        return Map.of("saved", saved);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "" : remote;
    }
}
