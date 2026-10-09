package com.lore.zzal.chat.line;

import com.lore.zzal.chat.prompt.ChatContext;

/**
 * 대사 생성기. 지금은 {@link TemplateLineGenerator}(성격×슬롯 문장)와 {@link LlmLineGenerator}(OpenAI 1회) 둘이다.
 *
 * ★ 어느 것을 쓸지·실패하면 무엇으로 넘어갈지는 {@link LineChain} 이 정한다. 생성기는 자기 일만 한다 —
 *   실패를 예외로 던지지 않고 사유를 담은 {@link LineAttempt} 로 돌려준다(폴백 사유를 남기려고).
 */
public interface LineGenerator {

    /** 이벤트·DB 에 남는 이름. */
    String name();

    LineAttempt generate(ChatContext ctx);
}
