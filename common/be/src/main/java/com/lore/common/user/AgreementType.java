package com.lore.common.user;

/**
 * 동의 항목.
 *
 * TERMS·PRIVACY 는 필수라 가입이 성립하려면 반드시 true 여야 하고,
 * MARKETING 은 선택이라 false 도 유효한 기록이다.
 */
public enum AgreementType {

    /** 이용약관 (필수) */
    TERMS,

    /** 개인정보 처리방침 (필수) */
    PRIVACY,

    /** 마케팅 수신 (선택) */
    MARKETING
}
