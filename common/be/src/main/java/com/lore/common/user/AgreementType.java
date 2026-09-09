package com.lore.common.user;

/**
 * 동의 항목.
 *
 * AGE_14·TERMS·PRIVACY 는 필수라 가입이 성립하려면 반드시 true 여야 하고,
 * MARKETING 은 선택이라 false 도 유효한 기록이다.
 */
public enum AgreementType {

    /**
     * 만 14세 이상 (필수).
     *
     * ★ 약관을 한 문서로 합치더라도 <b>이 항목은 따로 받는다</b> — 나이는 법이 요구하는 별도 사실이라,
     *   나중에 "언제 무엇에 동의했나" 를 물었을 때 이용약관 동의에 묻혀 있으면 답할 수 없다.
     */
    AGE_14,

    /** 이용약관 (필수) */
    TERMS,

    /** 개인정보 처리방침 (필수) */
    PRIVACY,

    /** 마케팅 수신 (선택) */
    MARKETING
}
