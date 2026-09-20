package com.lore.zzal.guard;

import com.lore.common.exception.ErrorCode;

/**
 * 부화를 막은 <b>사유</b> — 화면이 볼 오류 코드와 기록에 남길 이름을 한 줄로 묶어 둔다.
 *
 * <h3>★★ 왜 한 자리에 묶나</h3>
 * 같은 사유가 <b>세 곳</b>에서 따로 불린다 — 던지는 자리(오류 코드) · 기록하는 자리({@code reason}) ·
 * 화면의 문구 표. 셋이 흩어져 있으면 사유가 하나 늘 때 <b>한 곳만 고쳐지고 나머지는 조용히 남는다</b>
 * (기록엔 남는데 화면은 모르는 코드, 또는 그 반대). 여기 한 줄을 더하면 앞의 둘이 함께 따라온다.
 *
 * <h3>★ {@code reason} 값은 마음대로 못 정한다</h3>
 * 행동 기록표({@code zzal_event})의 {@code props} 는 <b>허용된 키만</b> 통과한다
 * ({@code AnalyticsService.ALLOWED_PROP_KEYS}). {@code reason} 은 이미 허용된 키라 표를 새로 만들
 * 필요가 없다 — 대신 값은 "열거된 사유" 여야 하고, 64자·짧은 문자열 규칙에 걸리지 않아야 한다.
 * 화면 쪽 표({@code zzal/fe/lib/hatchBlocked.ts})가 쓰는 이름과 <b>같은 글자</b>로 맞춰 둔다.
 */
public enum HatchBlock {

    /** 살아 있는 아이가 이미 있거나, 누적으로 만들 수 있는 만큼을 다 만들었다. */
    PET_LIMIT("pet_limit", ErrorCode.ZZAL_HATCH_BLOCKED_PET_LIMIT),

    /** 이 사람의 오늘 몫을 다 썼다. 한국 시각 자정에 풀린다. */
    DAILY_CAP("daily_cap", ErrorCode.ZZAL_HATCH_BLOCKED_DAILY_CAP),

    /** 서비스 전체의 오늘 몫을 다 썼다. 잔액 방벽이다. 한국 시각 자정에 풀린다. */
    SERVICE_CAP("service_cap", ErrorCode.ZZAL_HATCH_BLOCKED_SERVICE_CAP),

    /** 한 IP 가 너무 빠르게 새 아이를 만들고 있다. */
    IP_RATE("ip_rate", ErrorCode.ZZAL_HATCH_BLOCKED_IP_RATE),

    /** 바깥(그림 생성)이 한도(429)로 막았다. 굽기 시작 자체를 잠시 멈춘다. */
    QUOTA("quota_429", ErrorCode.ZZAL_HATCH_BLOCKED_QUOTA);

    private final String reason;
    private final ErrorCode errorCode;

    HatchBlock(String reason, ErrorCode errorCode) {
        this.reason = reason;
        this.errorCode = errorCode;
    }

    /** 기록({@code zzal_event.props.reason})에 남을 값. */
    public String reason() {
        return reason;
    }

    /** 화면이 분기에 쓸 오류 코드. 상태는 전부 409 다. */
    public ErrorCode errorCode() {
        return errorCode;
    }
}
