package com.lore.zzal.generation.client;

import java.math.BigDecimal;
import java.util.List;

/** API 를 부르지 않는 텍스트 클라이언트. 과금 0. */
public class FakeTextClient implements TextClient {

    private final int delayMillis;

    public FakeTextClient(int delayMillis) {
        this.delayMillis = delayMillis;
    }

    @Override
    public Result generate(String prompt, List<String> refImageKeys, ModelSpec spec) throws InterruptedException {
        Thread.sleep(delayMillis);
        return new Result("(가짜 정체성 문단 — 실제 생성은 설정을 켜면 돈다)", BigDecimal.ZERO);
    }
}
