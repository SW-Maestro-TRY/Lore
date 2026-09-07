package com.lore.common.credit;

/**
 * 크레딧을 <b>어느 서비스에서</b> 쓰거나 받았나.
 *
 * <h2>왜 필요한가</h2>
 *
 * 크레딧 장부는 처음부터 공통({@code common/credit})인데 쓰는 곳은 웹툰
 * 하나였다. 그래서 「무엇에 썼나」가 {@link CreditReason#SPEND} 하나로 뭉쳐
 * 있고, 그 설명이 <b>"웹툰 만들기"로 박혀</b> 있었다 — 짤이 같은 장부를
 * 쓰기 시작하면 짤에서 쓴 것도 내역에 「웹툰 만들기」로 찍힌다.
 *
 * 이 값이 있으면 셋이 된다.
 *
 * <ul>
 *   <li>내역 화면이 「웹툰 · 남은 시간만큼 −12」 처럼 갈라 보여 준다</li>
 *   <li>도메인별로 얼마가 나갔는지 셀 수 있다 (지금은 못 센다)</li>
 *   <li>{@code CreditReason} 을 도메인마다 늘리지 않아도 된다 — 이유는
 *       "무슨 성격의 움직임인가"(쓰기·주기·돌려주기)로 남고, 어디서인지는
 *       이 값이 맡는다</li>
 * </ul>
 *
 * <h2>COMMON 이 따로 있는 이유</h2>
 *
 * 가입 축하나 오늘의 무료 몫은 어느 도메인의 일도 아니다. 그것을 웹툰으로
 * 적어 두면 나중에 "웹툰에서 얼마 나갔나" 를 셀 때 받은 것까지 섞인다.
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
