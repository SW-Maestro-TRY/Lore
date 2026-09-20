package com.lore.common.analytics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * 화면이 보내오는 것들.
 *
 * <h3>★ 여기 적힌 것을 그대로 믿지 않는다</h3>
 * 이 주소는 로그인 없이 누구나 부를 수 있다. 그래서 이 record 들은 "받는 그릇" 일 뿐이고,
 * 실제 판단은 전부 {@code AnalyticsService} 가 다시 한다.
 *
 *   · 익명 번호를 받는 칸이 <b>아예 없다</b> — 쿠키만 신뢰한다. 본문으로 받으면
 *     남의 번호를 적어 넣어 그 사람의 기록을 오염시킬 수 있다.
 *   · 기기·User-Agent·IP 를 받는 칸도 없다. 기기는 서버가 헤더에서 대분류만 뽑는다.
 *   · {@code props} 는 {@code Map} 으로 받되 <b>허용된 키만</b> 통과시킨다.
 *     받는 모양이 넓은 것과 저장하는 모양이 넓은 것은 다른 이야기다.
 */
public final class EventRequests {

    private EventRequests() {
    }

    /**
     * 한 번에 보내는 묶음.
     *
     * ★ 낱개가 아니라 묶음인 이유 — 화면은 이벤트를 모았다가 5초/20건/떠날 때 한 번에 보낸다.
     *   낱개로 받으면 랜딩 한 번에 수십 번의 요청이 나가고, 떠나는 순간에는 그 대부분이 유실된다.
     *
     * ★ {@code referrer}·{@code source} 가 이벤트마다가 아니라 묶음에 붙어 있는 이유 —
     *   유입 출처는 방문 하나에 하나뿐이다. 화면은 <b>첫 묶음에만</b> 담아 보내고,
     *   그래서 한 방문의 첫 몇 줄에만 값이 남는다. 매 줄에 복사하면 같은 사실이 수십 번 쌓인다.
     */
    public record Batch(
            @Schema(description = "어디서 들어왔는지. 쿼리스트링은 서버가 잘라 버린다", example = "https://www.google.com/search")
            String referrer,

            @Schema(description = "유입 출처(utm_source/medium/campaign 을 접은 것)", example = "instagram/social/launch")
            String source,

            @NotEmpty(message = "보낼 이벤트가 없습니다")
            List<Event> events) {
    }

    /**
     * 이벤트 한 줄.
     *
     * @param name  화면이 부르는 이름 그대로(zzal_hatch_abandoned 등). 소문자·숫자·밑줄만 통과한다
     * @param ts    화면에서 일어난 시각(epoch ms). 브라우저 시계라 서버가 말이 되는 범위인지 본다
     * @param path  어느 화면이었나. 쿼리는 서버가 잘라 버린다
     * @param props 곁들이는 값. ★ 허용된 키만 남고 나머지는 서버가 버린다
     */
    public record Event(
            @Schema(description = "이벤트 이름", example = "zzal_upload_abandoned")
            String name,

            @Schema(description = "화면에서 일어난 시각(epoch ms)", example = "1757000000000")
            Long ts,

            @Schema(description = "일어난 화면 경로", example = "/zzal")
            String path,

            @Schema(description = "곁들이는 값. 허용된 키만 저장된다", example = "{\"reason\":\"limit\"}")
            Map<String, Object> props) {
    }
}
