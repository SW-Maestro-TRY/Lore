package com.lore.common.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * "이 브라우저는 나중에 이 사람이 되었다" 는 기록.
 *
 * <h3>왜 표를 따로 두는가</h3>
 * 사람들은 <b>로그인하기 전에 이미 많은 일을 한다</b> — 랜딩을 보고, 업로드 화면까지 갔다가
 * 나가기도 한다. 그 흔적은 익명 번호로 쌓인다. 나중에 가입하면 그 앞의 행동과 이어져야
 * "어디서 막혀서 가입을 안 했나" 를 볼 수 있다.
 *
 * ★ 지난 이벤트를 소급해서 고치지 않는다. 대량 UPDATE 는 작은 서버에서 위험하고,
 *   이 표 한 줄이면 나중에 조인해서 똑같이 풀린다.
 *
 * ⚠️ 이 연결이 일어나는 순간 <b>그 사람의 비로그인 시절 행동 전체가 실명이 된다.</b>
 *    개인정보처리방침에 이 처리가 적혀 있어야 한다.
 */
@Entity
@Table(
        name = "zzal_anon_identity",
        uniqueConstraints = @UniqueConstraint(name = "uk_anon_identity", columnNames = {"anon_id", "user_id"}),
        indexes = @Index(name = "idx_anon_identity_user", columnList = "user_id"))
public class AnonIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "anon_id", nullable = false, length = 40)
    private String anonId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    protected AnonIdentity() {
    }

    public static AnonIdentity link(String anonId, Long userId, Instant now) {
        AnonIdentity a = new AnonIdentity();
        a.anonId = anonId;
        a.userId = userId;
        a.linkedAt = now;
        return a;
    }

    public Long getId() {
        return id;
    }

    public String getAnonId() {
        return anonId;
    }

    public Long getUserId() {
        return userId;
    }
}
