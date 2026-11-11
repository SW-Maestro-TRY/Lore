package com.lore.zzal.openai;

import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * 줄 하나의 설정. 상한·자리 대기 시간은 설정값이고({@code app.zzal.openai.lanes.<줄>.max-concurrent},
 * {@code .max-wait-ms}), 연결 시간 제한은 지금 값을 그대로 둔다(설계 2-4: 그림·문단 20초, 대사 2초).
 *
 * @param maxConcurrent 동시에 나갈 수 있는 요청 수
 * @param maxWaitMs     자리 대기 상한. 0 이하면 제한 없음(자리가 날 때까지 기다린다)
 * @param connectTimeout 이 줄 HttpClient 의 연결 시간 제한
 */
public record LaneConfig(int maxConcurrent, long maxWaitMs, Duration connectTimeout) {

    public LaneConfig {
        if (maxConcurrent <= 0) throw new IllegalArgumentException("max-concurrent must be positive");
        java.util.Objects.requireNonNull(connectTimeout);
    }

    /**
     * 설계 2-1 시작값: 그림 3 · 급한 대사 10(대기 2초) · 느긋한 대사 1.
     * 급한 대사 대기 2초 = 상훈님 결정 안 "다"(2026-11-11) — 한 번만 기다리고 못 얻으면 대체 대사. 운영 수치를 본 뒤 조정.
     */
    public static Map<Lane, LaneConfig> defaults() {
        Map<Lane, LaneConfig> m = new EnumMap<>(Lane.class);
        m.put(Lane.IMAGE, new LaneConfig(3, 0, Duration.ofSeconds(20)));
        m.put(Lane.DIALOGUE_URGENT, new LaneConfig(10, 2_000, Duration.ofSeconds(2)));
        m.put(Lane.DIALOGUE_BACKGROUND, new LaneConfig(1, 0, Duration.ofSeconds(2)));
        return m;
    }

    /** 설정에서 읽는다. 없는 키는 {@link #defaults()} 값. */
    public static Map<Lane, LaneConfig> from(Environment env) {
        Map<Lane, LaneConfig> base = defaults();
        Map<Lane, LaneConfig> m = new EnumMap<>(Lane.class);
        for (Lane lane : Lane.values()) {
            LaneConfig d = base.get(lane);
            String prefix = "app.zzal.openai.lanes." + lane.key() + ".";
            m.put(lane, new LaneConfig(
                    env.getProperty(prefix + "max-concurrent", Integer.class, d.maxConcurrent()),
                    env.getProperty(prefix + "max-wait-ms", Long.class, d.maxWaitMs()),
                    d.connectTimeout()));
        }
        return m;
    }
}
