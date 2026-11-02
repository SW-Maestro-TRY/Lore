package com.lore.zzal.generation.client;

import java.math.BigDecimal;
import java.util.List;

/** 글을 만들어 주는 곳(정체성 문단 등). 이미지와 같은 이유로 분리한다. */
public interface TextClient {

    Result generate(String prompt, List<String> refImageKeys, ModelSpec spec) throws Exception;

    /**
     * reasoning_effort 를 지정해 부른다(null = 보내지 않음). 지원하지 않는 구현은 무시하고 위 메서드로 간다.
     */
    default Result generate(String prompt, List<String> refImageKeys, ModelSpec spec, String reasoningEffort) throws Exception {
        return generate(prompt, refImageKeys, spec);
    }

    record Result(String text, BigDecimal costUsd) {}
}
