package com.lore.zzal.generation.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

/**
 * API 를 부르지 않는 이미지 클라이언트. **과금 0.**
 *
 * 개발 중에는 이걸 쓴다. 파이프라인은 진짜로 돌면서 호출만 가짜라,
 * 단계 순서·기록·재시도·화면 표시를 전부 확인할 수 있다.
 * 실제 호출은 확인이 필요할 때만 설정을 바꿔 켠다.
 */
public class FakeImageClient implements ImageClient {

    private static final Logger log = LoggerFactory.getLogger(FakeImageClient.class);

    /** 실제 걸리는 시간의 비율만 흉내낸다. 전체 길이는 설정으로 줄인다. */
    private final int delayMillis;
    /** 격자 자리에 내놓을 실제 이미지. 후처리를 과금 없이 검증하려면 진짜 격자가 필요하다. */
    private final String fakeGridKey;

    public FakeImageClient(int delayMillis, String fakeGridKey) {
        this.delayMillis = delayMillis;
        this.fakeGridKey = fakeGridKey;
    }

    @Override
    public Result generate(String prompt, List<String> refImageKeys, String outputKey, ModelSpec spec)
            throws InterruptedException {
        log.info("[가짜] 이미지 생성 — out={} refs={} model={}", outputKey, refImageKeys.size(), spec.model());
        Thread.sleep(delayMillis);
        // 실제 파일은 만들지 않는다. 다만 격자를 달라고 하면 **진짜 격자**를 준다 —
        // 그래야 뒤의 후처리가 실제로 자를 것이 생기고, 과금 없이 검증할 수 있다.
        boolean wantsGrid = outputKey != null && outputKey.endsWith("grid.png");
        return new Result(wantsGrid ? fakeGridKey : "images/zzal/demo/idle.webp", BigDecimal.ZERO);
    }
}
