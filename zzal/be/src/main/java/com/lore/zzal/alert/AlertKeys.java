package com.lore.zzal.alert;

/**
 * 경보 종류와 그 종류가 {@code zzal_alert_state.last_value} 에 남기는 값.
 *
 * ★ 상수로 두는 이유 — 키 글자가 한 자라도 달라지면 <b>중복 막기가 통째로 풀린다</b>.
 *   그런데 그 사고는 예외도 로그도 없이, 같은 메일이 계속 오는 모습으로만 드러난다.
 */
public final class AlertKeys {

    /** 누적 생성 비용. 값 = 마지막으로 알린 임계 금액(달러, 정수 문자열). */
    public static final String COST = "cost";

    /** 부화 연속 실패. 값 = 알린 구간의 <b>첫 펫 id</b>(구간이 이어지는 동안 안 바뀐다 → 한 구간에 한 통). */
    public static final String HATCH_FAIL_STREAK = "hatch_fail_streak";

    /** 서비스 하루 상한 도달. 값 = 닿은 날(KST). */
    public static final String SERVICE_DAILY_CAP = "service_daily_cap";

    /** 밤 굽기 실패. 값 = 실패를 알린 날(KST). */
    public static final String NIGHT_BAKE_FAILED = "night_bake_failed";

    private AlertKeys() {
    }
}
