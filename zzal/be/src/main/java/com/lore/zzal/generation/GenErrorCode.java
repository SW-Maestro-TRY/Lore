package com.lore.zzal.generation;

/**
 * 생성이 왜 실패했는가.
 *
 * ★ 두 종류를 반드시 갈라야 한다. 처방이 정반대이기 때문이다.
 *
 *   TIMEOUT             서버가 느린 것뿐 → **같은 입력으로 다시** 하면 대개 된다
 *   MODERATION_BLOCKED  입력 자체가 거부된 것 → 같은 걸 다시 보내면 또 막힌다.
 *                       앞 단계(정체성 문단)부터 새로 만들어야 한다
 *
 * 안 가르면 "재시도 3번 하고 실패" 라는 최악이 나온다 — 시간은 다 쓰고 결과는 같다.
 * (2026-08-26 실측에서 고양이 시트를 보고 엉뚱한 캐릭터를 묘사한 문단 때문에 실제로 막혔다)
 */
public enum GenErrorCode {

    TIMEOUT,

    MODERATION_BLOCKED,

    /** 그 밖의 오류(네트워크·응답 형식 등). 로그를 봐야 한다. */
    UNKNOWN
}
