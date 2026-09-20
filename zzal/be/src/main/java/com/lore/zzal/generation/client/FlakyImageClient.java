package com.lore.zzal.generation.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 일부러 실패하는 이미지 클라이언트. **검증 전용이며 과금 0.**
 *
 * ★ 왜 필요한가 — 재시도와 타임아웃은 "실패했을 때" 만 도는 코드다. 정상 경로만 확인하면
 *   그 코드는 한 번도 실행되지 않은 채 배포되고, 진짜 실패가 났을 때 처음 돌아본다.
 *   그때 잘못돼 있으면 사용자의 부화가 날아가고 돈도 두 배로 나간다.
 *
 * 설정으로 무엇을 실패시킬지 정한다.
 *   app.zzal.generation.flaky.fail-step   실패시킬 단계 이름 (grid 등)
 *   app.zzal.generation.flaky.fail-times  몇 번까지 실패시킬지 (그 뒤엔 성공)
 *   app.zzal.generation.flaky.mode        timeout | moderation
 */
public class FlakyImageClient implements ImageClient {

    private static final Logger log = LoggerFactory.getLogger(FlakyImageClient.class);

    private final ImageClient delegate;
    private final String failStep;
    private final int failTimes;
    private final String mode;
    private final AtomicInteger attempts = new AtomicInteger();

    public FlakyImageClient(ImageClient delegate, String failStep, int failTimes, String mode) {
        this.delegate = delegate;
        this.failStep = failStep;
        this.failTimes = failTimes;
        this.mode = mode;
    }

    @Override
    public Result generate(String prompt, List<String> refImageKeys, String outputKey, ModelSpec spec)
            throws Exception {
        boolean target = outputKey != null && outputKey.contains(failStep);
        if (target && attempts.incrementAndGet() <= failTimes) {
            log.warn("[검증] 일부러 실패 — {} ({}회째, mode={})", outputKey, attempts.get(), mode);
            if ("timeout".equals(mode)) {
                // 단계 제한을 넘기도록 오래 붙든다. Runner 가 끊어야 한다.
                Thread.sleep(600_000);
            }
            throw new IllegalStateException(
                    "이미지 생성 실패(HTTP 400): {\"error\":{\"code\":\"moderation_blocked\"}}");
        }
        return delegate.generate(prompt, refImageKeys, outputKey, spec);
    }
}
