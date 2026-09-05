package com.lore.common.user;

/** 계정 상태. 상태값을 문자열로 흘려 쓰지 않는다. */
public enum UserStatus {

    ACTIVE,

    /** 탈퇴 표시. 30일 뒤 실제 삭제 대상이 된다. */
    DELETED
}
