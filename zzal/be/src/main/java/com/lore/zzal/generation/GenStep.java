package com.lore.zzal.generation;

/**
 * 생성이 어느 단계까지 왔는가. 화면의 "부화 중" 표시가 이 값을 읽는다.
 *
 * 2026-08-26 실측(5캐릭터) — 정상 경로 합계 2분 10초~20초.
 *   시트 54~60초 · 문단 15~22초 · 격자 54~60초 · 후처리 1~2초
 *
 * 리미트는 실측의 2배로 잡는다(이미지 API 가 붐빌 때 그만큼 늘어지는 것은 흔하다).
 */
public enum GenStep {

    /** 차례를 기다리는 중. 동시 생성이 몰리면 여기서 대기한다. */
    QUEUED("차례를 기다리는 중", 0),

    /** 원본 그림 → 캐릭터 시트. 이후 모든 생성의 기준이 된다. */
    SHEET("이 아이의 설정자료를 그리는 중", 120),

    /** 시트 → 생김새를 글로 받아 적기. 이게 있어야 격자가 안정된다. */
    IDENTITY("생김새를 정리하는 중", 60),

    /** 문단 + 시트 → 4x4 격자(8상태 × 2프레임). */
    GRID("움직임을 하나씩 익히는 중", 120),

    /** 초록 키잉 · 절단 · 정렬. 우리 서버 안 계산이라 늘어질 이유가 없다. */
    POST("깨어날 준비를 하는 중", 30);

    private final String label;
    private final int limitSeconds;

    GenStep(String label, int limitSeconds) {
        this.label = label;
        this.limitSeconds = limitSeconds;
    }

    /** 화면에 보여줄 말. 남은 시간(카운트다운)이 아니라 지금 하는 일을 알린다. */
    public String getLabel() {
        return label;
    }

    /** 이 시간을 넘으면 시간 초과로 본다. */
    public int getLimitSeconds() {
        return limitSeconds;
    }
}
