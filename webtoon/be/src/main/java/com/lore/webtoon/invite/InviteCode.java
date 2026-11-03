package com.lore.webtoon.invite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 사람마다 하나인 초대 코드. 처음 「친구 초대」 칸을 열 때 만들어지고 바뀌지 않는다. */
@Entity
@Table(name = "webtoon_invite_code")
public class InviteCode {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "code", nullable = false, length = 16)
    private String code;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InviteCode() {
    }

    InviteCode(Long userId, String code, Instant createdAt) {
        this.userId = userId;
        this.code = code;
        this.createdAt = createdAt;
    }

    public Long getUserId() {
        return userId;
    }

    public String getCode() {
        return code;
    }
}
