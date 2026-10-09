package com.lore.zzal.chat.line;

import com.lore.zzal.chat.prompt.ChatContext;

/**
 * 대사 생성기. 지금은 {@link LlmLineGenerator}(OpenAI 1회) 하나다 — 성격별 고정 문형(템플릿)은 #709 에서 지웠다.
 *
 * ★ 실패하면 몇 번 더 부를지·무엇으로 닫을지는 {@link LineChain} 이 정한다. 생성기는 자기 일만 한다 —
 *   실패를 예외로 던지지 않고 사유를 담은 {@link LineAttempt} 로 돌려준다(사유를 지표로 남기려고).
 */
public interface LineGenerator {

    /** 이벤트·DB 에 남는 이름. */
    String name();

    LineAttempt generate(ChatContext ctx);
}
