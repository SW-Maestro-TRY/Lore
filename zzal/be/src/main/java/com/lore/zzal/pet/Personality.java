package com.lore.zzal.pet;

/**
 * 성격 그룹 5개(정본 10·16장). 채팅 톤을 가른다. 개수는 5 고정, 이름은 구현 때 바꿀 수 있다.
 * 사용자가 12장 12분에 고르고 언제든 바꾼다. 어느 성격이든 사용자를 원망하는 대사는 없다(0장 6).
 */
public enum Personality {

    /** 온순. */
    GENTLE,

    /** 활발. */
    LIVELY,

    /** 수줍음. */
    SHY,

    /** 응석. */
    CLINGY,

    /** 시크. */
    COOL
}
