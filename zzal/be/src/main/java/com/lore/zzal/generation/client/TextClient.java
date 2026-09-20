package com.lore.zzal.generation.client;

import java.math.BigDecimal;
import java.util.List;

/** 글을 만들어 주는 곳(정체성 문단 등). 이미지와 같은 이유로 분리한다. */
public interface TextClient {

    Result generate(String prompt, List<String> refImageKeys, ModelSpec spec) throws Exception;

    record Result(String text, BigDecimal costUsd) {}
}
