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

    /** 그 밖의 오류. 로그를 봐야 한다. */
    UNKNOWN,

    // ── 2026-11-11 설계 1-2: 처방이 다른 실패를 UNKNOWN 에서 떼어 낸다 ─────────────────
    // ★ 값을 더하면 zzal_gen_job·zzal_gen_step 의 CHECK 제약도 마이그레이션으로 다시 만들어야 한다
    //   (Hibernate 는 이미 있는 CHECK 를 고치지 않는다 — V2 주석). V20261111_* 참고.

    /** 서버 종료·끼어들기로 끊김. 응답을 못 받았으니 비용은 0 으로 적는다. */
    INTERRUPTED,

    /** OpenAI 5xx·네트워크 오류. 같은 입력으로 다시 하면 대개 된다. */
    PROVIDER_ERROR,

    /** 429 또는 잔액·한도. 곧바로 다시 보내면 또 막힌다. */
    QUOTA,

    /** 격자 구조 게이트 탈락. 같은 격자로는 다시 해 봐야 소용없다. */
    GATE_REJECTED,

    /** 후처리 스크립트 비정상 종료. */
    POSTPROCESS_FAILED
}
