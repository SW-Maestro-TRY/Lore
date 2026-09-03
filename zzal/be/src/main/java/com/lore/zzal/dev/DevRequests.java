package com.lore.zzal.dev;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;

/** 개발용 도구가 받는 것들. */
public final class DevRequests {

    private DevRequests() {
    }

    /**
     * 얼마나 당길 것인가. 초·분 아무 쪽이나(둘 다 주면 더한다).
     *
     * ★ 단위를 두 개 받는 이유 — 확인해야 할 시간의 폭이 너무 넓다. 훈련은 1분,
     *   잠은 5분에서 3시간, 포만감은 4시간이다. 초로만 받으면 4시간을 손으로 14400 이라고
     *   적게 되고, 그 자리에서 자릿수를 틀리면 <b>엉뚱한 값을 확인하고도 맞다고 믿는다.</b>
     */
    @Schema(description = "시간 당기기 요청 — 초·분 중 아무 쪽이나 준다(둘 다 주면 더한다)")
    public record AdvanceClock(

            @Schema(description = "당길 초", example = "60") Long seconds,

            @Schema(description = "당길 분", example = "240") Long minutes) {

        public Duration toDuration() {
            long total = (seconds == null ? 0 : seconds) + (minutes == null ? 0 : minutes) * 60;
            return Duration.ofSeconds(total);
        }
    }
}
