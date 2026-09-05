package com.lore.zzal.dev;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;
import java.time.Instant;

/** 개발용 도구가 받는 것들. */
public final class DevRequests {

    private DevRequests() {
    }

    /**
     * 얼마나 당길 것인가. 초·분 아무 쪽이나(둘 다 주면 더한다).
     *
     * ★ 단위를 두 개 받는 이유 — 확인해야 할 시간의 폭이 넓다. 낮잠은 5분, 게이지는 3~4시간, 하루는 24시간이다.
     *   초로만 받으면 4시간을 손으로 14400 이라고 적게 되고, 자릿수를 틀리면 <b>엉뚱한 값을 확인하고도 맞다고 믿는다.</b>
     */
    @Schema(description = "시간 당기기 요청 — 초·분 중 아무 쪽이나 준다(둘 다 주면 더한다)")
    public record AdvanceClock(

            @Schema(description = "당길 초", example = "60") Long seconds,

            @Schema(description = "당길 분", example = "240") Long minutes) {

        /** ★ 넘치면 예외 — 조용히 음수로 감기면 30일 상한을 우회한다(리뷰 주입: minutes=Long.MAX). */
        public Duration toDuration() {
            long total = Math.addExact(seconds == null ? 0 : seconds,
                    Math.multiplyExact(minutes == null ? 0 : minutes, 60L));
            return Duration.ofSeconds(total);
        }
    }

    /**
     * 펫의 시계를 특정 시각으로 맞춘다. 셋 중 하나만 준다.
     *
     * <ul>
     *   <li>{@code at} — ISO-8601 시각 그대로</li>
     *   <li>{@code sinceHatchMinutes} — 부화 뒤 N분(아기 시간표 0·3·8·12·15·20·25·40·60 확인용)</li>
     *   <li>{@code localTime} — 오늘(KST) 그 시각("19:00" — 재우기 창·"23:00" 자동 취침 확인용)</li>
     * </ul>
     */
    @Schema(description = "시계 맞추기 — at · sinceHatchMinutes · localTime 중 하나")
    public record SetClock(

            @Schema(description = "ISO-8601 시각", example = "2026-09-05T10:00:00Z") Instant at,

            @Schema(description = "부화 뒤 몇 분", example = "40") Integer sinceHatchMinutes,

            @Schema(description = "오늘(KST) HH:mm", example = "19:00") String localTime) {

        public int given() {
            return (at != null ? 1 : 0) + (sinceHatchMinutes != null ? 1 : 0) + (localTime != null ? 1 : 0);
        }
    }
}
