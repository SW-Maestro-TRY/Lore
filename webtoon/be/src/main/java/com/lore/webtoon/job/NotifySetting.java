package com.lore.webtoon.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 로그인한 사람이 "웹툰 완성 메일"을 받을지. 계정당 한 줄이라 {@code user_id}
 * 가 그대로 기본키다 — 여러 값이 붙을 일이 생기면 그때 별도 표로 옮긴다.
 *
 * <b>행이 없으면 켜진 것이다.</b> {@link JobNotice}는 항상 계정 이메일로
 * 보내 왔다(마케팅 수신과 성격이 달라 따로 묻지 않았다). 이 표는 그중
 * "끄고 싶다"고 실제로 누른 사람만 담는다 — 아무도 안 건드린 계정에 기본값
 * 행을 미리 깔아 둘 이유가 없다.
 */
@Entity
@Table(name = "webtoon_notify_setting")
@EntityListeners(AuditingEntityListener.class)
public class NotifySetting {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "notify_on_complete", nullable = false)
    private boolean notifyOnComplete;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    protected NotifySetting() {
    }

    public NotifySetting(Long userId, boolean notifyOnComplete) {
        this.userId = userId;
        this.notifyOnComplete = notifyOnComplete;
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isNotifyOnComplete() {
        return notifyOnComplete;
    }

    public void setNotifyOnComplete(boolean notifyOnComplete) {
        this.notifyOnComplete = notifyOnComplete;
    }
}
