package com.lore.common.credit;

/**
 * 크레딧을 <b>어느 서비스에서</b> 쓰거나 받았나.
 */
public enum CreditDomain {

    /** 어느 도메인의 일도 아닌 것 — 가입 축하, 오늘의 무료 몫, 충전, 직접 조정. */
    COMMON("공통"),

    WEBTOON("웹툰"),
    ZZAL("짤"),
    TRAILER("예고편");

    private final String label;

    CreditDomain(String label) {
        this.label = label;
    }

    /** 화면에 그대로 나가는 이름. */
    public String label() {
        return label;
    }
}
