package com.lore.trailer.credit;

import com.lore.common.credit.CreditDomain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * trailer 가 크레딧을 쓰는 규칙 — 판정 1회의 값과 장부에 적는 열쇠(refId).
 *
 * <p>값은 {@code lore.credit.trailer-judge}(기본 5)다. 근거는 trailer/docs/크레딧설계.md 2절. 맡길 때 깎고
 * ({@code HypothesisService.submit}) 운영자가 FAILED 를 넣으면 돌려준다({@code judgeForOperator}).
 * 무료 몫은 따로 두지 않는다 — 공통의 가입 · 매일 지급을 그대로 쓴다(같은 문서 1절 7번).
 *
 * <p>refId 는 가설 하나가 일 하나라 {@code hypothesis:<id>} 다. 공통 장부의 유일키가 (계정, 이유, 도메인, refId)
 * 라서 이 열쇠 하나로 같은 가설을 두 번 깎지도, 두 번 돌려주지도 못한다. 도메인은 깎을 때와 돌려줄 때 같아야
 * 한다 — 다르면 낸 줄을 못 찾아 조용히 0 이 된다({@code CreditService.refund}).
 */
@Component
public class TrailerCreditPolicy {

    private final int judgeCredits;

    public TrailerCreditPolicy(@Value("${lore.credit.trailer-judge:5}") int judgeCredits) {
        this.judgeCredits = judgeCredits;
    }

    /** 판정 1회에 깎는 크레딧. 장부 정보(2-1)로 화면에도 간다. */
    public int judgeCredits() {
        return judgeCredits;
    }

    /** 장부의 도메인. */
    public CreditDomain domain() {
        return CreditDomain.TRAILER;
    }

    /** 가설 하나를 가리키는 장부 열쇠. */
    public String refId(long hypothesisId) {
        return "hypothesis:" + hypothesisId;
    }
}
