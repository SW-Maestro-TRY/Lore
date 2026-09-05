package com.lore.zzal.generation.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 실제로 자르지 않는 후처리. 파이썬 연동 전까지 흐름 확인용. */
public class FakePostProcessor implements PostProcessor {

    private static final Logger log = LoggerFactory.getLogger(FakePostProcessor.class);

    private final int delayMillis;

    public FakePostProcessor(int delayMillis) {
        this.delayMillis = delayMillis;
    }

    @Override
    public void split(String gridImageKey, String outputPrefix) throws InterruptedException {
        log.info("[가짜] 후처리 — grid={} → {}/*.webp", gridImageKey, outputPrefix);
        Thread.sleep(delayMillis);
    }

    @Override
    public void split(String gridImageKey, String outputPrefix, java.util.List<String> keys) throws InterruptedException {
        log.info("[가짜] 후처리 v2 — grid={} → {}/{{{}}}.webp", gridImageKey, outputPrefix, String.join(",", keys));
        Thread.sleep(delayMillis);
    }
}
